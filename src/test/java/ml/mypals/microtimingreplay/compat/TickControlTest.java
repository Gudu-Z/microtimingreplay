package ml.mypals.microtimingreplay.compat;

import it.unimi.dsi.fastutil.longs.LongSets;
import ml.mypals.microtimingreplay.MTRState;
import ml.mypals.microtimingreplay.MicroTimingReplay;
import ml.mypals.microtimingreplay.event.SetBlockEvent;
import ml.mypals.microtimingreplay.event.MTREvents;
import ml.mypals.microtimingreplay.profile.MTRProfile;
import ml.mypals.microtimingreplay.profile.ProfileManager;
import ml.mypals.microtimingreplay.record.RecordingBossBar;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Answers;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Exercises vanilla ticking and, when supplied, the installed SubTick state machine. */
class TickControlTest {
    private static ClassLoader previousContextClassLoader;

    @BeforeAll
    static void bootstrap() {
        // Mockito discovers plugins through TCCL. Fabric loads the test and Mockito
        // in Knot, so their plugins must use that same loader rather than Gradle's.
        previousContextClassLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(TickControlTest.class.getClassLoader());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        MTREvents.init();
        if (Boolean.getBoolean("mtr.test.subtick")) {
            assertTrue(FabricLoader.getInstance().isModLoaded("subtick"),
                    "-PsubtickCompatMods must put the real SubTick jar on the Fabric test runtime");
        }
    }

    @AfterAll
    static void restoreContextClassLoader() {
        Thread.currentThread().setContextClassLoader(previousContextClassLoader);
    }

    @Test
    void missingServerIsNotFrozenAndCannotStep() {
        assertFalse(TickControl.isFrozen(null));
        assertFalse(TickControl.isSubTickFrozen(null));
        assertFalse(TickControl.isSubTickStepping(null));
        assertFalse(TickControl.stepIfFrozen(null, 1));
    }

    @Test
    void runningVanillaServerDoesNotStartStepping() {
        MinecraftServer server = serverWithHandler(null);
        assertFalse(TickControl.isFrozen(server));
        assertFalse(TickControl.isSubTickFrozen(server));
        assertFalse(TickControl.isSubTickStepping(server));
        assertFalse(TickControl.stepIfFrozen(server, 3));
        assertFalse(server.tickRateManager().isSteppingForward());
    }

    @Test
    void frozenVanillaServerStepsWithoutUnfreezing() {
        MinecraftServer server = serverWithHandler(null);
        ServerTickRateManager ticks = server.tickRateManager();
        ticks.setFrozen(true);
        assertTrue(TickControl.isFrozen(server));
        assertFalse(TickControl.stepIfFrozen(server, 0));
        assertFalse(TickControl.stepIfFrozen(server, -1));
        assertFalse(ticks.isSteppingForward());
        assertTrue(TickControl.stepIfFrozen(server, 3));
        assertTrue(ticks.isFrozen());
        assertEquals(3, ticks.frozenTicksToRun());
        assertTrue(ticks.isSteppingForward());
        assertFalse(TickControl.isSubTickStepping(server));
    }

    @Test
    void subtickStepPreservesFrozenDimensionAndPhaseAndRejectsAnotherStep() throws Exception {
        SubTickFixture fixture = frozenSubTick();
        Object originalPhase = fixture.currentPhase();
        assertFalse(fixture.server.tickRateManager().isFrozen(), "SubTick owns a separate freeze");
        assertTrue(TickControl.isFrozen(fixture.server));
        assertTrue(TickControl.isSubTickFrozen(fixture.server));
        assertFalse(TickControl.isSubTickStepping(fixture.server));

        assertTrue(TickControl.stepIfFrozen(fixture.server, 2));
        assertEquals(originalPhase, fixture.targetPhase());
        assertEquals("the_nether", fixture.phaseClass.getMethod("getPath").invoke(fixture.targetPhase()));
        assertEquals(fixture.blockTickPhase, fixture.phaseClass.getMethod("phase").invoke(fixture.targetPhase()));
        assertEquals(2, fixture.remainingTicks());
        assertFalse(TickControl.isSubTickFrozen(fixture.server));
        assertTrue(TickControl.isSubTickStepping(fixture.server));
        assertFalse(fixture.server.tickRateManager().isSteppingForward(),
                "SubTick must receive the step instead of the vanilla tick manager");

        assertFalse(TickControl.stepIfFrozen(fixture.server, 99));
        assertEquals(2, fixture.remainingTicks(), "A second request must not replace the active step");
        assertEquals(originalPhase, fixture.targetPhase());
    }

