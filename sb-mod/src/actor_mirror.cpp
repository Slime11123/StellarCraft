// StellarCraft — actor mirror (Stellar Blade side).
//
// Mirrors nearby SB characters into the shared-memory actor table so Minecraft can spawn
// invisible, hittable ActorProxy entities for them. Stellar Blade still draws the real
// characters; this is purely so MC's combat/physics can interact with them.
//
// For each character within ~64 blocks, fill an ActorRecord:
//   uid (stable), flags (hostile/dead/boss/...), position/rotation (MC coords),
//   capsule dimensions, health fraction, level, and a display name.
//
// NOTE: skeleton. Real impl iterates the UWorld's pawns/characters via the Unreal SDK.

#include <windows.h>
#include <cstdint>
#include <cstring>

#include "../../protocol/stellarcraft_protocol.h"
#include "shared.h"

namespace stellarcraft::sb
{
    using namespace stellarcraft::proto;

    static void mirror_actors()
    {
        ActorTable* table = reinterpret_cast<ActorTable*>(g_pBase + kOffActorTable);

        // Seqlock write.
        uint32_t seq = table->seq + 1;
        table->seq = seq | 1;

        uint32_t count = 0;
        // TODO(real): for each nearby SB character:
        //   ActorRecord& r = table->actors[count++];
        //   r.uid = hash(actor->GetName());
        //   r.x/y/z = convert(actor->K2_GetActorLocation());
        //   r.width = actor->GetSimpleCollisionRadius() / kUnitsPerBlock;
        //   r.height = actor->GetSimpleCollisionHalfHeight() * 2 / kUnitsPerBlock;
        //   r.healthFrac = actor->GetHealth() / actor->GetMaxHealth();
        //   std::strncpy(r.name, actorName, sizeof(r.name) - 1);

        table->count = count;
        table->seq = seq + 1;
    }

} // namespace stellarcraft::sb
