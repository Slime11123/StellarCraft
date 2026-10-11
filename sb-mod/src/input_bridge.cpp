// StellarCraft — input bridge (Stellar Blade side).
//
// Forwards raw keyboard/mouse input from Stellar Blade's window to Minecraft, and swallows
// it from SB's own controls so the two don't fight. Minecraft's window is hidden but told
// it has focus, so it never pauses or releases the mouse.
//
// This is the mirror of Minecraft's input handling. SB reads input through its own input
// system, writes kInKey / kInMouseButton / kInScroll / kInCursor events into the input
// ring, and consumes them so SB's UI/player don't also react.
//
// Routing modes (per the design doc):
//   - Gameplay: input goes to MC; SB gets only an allow-list (pause menu, etc).
//   - MC screen open: input goes to MC, MC cursor shown.
//   - SB menu open: input goes to SB, MC client frozen.
//
// NOTE: skeleton.

#include <windows.h>
#include <cstdint>

#include "../../protocol/stellarcraft_protocol.h"
#include "shared.h"

namespace stellarcraft::sb
{
    using namespace stellarcraft::proto;

    static uint64_t* input_head() { return reinterpret_cast<uint64_t*>(g_pBase + kOffInputRing + kInputRingHeadOff); }
    static uint64_t* input_tail() { return reinterpret_cast<uint64_t*>(g_pBase + kOffInputRing + kInputRingTailOff); }

    // Forward a keyboard event to MC.
    static void forward_key(uint16_t vkCode, bool down)
    {
        // Write an kInKey event: { type=u16, code=u16, a=down?1:0 } into the ring.
        // TODO(real): serialize into the SPSC ring at *input_head() and advance.
    }

    // Forward mouse motion (delta) to MC.
    static void forward_mouse_delta(float dx, float dy)
    {
        // Write kInCursor / mouse events. MC applies its own sensitivity + smoothing.
    }

    // Called when SB's input system receives an event. The real mod hooks SB's input
    // (e.g. a UE4SS input hook) and routes here instead of letting SB consume it.
    static void on_input_event(uint16_t type, uint16_t code, int32_t a, int32_t b)
    {
        // Decide routing by current mode (read McState flags / SbState flags).
        // Then write the appropriate kIn* event into the input ring.
    }

} // namespace stellarcraft::sb