    @Test
    void subtickStepRemainsActiveAcrossServerTickBoundaryUntilOriginalPhase() throws Exception {
        SubTickFixture fixture = frozenSubTick();
        Object originalPhase = fixture.currentPhase();
        assertTrue(TickControl.stepIfFrozen(fixture.server, 1));
        fixture.serverTick();
        assertTrue(TickControl.isSubTickStepping(fixture.server),
                "A blockTick freeze needs the next server loop to return to its original phase");
        fixture.serverTick();
        assertFalse(TickControl.isSubTickStepping(fixture.server));
        assertTrue(TickControl.isSubTickFrozen(fixture.server));
        assertEquals(originalPhase, fixture.currentPhase());
        assertEquals(0, fixture.remainingTicks());
    }

    @Test
    void subtickQueueStepRejectsWholeTickStepWithoutChangingFreeze() throws Exception {
        SubTickFixture fixture = frozenSubTick();
        Object originalPhase = fixture.currentPhase();
        Object queues = fixture.handlerClass.getMethod("queues").invoke(fixture.handler);
        queues.getClass().getField("scheduled").setBoolean(queues, true);

        assertFalse(TickControl.stepIfFrozen(fixture.server, 3));
        assertTrue(TickControl.isSubTickFrozen(fixture.server));
        assertFalse(TickControl.isSubTickStepping(fixture.server));
        assertEquals(0, fixture.remainingTicks());
        assertEquals(originalPhase, fixture.currentPhase());
        assertFalse(fixture.server.tickRateManager().isSteppingForward());
    }

    @Test
    void frozenVanillaRecordingWaitsForManualStepsAndIgnoresIdleLoops(@TempDir Path worldRoot) throws Exception {
        MinecraftServer server = serverWithHandler(null);
        server.tickRateManager().setFrozen(true);
        server.tickRateManager().tick();
        try (RecordingContext recording = new RecordingContext(server, worldRoot)) {
            assertTrue(MTRState.startRecording(recording.profileName, server, 2));
            assertTrue(server.tickRateManager().isFrozen());
            assertFalse(server.tickRateManager().isSteppingForward(), "Recording must never issue /tick step");
            for (int i = 0; i < 8; i++) recording.loop(() -> {});
            assertEquals(MTRState.State.RECORDING, MTRState.getCurrentState());
            assertTrue(MTRState.getActiveProfile().getFrames().isEmpty());
            assertEquals(1.0f, recordingProgress(), "Waiting while frozen must not consume the countdown");

            assertTrue(server.tickRateManager().stepGameIfPaused(1)); // Player's /tick step 1.
            recording.loop(() -> recordBlockChangeIfActive(null));
            assertEquals(MTRState.State.RECORDING, MTRState.getCurrentState());
            assertEquals(0.5f, recordingProgress());
            for (int i = 0; i < 5; i++) recording.loop(() -> {});
            assertEquals(1, MTRState.getActiveProfile().getFrames().size());
            assertEquals(0.5f, recordingProgress());

            assertTrue(server.tickRateManager().stepGameIfPaused(1));
            recording.loop(() -> recordBlockChangeIfActive(null));
            assertEquals(MTRState.State.IDLE, MTRState.getCurrentState());
            assertTrue(server.tickRateManager().isFrozen());
            assertFrames(recording.savedProfile(), 3, List.of(2L, 3L));
        }
    }

