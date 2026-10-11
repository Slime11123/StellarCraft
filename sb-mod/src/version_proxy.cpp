// StellarCraft — version.dll proxy loader.
//
// Loaded by Windows at PROCESS START (before the engine initializes), which lets the
// DX12 overlay hooks (dx12_hook.cpp) be installed BEFORE the game creates its swap
// chain. Capturing the game's PRESENT queue requires hooking IDXGIFactory::CreateSwapChain*
// ahead of time — UE4SS's Lua mods load far too late for that, which is why the overlay
// lives here instead of in stellarcraft_shm.dll.
//
// All real version.dll exports are forwarded in code to the SYSTEM copy at
// C:\Windows\System32\version.dll (loaded by ABSOLUTE path — a bare "version.dll" would
// resolve to THIS module and recurse forever).

#include <windows.h>

// DX12 overlay hook entry (defined in dx12_hook.cpp) — installs all hooks on a worker
// thread as early as possible.
extern "C" void sc_start_dx12_hook_proxy();

// ---- Forwarding to the real system version.dll ---------------------------------------
static HMODULE g_realVersion = nullptr;

static FARPROC realProc(const char* name)
{
    if (!g_realVersion) {
        // Absolute path is mandatory: a bare module name would resolve to this proxy.
        g_realVersion = LoadLibraryW(L"C:\\Windows\\System32\\version.dll");
    }
    return g_realVersion ? GetProcAddress(g_realVersion, name) : nullptr;
}

// Signature-compatible forwarders (version.dll's public API).
BOOL WINAPI GetFileVersionInfoA(LPCSTR a, DWORD b, DWORD c, LPVOID d)
{ using F = BOOL(WINAPI*)(LPCSTR, DWORD, DWORD, LPVOID); auto f = (F)realProc("GetFileVersionInfoA"); return f ? f(a, b, c, d) : FALSE; }
BOOL WINAPI GetFileVersionInfoW(LPCWSTR a, DWORD b, DWORD c, LPVOID d)
{ using F = BOOL(WINAPI*)(LPCWSTR, DWORD, DWORD, LPVOID); auto f = (F)realProc("GetFileVersionInfoW"); return f ? f(a, b, c, d) : FALSE; }
BOOL WINAPI GetFileVersionInfoExA(DWORD a, LPCSTR b, DWORD c, DWORD d, LPVOID e)
{ using F = BOOL(WINAPI*)(DWORD, LPCSTR, DWORD, DWORD, LPVOID); auto f = (F)realProc("GetFileVersionInfoExA"); return f ? f(a, b, c, d, e) : FALSE; }
BOOL WINAPI GetFileVersionInfoExW(DWORD a, LPCWSTR b, DWORD c, DWORD d, LPVOID e)
{ using F = BOOL(WINAPI*)(DWORD, LPCWSTR, DWORD, DWORD, LPVOID); auto f = (F)realProc("GetFileVersionInfoExW"); return f ? f(a, b, c, d, e) : FALSE; }
DWORD WINAPI GetFileVersionInfoSizeA(LPCSTR a, LPDWORD b)
{ using F = DWORD(WINAPI*)(LPCSTR, LPDWORD); auto f = (F)realProc("GetFileVersionInfoSizeA"); return f ? f(a, b) : 0; }
DWORD WINAPI GetFileVersionInfoSizeW(LPCWSTR a, LPDWORD b)
{ using F = DWORD(WINAPI*)(LPCWSTR, LPDWORD); auto f = (F)realProc("GetFileVersionInfoSizeW"); return f ? f(a, b) : 0; }
DWORD WINAPI GetFileVersionInfoSizeExA(DWORD a, LPCSTR b, LPDWORD c)
{ using F = DWORD(WINAPI*)(DWORD, LPCSTR, LPDWORD); auto f = (F)realProc("GetFileVersionInfoSizeExA"); return f ? f(a, b, c) : 0; }
DWORD WINAPI GetFileVersionInfoSizeExW(DWORD a, LPCWSTR b, LPDWORD c)
{ using F = DWORD(WINAPI*)(DWORD, LPCWSTR, LPDWORD); auto f = (F)realProc("GetFileVersionInfoSizeExW"); return f ? f(a, b, c) : 0; }
BOOL WINAPI GetFileVersionInfoByHandle(DWORD a, HANDLE b, LPVOID c)
{ using F = BOOL(WINAPI*)(DWORD, HANDLE, LPVOID); auto f = (F)realProc("GetFileVersionInfoByHandle"); return f ? f(a, b, c) : FALSE; }
BOOL WINAPI VerQueryValueA(LPCSTR a, LPCSTR b, LPVOID* c, UINT* d)
{ using F = BOOL(WINAPI*)(LPCSTR, LPCSTR, LPVOID*, UINT*); auto f = (F)realProc("VerQueryValueA"); return f ? f(a, b, c, d) : FALSE; }
BOOL WINAPI VerQueryValueW(LPCWSTR a, LPCWSTR b, LPVOID* c, UINT* d)
{ using F = BOOL(WINAPI*)(LPCWSTR, LPCWSTR, LPVOID*, UINT*); auto f = (F)realProc("VerQueryValueW"); return f ? f(a, b, c, d) : FALSE; }
DWORD WINAPI VerLanguageNameA(DWORD a, LPSTR b, DWORD c)
{ using F = DWORD(WINAPI*)(DWORD, LPSTR, DWORD); auto f = (F)realProc("VerLanguageNameA"); return f ? f(a, b, c) : 0; }
DWORD WINAPI VerLanguageNameW(DWORD a, LPWSTR b, DWORD c)
{ using F = DWORD(WINAPI*)(DWORD, LPWSTR, DWORD); auto f = (F)realProc("VerLanguageNameW"); return f ? f(a, b, c) : 0; }

static DWORD WINAPI proxy_init_thread(LPVOID)
{
    sc_start_dx12_hook_proxy();
    return 0;
}

BOOL WINAPI DllMain(HINSTANCE, DWORD reason, LPVOID)
{
    if (reason == DLL_PROCESS_ATTACH) {
        DisableThreadLibraryCalls(GetModuleHandleW(L"version.dll"));
        // Start the hook thread immediately — the earlier the better. CreateThread from
        // DllMain is safe (the thread's start routine runs after the loader lock clears).
        CreateThread(nullptr, 0, proxy_init_thread, nullptr, 0, nullptr);
    }
    return TRUE;
}
