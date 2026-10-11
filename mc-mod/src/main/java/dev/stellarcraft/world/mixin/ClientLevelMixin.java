package dev.stellarcraft.world.mixin;

import dev.stellarcraft.world.BlockBridge;
import dev.stellarcraft.world.PlacedBlocks;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds {@link PlacedBlocks} with every authoritative client-side block change and forwards the
 * real changes to Stellar Blade, so a placed block becomes a physical object in Stellar Blade's
 * world rather than a pixel drawn into the overlay.
 *
 * <p>Target choice was verified by disassembly rather than assumed:
 * <ul>
 *   <li>{@code MultiPlayerGameMode.useItemOn} contains <b>no</b> {@code setBlock} call — the
 *       client does not predict block placement, it only sends the packet;</li>
 *   <li>{@code ClientPacketListener.handleBlockUpdate} calls
 *       {@code ClientLevel.setServerVerifiedBlockState(BlockPos, BlockState, int)};</li>
 *   <li>bulk section updates funnel through the same method.</li>
 * </ul>
 * That makes it the single, precise hook for "a block changed in my world" — no polling, no
 * scanning, and no false positives from terrain that merely streams in with chunks.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {

    @Inject(method = "setServerVerifiedBlockState", at = @At("RETURN"))
    private void stellarcraft$trackChangedBlock(BlockPos pos, BlockState state, int flags,
                                                CallbackInfo ci) {
        PlacedBlocks.ensureLevel((ClientLevel)(Object)this);
        boolean present = !state.isAir();
        long packed = pos.asLong();
        boolean tracked = PlacedBlocks.contains(packed);
        // A server correction is not a player placement. Require a recent right-click target
        // for new entries, while updates/removals of already tracked blocks remain valid.
        if (present && !tracked && !BlockBridge.isPlacementReply(packed,state)) return;
        if (PlacedBlocks.record((Object) this, packed, present)) {
            // Only real changes are forwarded. setServerVerifiedBlockState also fires for
            // blocks we do not track (chunk corrections, other entities' edits), and echoing
            // every one of those would flood the event ring.
            BlockBridge.forward(packed, present);
        }
    }
}