    @Test
    void frozenVanillaPlayerChangesAreRecordedWithoutConsumingTime(@TempDir Path worldRoot) throws Exception {
        MinecraftServer server = serverWithHandler(null);
        server.tickRateManager().setFrozen(true);
        server.tickRateManager().tick();
        try (RecordingContext recording = new RecordingContext(server, worldRoot)) {
            assertTrue(MTRState.startRecording(recording.profileName, server, 3));
            recordBlockChangeIfActive(null); // A real player/command edit, not an idle simulation loop.
            for (int i = 0; i < 10; i++) recording.loop(() -> {});
            assertEquals(MTRState.State.RECORDING, MTRState.getCurrentState());
            assertEquals(1.0f, recordingProgress());
            assertFalse(server.tickRateManager().isSteppingForward());
            MTRState.stopRecording();
            assertFrames(recording.savedProfile(), 2, List.of(2L));
            assertTrue(server.tickRateManager().isFrozen());
        }
    }
    @Test
    void runningVanillaRecordingCountsRealTicksAndAppendsAfterManualEarlyStop(@TempDir Path worldRoot) throws Exception {
        MinecraftServer server = serverWithHandler(null);
        try (RecordingContext recording = new RecordingContext(server, worldRoot)) {
            assertTrue(MTRState.startRecording(recording.profileName, server, 2));
            recording.loop(() -> recordBlockChangeIfActive(null));
            assertEquals(MTRState.State.RECORDING, MTRState.getCurrentState());
            recording.loop(() -> recordBlockChangeIfActive(null));
            assertEquals(MTRState.State.IDLE, MTRState.getCurrentState());
            assertFrames(recording.savedProfile(), 3, List.of(2L, 3L));

            server.tickRateManager().setFrozen(true);
            server.tickRateManager().tick();
            assertTrue(MTRState.startRecording(recording.profileName, server, 5));
            for (int i = 0; i < 4; i++) recording.loop(() -> {});
            assertTrue(server.tickRateManager().stepGameIfPaused(1));
            recording.loop(() -> recordBlockChangeIfActive(null));
            assertEquals(MTRState.State.RECORDING, MTRState.getCurrentState());
            MTRState.stopRecording();
            assertFrames(recording.savedProfile(), 4, List.of(2L, 3L, 4L));
            assertTrue(server.tickRateManager().isFrozen());
            assertNull(recordingBossBar());
        }
    }

    @Test
    void subtickRecordingWaitsForSeparateManualStepsAndPreservesTailAndAppend(@TempDir Path worldRoot) throws Exception {
        SubTickFixture fixture = frozenSubTick();
        Object frozenPhase = fixture.currentPhase();
        try (RecordingContext recording = new RecordingContext(fixture.server, worldRoot)) {
            assertTrue(MTRState.startRecording(recording.profileName, fixture.server, 2));
            assertTrue(TickControl.isSubTickFrozen(fixture.server));
            assertFalse(TickControl.isSubTickStepping(fixture.server));
            assertEquals(0, fixture.remainingTicks());
            for (int i = 0; i < 8; i++) recording.loop(fixture::serverTick);
            assertEquals(frozenPhase, fixture.currentPhase());
            assertEquals(MTRState.State.RECORDING, MTRState.getCurrentState());
            assertTrue(MTRState.getActiveProfile().getFrames().isEmpty());
            assertEquals(1.0f, recordingProgress());

            fixture.manualStep(1);
            recording.loop(() -> fixture.serverTick(TickControlTest::recordTailPhase));
            assertTrue(TickControl.isSubTickStepping(fixture.server));
            assertEquals(MTRState.State.RECORDING, MTRState.getCurrentState());
            recording.loop(() -> fixture.serverTick(TickControlTest::recordTailPhase));
            assertTrue(TickControl.isSubTickFrozen(fixture.server));
            assertEquals(MTRState.State.RECORDING, MTRState.getCurrentState());
            assertEquals(0.5f, recordingProgress());
            assertEquals(1, MTRState.getActiveProfile().getFrames().size());
            for (int i = 0; i < 6; i++) recording.loop(fixture::serverTick);
            assertEquals(0.5f, recordingProgress());
            assertEquals(1, MTRState.getActiveProfile().getFrames().size());

            fixture.manualStep(1);
            recording.loop(() -> fixture.serverTick(TickControlTest::recordTailPhase));
            assertEquals(MTRState.State.RECORDING, MTRState.getCurrentState(),
                    "The second step's final partial loop must remain recordable");
            recording.loop(() -> fixture.serverTick(TickControlTest::recordTailPhase));
            assertEquals(MTRState.State.IDLE, MTRState.getCurrentState());
            assertEquals(frozenPhase, fixture.currentPhase());
            assertFrames(recording.savedProfile(), 3, List.of(2L, 3L));

            assertTrue(MTRState.startRecording(recording.profileName, fixture.server, 1));
            assertTrue(TickControl.isSubTickFrozen(fixture.server));
            for (int i = 0; i < 4; i++) recording.loop(fixture::serverTick);
            fixture.manualStep(1);
            recording.loop(() -> fixture.serverTick(TickControlTest::recordTailPhase));
            recording.loop(() -> fixture.serverTick(TickControlTest::recordTailPhase));
            assertEquals(MTRState.State.IDLE, MTRState.getCurrentState());
            assertFrames(recording.savedProfile(), 4, List.of(2L, 3L, 4L));
        }
    }

