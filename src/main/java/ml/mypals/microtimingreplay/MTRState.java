package ml.mypals.microtimingreplay;

import ml.mypals.microtimingreplay.compat.TickControl;
import ml.mypals.microtimingreplay.event.EntityTickEvent;
import ml.mypals.microtimingreplay.event.LevelTickEvent;
import ml.mypals.microtimingreplay.event.MTREvent;
import ml.mypals.microtimingreplay.event.PhaseEvent;
import ml.mypals.microtimingreplay.profile.MTRProfile;
import ml.mypals.microtimingreplay.profile.ProfileManager;
import ml.mypals.microtimingreplay.record.RecordingBossBar;
import ml.mypals.microtimingreplay.record.RecordingClock;
import ml.mypals.microtimingreplay.replay.ReplayManager;
import ml.mypals.microtimingreplay.replay.WorldBackupManager;
import ml.mypals.microtimingreplay.replay.stackTrace.StackTraceManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

public class MTRState {
    /** Recording is exclusive; replays are tracked independently by ReplayManager. */
    public enum State { IDLE, RECORDING }

    private static State currentState = State.IDLE;
    private static MTRProfile activeProfile;
    private static RecordingClock recordingClock;
    private static boolean recordingPaused;
    private static final Map<MTREvent, Long> scopeActivity = new IdentityHashMap<>();
    public static final Stack<MTREvent> currentEventStack = new Stack<>();

    public static State getCurrentState() { return currentState; }

    public static boolean isRecording(Level level) {
        if (currentState != State.RECORDING || recordingClock == null || recordingClock.finished())
            return false;
        if (level != null && level.isClientSide()) return false;
        if (MicroTimingReplay.server == null || activeProfile == null) return false;
        return level == null || !activeProfile.outsideDimensionOrArea(level.dimension().location().toString());
    }

    public static MTRProfile getActiveProfile() { return activeProfile; }

    /** All event timestamps use simulation time, including events during a frozen queue step. */
    public static long getRecordingTick() {
        return recordingClock == null ? 0 : recordingClock.currentTick();
    }

    public static boolean startRecording(String profileName, MinecraftServer server, int advance) {
        if (currentState != State.IDLE || ReplayManager.isAnyRunning() || advance <= 0) return false;
        MTRProfile profile = ProfileManager.loadProfile(profileName);
        if (profile == null) return false;

        long baseTick = profile.getTicksRecorded();
        for (var frame : profile.getFrames()) baseTick = Math.max(baseTick, frame.getTick());
        List<String> dimensions = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) dimensions.add(level.dimension().location().getPath());
        TickControl.FreezePhase frozen = TickControl.subTickFreezePhase(server);
        recordingPaused = TickControl.isFrozen(server);
        recordingClock = new RecordingClock(baseTick, advance, dimensions, TickControl.subTickPhaseCount(),
                frozen == null ? null : frozen.dimensionPath(), frozen == null ? -1 : frozen.phase());
        currentState = State.RECORDING;
        activeProfile = profile;
        currentEventStack.clear();
        scopeActivity.clear();

