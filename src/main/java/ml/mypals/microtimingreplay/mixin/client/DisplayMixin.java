package ml.mypals.microtimingreplay.mixin.client;

import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

@Mixin(Display.class)
public abstract class DisplayMixin extends Entity {
    @Shadow protected boolean updateRenderState;
    @Shadow private Display.RenderState renderState;

    protected DisplayMixin(EntityType<?> type, Level level) {
        super(type, level);
    }

    @Shadow private Display.RenderState createFreshRenderState() { throw new AssertionError(); }
    @Shadow protected abstract void updateRenderSubState(boolean interpolate, float progress);

    @Override
    public void onSyncedDataUpdated(List<SynchedEntityData.DataValue<?>> values) {
        super.onSyncedDataUpdated(values);
        // The batch callback runs after the subclass has received its block, item or text.
        // Initialize new displays even when a mod freezes simulation independently of vanilla.
        if (level().isClientSide() && (renderState == null || (updateRenderState && !level().tickRateManager().runsNormally()))) {
            renderState = createFreshRenderState();
            updateRenderSubState(false, 0.0F);
            updateRenderState = false;
        }
    }
}
