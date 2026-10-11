// StellarCraft — DX12 Present hook + overlay compositor.
//
// Stage 2: composite Minecraft's overlay frame (HUD + screens) on top of Stellar Blade.
//
// How it works:
//   1. DllMain starts hook_thread (via sc_start_dx12_hook).
//   2. hook_thread builds a throwaway D3D12 device + swap chain to resolve the Present
//      vtable, then swaps vtable[8] (Present) with hkPresent.
//   3. hkPresent, on the first real call, pulls the game's ID3D12Device + command queue
//      out of the swap chain, builds a root signature + full-screen-quad PSO.
//   4. Every Present: read the MC overlay frame from shared memory, upload it to a D3D12
//      texture, and draw a full-screen quad before calling the original Present.
//
// Stellar Blade is a DirectX 12 game (amd_fidelityfx_dx12.dll), so the overlay must be
// drawn with a D3D12 pipeline (not D3D11 like SkyCraft's Overlay.cpp which targets Skyrim).

#include <windows.h>
#include <d3d12.h>
#include <d3dcompiler.h>
#include <dxgi1_4.h>
#include <cstdio>
#include <cstdarg>
#include <cstdint>
#include <cstring>
#include <intrin.h>

// This DLL is loaded as a version.dll PROXY at process start (before the engine
// initializes its D3D12 RHI), so the CreateSwapChain* hooks are installed BEFORE the
// game creates its swap chain — which is the only way to capture the PRESENT queue.
// The shared-memory mapping is owned by stellarcraft_shm.dll (loaded later by UE4SS);
// we open it lazily once it appears.

// ---- Protocol constants (mirror of protocol/stellarcraft_protocol.h + Proto.java) -----
static constexpr uint64_t kOffCollisionRing   = 0x20000;
static constexpr uint64_t kCollisionRingBytes = 32ULL << 20;
static constexpr uint64_t kOffOverlayCtl      = kOffCollisionRing + kCollisionRingBytes;
static constexpr uint64_t kOverlayCtlBytes    = 0x1000;
static constexpr uint64_t kOffOverlayPixels   = kOffOverlayCtl + kOverlayCtlBytes;
static constexpr uint32_t kMaxOverlayW        = 3840;
static constexpr uint32_t kMaxOverlayH        = 2160;
static constexpr uint64_t kOverlaySlotBytes   = (uint64_t)kMaxOverlayW * kMaxOverlayH * 4;
static constexpr uint32_t kOverlaySlots       = 3;
static constexpr uint32_t kOverlayDirty       = 1u << 2;

// OverlayCtl: state(u32) pad(u32) framesPublished(u64); then slot headers at +0x10, each 0x40.
static constexpr uint64_t kOvCtlState   = 0x00;
static constexpr uint64_t kOvCtlFrames  = 0x08;
static constexpr uint64_t kOvSlotHdr    = 0x10;
static constexpr uint64_t kOvSlotW      = 0x00;
static constexpr uint64_t kOvSlotH      = 0x04;
static constexpr uint64_t kOvSlotFlags  = 0x08;
static constexpr uint64_t kOvSlotFrame  = 0x10;

// -----------------------------------------------------------------------------------
// Logging.
// -----------------------------------------------------------------------------------
static void hook_log(const char* fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    char buf[1024];
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    OutputDebugStringA(buf);
    FILE* f = fopen("D:/SteamLibrary/steamapps/common/StellarBlade/SB/Binaries/Win64/ue4ss/Mods/StellarCraft/dx12_hook.log", "a");
    if (f) { fprintf(f, "%s\n", buf); fclose(f); }
}

// -----------------------------------------------------------------------------------
// Present hook.
// -----------------------------------------------------------------------------------
using PresentFn = HRESULT (STDMETHODCALLTYPE*)(IDXGISwapChain*, UINT, UINT);
static PresentFn g_originalPresent = nullptr;
static volatile LONG g_hookInstalled = 0;

// D3D12 overlay resources.
static ID3D12Device*            g_device = nullptr;
static ID3D12CommandQueue*      g_gameQueue = nullptr;   // the GAME's queue (captured via ECL hook)
static ID3D12RootSignature*     g_rootSig = nullptr;
static ID3D12PipelineState*     g_pso = nullptr;
static ID3D12Resource*          g_overlayTex = nullptr;
static ID3D12Resource*          g_upload = nullptr;
static UINT64                   g_uploadBytes = 0;
static ID3D12DescriptorHeap*    g_srvHeap = nullptr;
static ID3D12DescriptorHeap*    g_rtvHeap = nullptr;
static UINT                     g_rtvDescSize = 0;
static UINT                     g_srvDescSize = 0;
static UINT                     g_texW = 0, g_texH = 0;
static ID3D12CommandAllocator*  g_cmdAlloc = nullptr;
static ID3D12GraphicsCommandList* g_cmdList = nullptr;
static ID3D12Fence*             g_fence = nullptr;
static UINT64                   g_fenceValue = 0;
static HANDLE                   g_fenceEvent = nullptr;
static volatile LONG            g_pipeReady = 0;
static volatile LONG64          g_frameCount = 0;
static bool                     g_loggedDesc = false;

// ---- ExecuteCommandLists hook: capture the game's DIRECT command queue --------------
// WHY: D3D12 tracks resource states (e.g. the back buffer's PRESENT state) PER QUEUE.
// The game's queue transitioned the back buffer to PRESENT; submitting our overlay
// barrier (PRESENT -> RENDER_TARGET) on a queue of our own would be an illegal
// transition (our queue never saw that state) and trips the driver into
// DXGI_ERROR_DEVICE_REMOVED. The overlay MUST be submitted on the game's own queue
// (this is how ReShade/Steam overlay do it). Present itself gives us no way to find
// that queue, so we hook ID3D12CommandQueue::ExecuteCommandLists (vtable slot 8) and
// remember the first DIRECT queue the game uses.
using ExecuteCommandListsFn = void (STDMETHODCALLTYPE*)(ID3D12CommandQueue*, UINT, ID3D12CommandList* const*);
static ExecuteCommandListsFn g_originalECL = nullptr;