        if (profile.getTicksRecorded() == 0 && profile.getFrames().isEmpty()) {
            // Partial recordings already have their initial snapshot and must retain it when appended.
            WorldBackupManager.backup(activeProfile, "record", true);
        }
        RecordingBossBar.start(server, profile.getName(), advance);
        return true;
    }

    public static boolean isTimeFrozen(MinecraftServer server) { return TickControl.isFrozen(server); }

    /** Called after vanilla has decided whether this loop will advance its simulation. */
    public static void beginServerTick(MinecraftServer server) {
        if (currentState == State.RECORDING && recordingClock != null) {
            recordingPaused = TickControl.isFrozen(server);
            recordingClock.beginServerTick(server.tickRateManager().runsNormally());
        }
    }

    /** Optional SubTick hook. Rejected phases only count at the actual freeze boundary. */
    public static void onSubTickPhase(ServerLevel level, int phase, boolean allowed) {
        if (currentState != State.RECORDING || recordingClock == null || recordingClock.finished()) return;
        String dimension = level.dimension().location().getPath();
        boolean freezePoint = false;
        if (!allowed && (recordingClock.subTickPhaseRunning() || recordingClock.needsSubTickAnchor()
                || recordingClock.couldCompleteAt(dimension, phase))) {
            TickControl.FreezePhase frozen = TickControl.subTickFreezePhase(level.getServer());
            recordingPaused = frozen != null;
            freezePoint = frozen != null && frozen.phase() == phase && frozen.dimensionPath().equals(dimension);
        }
        if (allowed) recordingPaused = false;
        if (recordingClock.observeSubTickPhase(dimension, phase, allowed, freezePoint)
                && !recordingClock.finished()) splitOpenPhases();
    }

    public static void stopRecording() {
        if (currentState != State.RECORDING || activeProfile == null) return;
        while (!currentEventStack.isEmpty()) popEvent();
        pruneEmptyTail();
        long ticks = recordingClock.completedTick();
        // A manually stopped partial tick still owns its frame number on the next append.
        for (var frame : activeProfile.getFrames())
            if (!frame.getEvents().isEmpty()) ticks = Math.max(ticks, frame.getTick());
        activeProfile.setTicksRecorded((int) ticks);
        ProfileManager.saveProfile(activeProfile);
        StackTraceManager.collectAndSaveForProfile(activeProfile);
        RecordingBossBar.stop();
        currentState = State.IDLE;
        activeProfile = null;
        recordingClock = null;
        currentEventStack.clear();
        scopeActivity.clear();
    }

    public static void checkAutoStop(MinecraftServer server) {
        if (currentState == State.RECORDING) {
            if (!currentEventStack.isEmpty()) {
                MicroTimingReplay.LOGGER.warn("Event stack was not empty at end of tick ({} entries left); closing residue.",
                        currentEventStack.size());
                while (!currentEventStack.isEmpty()) popEvent();
            }
            recordingClock.endServerTick();
            RecordingBossBar.tick(server, recordingClock.remainingTicks());
            if (recordingClock.finished()) stopRecording();
        }
        ReplayManager.tickAll(server);
    }

    public static boolean canStartReplay() { return currentState != State.RECORDING; }

    public static void beginReplayWorld(MTRProfile profile) {
        WorldBackupManager.backup(profile, "replay", false);
        WorldBackupManager.restore(profile, "record", true);
    }

    public static void endReplayWorld(MTRProfile profile) {
        if (profile != null) WorldBackupManager.restore(profile, "replay", false);
    }

    public static void pushEvent(MTREvent event) {
        if (!isRecording(null)) return;
        if (currentEventStack.isEmpty()) activeProfile.addEvent(getRecordingTick(), event);
        else currentEventStack.peek().addChild(event);
        currentEventStack.push(event);
        scopeActivity.put(event, recordingClock.activity());
    }

    public static void popEvent() {
        if (currentState != State.RECORDING || currentEventStack.isEmpty()) return;
        MTREvent popped = currentEventStack.pop();
        Long began = scopeActivity.remove(popped);
        boolean idlePhase = popped instanceof PhaseEvent && recordingPaused && !recordingClock.simulationRunning()
                && began != null && began == recordingClock.activity();
        // Players keep receiving ticks while the world is frozen; keep their actual actions,
        // but do not fill an indefinitely paused recording with empty player containers.
        boolean idlePlayer = popped instanceof EntityTickEvent entity && "minecraft:player".equals(entity.getEntityType())
                && recordingPaused && !recordingClock.simulationRunning();
        if (popped.getChildren().isEmpty()
                && (idlePhase || idlePlayer || !popped.saveEvenWithoutAction(MicroTimingReplay.server))) {
            if (!currentEventStack.isEmpty()) currentEventStack.peek().removeChild(popped);
            else activeProfile.removeEvent(popped.getTick(), popped);
            pruneEmptyTail();
        }
    }

    public static void recordStep(MTREvent step) {
        if (!isRecording(null)) return;
        if (currentEventStack.isEmpty()) activeProfile.addEvent(getRecordingTick(), step);
        else currentEventStack.peek().addChild(step);
    }

    /** SubTick can cross the recording's tick boundary inside a level tick wrapper. */
    private static void splitOpenPhases() {
        if (currentEventStack.isEmpty()) return;
        List<MTREvent> reopened = new ArrayList<>(currentEventStack.size());
        for (MTREvent event : currentEventStack) {
            // shouldTick is outside block/entity/queue scopes; only phase wrappers span it.
            if (!(event instanceof PhaseEvent phase)) return;
            PhaseEvent next = phase instanceof LevelTickEvent level
                    ? new LevelTickEvent(getRecordingTick(), level.getPhaseName(), level.getDimension())
                    : new PhaseEvent(getRecordingTick(), phase.getPhaseName());
            next.setStackTrace(event.getStackTrace());
            reopened.add(next);
        }
        while (!currentEventStack.isEmpty()) popEvent();
        for (MTREvent event : reopened) pushEvent(event);
    }

    private static void pruneEmptyTail() {
        var frames = activeProfile.getFrames();
        while (!frames.isEmpty() && frames.getLast().getTick() > recordingClock.baseTick()
                && frames.getLast().getEvents().isEmpty()) frames.removeLast();
    }

    public static void stoppingServer(MinecraftServer server) {
        if (currentState == State.RECORDING) stopRecording();
        ReplayManager.onServerStopping();
    }
}
