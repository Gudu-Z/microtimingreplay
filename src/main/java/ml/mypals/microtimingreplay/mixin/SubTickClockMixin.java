package ml.mypals.microtimingreplay.mixin;

import ml.mypals.microtimingreplay.MTRState;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Optional observation only: SubTick remains in charge of every phase and queue. */
@Pseudo
@Mixin(targets = "subtick.TickHandler", remap = false)
public abstract class SubTickClockMixin {
    @Inject(method = "shouldTick", at = @At("RETURN"), remap = false)
    private void mtr$observePhase(ServerLevel level, int phase, CallbackInfoReturnable<Boolean> cir) {
        MTRState.onSubTickPhase(level, phase, cir.getReturnValueZ());
    }
}