// The PRESENT queue — the one the game passed to CreateSwapChain* when creating its swap
// chain. D3D12's CreateSwapChain* take the command queue as their first argument, so
// hooking those calls captures it with 100% certainty. The back buffer's PRESENT resource
// state lives ONLY on this queue; submitting our overlay barrier chain on any other queue
// is an illegal transition that trips the driver into a GPU page fault
// (DXGI_ERROR_DEVICE_REMOVED, reason 0x887A002B).
static ID3D12CommandQueue* g_presentQueue = nullptr;

// Shared capture logic: verify the IUnknown* is really a DIRECT command queue, keep a ref.
static void capture_present_queue(IUnknown* unknownDevice)
{
    if (!unknownDevice || g_presentQueue) return;
    ID3D12CommandQueue* q = nullptr;
    if (FAILED(unknownDevice->QueryInterface(IID_PPV_ARGS(&q))) || !q) return;
    D3D12_COMMAND_QUEUE_DESC d = q->GetDesc();
    if (d.Type == D3D12_COMMAND_LIST_TYPE_DIRECT) {
        g_presentQueue = q; // keep the QueryInterface reference
        hook_log("[StellarCraft] captured PRESENT queue %p (from CreateSwapChain)", (void*)q);
    } else {
        q->Release();
    }
}

// ID3D12CommandQueue vtable layout (inherits IUnknown -> ID3D12Object -> ID3D12DeviceChild):
//   0 QueryInterface, 1 AddRef, 2 Release                    (IUnknown)
//   3 GetPrivateData, 4 SetPrivateData,
//   5 SetPrivateDataInterface, 6 SetName                     (ID3D12Object)
//   7 GetDevice                                              (ID3D12DeviceChild)
//   8 UpdateTileMappings, 9 CopyTileMappings,
//   10 ExecuteCommandLists, 11 SetMarker, 12 BeginEvent,
//   13 EndEvent, 14 Signal, 15 Wait                          (ID3D12CommandQueue)
// ExecuteCommandLists is vtable slot 10 (NOT 8 — 8 is UpdateTileMappings).
static constexpr int kEclVtableIndex = 10;

static void STDMETHODCALLTYPE hkExecuteCommandLists(ID3D12CommandQueue* queue, UINT numLists, ID3D12CommandList* const* lists)
{
    // Fallback capture: only used if the CreateSwapChain hooks missed the game's swap
    // chain creation (e.g. it happened before our hooks were installed).
    if (!g_presentQueue && !g_gameQueue && queue) {
        D3D12_COMMAND_QUEUE_DESC d = queue->GetDesc();
        if (d.Type == D3D12_COMMAND_LIST_TYPE_DIRECT) {
            queue->AddRef();
            g_gameQueue = queue;
            hook_log("[StellarCraft] fallback: captured game DIRECT command queue %p", (void*)queue);
        }
    }
    g_originalECL(queue, numLists, lists);
}

// ---- CreateSwapChain* hooks: capture the game's PRESENT queue -----------------------
// IDXGIFactory vtable layout:
//   IUnknown 0-2; IDXGIObject 3-6 (SetPrivateData/SetPrivateDataInterface/GetPrivateData/GetParent)
//   IDXGIFactory: 7 EnumAdapters, 8 MakeWindowAssociation, 9 GetWindowAssociation,
//                 10 CreateSwapChain, 11 CreateSoftwareAdapter
//   IDXGIFactory1: 12 EnumAdapters1, 13 IsCurrent
//   IDXGIFactory2: 14 IsWindowedStereoEnabled, 15 CreateSwapChainForHwnd,
//                  16 CreateSwapChainForCoreWindow, 17 GetSharedResourceAdapterLuid,
//                  18-23 stereo/occlusion, 24 CreateSwapChainForComposition
// In D3D12, the first argument of every CreateSwapChain* call IS the command queue that
// will present the swap chain — exactly the queue whose state machine owns the back
// buffer's PRESENT state.
static constexpr int kFactoryCreateSwapChain = 10;
static constexpr int kFactoryCreateSwapChainForHwnd = 15;
static constexpr int kFactoryCreateSwapChainForCoreWindow = 16;
static constexpr int kFactoryCreateSwapChainForComposition = 24;

using CreateSwapChainFn = HRESULT (STDMETHODCALLTYPE*)(IDXGIFactory*, IUnknown*, DXGI_SWAP_CHAIN_DESC*, IDXGISwapChain**);
using CreateSwapChainForHwndFn = HRESULT (STDMETHODCALLTYPE*)(IDXGIFactory2*, IUnknown*, HWND, const DXGI_SWAP_CHAIN_DESC1*, const DXGI_SWAP_CHAIN_FULLSCREEN_DESC*, IDXGIOutput*, IDXGISwapChain1**);
using CreateSwapChainForCoreWindowFn = HRESULT (STDMETHODCALLTYPE*)(IDXGIFactory2*, IUnknown*, IUnknown*, const DXGI_SWAP_CHAIN_DESC1*, IDXGIOutput*, IDXGISwapChain1**);
using CreateSwapChainForCompositionFn = HRESULT (STDMETHODCALLTYPE*)(IDXGIFactory2*, IUnknown*, const DXGI_SWAP_CHAIN_DESC1*, IDXGIOutput*, IDXGISwapChain1**);

static CreateSwapChainFn g_originalCreateSwapChain = nullptr;
static CreateSwapChainForHwndFn g_originalCreateSwapChainForHwnd = nullptr;
static CreateSwapChainForCoreWindowFn g_originalCreateSwapChainForCoreWindow = nullptr;
static CreateSwapChainForCompositionFn g_originalCreateSwapChainForComposition = nullptr;

