package ml.mypals.microtimingreplay.compat;

import ml.mypals.microtimingreplay.MicroTimingReplay;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Uses the tick controller that owns the freeze, without requiring SubTick. */
public final class TickControl {
    private static boolean warnedAboutSubTick;

    private TickControl() {}

    public static boolean isFrozen(MinecraftServer server) {
        if (server == null) return false;
        if (server.tickRateManager().isFrozen()) return true;
        return isSubTickFrozen(server);
    }

    public static boolean isSubTickFrozen(MinecraftServer server) {
        return server != null && frozenSubTickHandler(server) != null;
    }

    /** SubTick's public frozen() is false both while stepping and after unfreezing. */
    public static boolean isSubTickStepping(MinecraftServer server) {
        if (server == null) return false;
        SubTickAccess access = SubTickHolder.ACCESS;
        if (access == null || !access.tickHandler.getDeclaringClass().isInstance(server)) return false;
        try {
            Object handler = access.tickHandler.invoke(server);
            return handler != null && access.state.get(handler) instanceof Enum<?> state
                    && state.name().equals("STEPPING");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            warnAboutSubTick(e);
            return false;
        }
    }

    public static boolean stepIfFrozen(MinecraftServer server, int ticks) {
        if (server == null || ticks <= 0) return false;
        if (server.tickRateManager().isFrozen()) {
            return server.tickRateManager().stepGameIfPaused(ticks);
        }

        Object handler = frozenSubTickHandler(server);
        if (handler == null) return false;
        SubTickAccess access = SubTickHolder.ACCESS;
        try {
            Object phase = access.currentPhase.invoke(handler);
            String dimensionPath = (String) access.phasePath.invoke(phase);
            int phaseIndex = (int) access.phaseIndex.invoke(phase);
            // SubTick identifies dimensions by path, and its step command derives
            // the target dimension from the source. Preserve the actual freeze point.
            for (ServerLevel level : server.getAllLevels()) {
                if (level.dimension().location().getPath().equals(dimensionPath)) {
                    CommandSourceStack source = server.createCommandSourceStack()
                            .withLevel(level).withSuppressedOutput();
                    return (int) access.step.invoke(handler, source, ticks, phaseIndex) > 0;
                }
            }
            warnAboutSubTick(new IllegalStateException("Frozen dimension is unavailable: " + dimensionPath));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            warnAboutSubTick(e);
        }
        return false;
    }

    private static Object frozenSubTickHandler(MinecraftServer server) {
        SubTickAccess access = SubTickHolder.ACCESS;
        if (access == null || !access.tickHandler.getDeclaringClass().isInstance(server)) return null;
        try {
            Object handler = access.tickHandler.invoke(server);
            return handler != null && (boolean) access.frozen.invoke(handler) ? handler : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            warnAboutSubTick(e);
            return null;
        }
    }

    private static void warnAboutSubTick(Throwable cause) {
        if (!warnedAboutSubTick) {
            warnedAboutSubTick = true;
            MicroTimingReplay.LOGGER.warn("Unable to use SubTick's freeze/step API", cause);
        }
    }

    private static final class SubTickHolder {
        private static final SubTickAccess ACCESS = load();

        private static SubTickAccess load() {
            if (!FabricLoader.getInstance().isModLoaded("subtick")) return null;
            try {
                ClassLoader loader = TickControl.class.getClassLoader();
                Class<?> owner = Class.forName("subtick.ITickHandleable", false, loader);
                Class<?> handler = Class.forName("subtick.ITickHandler", false, loader);
                Class<?> phase = Class.forName("subtick.TickPhase", false, loader);
                // Resolve completion tracking before permitting a step: a SubTick
                // step can span one more server loop than its requested tick count.
                Method tickHandler = owner.getMethod("tickHandler");
                Field state = tickHandler.getReturnType().getDeclaredField("state");
                if (!state.trySetAccessible()) throw new IllegalAccessException("SubTick state is inaccessible");
                return new SubTickAccess(tickHandler, handler.getMethod("frozen"),
                        handler.getMethod("currentPhase"), phase.getMethod("getPath"), phase.getMethod("phase"),
                        handler.getMethod("step", CommandSourceStack.class, int.class, int.class), state);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                warnAboutSubTick(e);
                return null;
            }
        }
    }

    private record SubTickAccess(Method tickHandler, Method frozen, Method currentPhase,
                                 Method phasePath, Method phaseIndex, Method step, Field state) {}
}
