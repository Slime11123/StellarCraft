// StellarCraft — damage applier (Stellar Blade side).
//
// Applies Minecraft-sourced damage (melee, arrows, and especially TNT explosions) to the
// real Stellar Blade characters. This is the SB-side counterpart of dev.stellarcraft.world
// .DamageBridge / ExplosionMixin.
//
// Flow for an explosion:
//   MC writes kEvExplosion events (one per affected actor) into the event ring,
//   carrying the actor uid and the scaled damage.
//   SB reads them, maps uid -> real actor, and calls Stellar Blade's own damage pipeline
//   so the Naytiba reacts authentically: health loss, hit reaction/stagger, aggro, death.
//
// NOTE: skeleton. The real damage entry point is found via UE4SS's live property editor /
// FName dump. A robust fallback is to directly modify the actor's health property, then
// trigger an aggro/impact reaction.

#include <windows.h>
#include <cstdint>

#include "../../protocol/stellarcraft_protocol.h"
#include "shared.h"

namespace stellarcraft::sb
{
    using namespace stellarcraft::proto;

    static uint64_t* event_head() { return reinterpret_cast<uint64_t*>(g_pBase + kOffEventRing + kEventRingHeadOff); }
    static uint64_t* event_tail() { return reinterpret_cast<uint64_t*>(g_pBase + kOffEventRing + kEventRingTailOff); }

    // Map an actor uid back to the real Unreal actor. The ActorMirror keeps a stable
    // uid <-> actor mapping (hash of the actor name/ptr).
    static void* resolve_actor(uint32_t uid)
    {
        // TODO(real): look up uid in a map maintained by actor_mirror.cpp.
        return nullptr;
    }

    // Apply damage to a real SB character through its own hit pipeline.
    static void apply_damage(void* actor, float damage, float knockback)
    {
        // TODO(real): find the damage function (via UE4SS property editor) and call it:
        //   // e.g. actor->ReceiveAnyDamage(damage, damageType, instigator, causer);
        // Fallback: subtract health directly + trigger aggro.
        (void)actor; (void)damage; (void)knockback;
    }

    // Drain the event ring and dispatch each kEvExplosion (and kEvHitActor) to the real actor.
    static void drain_events()
    {
        // TODO(real): read events from the ring at *event_tail() up to *event_head(),
        // switch on McEventType, resolve the actor, and apply_damage().
    }

} // namespace stellarcraft::sb