static HRESULT STDMETHODCALLTYPE hkCreateSwapChain(IDXGIFactory* factory, IUnknown* device, DXGI_SWAP_CHAIN_DESC* desc, IDXGISwapChain** swapChain)
{
    capture_present_queue(device);
    return g_originalCreateSwapChain(factory, device, desc, swapChain);
}

static HRESULT STDMETHODCALLTYPE hkCreateSwapChainForHwnd(IDXGIFactory2* factory, IUnknown* device, HWND hwnd, const DXGI_SWAP_CHAIN_DESC1* desc, const DXGI_SWAP_CHAIN_FULLSCREEN_DESC* fullscreenDesc, IDXGIOutput* restrict, IDXGISwapChain1** swapChain)
{
    capture_present_queue(device);
    return g_originalCreateSwapChainForHwnd(factory, device, hwnd, desc, fullscreenDesc, restrict, swapChain);
}

static HRESULT STDMETHODCALLTYPE hkCreateSwapChainForCoreWindow(IDXGIFactory2* factory, IUnknown* device, IUnknown* coreWindow, const DXGI_SWAP_CHAIN_DESC1* desc, IDXGIOutput* restrict, IDXGISwapChain1** swapChain)
{
    capture_present_queue(device);
    return g_originalCreateSwapChainForCoreWindow(factory, device, coreWindow, desc, restrict, swapChain);
}

static HRESULT STDMETHODCALLTYPE hkCreateSwapChainForComposition(IDXGIFactory2* factory, IUnknown* device, const DXGI_SWAP_CHAIN_DESC1* desc, IDXGIOutput* restrict, IDXGISwapChain1** swapChain)
{
    capture_present_queue(device);
    return g_originalCreateSwapChainForComposition(factory, device, desc, restrict, swapChain);
}

// ---- Full-screen triangle vertex/pixel shader (HLSL) --------------------------------
static const char kOverlayShader[] = R"(
Texture2D<float4> overlay : register(t0);
SamplerState samp : register(s0);
struct VSOut { float4 pos : SV_Position; float2 uv : TEXCOORD0; };
VSOut VSMain(uint id : SV_VertexID) {
    VSOut o;
    float2 uv = float2((id << 1) & 2, id & 2);
    o.pos = float4(uv * float2(2, -2) + float2(-1, 1), 0, 1);
    o.uv = uv;
    return o;
}
float4 PSMain(VSOut i) : SV_Target {
    // Flip V: glReadPixels rows are bottom-up.
    float2 uv = float2(i.uv.x, 1.0 - i.uv.y);
    return overlay.Sample(samp, uv);
}
)";

// Compile an HLSL blob.
static bool compile_shader(const char* entry, const char* target, ID3DBlob** out)
{
    ID3DBlob* err = nullptr;
    HRESULT hr = D3DCompile(kOverlayShader, (SIZE_T)strlen(kOverlayShader), "sc_overlay", nullptr, nullptr,
                            entry, target, D3DCOMPILE_OPTIMIZATION_LEVEL3, 0, out, &err);
    if (FAILED(hr)) {
        hook_log("[StellarCraft] shader %s compile failed: %s", entry,
                 err ? (const char*)err->GetBufferPointer() : "?");
        if (err) err->Release();
        return false;
    }
    if (err) err->Release();
    return true;
}