    @Test
    void subtickQueueActivityDoesNotPreventStartingARecordingListener(@TempDir Path worldRoot) throws Exception {
        SubTickFixture fixture = frozenSubTick();
        Object queues = fixture.handlerClass.getMethod("queues").invoke(fixture.handler);
        Field scheduled = queues.getClass().getField("scheduled");
        scheduled.setBoolean(queues, true);
        try (RecordingContext recording = new RecordingContext(fixture.server, worldRoot)) {
            assertTrue(MTRState.startRecording(recording.profileName, fixture.server, 2));
            assertEquals(MTRState.State.RECORDING, MTRState.getCurrentState());
            assertTrue(TickControl.isSubTickFrozen(fixture.server));
            assertTrue(scheduled.getBoolean(queues), "Recording must not cancel or replace the player's queue step");
            recordBlockChangeIfActive(fixture.levels.get(1)); // An actual queued/command block update while frozen.
            assertEquals(0, fixture.remainingTicks());
            for (int i = 0; i < 5; i++) recording.loop(fixture::serverTick);
            assertEquals(1, MTRState.getActiveProfile().getFrames().size());
            assertEquals(1.0f, recordingProgress());
            MTRState.stopRecording();
            assertFrames(recording.savedProfile(), 2, List.of(2L));
            assertNull(recordingBossBar());
            assertTrue(scheduled.getBoolean(queues));
        }
    }

    @Test
    void subtickManualStopPersistsOnlyCompletedSteps(@TempDir Path worldRoot) throws Exception {
        SubTickFixture fixture = frozenSubTick();
        try (RecordingContext recording = new RecordingContext(fixture.server, worldRoot)) {
            assertTrue(MTRState.startRecording(recording.profileName, fixture.server, 5));
            fixture.manualStep(1);
            recording.loop(() -> fixture.serverTick(TickControlTest::recordTailPhase));
            recording.loop(() -> fixture.serverTick(TickControlTest::recordTailPhase));
            for (int i = 0; i < 9; i++) recording.loop(fixture::serverTick);
            assertEquals(MTRState.State.RECORDING, MTRState.getCurrentState());
            MTRState.stopRecording();
            assertFrames(recording.savedProfile(), 2, List.of(2L));
            assertTrue(TickControl.isSubTickFrozen(fixture.server));
            assertFalse(TickControl.isSubTickStepping(fixture.server));
        }
    }

