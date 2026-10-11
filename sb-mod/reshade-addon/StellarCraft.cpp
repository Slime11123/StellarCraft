// StellarCraft in-process DX12 compositor and input bridge.
// ReShade owns swap-chain interception; this add-on never patches a DXGI vtable.
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <Windows.h>
#include <reshade.hpp>
#include "../../protocol/stellarcraft_protocol.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <vector>

namespace sc = stellarcraft::proto;
using namespace reshade::api;

namespace
{
constexpr char kEffect[] = "StellarCraft.fx";
constexpr char kTechnique[] = "StellarCraftUI";
constexpr char kSemantic[] = "STELLARCRAFT_UI";
constexpr std::uint64_t kInputHead = 0;
constexpr std::uint64_t kInputTail = 0x40;
constexpr std::uint64_t kInputData = 0x80;
constexpr std::uint64_t kInputCapacity = 0x10000 - kInputData;
constexpr std::uint64_t kInputEventBytes = 12;
constexpr UINT kRawMouseMessage = WM_APP + 0x51;
constexpr UINT kTextMessage = WM_APP + 0x52;

struct Layout
{
    float x = 0, y = 0, width = 0, height = 0;
    float scale_x = 0, scale_y = 0;
    std::uint32_t buffer_width = 0, buffer_height = 0;
    bool valid = false;
};

struct __declspec(uuid("cdb40b8a-47bc-4477-9b70-5eb4eab14248")) RuntimeData
{
    resource texture = {};
    resource_view view = {};
    resource uploads[3] = {};
    fence upload_fence = {};
    std::uint64_t upload_values[3] = {};
    std::uint64_t next_fence_value = 0;
    std::uint32_t next_upload = 0;
    bool resources_ready = false;
    bool cpu_frame_dirty = false;
    bool gpu_frame_ready = false;
    std::vector<std::uint8_t> pixels;
    std::uint64_t last_frame = 0;
    std::uint32_t width = 0, height = 0;
    bool bottom_up = true;
    std::uint32_t gpu_width = 0, gpu_height = 0;
    bool gpu_bottom_up = true;
    bool first_upload_logged = false;
    bool texture_error_logged = false;
};

HMODULE g_module = nullptr;
HANDLE g_mapping = nullptr;
std::atomic<std::uint8_t *> g_base { nullptr };
std::atomic<HWND> g_game_window { nullptr };
std::atomic<bool> g_running { false };
HANDLE g_input_thread = nullptr;
HANDLE g_thread_ready = nullptr;
std::atomic<DWORD> g_input_thread_id { 0 };
HHOOK g_keyboard_hook = nullptr;
HHOOK g_mouse_hook = nullptr;
std::mutex g_layout_mutex;
Layout g_layout;
std::atomic<float> g_cursor_x { 0 }, g_cursor_y { 0 };
std::atomic<bool> g_cursor_valid { false };
std::atomic<bool> g_steve_baseline { false };
bool g_first_mouse_delta_logged = false;
WNDPROC g_original_window_proc = nullptr;

LRESULT CALLBACK game_window_proc(HWND window, UINT message, WPARAM wparam, LPARAM lparam);

void log(reshade::log::level severity, const char *message)
{
    reshade::log::message(severity, message);
}

std::uint8_t *mapping()
{
    if (auto *base = g_base.load(std::memory_order_acquire))
        return base;

    // The UE4SS bridge creates the mapping after ReShade may already be loaded.
    HANDLE handle = OpenFileMappingW(FILE_MAP_READ | FILE_MAP_WRITE, FALSE, sc::kMappingName);
    if (!handle)
        return nullptr;
    auto *base = static_cast<std::uint8_t *>(MapViewOfFile(handle, FILE_MAP_READ | FILE_MAP_WRITE, 0, 0, 0));
    if (!base)
    {
        CloseHandle(handle);
        return nullptr;
    }
    const auto *header = reinterpret_cast<const sc::Header *>(base + sc::kOffHeader);
    if (header->magic != sc::kMagic || header->version != sc::kVersion ||
        header->sbPid != GetCurrentProcessId())
    {
        UnmapViewOfFile(base);
        CloseHandle(handle);
        return nullptr;
    }
    g_mapping = handle;
    g_base.store(base, std::memory_order_release);
    log(reshade::log::level::info, "StellarCraft: shared memory connected");
    return base;
}

std::uint32_t sb_flags(const std::uint8_t *base)
{
    return reinterpret_cast<const sc::SbState *>(base + sc::kOffSbState)->flags;
}

std::uint32_t mc_flags(const std::uint8_t *base)
{
    return reinterpret_cast<const sc::McState *>(base + sc::kOffMcState)->flags;
}

bool game_foreground()
{
    HWND foreground = GetForegroundWindow();
    DWORD pid = 0;
    if (foreground)
        GetWindowThreadProcessId(foreground, &pid);
    return pid == GetCurrentProcessId();
}

bool steve_latched(const std::uint8_t *base)
{
    const std::uint32_t flags = sb_flags(base);
    const bool latched = (flags & sc::kSbControlSteve) != 0;
    // A confirmed EVE frame prevents a stale mapping from starting in Steve mode.
    if (!g_steve_baseline.load(std::memory_order_acquire))
    {
        if (!(flags & sc::kSbInGame) || latched)
            return false;
        g_steve_baseline.store(true, std::memory_order_release);
        return false;
    }
    return latched;
}

bool should_forward(const std::uint8_t *base)
{
    if (!base || !steve_latched(base) || !game_foreground())
        return false;
    const auto *header = reinterpret_cast<const sc::Header *>(base);
    const std::uint64_t now = GetTickCount64();
    if (!header->mcHeartbeatMs || now < header->mcHeartbeatMs ||
        now - header->mcHeartbeatMs >= 5000)
        return false;
    const std::uint32_t sb = sb_flags(base);
    const std::uint32_t mc = mc_flags(base);
    return (mc & sc::kMcControlSteve) != 0 &&
           (sb & (sc::kSbMenuOpen | sc::kSbLoading)) == 0;
}

LRESULT CALLBACK game_window_proc(HWND window, UINT message, WPARAM wparam, LPARAM lparam)
{
    if (should_forward(g_base.load(std::memory_order_acquire)))
    {
        if (message == WM_CHAR)
        {
            PostThreadMessageW(g_input_thread_id.load(std::memory_order_acquire),kTextMessage,wparam,0);
            return 0;
        }
        // ReShade wraps global low-level hooks and intentionally ignores their nonzero
        // return values. Filter the game's messages too, so replayed input has one owner.
        if (message == WM_INPUT)
        {
            // Relative raw motion keeps working at screen edges and while UE clips/recentres
            // its cursor. Queue it to the hook thread: the input ring remains single-producer.
            if (!(mc_flags(g_base.load(std::memory_order_acquire)) & sc::kMcScreenOpen))
            {
                RAWINPUT input = {};
                UINT bytes = sizeof(input);
                if (GetRawInputData(reinterpret_cast<HRAWINPUT>(lparam), RID_INPUT, &input,
                                    &bytes, sizeof(RAWINPUTHEADER)) != UINT(-1) &&
                    input.header.dwType == RIM_TYPEMOUSE &&
                    !(input.data.mouse.usFlags & MOUSE_MOVE_ABSOLUTE) &&
                    (input.data.mouse.lLastX || input.data.mouse.lLastY))
                    PostThreadMessageW(g_input_thread_id.load(std::memory_order_acquire),
                        kRawMouseMessage, static_cast<WPARAM>(input.data.mouse.lLastX),
                        static_cast<LPARAM>(input.data.mouse.lLastY));
            }
            return DefWindowProcW(window, message, wparam, lparam); // release raw-input storage
        }
        if ((message >= WM_KEYFIRST && message <= WM_KEYLAST && wparam != VK_F4 &&
             message != WM_SYSKEYDOWN && message != WM_SYSKEYUP && message != WM_SYSCHAR) ||
            (message >= WM_MOUSEFIRST && message <= WM_MOUSELAST))
            return 0;
    }
    return CallWindowProcW(g_original_window_proc, window, message, wparam, lparam);
}

void publish_authority(std::uint8_t *base, bool forward)
{
    if (!base)
        return;
    auto *flags = reinterpret_cast<volatile LONG *>(base + sc::kOffSbState + offsetof(sc::SbState, flags));
    LONG old_value = *flags;
    LONG new_value;
    do
    {
        new_value = old_value & ~static_cast<LONG>(sc::kSbEdit | sc::kSbNativeUi);
        if (forward)
            new_value |= sc::kSbEdit;
        else if (g_steve_baseline.load(std::memory_order_acquire) && (old_value & sc::kSbControlSteve))
            new_value |= sc::kSbNativeUi;
        const LONG observed = InterlockedCompareExchange(flags, new_value, old_value);
        if (observed == old_value)
            break;
        old_value = observed;
    } while (true);
}

bool input_push(std::uint16_t type, std::uint16_t code, std::int32_t a, std::int32_t b)
{
    auto *base = g_base.load(std::memory_order_acquire);
    if (!base)
        return false;
    auto *ring = base + sc::kOffInputRing;
    auto *head = reinterpret_cast<volatile std::uint64_t *>(ring + kInputHead);
    auto *tail = reinterpret_cast<volatile std::uint64_t *>(ring + kInputTail);
    const std::uint64_t h = *head, t = *tail;
    if (h < t || h + kInputEventBytes - t > kInputCapacity)
        return false;

    std::uint8_t event[kInputEventBytes] = {};
    std::memcpy(event + 0, &type, 2);
    std::memcpy(event + 2, &code, 2);
    std::memcpy(event + 4, &a, 4);
    std::memcpy(event + 8, &b, 4);
    auto *data = ring + kInputData;
    for (std::uint64_t i = 0; i < kInputEventBytes; ++i)
        data[(h + i) % kInputCapacity] = event[i];
    MemoryBarrier();
    *head = h + kInputEventBytes;
    return true;
}

bool client_cursor(POINT screen, float &x, float &y)
{
    HWND window = g_game_window.load(std::memory_order_acquire);
    if (!window || !IsWindow(window) || !ScreenToClient(window, &screen))
        return false;
    RECT client = {};
    if (!GetClientRect(window, &client) || client.right <= 0 || client.bottom <= 0)
        return false;
    Layout layout;
    {
        std::lock_guard<std::mutex> lock(g_layout_mutex);
        layout = g_layout;
    }
    if (!layout.valid)
        return false;
    x = static_cast<float>(screen.x) * layout.buffer_width / client.right;
    y = static_cast<float>(screen.y) * layout.buffer_height / client.bottom;
    return true;
}

LRESULT CALLBACK keyboard_hook(int code, WPARAM message, LPARAM value)
{
    auto *base = g_base.load(std::memory_order_acquire);
    if (code == HC_ACTION && should_forward(base))
    {
        const auto *key = reinterpret_cast<const KBDLLHOOKSTRUCT *>(value);
        const bool down = message == WM_KEYDOWN || message == WM_SYSKEYDOWN;
        const bool up = message == WM_KEYUP || message == WM_SYSKEYUP;
        if ((down || up) && key->vkCode != VK_F4)
        {
            input_push(1, static_cast<std::uint16_t>(key->vkCode), down ? 1 : 0, 0);
            if ((key->flags & LLKHF_ALTDOWN) == 0)
                return 1;
        }
    }
    return CallNextHookEx(g_keyboard_hook, code, message, value);
}

void publish_cursor(std::uint8_t *base)
{
    if (!base || !(mc_flags(base) & sc::kMcScreenOpen) || !g_cursor_valid.load())
        return;
    Layout layout;
    {
        std::lock_guard<std::mutex> lock(g_layout_mutex);
        layout = g_layout;
    }
    if (!layout.valid || layout.scale_x <= 0 || layout.scale_y <= 0)
        return;
    const float fx = std::clamp((g_cursor_x.load() - layout.x) / layout.scale_x,
                                0.0f, layout.width / layout.scale_x - 1.0f);
    const float fy = std::clamp((g_cursor_y.load() - layout.y) / layout.scale_y,
                                0.0f, layout.height / layout.scale_y - 1.0f);
    // GLFW cursor coordinates are top-down, independently of texture row order.
    input_push(4, 0, static_cast<std::int32_t>(fx + 0.5f), static_cast<std::int32_t>(fy + 0.5f));
}

LRESULT CALLBACK mouse_hook(int code, WPARAM message, LPARAM value)
{
    auto *base = g_base.load(std::memory_order_acquire);
    if (code == HC_ACTION && should_forward(base))
    {
        const auto *mouse = reinterpret_cast<const MSLLHOOKSTRUCT *>(value);
        float x = 0, y = 0;
        if (client_cursor(mouse->pt, x, y))
        {
            g_cursor_x.store(x, std::memory_order_relaxed);
            g_cursor_y.store(y, std::memory_order_relaxed);
            g_cursor_valid.store(true, std::memory_order_release);
        }
        if (mc_flags(base) & sc::kMcScreenOpen) publish_cursor(base);
        if (message != WM_MOUSEMOVE)
        {
            switch (message)
            {
            case WM_LBUTTONDOWN: input_push(2, 0, 1, 0); break;
            case WM_LBUTTONUP:   input_push(2, 0, 0, 0); break;
            case WM_RBUTTONDOWN: input_push(2, 1, 1, 0); break;
            case WM_RBUTTONUP:   input_push(2, 1, 0, 0); break;
            case WM_MBUTTONDOWN: input_push(2, 2, 1, 0); break;
            case WM_MBUTTONUP:   input_push(2, 2, 0, 0); break;
            case WM_XBUTTONDOWN:
            case WM_XBUTTONUP:
                input_push(2, HIWORD(mouse->mouseData) == XBUTTON1 ? 3 : 4,
                           message == WM_XBUTTONDOWN ? 1 : 0, 0);
                break;
            case WM_MOUSEWHEEL:
                input_push(3, 0, static_cast<short>(HIWORD(mouse->mouseData)), 0);
                break;
            default: break;
            }
            if (message == WM_LBUTTONDOWN || message == WM_LBUTTONUP ||
                message == WM_RBUTTONDOWN || message == WM_RBUTTONUP ||
                message == WM_MBUTTONDOWN || message == WM_MBUTTONUP ||
                message == WM_XBUTTONDOWN || message == WM_XBUTTONUP ||
                message == WM_MOUSEWHEEL)
                return 1;
        }
    }
    return CallNextHookEx(g_mouse_hook, code, message, value);
}

void input_tick()
{
    auto *base = g_base.load(std::memory_order_acquire);
    static bool was_forwarding = false;
    static bool was_steve = false, look_pending = false;
    static float entry_yaw=0, entry_pitch=0;
    const bool forward = should_forward(base);
    publish_authority(base, forward);
    if (was_forwarding && !forward)
        input_push(6, 0, 0, 0); // release all held keys and buttons
    was_forwarding = forward;
    const auto *sb=base?reinterpret_cast<const sc::SbState *>(base+sc::kOffSbState):nullptr;
    const bool steve=sb && (sb->flags & sc::kSbControlSteve);
    const bool in_world=steve && (mc_flags(base) & sc::kMcInWorld);
    if(steve && !was_steve) {
        entry_yaw=sb->yaw;entry_pitch=sb->pitch;look_pending=true;
    }
    if(!steve) look_pending=false;
    // Focus/menu changes release held inputs, but must not reseed MC's view from
    // the native third-person front camera (which points 180 degrees the other way).
    if(forward && in_world && look_pending)
    {
        input_push(sc::kInLookSync,0,static_cast<std::int32_t>(entry_yaw*1000),
                   static_cast<std::int32_t>(entry_pitch*1000));
        look_pending=false;
    }
    was_steve=steve;
    if (forward && (mc_flags(base) & sc::kMcScreenOpen))
        publish_cursor(base);
}

DWORD WINAPI input_thread(void *)
{
    MSG message = {};
    PeekMessageW(&message, nullptr, WM_USER, WM_USER, PM_NOREMOVE);
    g_input_thread_id.store(GetCurrentThreadId(), std::memory_order_release);
    g_keyboard_hook = SetWindowsHookExW(WH_KEYBOARD_LL, keyboard_hook, g_module, 0);
    g_mouse_hook = SetWindowsHookExW(WH_MOUSE_LL, mouse_hook, g_module, 0);
    if (!g_keyboard_hook || !g_mouse_hook)
        log(reshade::log::level::warning, "StellarCraft: one or more input hooks could not be installed");
    else
        log(reshade::log::level::info, "StellarCraft: in-process keyboard and mouse hooks installed");
    SetTimer(nullptr, 1, 16, nullptr);
    SetEvent(g_thread_ready);
    while (g_running.load(std::memory_order_acquire) && GetMessageW(&message, nullptr, 0, 0) > 0)
    {
        if (message.message == WM_TIMER)
            input_tick();
        else if (message.message == kRawMouseMessage)
        {
            auto *base = g_base.load(std::memory_order_acquire);
            if (should_forward(base) && !(mc_flags(base) & sc::kMcScreenOpen))
            {
                input_push(sc::kInMouseDelta, 0, static_cast<std::int32_t>(message.wParam),
                           static_cast<std::int32_t>(message.lParam));
                if (!g_first_mouse_delta_logged)
                {
                    log(reshade::log::level::info, "StellarCraft: relative raw mouse look forwarding active");
                    g_first_mouse_delta_logged = true;
                }
            }
        }
        else if (message.message == kTextMessage)
        {
            auto *base=g_base.load(std::memory_order_acquire);
            if (should_forward(base) && (mc_flags(base) & sc::kMcScreenOpen))
                input_push(sc::kInText,0,static_cast<std::int32_t>(message.wParam),0);
        }
        else
        {
            TranslateMessage(&message);
            DispatchMessageW(&message);
        }
    }
    KillTimer(nullptr, 1);
    if (g_keyboard_hook) UnhookWindowsHookEx(g_keyboard_hook);
    if (g_mouse_hook) UnhookWindowsHookEx(g_mouse_hook);
    g_keyboard_hook = g_mouse_hook = nullptr;
    return 0;
}

void start_input()
{
    if (g_input_thread)
        return;
    g_thread_ready = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    if (!g_thread_ready)
        return;
    g_running.store(true, std::memory_order_release);
    g_input_thread = CreateThread(nullptr, 0, input_thread, nullptr, 0, nullptr);
    if (!g_input_thread)
    {
        g_running.store(false, std::memory_order_release);
        CloseHandle(g_thread_ready);
        g_thread_ready = nullptr;
        return;
    }
    WaitForSingleObject(g_thread_ready, 5000);
}

void stop_input()
{
    if (!g_input_thread)
        return;
    g_running.store(false, std::memory_order_release);
    PostThreadMessageW(g_input_thread_id.load(std::memory_order_acquire), WM_QUIT, 0, 0);
    WaitForSingleObject(g_input_thread, 5000);
    CloseHandle(g_input_thread);
    CloseHandle(g_thread_ready);
    g_input_thread = g_thread_ready = nullptr;
    g_input_thread_id.store(0, std::memory_order_release);
}

Layout calculate_layout(std::uint32_t frame_w, std::uint32_t frame_h,
                        std::uint32_t target_w, std::uint32_t target_h)
{
    Layout out;
    if (!frame_w || !frame_h || !target_w || !target_h)
        return out;
    const float source_aspect = static_cast<float>(frame_w) / frame_h;
    const float target_aspect = static_cast<float>(target_w) / target_h;
    const float aspect_error = std::max(source_aspect / target_aspect, target_aspect / source_aspect);
    float sx = 0, sy = 0;
    if (aspect_error <= 1.12f)
    {
        sx = static_cast<float>(target_w) / frame_w;
        sy = static_cast<float>(target_h) / frame_h;
    }
    else
    {
        sx = sy = std::min(static_cast<float>(target_w) / frame_w,
                           static_cast<float>(target_h) / frame_h);
    }
    out.width = std::min(static_cast<float>(target_w), std::floor(frame_w * sx + 0.5f));
    out.height = std::min(static_cast<float>(target_h), std::floor(frame_h * sy + 0.5f));
    out.x = std::floor((target_w - out.width) / 2.0f);
    out.y = target_h - out.height;
    out.scale_x = out.width / frame_w;
    out.scale_y = out.height / frame_h;
    out.buffer_width = target_w;
    out.buffer_height = target_h;
    out.valid = true;
    return out;
}

bool ensure_texture(effect_runtime *runtime, RuntimeData *data)
{
    if (data->resources_ready)
        return true;
    device *device = runtime->get_device();
    if (!device->check_capability(device_caps::copy_buffer_to_texture))
    {
        if (!data->texture_error_logged)
            log(reshade::log::level::error, "StellarCraft: buffer-to-texture copy is unavailable on this device");
        data->texture_error_logged = true;
        return false;
    }
    const auto usage = resource_usage::shader_resource | resource_usage::copy_dest;
    if (!device->create_resource(resource_desc(sc::kMaxOverlayW, sc::kMaxOverlayH, 1, 1,
                                 format::r8g8b8a8_unorm, 1, memory_heap::default_, usage),
                                 nullptr, resource_usage::shader_resource, &data->texture))
        return false;
    if (!device->create_resource_view(data->texture, resource_usage::shader_resource,
                                      resource_view_desc(format::r8g8b8a8_unorm), &data->view))
    {
        device->destroy_resource(data->texture);
        data->texture = {};
        return false;
    }
    const auto cleanup = [&]() {
        for (resource &upload : data->uploads)
        {
            device->destroy_resource(upload);
            upload = {};
        }
        device->destroy_fence(data->upload_fence);
        device->destroy_resource_view(data->view);
        device->destroy_resource(data->texture);
        data->upload_fence = {};
        data->view = {};
        data->texture = {};
    };
    if (!device->create_fence(0, fence_flags::none, &data->upload_fence))
    {
        cleanup();
        return false;
    }
    // A fixed row of 3840 RGBA8 pixels is 15360 bytes, aligned to DX12's 256-byte
    // texture pitch requirement. Three upload buffers are fenced before CPU reuse.
    for (resource &upload : data->uploads)
    {
        if (!device->create_resource(resource_desc(sc::kOverlaySlotBytes, memory_heap::upload,
                resource_usage::copy_source), nullptr, resource_usage::cpu_access, &upload))
        {
            cleanup();
            return false;
        }
    }
    data->resources_ready = true;
    return true;
}

bool upload_frame(effect_runtime *runtime, command_list *commands, RuntimeData *data)
{
    device *device = runtime->get_device();
    const std::uint64_t completed = device->get_completed_fence_value(data->upload_fence);
    std::uint32_t index = 3;
    for (std::uint32_t i = 0; i < 3; ++i)
    {
        const std::uint32_t candidate = (data->next_upload + i) % 3;
        if (data->upload_values[candidate] != UINT64_MAX && data->upload_values[candidate] <= completed)
        {
            index = candidate;
            break;
        }
    }
    if (index == 3)
        return false; // Preserve the current GPU frame while an upload buffer is busy.
    void *mapped = nullptr;
    if (!device->map_buffer_region(data->uploads[index], 0, sc::kOverlaySlotBytes,
                                  map_access::write_only, &mapped))
        return false;
    constexpr std::size_t pitch = static_cast<std::size_t>(sc::kMaxOverlayW) * 4;
    const std::size_t source_pitch = static_cast<std::size_t>(data->width) * 4;
    for (std::uint32_t y = 0; y < data->height; ++y)
        std::memcpy(static_cast<std::uint8_t *>(mapped) + y * pitch,
                    data->pixels.data() + y * source_pitch, source_pitch);
    device->unmap_buffer_region(data->uploads[index]);
    const subresource_box box { 0, 0, 0, data->width, data->height, 1 };
    commands->barrier(data->texture, resource_usage::shader_resource, resource_usage::copy_dest);
    commands->copy_buffer_to_texture(data->uploads[index], 0, sc::kMaxOverlayW,
                                     data->height, data->texture, 0, &box);
    commands->barrier(data->texture, resource_usage::copy_dest, resource_usage::shader_resource);
    data->upload_values[index] = UINT64_MAX; // Reserved until on_present submits a fence.
    data->next_upload = (index + 1) % 3;
    data->cpu_frame_dirty = false;
    data->gpu_frame_ready = true;
    data->gpu_width = data->width;
    data->gpu_height = data->height;
    data->gpu_bottom_up = data->bottom_up;
    return true;
}

void on_present(effect_runtime *runtime)
{
    auto *data = runtime->get_private_data<RuntimeData>();
    if (!data || !data->resources_ready)
        return;
    bool pending = false;
    for (const std::uint64_t value : data->upload_values)
        pending |= value == UINT64_MAX;
    if (!pending)
        return;
    // ReShade's present event occurs after it records effects. Flush those commands
    // before signalling, so the CPU never overwrites an upload still read by the GPU.
    auto *queue = runtime->get_command_queue();
    queue->flush_immediate_command_list();
    const std::uint64_t value = ++data->next_fence_value;
    const bool signalled = queue->signal(data->upload_fence, value);
    if (!signalled)
    {
        static bool reported=false;
        if (!reported) {
            reported=true;
            log(reshade::log::level::warning,"StellarCraft: upload fence signal failed; GPU idle fallback active");
        }
        queue->wait_idle();
    }
    for (std::uint64_t &slot_value : data->upload_values)
        if (slot_value == UINT64_MAX)
            slot_value = signalled ? value : 0;
}

LONG publication_sequence(const std::uint8_t *base)
{
    auto *sequence = reinterpret_cast<volatile LONG *>(
        const_cast<std::uint8_t *>(base) + sc::kOffOverlayCtl + offsetof(sc::OverlayCtl, pad));
    return InterlockedCompareExchange(sequence, 0, 0);
}

bool read_frame(std::uint8_t *base, RuntimeData *data)
{
    const LONG before = publication_sequence(base);
    if (before & 1)
        return false;
    const auto *ctl = reinterpret_cast<const sc::OverlayCtl *>(base + sc::kOffOverlayCtl);
    const auto *slot = reinterpret_cast<const sc::OverlaySlotHdr *>(base + sc::kOffOverlayCtl + sizeof(sc::OverlayCtl));
    const std::uint64_t frame = ctl->framesPublished;
    const std::uint32_t width = slot->width, height = slot->height, flags = slot->flags;
    if (!frame || frame == data->last_frame || !width || !height ||
        width > sc::kMaxOverlayW || height > sc::kMaxOverlayH)
        return false;
    const std::size_t bytes = static_cast<std::size_t>(width) * height * 4;
    data->cpu_frame_dirty = false; // A torn copy must not upload over a previously pending CPU frame.
    data->pixels.resize(bytes);
    std::memcpy(data->pixels.data(), base + sc::kOffOverlayPixels, bytes);
    if (publication_sequence(base) != before)
        return false;
    data->width = width;
    data->height = height;
    data->bottom_up = (flags & sc::kOverlayRowsBottomUp) != 0;
    data->last_frame = frame;
    return true;
}

void on_init_runtime(effect_runtime *runtime)
{
    if (runtime->get_device()->get_api() != device_api::d3d12)
        return;
    runtime->create_private_data<RuntimeData>();
    g_game_window.store(static_cast<HWND>(runtime->get_hwnd()), std::memory_order_release);
    HWND window = g_game_window.load();
    if (window && !g_original_window_proc)
    {
        g_original_window_proc = reinterpret_cast<WNDPROC>(GetWindowLongPtrW(window, GWLP_WNDPROC));
        SetLastError(0);
        if (!SetWindowLongPtrW(window, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(game_window_proc)) && GetLastError())
        {
            g_original_window_proc = nullptr;
            log(reshade::log::level::warning, "StellarCraft: game-window input filter installation failed");
        }
        else
            log(reshade::log::level::info, "StellarCraft: game-window input filter installed");
    }
    start_input();
    log(reshade::log::level::info, "StellarCraft: DX12 runtime initialized");
}

void on_destroy_runtime(effect_runtime *runtime)
{
    auto *data = runtime->get_private_data<RuntimeData>();
    if (!data)
        return;
    stop_input();
    HWND window = g_game_window.load();
    if (window && g_original_window_proc &&
        reinterpret_cast<WNDPROC>(GetWindowLongPtrW(window, GWLP_WNDPROC)) == game_window_proc)
    {
        SetWindowLongPtrW(window, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(g_original_window_proc));
        g_original_window_proc = nullptr;
    }
    if (data->view.handle || data->texture.handle || data->upload_fence.handle)
    {
        runtime->get_command_queue()->flush_immediate_command_list();
        runtime->get_command_queue()->wait_idle();
        runtime->get_device()->destroy_resource_view(data->view);
        runtime->get_device()->destroy_resource(data->texture);
        for (resource upload : data->uploads)
            runtime->get_device()->destroy_resource(upload);
        runtime->get_device()->destroy_fence(data->upload_fence);
    }
    runtime->destroy_private_data<RuntimeData>();
    g_game_window.store(nullptr, std::memory_order_release);
}

void on_reload_effects(effect_runtime *runtime)
{
    if (!runtime->get_private_data<RuntimeData>())
        return;
    const effect_technique technique = runtime->find_technique(kEffect, kTechnique);
    if (technique.handle)
    {
        runtime->set_technique_state(technique, true);
        log(reshade::log::level::info, "StellarCraft: FX compositor ready");
    }
    else
        log(reshade::log::level::warning, "StellarCraft: StellarCraft.fx technique was not loaded");
}

void on_begin_effects(effect_runtime *runtime, command_list *commands, resource_view, resource_view)
{
    auto *data = runtime->get_private_data<RuntimeData>();
    if (!data)
        return;
    auto *base = mapping();
    // Publish host output dimensions even in Eve mode, before visibility gating.
    std::uint32_t viewport_w = 0, viewport_h = 0;
    runtime->get_screenshot_width_and_height(&viewport_w, &viewport_h);
    if (base && viewport_w && viewport_h)
    {
        auto *sb = reinterpret_cast<sc::SbState *>(base + sc::kOffSbState);
        InterlockedExchange(reinterpret_cast<volatile LONG *>(&sb->viewportW), viewport_w);
        InterlockedExchange(reinterpret_cast<volatile LONG *>(&sb->viewportH), viewport_h);
    }
    const auto *header = base ? reinterpret_cast<const sc::Header *>(base) : nullptr;
    const std::uint64_t now = GetTickCount64();
    const bool mc_alive = header && header->mcHeartbeatMs != 0 &&
                          now >= header->mcHeartbeatMs && now - header->mcHeartbeatMs < 5000;
    const bool visible = mc_alive && g_steve_baseline.load(std::memory_order_acquire) &&
                         (sb_flags(base) & sc::kSbControlSteve) != 0 &&
                         (sb_flags(base) & (sc::kSbMenuOpen | sc::kSbLoading)) == 0;
    const effect_uniform_variable active = runtime->find_uniform_variable(kEffect, "McActive");
    if (!visible)
    {
        if (active.handle)
            runtime->set_uniform_value_float(active, 0.0f);
        return;
    }

    const bool has_new_frame = read_frame(base, data);
    data->cpu_frame_dirty |= has_new_frame;
    if (!data->last_frame || !ensure_texture(runtime, data))
    {
        if (active.handle)
            runtime->set_uniform_value_float(active, 0.0f);
        return;
    }
    if (data->cpu_frame_dirty && upload_frame(runtime, commands, data))
    {
        if (!data->first_upload_logged)
        {
            char message[160] = {};
            std::snprintf(message, sizeof(message), "StellarCraft: first MC frame uploaded %ux%u (frame=%llu, bottomUp=%u)",
                data->width, data->height, static_cast<unsigned long long>(data->last_frame), data->bottom_up ? 1u : 0u);
            log(reshade::log::level::info, message);
            data->first_upload_logged = true;
        }
    }
    if (active.handle)
        runtime->set_uniform_value_float(active, data->gpu_frame_ready ? 1.0f : 0.0f);
    if (!data->gpu_frame_ready)
        return;
    runtime->update_texture_bindings(kSemantic, data->view, data->view);

    std::uint32_t target_w = 0, target_h = 0;
    runtime->get_screenshot_width_and_height(&target_w, &target_h);
    const Layout layout = calculate_layout(data->gpu_width, data->gpu_height, target_w, target_h);
    {
        std::lock_guard<std::mutex> lock(g_layout_mutex);
        g_layout = layout;
    }
    const effect_uniform_variable rect = runtime->find_uniform_variable(kEffect, "McRect");
    if (rect.handle && layout.valid)
        runtime->set_uniform_value_float(rect, layout.x / target_w, layout.y / target_h,
                                         layout.width / target_w, layout.height / target_h);
    const effect_uniform_variable texture = runtime->find_uniform_variable(kEffect, "McTextureInfo");
    if (texture.handle)
        runtime->set_uniform_value_float(texture,
            static_cast<float>(data->gpu_width) / sc::kMaxOverlayW,
            static_cast<float>(data->gpu_height) / sc::kMaxOverlayH,
            data->gpu_bottom_up ? 1.0f : 0.0f, 0.0f);
    const effect_uniform_variable cursor = runtime->find_uniform_variable(kEffect, "McCursor");
    if (cursor.handle && layout.valid)
        runtime->set_uniform_value_float(cursor,
            g_cursor_x.load(std::memory_order_relaxed) / target_w,
            g_cursor_y.load(std::memory_order_relaxed) / target_h,
            (mc_flags(base) & sc::kMcScreenOpen) && g_cursor_valid.load() ? 1.0f : 0.0f,
            0.0f);
}
} // namespace

extern "C" __declspec(dllexport) const char *NAME = "StellarCraft";
extern "C" __declspec(dllexport) const char *DESCRIPTION = "In-process DX12 Minecraft UI and input bridge for Stellar Blade";

BOOL APIENTRY DllMain(HMODULE module, DWORD reason, LPVOID)
{
    if (reason == DLL_PROCESS_ATTACH)
    {
        g_module = module;
        DisableThreadLibraryCalls(module);
        if (!reshade::register_addon(module))
            return FALSE;
        reshade::register_event<reshade::addon_event::init_effect_runtime>(on_init_runtime);
        reshade::register_event<reshade::addon_event::destroy_effect_runtime>(on_destroy_runtime);
        reshade::register_event<reshade::addon_event::reshade_reloaded_effects>(on_reload_effects);
        reshade::register_event<reshade::addon_event::reshade_begin_effects>(on_begin_effects);
        reshade::register_event<reshade::addon_event::reshade_present>(on_present);
    }
    else if (reason == DLL_PROCESS_DETACH)
    {
        reshade::unregister_addon(module);
        // ReShade destroys the effect runtime before unloading add-ons. The mapping stays
        // mapped until process exit; no render callback can observe an unmapped pointer.
    }
    return TRUE;
}