// Lazily build the D3D12 overlay pipeline from the game's device/queue.
static void ensure_pipeline(IDXGISwapChain* sc)
{
    if (g_pipeReady) return;

    // Pull the device out of the swap chain.
    ID3D12Device* device = nullptr;
    if (FAILED(sc->GetDevice(IID_PPV_ARGS(&device))) || !device) {
        // Fallback: query the factory for the device (older swap chain paths).
        IDXGIDevice* dxgiDev = nullptr;
        if (SUCCEEDED(sc->GetDevice(IID_PPV_ARGS(&dxgiDev))) && dxgiDev) {
            dxgiDev->QueryInterface(IID_PPV_ARGS(&device));
            dxgiDev->Release();
        }
    }
    if (!device) {
        return; // retry next Present
    }
    g_device = device;

    // Query the swap chain's back-buffer format. This is CRITICAL: the PSO's RTV format
    // must match the actual back buffer format exactly. Stellar Blade uses a 10-bit HDR
    // back buffer (DXGI_FORMAT_R10G10B10A2_UNORM, value 24), NOT R8G8B8A8 — a mismatch
    // trips the driver into DXGI_ERROR_DEVICE_REMOVED on the first draw.
    DXGI_SWAP_CHAIN_DESC scDesc{};
    DXGI_FORMAT bbFormat = DXGI_FORMAT_R8G8B8A8_UNORM;
    if (SUCCEEDED(sc->GetDesc(&scDesc))) {
        bbFormat = scDesc.BufferDesc.Format;
        hook_log("[StellarCraft] back buffer format=%d", (int)bbFormat);
    }

    // Hook ExecuteCommandLists on THIS (the game's) device. The dummy queue we created
    // earlier in install_hook used a separate D3D12CreateDevice, whose command-queue
    // vtable may differ from the game device's (per-driver, per-device), so that earlier
    // hook never fired. Hooking a queue created from the game's own device guarantees the
    // vtable matches the game's queues, so we can capture its DIRECT queue here.
    if (!g_originalECL) {
        ID3D12CommandQueue* q = nullptr;
        D3D12_COMMAND_QUEUE_DESC qd{};
        qd.Type = D3D12_COMMAND_LIST_TYPE_DIRECT;
        qd.Flags = D3D12_COMMAND_QUEUE_FLAG_NONE;
        if (SUCCEEDED(device->CreateCommandQueue(&qd, IID_PPV_ARGS(&q))) && q) {
            void** qv = *reinterpret_cast<void***>(q);
            g_originalECL = reinterpret_cast<ExecuteCommandListsFn>(qv[kEclVtableIndex]);
            DWORD op = 0;
            if (VirtualProtect(&qv[kEclVtableIndex], sizeof(void*), PAGE_EXECUTE_READWRITE, &op)) {
                qv[kEclVtableIndex] = reinterpret_cast<void*>(&hkExecuteCommandLists);
                VirtualProtect(&qv[kEclVtableIndex], sizeof(void*), op, &op);
                hook_log("[StellarCraft] ECL hooked on game device (orig=%p)", (void*)g_originalECL);
            }
            q->Release();
        }
    }

    // NOTE: we deliberately do NOT create our own command queue for drawing. The overlay
    // must be submitted on the game's own DIRECT queue (captured by the ExecuteCommandLists
    // hook) — D3D12 resource-state tracking is per-queue, and only the game's queue has
    // seen the back buffer's PRESENT state transition. If the game queue hasn't been
    // captured yet, draw_overlay just skips this frame and we retry next Present.

    // Root signature: one SRV (t0) + one static sampler (s0).
    D3D12_ROOT_SIGNATURE_DESC rsd{};
    D3D12_DESCRIPTOR_RANGE range{};
    range.RangeType = D3D12_DESCRIPTOR_RANGE_TYPE_SRV;
    range.NumDescriptors = 1;
    range.BaseShaderRegister = 0;
    range.RegisterSpace = 0;
    range.OffsetInDescriptorsFromTableStart = D3D12_DESCRIPTOR_RANGE_OFFSET_APPEND;
    D3D12_ROOT_PARAMETER rp{};
    rp.ParameterType = D3D12_ROOT_PARAMETER_TYPE_DESCRIPTOR_TABLE;
    rp.DescriptorTable.NumDescriptorRanges = 1;
    rp.DescriptorTable.pDescriptorRanges = &range;
    rp.ShaderVisibility = D3D12_SHADER_VISIBILITY_PIXEL;
    D3D12_STATIC_SAMPLER_DESC samp{};
    samp.Filter = D3D12_FILTER_MIN_MAG_MIP_POINT;
    samp.AddressU = samp.AddressV = samp.AddressW = D3D12_TEXTURE_ADDRESS_MODE_CLAMP;
    samp.ShaderRegister = 0;
    samp.RegisterSpace = 0;
    samp.ShaderVisibility = D3D12_SHADER_VISIBILITY_PIXEL;
    rsd.NumParameters = 1;
    rsd.pParameters = &rp;
    rsd.NumStaticSamplers = 1;
    rsd.pStaticSamplers = &samp;
    rsd.Flags = D3D12_ROOT_SIGNATURE_FLAG_ALLOW_INPUT_ASSEMBLER_INPUT_LAYOUT;

    ID3DBlob* sig = nullptr;
    ID3DBlob* err = nullptr;
    if (FAILED(D3D12SerializeRootSignature(&rsd, D3D_ROOT_SIGNATURE_VERSION_1, &sig, &err))) {
        hook_log("[StellarCraft] root signature serialize failed: %s",
                 err ? (const char*)err->GetBufferPointer() : "?");
        if (err) err->Release();
        return;
    }
    device->CreateRootSignature(0, sig->GetBufferPointer(), sig->GetBufferSize(), IID_PPV_ARGS(&g_rootSig));
    sig->Release();

    // Compile shaders.
    ID3DBlob* vs = nullptr, * ps = nullptr;
    if (!compile_shader("VSMain", "vs_5_0", &vs) || !compile_shader("PSMain", "ps_5_0", &ps)) {
        return;
    }

    // PSO.
    D3D12_GRAPHICS_PIPELINE_STATE_DESC pd{};
    pd.pRootSignature = g_rootSig;
    pd.VS = { vs->GetBufferPointer(), vs->GetBufferSize() };
    pd.PS = { ps->GetBufferPointer(), ps->GetBufferSize() };
    pd.BlendState.AlphaToCoverageEnable = FALSE;
    pd.BlendState.IndependentBlendEnable = FALSE;
    // Standard alpha blend (overlay has premultiplied alpha from MC).
    D3D12_RENDER_TARGET_BLEND_DESC& rt = pd.BlendState.RenderTarget[0];
    rt.BlendEnable = TRUE;
    rt.SrcBlend = D3D12_BLEND_ONE;
    rt.DestBlend = D3D12_BLEND_INV_SRC_ALPHA;
    rt.BlendOp = D3D12_BLEND_OP_ADD;
    rt.SrcBlendAlpha = D3D12_BLEND_ONE;
    rt.DestBlendAlpha = D3D12_BLEND_INV_SRC_ALPHA;
    rt.BlendOpAlpha = D3D12_BLEND_OP_ADD;
    rt.RenderTargetWriteMask = D3D12_COLOR_WRITE_ENABLE_ALL;
    pd.SampleMask = UINT_MAX;
    pd.RasterizerState.FillMode = D3D12_FILL_MODE_SOLID;
    pd.RasterizerState.CullMode = D3D12_CULL_MODE_NONE;
    pd.RasterizerState.DepthClipEnable = FALSE;
    pd.PrimitiveTopologyType = D3D12_PRIMITIVE_TOPOLOGY_TYPE_TRIANGLE;
    pd.NumRenderTargets = 1;
    pd.RTVFormats[0] = bbFormat; // must match the swap chain's back buffer format
    pd.SampleDesc.Count = 1;

    if (FAILED(device->CreateGraphicsPipelineState(&pd, IID_PPV_ARGS(&g_pso)))) {
        hook_log("[StellarCraft] PSO creation failed");
        vs->Release(); ps->Release();
        return;
    }
    vs->Release(); ps->Release();

    // Descriptor heaps.
    D3D12_DESCRIPTOR_HEAP_DESC srvHeap{};
    srvHeap.Type = D3D12_DESCRIPTOR_HEAP_TYPE_CBV_SRV_UAV;
    srvHeap.NumDescriptors = 1;
    srvHeap.Flags = D3D12_DESCRIPTOR_HEAP_FLAG_SHADER_VISIBLE;
    device->CreateDescriptorHeap(&srvHeap, IID_PPV_ARGS(&g_srvHeap));
    g_srvDescSize = device->GetDescriptorHandleIncrementSize(D3D12_DESCRIPTOR_HEAP_TYPE_CBV_SRV_UAV);

    D3D12_DESCRIPTOR_HEAP_DESC rtvHeap{};
    rtvHeap.Type = D3D12_DESCRIPTOR_HEAP_TYPE_RTV;
    rtvHeap.NumDescriptors = 1;
    rtvHeap.Flags = D3D12_DESCRIPTOR_HEAP_FLAG_NONE;
    device->CreateDescriptorHeap(&rtvHeap, IID_PPV_ARGS(&g_rtvHeap));
    g_rtvDescSize = device->GetDescriptorHandleIncrementSize(D3D12_DESCRIPTOR_HEAP_TYPE_RTV);

    // Command allocator + list.
    device->CreateCommandAllocator(D3D12_COMMAND_LIST_TYPE_DIRECT, IID_PPV_ARGS(&g_cmdAlloc));
    device->CreateCommandList(0, D3D12_COMMAND_LIST_TYPE_DIRECT, g_cmdAlloc, nullptr, IID_PPV_ARGS(&g_cmdList));
    g_cmdList->Close();

    // Fence for GPU sync (wait for the previous frame's commands before reusing the allocator).
    device->CreateFence(0, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&g_fence));
    g_fenceEvent = CreateEventW(nullptr, FALSE, FALSE, nullptr);

    InterlockedExchange(&g_pipeReady, 1);
    hook_log("[StellarCraft] D3D12 overlay pipeline ready");
}