    @Test
    void subtickManualStopRetainsPartialFrameAndDoesNotCancelPlayerStep(@TempDir Path worldRoot) throws Exception {
        SubTickFixture fixture = frozenSubTick();
        try (RecordingContext recording = new RecordingContext(fixture.server, worldRoot)) {
            assertTrue(MTRState.startRecording(recording.profileName, fixture.server, 5));
            fixture.manualStep(1);
            recording.loop(() -> fixture.serverTick((level, phase) -> {
                if (level.dimension().equals(Level.NETHER) && phase == fixture.blockTickPhase) {
                    recordBlockChangeIfActive(level);
                }
            }));
            assertTrue(TickControl.isSubTickStepping(fixture.server));
            MTRState.stopRecording();
            assertFrames(recording.savedProfile(), 2, List.of(2L));
            assertTrue(TickControl.isSubTickStepping(fixture.server), "Stopping recording must not stop the player's step");
            recording.loop(fixture::serverTick);
            assertTrue(TickControl.isSubTickFrozen(fixture.server));

            assertTrue(MTRState.startRecording(recording.profileName, fixture.server, 1));
            fixture.manualStep(1);
            recording.loop(() -> fixture.serverTick(TickControlTest::recordTailPhase));
            recording.loop(() -> fixture.serverTick(TickControlTest::recordTailPhase));
            assertFrames(recording.savedProfile(), 3, List.of(2L, 3L));
        }
    }

    @Test
    void vanillaFreezeAndStepCommandsInOneLoopDoNotCountThePreviousTick(@TempDir Path worldRoot) throws Exception {
        MinecraftServer server = serverWithHandler(null);
        try (RecordingContext recording = new RecordingContext(server, worldRoot)) {
            server.tickRateManager().tick();
            MTRState.beginServerTick(server); // This loop began before recording was requested.
            server.tickRateManager().setFrozen(true);
            assertTrue(MTRState.startRecording(recording.profileName, server, 1));
            assertFalse(server.tickRateManager().isSteppingForward());
            assertTrue(server.tickRateManager().stepGameIfPaused(1));
            MTRState.checkAutoStop(server);
            assertEquals(MTRState.State.RECORDING, MTRState.getCurrentState());
            assertEquals(1.0f, recordingProgress());
            assertTrue(MTRState.getActiveProfile().getFrames().isEmpty());

            recording.loop(() -> recordBlockChangeIfActive(null));
            assertEquals(MTRState.State.IDLE, MTRState.getCurrentState());
            assertFrames(recording.savedProfile(), 2, List.of(2L));
            assertTrue(server.tickRateManager().isFrozen());
        }
    }

    @Test
    void subtickRecordingStopsAtBudgetWithoutTruncatingLargerPlayerStep(@TempDir Path worldRoot) throws Exception {
        SubTickFixture fixture = frozenSubTick();
        try (RecordingContext recording = new RecordingContext(fixture.server, worldRoot)) {
            assertTrue(MTRState.startRecording(recording.profileName, fixture.server, 1));
            fixture.manualStep(3);
            recording.loop(() -> fixture.serverTick(TickControlTest::recordTailPhase));
            assertEquals(MTRState.State.RECORDING, MTRState.getCurrentState());
            recording.loop(() -> fixture.serverTick(TickControlTest::recordTailPhase));
            assertEquals(MTRState.State.IDLE, MTRState.getCurrentState());
            assertFrames(recording.savedProfile(), 2, List.of(2L));
            assertTrue(TickControl.isSubTickStepping(fixture.server),
                    "Reaching the recording budget must not cancel the player's larger /tick step");
            assertEquals(1, fixture.remainingTicks());

            recording.loop(() -> fixture.serverTick(TickControlTest::recordTailPhase));
            recording.loop(() -> fixture.serverTick(TickControlTest::recordTailPhase));
            assertTrue(TickControl.isSubTickFrozen(fixture.server));
            assertFrames(recording.savedProfile(), 2, List.of(2L));
        }
    }
    private static void recordTailPhase(ServerLevel level, int phase) {
        // TIME (2) is the final phase before refreezing at Nether BLOCK_TICK (3).
        if (level.dimension().equals(Level.NETHER) && phase == 2) recordBlockChangeIfActive(level);
    }

    private static void recordBlockChangeIfActive(ServerLevel level) {
        if (!MTRState.isRecording(level)) return;
        long tick = MTRState.getRecordingTick();
        MTRState.recordStep(new SetBlockEvent(tick, 3, 512, 0, 64, 0,
                Block.getId(Blocks.STONE.defaultBlockState()),
                Block.getId(Blocks.REDSTONE_BLOCK.defaultBlockState()), true,
                level == null ? "minecraft:overworld" : level.dimension().location().toString()));
    }

