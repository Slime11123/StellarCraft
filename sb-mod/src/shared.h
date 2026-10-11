// StellarCraft — shared globals for the SB-side mod.
//
// g_pBase / g_hMapping are defined in stellar_blade_mod.cpp (which owns the mapping
// lifecycle) and shared with every other module via these extern declarations.
#pragma once

#include <cstdint>
#include <windows.h>

namespace stellarcraft::sb
{
    // Base address of the shared-memory mapping (created by stellar_blade_mod.cpp).
    extern uint8_t* g_pBase;
    extern HANDLE    g_hMapping;

    // Player transform snapshot, shared by read_player() and the collision exporter.
    struct PlayerSnapshot
    {
        double x, y, z;      // MC coords (converted from Unreal cm + Z-up)
        float  yaw, pitch;   // MC degrees
        bool   inGame;
    };

    // appearance.cpp: apply the MC player's skin/cape (called each frame).
    void update_appearance();
}