// Ensure the overlay texture matches the given dimensions (recreate on resize).
static void ensure_texture(uint32_t w, uint32_t h)
{
    if (g_overlayTex && g_texW == w && g_texH == h) return;
    if (g_overlayTex) { g_overlayTex->Release(); g_overlayTex = nullptr; }

    D3D12_RESOURCE_DESC rd{};
    rd.Dimension = D3D12_RESOURCE_DIMENSION_TEXTURE2D;
    rd.Alignment = 0;
    rd.Width = w;
    rd.Height = h;
    rd.DepthOrArraySize = 1;
    rd.MipLevels = 1;
    rd.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    rd.SampleDesc.Count = 1;
    rd.Layout = D3D12_TEXTURE_LAYOUT_UNKNOWN;
    rd.Flags = D3D12_RESOURCE_FLAG_NONE;

    D3D12_HEAP_PROPERTIES hp{};
    hp.Type = D3D12_HEAP_TYPE_DEFAULT;

    if (FAILED(g_device->CreateCommittedResource(&hp, D3D12_HEAP_FLAG_NONE, &rd,
        D3D12_RESOURCE_STATE_COPY_DEST, nullptr, IID_PPV_ARGS(&g_overlayTex)))) {
        hook_log("[StellarCraft] overlay texture %ux%u creation failed", w, h);
        return;
    }

    // Create the SRV.
    D3D12_SHADER_RESOURCE_VIEW_DESC sv{};
    sv.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    sv.ViewDimension = D3D12_SRV_DIMENSION_TEXTURE2D;
    sv.Shader4ComponentMapping = D3D12_DEFAULT_SHADER_4_COMPONENT_MAPPING;
    sv.Texture2D.MipLevels = 1;
    D3D12_CPU_DESCRIPTOR_HANDLE hdl = g_srvHeap->GetCPUDescriptorHandleForHeapStart();
    g_device->CreateShaderResourceView(g_overlayTex, &sv, hdl);

    g_texW = w;
    g_texH = h;
    hook_log("[StellarCraft] overlay texture %ux%u", w, h);
}

// Read the MC overlay frame (slot 0) from shared memory and draw it.
// Only draws when MC is in STEVE mode (the player pressed F4 to hand control to Steve),
// so the MC UI appears only after switching, not immediately on entering the game.
static ID3D12CommandQueue* overlay_queue()
{
    // Prefer the PRESENT queue (captured from CreateSwapChain — the queue whose state
    // machine owns the back buffer's PRESENT state). Fall back to the first DIRECT queue
    // seen via ExecuteCommandLists if the swap-chain hooks missed the creation.
    return g_presentQueue ? g_presentQueue : g_gameQueue;
}

// The shared-memory mapping created by stellarcraft_shm.dll (opened lazily — the UE4SS
// mod creates it later in the process lifetime than our early proxy load).
static HANDLE    g_shmHandle = nullptr;
static uintptr_t g_shmBase = 0;

static void ensure_shm()
{
    if (g_shmBase) return;
    if (!g_shmHandle) {
        g_shmHandle = OpenFileMappingW(FILE_MAP_ALL_ACCESS, FALSE, L"Local\\StellarCraft_v1");
        if (!g_shmHandle) return;
    }
    void* p = MapViewOfFile(g_shmHandle, FILE_MAP_ALL_ACCESS, 0, 0, 0);
    if (p) {
        g_shmBase = reinterpret_cast<uintptr_t>(p);
        hook_log("[StellarCraft] proxy: shared memory mapped at %p", p);
    }
}