    private static void assertFrames(MTRProfile profile, int recordedTicks, List<Long> frameTicks) {
        assertNotNull(profile);
        assertEquals(recordedTicks, profile.getTicksRecorded());
        assertEquals(frameTicks, profile.getFrames().stream().map(frame -> frame.getTick()).toList());
        for (var frame : profile.getFrames()) {
            assertEquals(1, frame.getEvents().size());
            assertInstanceOf(SetBlockEvent.class, frame.getEvents().getFirst());
            assertEquals(frame.getTick(), frame.getEvents().getFirst().getTick(),
                    "Event timestamps and frame indices must share the recording clock");
        }
    }

    private static net.minecraft.server.level.ServerBossEvent recordingBossBar() throws Exception {
        Field field = RecordingBossBar.class.getDeclaredField("bossBar");
        field.setAccessible(true);
        return (net.minecraft.server.level.ServerBossEvent) field.get(null);
    }

    private static float recordingProgress() throws Exception {
        assertNotNull(recordingBossBar());
        return recordingBossBar().getProgress();
    }

    @FunctionalInterface
    private interface TickWork {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface PhaseWork {
        void run(ServerLevel level, int phase) throws Exception;
    }

    private static final class RecordingContext implements AutoCloseable {
        final MinecraftServer server;
        final String profileName = "manual-recording-" + UUID.randomUUID();
        final MinecraftServer previousServer;
        final AtomicInteger tickCount = new AtomicInteger(100);

        RecordingContext(MinecraftServer server, Path worldRoot) {
            this.server = server;
            this.previousServer = MicroTimingReplay.server;
            when(server.getTickCount()).thenAnswer(invocation -> tickCount.get());
            when(server.getWorldPath(any(LevelResource.class))).thenReturn(worldRoot);
            MicroTimingReplay.server = server;
            existingProfile(profileName);
        }

        void loop(TickWork worldWork) throws Exception {
            tickCount.incrementAndGet();
            server.tickRateManager().tick();
            MTRState.beginServerTick(server);
            worldWork.run();
            MTRState.checkAutoStop(server);
        }

        MTRProfile savedProfile() {
            return ProfileManager.loadProfile(profileName);
        }

