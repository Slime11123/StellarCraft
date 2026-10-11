-- StellarCraft — Stellar Blade side (UE4SS C++ mod) build script.
--
-- Builds the mod DLL. The skeleton currently compiles WITHOUT the SDK headers
-- (they are stubbed/commentd), so it verifies the protocol + shared-memory +
-- event-loop scaffolding. Once the SDK reverse-engineering lands, flip
-- HAS_SDK below to true to pull in the generated headers.
--
-- Requires: xmake + Visual Studio 2022 (MSVC, C++ Desktop workload).
-- Build:    xmake    (in this directory)
-- Output:   build/windows/x64/release/stellarcraft_sb.dll

set_project("stellarcraft_sb")
set_version("0.1.0")

add_rules("mode.debug", "mode.release")

-- Optionally compile against the dumped SDK headers (default off until RE lands).
option("sdk")
    set_default(false)
    set_showmenu(true)
    set_description("Compile against the dumped Stellar Blade SDK headers")

target("stellarcraft_sb")
    set_kind("shared")          -- DLL
    set_languages("cxx17")

    -- UE4SS mods load into the game process; no CRT linkage surprises, keep it lean.
    add_cxxflags("/permissive-", "/Zc:__cplusplus")

    -- Source files (all modules).
    add_files("src/*.cpp")

    -- Protocol header is outside the src dir.
    add_includedirs("src", "../protocol")

    if has_config("sdk") then
        add_includedirs("src/SDK")
        add_defines("STELLARCRAFT_HAS_SDK=1")
    end

    -- Link against Windows APIs used by shared memory / input / rendering.
    add_links("user32", "kernel32", "gdi32")

    if is_mode("debug") then
        add_defines("STELLARCRAFT_DEBUG=1")
    end

    -- UE4SS loads the DLL into the game; keep symbols stripped in release.
    if is_mode("release") then
        set_symbols("none")
        set_optimize("fastest")
    end