static void draw_overlay(IDXGISwapChain* sc)
{
    if (!g_pipeReady) return;
    if (!overlay_queue()) return; // no game queue captured yet
    ensure_shm();
    if (!g_shmBase) return;
    uintptr_t base = g_shmBase;

    // MC->SB control flag lives in McState.flags (OFF_MC_STATE = 0x200, flags at +0x04).
    // McFlags.CONTROL_STEVE = 1<<8 = 256. Only composite the overlay while MC is in STEVE
    // mode (otherwise the MC world/HUD is not shown in Stellar Blade).
    int32_t mcFlags = *reinterpret_cast<int32_t*>(base + 0x200 + 0x04);
    if (!(mcFlags & 0x100)) {
        return; // not in STEVE mode — do not draw the overlay
    }

    // Defensive: if the device has already been removed (e.g. by the game's Streamline
    // /DLSSG plugin after an earlier fault), do NOT submit more work — it only makes
    // things worse and can crash inside our own submission path.
    if (g_device) {
        HRESULT removed = g_device->GetDeviceRemovedReason();
        if (FAILED(removed)) {
            static bool loggedRemoval = false;
            if (!loggedRemoval) {
                loggedRemoval = true;
                hook_log("[StellarCraft] device removed (hr=0x%08lX); overlay disabled", (unsigned long)removed);
            }
            return;
        }
    }

    uint8_t* ctl = reinterpret_cast<uint8_t*>(base) + kOffOverlayCtl;
    // Check dirty bit (state bit 2).
    uint32_t state = *reinterpret_cast<uint32_t*>(ctl + kOvCtlState);
    if (!(state & kOverlayDirty)) return;

    uint32_t w = *reinterpret_cast<uint32_t*>(ctl + kOvSlotHdr + kOvSlotW);
    uint32_t h = *reinterpret_cast<uint32_t*>(ctl + kOvSlotHdr + kOvSlotH);
    if (w == 0 || h == 0 || w > kMaxOverlayW || h > kMaxOverlayH) return;

    const uint8_t* pixels = reinterpret_cast<uint8_t*>(base) + kOffOverlayPixels;
    uint64_t rowBytes = (uint64_t)w * 4;
    uint64_t total = rowBytes * h;
    if (total > kOverlaySlotBytes) return;

    ensure_texture(w, h);
    if (!g_overlayTex) return;

    // Reuse an upload buffer (resize only when needed), so we don't create/release one
    // every frame (which races with the GPU's async CopyTextureRegion).
    if (!g_upload || g_uploadBytes < total) {
        if (g_upload) { g_upload->Release(); g_upload = nullptr; }
        D3D12_HEAP_PROPERTIES uh{};
        uh.Type = D3D12_HEAP_TYPE_UPLOAD;
        D3D12_RESOURCE_DESC urd{};
        urd.Dimension = D3D12_RESOURCE_DIMENSION_BUFFER;
        urd.Width = total;
        urd.Height = 1;
        urd.DepthOrArraySize = 1;
        urd.MipLevels = 1;
        urd.SampleDesc.Count = 1;
        urd.Layout = D3D12_TEXTURE_LAYOUT_ROW_MAJOR;
        if (FAILED(g_device->CreateCommittedResource(&uh, D3D12_HEAP_FLAG_NONE, &urd,
            D3D12_RESOURCE_STATE_GENERIC_READ, nullptr, IID_PPV_ARGS(&g_upload)))) {
            return;
        }
        g_uploadBytes = total;
    }
    void* mapped = nullptr;
    g_upload->Map(0, nullptr, &mapped);
    if (mapped) {
        memcpy(mapped, pixels, total);
        g_upload->Unmap(0, nullptr);
    }

    // Get the back buffer RTV.
    ID3D12Resource* backBuffer = nullptr;
    if (FAILED(sc->GetBuffer(0, IID_PPV_ARGS(&backBuffer))) || !backBuffer) {
        return;
    }
    D3D12_CPU_DESCRIPTOR_HANDLE rtvH = g_rtvHeap->GetCPUDescriptorHandleForHeapStart();
    g_device->CreateRenderTargetView(backBuffer, nullptr, rtvH);

    // Back-buffer size (NOT the overlay size — the viewport must cover the whole screen).
    D3D12_RESOURCE_DESC bbDesc = backBuffer->GetDesc();
    const float bbW = (float)bbDesc.Width;
    const float bbH = (float)bbDesc.Height;

    ID3D12CommandQueue* queue = overlay_queue();
    if (!queue) { backBuffer->Release(); return; }

    // Wait for the previous frame's overlay work before reusing the allocator/list.
    if (g_fence && g_fenceEvent) {
        queue->Signal(g_fence, ++g_fenceValue);
        if (g_fence->GetCompletedValue() < g_fenceValue) {
            g_fence->SetEventOnCompletion(g_fenceValue, g_fenceEvent);
            WaitForSingleObject(g_fenceEvent, 1000);
        }
    }

    // Reset and record.
    g_cmdAlloc->Reset();
    g_cmdList->Reset(g_cmdAlloc, g_pso);

    D3D12_RESOURCE_BARRIER b1{};
    b1.Type = D3D12_RESOURCE_BARRIER_TYPE_TRANSITION;
    b1.Transition.pResource = g_overlayTex;
    b1.Transition.StateBefore = D3D12_RESOURCE_STATE_COPY_DEST;
    b1.Transition.StateAfter = D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE;
    b1.Transition.Subresource = D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES;
    g_cmdList->ResourceBarrier(1, &b1);

    // Copy upload -> texture (tightly packed rows; RowPitch = rowBytes).
    D3D12_TEXTURE_COPY_LOCATION dst{};
    dst.pResource = g_overlayTex;
    dst.Type = D3D12_TEXTURE_COPY_TYPE_SUBRESOURCE_INDEX;
    dst.SubresourceIndex = 0;
    D3D12_TEXTURE_COPY_LOCATION src{};
    src.pResource = g_upload;
    src.Type = D3D12_TEXTURE_COPY_TYPE_PLACED_FOOTPRINT;
    src.PlacedFootprint.Offset = 0;
    src.PlacedFootprint.Footprint.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    src.PlacedFootprint.Footprint.Width = w;
    src.PlacedFootprint.Footprint.Height = h;
    src.PlacedFootprint.Footprint.Depth = 1;
    src.PlacedFootprint.Footprint.RowPitch = (UINT)rowBytes;
    g_cmdList->CopyTextureRegion(&dst, 0, 0, 0, &src, nullptr);

    D3D12_RESOURCE_BARRIER b2{};
    b2.Type = D3D12_RESOURCE_BARRIER_TYPE_TRANSITION;
    b2.Transition.pResource = g_overlayTex;
    b2.Transition.StateBefore = D3D12_RESOURCE_STATE_PIXEL_SHADER_RESOURCE;
    b2.Transition.StateAfter = D3D12_RESOURCE_STATE_COPY_DEST;
    b2.Transition.Subresource = D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES;
    g_cmdList->ResourceBarrier(1, &b2);

    // Draw the full-screen triangle to the back buffer.
    // State: PRESENT -> RENDER_TARGET -> PRESENT. At Present-hook time the game has just
    // transitioned the back buffer to PRESENT on its queue, so on that same queue this
    // transition chain is legal. Crucially we hand the buffer back in PRESENT state too —
    // the game's Streamline/DLSSG plugin (which wraps this same Present) expects the back
    // buffer in PRESENT and fails with DXGI_ERROR_DEVICE_REMOVED if it finds anything else.
    D3D12_RESOURCE_BARRIER bb{};
    bb.Type = D3D12_RESOURCE_BARRIER_TYPE_TRANSITION;
    bb.Transition.pResource = backBuffer;
    bb.Transition.StateBefore = D3D12_RESOURCE_STATE_PRESENT;
    bb.Transition.StateAfter = D3D12_RESOURCE_STATE_RENDER_TARGET;
    bb.Transition.Subresource = D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES;
    g_cmdList->ResourceBarrier(1, &bb);

    D3D12_CPU_DESCRIPTOR_HANDLE rtv = g_rtvHeap->GetCPUDescriptorHandleForHeapStart();
    g_cmdList->OMSetRenderTargets(1, &rtv, FALSE, nullptr);
    g_cmdList->SetGraphicsRootSignature(g_rootSig);
    g_cmdList->SetPipelineState(g_pso);
    g_cmdList->SetDescriptorHeaps(1, &g_srvHeap);
    g_cmdList->SetGraphicsRootDescriptorTable(0, g_srvHeap->GetGPUDescriptorHandleForHeapStart());
    g_cmdList->IASetPrimitiveTopology(D3D_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
    D3D12_VIEWPORT vp{ 0, 0, bbW, bbH, 0, 1 };
    D3D12_RECT sr{ 0, 0, (LONG)bbW, (LONG)bbH };
    g_cmdList->RSSetViewports(1, &vp);
    g_cmdList->RSSetScissorRects(1, &sr);
    g_cmdList->DrawInstanced(3, 1, 0, 0);

    // Back to PRESENT before the real Present.
    D3D12_RESOURCE_BARRIER bb2{};
    bb2.Type = D3D12_RESOURCE_BARRIER_TYPE_TRANSITION;
    bb2.Transition.pResource = backBuffer;
    bb2.Transition.StateBefore = D3D12_RESOURCE_STATE_RENDER_TARGET;
    bb2.Transition.StateAfter = D3D12_RESOURCE_STATE_PRESENT; // hand back in PRESENT (Streamline expects this)
    bb2.Transition.Subresource = D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES;
    g_cmdList->ResourceBarrier(1, &bb2);

    g_cmdList->Close();
    ID3D12CommandList* lists[] = { g_cmdList };
    // Submit on the PRESENT queue — the one the game passed to CreateSwapChain. Only on
    // this queue is the back buffer's PRESENT->RENDER_TARGET->PRESENT chain a legal state
    // transition (D3D12 tracks resource state per-queue).
    queue->ExecuteCommandLists(1, lists);
    // Signal the fence so the next frame waits for this one (no nullptr fence).
    if (g_fence) {
        queue->Signal(g_fence, ++g_fenceValue);
    }

    backBuffer->Release();
}

static HRESULT STDMETHODCALLTYPE hkPresent(IDXGISwapChain* swapChain, UINT syncInterval, UINT flags)
{
    if (!g_loggedDesc) {
        DXGI_SWAP_CHAIN_DESC desc{};
        if (SUCCEEDED(swapChain->GetDesc(&desc))) {
            g_loggedDesc = true;
            hook_log("[StellarCraft] DX12 swapchain %ux%u, format=%d, buffers=%u, windowed=%d",
                     desc.BufferDesc.Width, desc.BufferDesc.Height, (int)desc.BufferDesc.Format,
                     desc.BufferCount, desc.Windowed);
        }
    }

    ensure_pipeline(swapChain);
    draw_overlay(swapChain);

    ULONGLONG n = InterlockedIncrement64(&g_frameCount);
    if (n <= 5 || (n % 600 == 0)) {
        hook_log("[StellarCraft] Present frame #%llu (pipe=%d)", (unsigned long long)n, (int)g_pipeReady);
    }

    return g_originalPresent(swapChain, syncInterval, flags);
}

// -----------------------------------------------------------------------------------
// Hook installation.
// -----------------------------------------------------------------------------------
static constexpr int kPresentVtableIndex = 8;

static void install_hook()
{
    if (InterlockedCompareExchange(&g_hookInstalled, 1, 0) != 0) return;

    HMODULE dxgi = LoadLibraryW(L"dxgi.dll");
    if (!dxgi) { hook_log("[StellarCraft] dxgi.dll load failed"); return; }
    auto pCreateFactory2 = reinterpret_cast<HRESULT(WINAPI*)(UINT, REFIID, void**)>(GetProcAddress(dxgi, "CreateDXGIFactory2"));
    if (!pCreateFactory2) { hook_log("[StellarCraft] CreateDXGIFactory2 not found"); return; }

    IDXGIFactory4* factory = nullptr;
    if (FAILED(pCreateFactory2(0, IID_PPV_ARGS(&factory))) || !factory) { hook_log("[StellarCraft] CreateDXGIFactory2 failed"); return; }

    WNDCLASSEXW wc{};
    wc.cbSize = sizeof(wc);
    wc.lpfnWndProc = DefWindowProcW;
    wc.lpszClassName = L"SC_DX12_DUMMY";
    wc.hInstance = GetModuleHandleW(nullptr);
    RegisterClassExW(&wc);
    HWND hwnd = CreateWindowExW(0, wc.lpszClassName, L"sc", WS_OVERLAPPED, 0, 0, 16, 16, nullptr, nullptr, wc.hInstance, nullptr);

    ID3D12Device* device = nullptr;
    HMODULE d3d12 = LoadLibraryW(L"d3d12.dll");
    auto pD3D12CreateDevice = reinterpret_cast<HRESULT(WINAPI*)(IUnknown*, D3D_FEATURE_LEVEL, REFIID, void**)>(GetProcAddress(d3d12, "D3D12CreateDevice"));
    if (!pD3D12CreateDevice || FAILED(pD3D12CreateDevice(nullptr, D3D_FEATURE_LEVEL_11_0, IID_PPV_ARGS(&device))) || !device) {
        hook_log("[StellarCraft] D3D12CreateDevice failed"); return;
    }

    ID3D12CommandQueue* queue = nullptr;
    D3D12_COMMAND_QUEUE_DESC qd{};
    qd.Type = D3D12_COMMAND_LIST_TYPE_DIRECT;
    device->CreateCommandQueue(&qd, IID_PPV_ARGS(&queue));

    DXGI_SWAP_CHAIN_DESC sd{};
    sd.BufferCount = 2;
    sd.BufferDesc.Width = 16;
    sd.BufferDesc.Height = 16;
    sd.BufferDesc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    sd.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
    sd.SampleDesc.Count = 1;
    sd.OutputWindow = hwnd;
    sd.Windowed = TRUE;
    sd.SwapEffect = DXGI_SWAP_EFFECT_FLIP_DISCARD;

    IDXGISwapChain* sc = nullptr;
    factory->CreateSwapChain(queue, &sd, &sc);
    if (!sc) { hook_log("[StellarCraft] dummy CreateSwapChain failed"); return; }

    // Hook IDXGISwapChain::Present (vtable slot 8).
    void** vtable = *reinterpret_cast<void***>(sc);
    g_originalPresent = reinterpret_cast<PresentFn>(vtable[kPresentVtableIndex]);

    DWORD oldProtect = 0;
    if (VirtualProtect(&vtable[kPresentVtableIndex], sizeof(void*), PAGE_EXECUTE_READWRITE, &oldProtect)) {
        vtable[kPresentVtableIndex] = reinterpret_cast<void*>(&hkPresent);
        VirtualProtect(&vtable[kPresentVtableIndex], sizeof(void*), oldProtect, &oldProtect);
        hook_log("[StellarCraft] dx12 hook: Present hooked (orig=%p)", (void*)g_originalPresent);
    } else {
        hook_log("[StellarCraft] VirtualProtect on vtable failed");
    }

    // NOTE: ExecuteCommandLists is hooked later, in ensure_pipeline(), on the GAME's own
    // device (a queue created from a separate D3D12CreateDevice may have a different
    // vtable and its hook would never fire — that's why it's not hooked here anymore).

    // Hook the factory's CreateSwapChain* methods so we capture the game's PRESENT queue
    // (the queue it passes to CreateSwapChain* — the one whose state machine owns the
    // back buffer's PRESENT state). Submitting the overlay anywhere else trips a GPU
    // page fault (DXGI_ERROR_DEVICE_REMOVED, 0x887A002B).
    void** fvtable = *reinterpret_cast<void***>(factory);
    struct FactoryHook { int slot; void** hook; void** original; const char* name; };
    FactoryHook hooks[] = {
        { kFactoryCreateSwapChain,            reinterpret_cast<void**>(&hkCreateSwapChain),              reinterpret_cast<void**>(&g_originalCreateSwapChain),              "CreateSwapChain" },
        { kFactoryCreateSwapChainForHwnd,     reinterpret_cast<void**>(&hkCreateSwapChainForHwnd),       reinterpret_cast<void**>(&g_originalCreateSwapChainForHwnd),       "CreateSwapChainForHwnd" },
        { kFactoryCreateSwapChainForCoreWindow, reinterpret_cast<void**>(&hkCreateSwapChainForCoreWindow), reinterpret_cast<void**>(&g_originalCreateSwapChainForCoreWindow), "CreateSwapChainForCoreWindow" },
        { kFactoryCreateSwapChainForComposition, reinterpret_cast<void**>(&hkCreateSwapChainForComposition), reinterpret_cast<void**>(&g_originalCreateSwapChainForComposition), "CreateSwapChainForComposition" },
    };
    for (auto& h : hooks) {
        *h.original = fvtable[h.slot];
        DWORD op = 0;
        if (VirtualProtect(&fvtable[h.slot], sizeof(void*), PAGE_EXECUTE_READWRITE, &op)) {
            fvtable[h.slot] = *h.hook;
            VirtualProtect(&fvtable[h.slot], sizeof(void*), op, &op);
            hook_log("[StellarCraft] dx12 hook: %s hooked (orig=%p)", h.name, *h.original);
        }
    }

    if (queue) queue->Release();
}

static DWORD WINAPI hook_thread(LPVOID)
{
    // Install as early as possible: the game's swap chain is created during engine init,
    // and the CreateSwapChain hooks must be in place BEFORE that happens. 500 ms into the
    // No Sleep: we are loaded as a version.dll proxy at process start, so the hooks must
    // go up as early as possible — before the engine initializes its D3D12 RHI and creates
    // the swap chain (the CreateSwapChain hooks are time-critical).
    hook_log("[StellarCraft] dx12 hook thread starting (version.dll proxy)...");
    install_hook();
    return 0;
}

extern "C" __declspec(dllexport) void sc_start_dx12_hook_proxy()
{
    CreateThread(nullptr, 0, hook_thread, nullptr, 0, nullptr);
}