        @Override
        public void close() {
            try {
                MTRState.stopRecording();
                ProfileManager.deleteProfile(profileName);
            } finally {
                MicroTimingReplay.server = previousServer;
            }
        }
    }
    private static MTRProfile existingProfile(String name) {
        assertEquals(MTRState.State.IDLE, MTRState.getCurrentState());
        MTRProfile profile = new MTRProfile(name);
        // An existing recording avoids snapshotting a complete Minecraft world in this fixture.
        profile.setTicksRecorded(1);
        ProfileManager.saveProfile(profile);
        assertTrue(ProfileManager.hasProfile(name));
        return profile;
    }
    private static MinecraftServer serverWithHandler(Object handler) {
        MinecraftServer server = mock(MinecraftServer.class, invocation -> {
            if (invocation.getMethod().getName().equals("tickHandler")) return handler;
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        PlayerList players = mock(PlayerList.class);
        when(players.getPlayers()).thenReturn(List.of());
        when(server.getPlayerList()).thenReturn(players);
        when(server.getAllLevels()).thenReturn(List.of());
        when(server.isRunning()).thenReturn(true);
        when(server.isStopped()).thenReturn(false);
        when(server.tickRateManager()).thenReturn(new ServerTickRateManager(server));
        return server;
    }

    private static ServerLevel level(MinecraftServer server, ResourceKey<Level> dimension) {
        ServerLevel level = mock(ServerLevel.class);
        when(level.getServer()).thenReturn(server);
        when(level.dimension()).thenReturn(dimension);
        when(level.players()).thenReturn(List.of());
        when(level.getForceLoadedChunks()).thenReturn(LongSets.EMPTY_SET);
        return level;
    }

    private static CommandSourceStack source(MinecraftServer server, ServerLevel level) {
        CommandSourceStack source = mock(CommandSourceStack.class, Answers.RETURNS_SELF);
        when(source.getServer()).thenReturn(server);
        when(source.getLevel()).thenReturn(level);
        when(source.withLevel(any(ServerLevel.class)))
                .thenAnswer(invocation -> source(server, invocation.getArgument(0)));
        return source;
    }

    private static SubTickFixture frozenSubTick() throws Exception {
        assumeTrue(FabricLoader.getInstance().isModLoaded("subtick"),
                "Supply -PsubtickCompatMods=<mods directory> to exercise the real SubTick jar");
        Class<?> handlerClass = Class.forName("subtick.TickHandler");
        Class<?> phaseClass = Class.forName("subtick.TickPhase");
        phaseClass.getMethod("reset").invoke(null);
        Object handler = handlerClass.getConstructor().newInstance();
        MinecraftServer server = serverWithHandler(handler);
        assertTrue(Class.forName("subtick.ITickHandleable").isInstance(server),
                "The real SubTick MinecraftServer mixin must be applied");
        ServerLevel overworld = level(server, Level.OVERWORLD);
        ServerLevel nether = level(server, Level.NETHER);
        List<ServerLevel> levels = List.of(overworld, nether);
        // Carpet Messenger checks the final overworld() method before sending feedback.
        // Subclass mocks cannot override it, so provide the real field it reads.
        Field serverLevels = MinecraftServer.class.getDeclaredField("levels");
        serverLevels.setAccessible(true);
        serverLevels.set(server, Map.of(Level.OVERWORLD, overworld, Level.NETHER, nether));
        assertSame(overworld, server.overworld());
        when(server.getAllLevels()).thenReturn(levels);
        // Server commands start in the overworld; TickControl must select the frozen dimension.
        CommandSourceStack serverSource = source(server, overworld);
        when(server.createCommandSourceStack()).thenReturn(serverSource);
        for (ServerLevel level : levels) {
            phaseClass.getMethod("addDimension", ServerLevel.class).invoke(null, level);
        }
        int blockTickPhase = phaseClass.getField("BLOCK_TICK").getInt(null);
        assertEquals(1, handlerClass.getMethod("freeze", CommandSourceStack.class, int.class)
                .invoke(handler, source(server, nether), blockTickPhase));
        assertFalse((boolean) handlerClass.getMethod("frozen").invoke(handler));
        assertFalse((boolean) handlerClass.getMethod("shouldTick", ServerLevel.class, int.class)
                .invoke(handler, nether, blockTickPhase));
        assertTrue((boolean) handlerClass.getMethod("frozen").invoke(handler));
        return new SubTickFixture(server, handler, handlerClass, phaseClass, levels, blockTickPhase);
    }

    private record SubTickFixture(MinecraftServer server, Object handler, Class<?> handlerClass,
                                  Class<?> phaseClass, List<ServerLevel> levels, int blockTickPhase) {
        Object currentPhase() throws Exception {
            return handlerClass.getMethod("currentPhase").invoke(handler);
        }

        Object targetPhase() throws Exception {
            return handlerClass.getMethod("targetPhase").invoke(handler);
        }

        int remainingTicks() throws Exception {
            Field remaining = handlerClass.getDeclaredField("remainingTicks");
            remaining.setAccessible(true);
            return remaining.getInt(handler);
        }

        void manualStep(int ticks) throws Exception {
            assertEquals(1, handlerClass.getMethod("step", CommandSourceStack.class, int.class, int.class)
                    .invoke(handler, source(server, levels.get(1)), ticks, blockTickPhase));
        }

        void serverTick() throws Exception {
            serverTick((level, phase) -> {});
        }

        void serverTick(PhaseWork worldWork) throws Exception {
            int lastPhase = phaseClass.getField("ENTITY_MANAGEMENT").getInt(null);
            for (ServerLevel level : levels) {
                for (int phase = 0; phase <= lastPhase; phase++) {
                    boolean allowed = (boolean) handlerClass.getMethod("shouldTick", ServerLevel.class, int.class)
                            .invoke(handler, level, phase);
                    if (allowed) worldWork.run(level, phase);
                }
            }
        }
    }
}
