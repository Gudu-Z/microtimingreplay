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
    void recordingKeepsFinalPartialTickUntilSubTickRefreezes(@TempDir Path worldRoot) throws Exception {
        SubTickFixture fixture = frozenSubTick();
        AtomicInteger tickCount = new AtomicInteger(100);
        when(fixture.server.getTickCount()).thenAnswer(invocation -> tickCount.get());
        when(fixture.server.getWorldPath(any(LevelResource.class))).thenReturn(worldRoot);
        MinecraftServer previousServer = MicroTimingReplay.server;
        MicroTimingReplay.server = fixture.server;
        String profileName = "subtick-tail-" + UUID.randomUUID();
        try {
            MTRProfile profile = existingProfile(profileName);
            int initialTicks = profile.getTicksRecorded();
            assertTrue(MTRState.startRecording(profileName, fixture.server, 2));
            assertEquals(MTRState.State.RECORDING, MTRState.getCurrentState());

            fixture.serverTick();
            tickCount.incrementAndGet();
            MTRState.checkAutoStop(fixture.server);
            fixture.serverTick();
            tickCount.incrementAndGet();
            assertTrue(TickControl.isSubTickStepping(fixture.server));
            MTRState.checkAutoStop(fixture.server);
            assertEquals(MTRState.State.RECORDING, MTRState.getCurrentState(),
                    "The nominal deadline must not discard SubTick's final partial tick");

            fixture.serverTick();
            tickCount.incrementAndGet();
            assertTrue(TickControl.isSubTickFrozen(fixture.server));
            SetBlockEvent finalSegmentEvent = new SetBlockEvent(tickCount.get(), 3, 512, 0, 64, 0,
                    Block.getId(Blocks.STONE.defaultBlockState()),
                    Block.getId(Blocks.REDSTONE_BLOCK.defaultBlockState()), true, "minecraft:the_nether");
            MTRState.recordStep(finalSegmentEvent);
            MTRState.checkAutoStop(fixture.server);
            assertEquals(MTRState.State.IDLE, MTRState.getCurrentState());
            assertNull(MTRState.getActiveProfile());
            MTRProfile recorded = ProfileManager.loadProfile(profileName);
            assertNotNull(recorded);
            assertEquals(initialTicks + 3, recorded.getTicksRecorded());
            assertEquals(1, recorded.getFrames().size());
            assertEquals(initialTicks + 3, recorded.getFrames().getFirst().getTick());
            assertEquals(1, recorded.getFrames().getFirst().getEvents().size(),
                    "The event from the final partial tick must survive saving");
            assertInstanceOf(SetBlockEvent.class, recorded.getFrames().getFirst().getEvents().getFirst());
        } finally {
            try {
                MTRState.stopRecording();
                ProfileManager.deleteProfile(profileName);
            } finally {
                MicroTimingReplay.server = previousServer;
            }
        }
    }

    @Test
    void rejectedSubTickStepRollsRecordingBackAndAllowsRetry(@TempDir Path worldRoot) throws Exception {
        SubTickFixture fixture = frozenSubTick();
        when(fixture.server.getTickCount()).thenReturn(100);
        when(fixture.server.getWorldPath(any(LevelResource.class))).thenReturn(worldRoot);
        Object queues = fixture.handlerClass.getMethod("queues").invoke(fixture.handler);
        Field scheduled = queues.getClass().getField("scheduled");
        scheduled.setBoolean(queues, true);
        MinecraftServer previousServer = MicroTimingReplay.server;
        MicroTimingReplay.server = fixture.server;
        String profileName = "subtick-rejected-" + UUID.randomUUID();
        try {
            existingProfile(profileName);
            assertFalse(MTRState.startRecording(profileName, fixture.server, 2));
            assertEquals(MTRState.State.IDLE, MTRState.getCurrentState());
            assertNull(MTRState.getActiveProfile());
            assertEquals(0, MTRState.getRecordStartTick());
            assertTrue(MTRState.currentEventStack.isEmpty());
            assertTrue(MTRState.canStartReplay());
            Field bossBar = RecordingBossBar.class.getDeclaredField("bossBar");
            bossBar.setAccessible(true);
            assertNull(bossBar.get(null), "A rejected recording must remove its progress bar");
            assertTrue(TickControl.isSubTickFrozen(fixture.server));
            assertEquals(0, fixture.remainingTicks());
            assertEquals(1, ProfileManager.loadProfile(profileName).getTicksRecorded());

            scheduled.setBoolean(queues, false);
            assertTrue(MTRState.startRecording(profileName, fixture.server, 2),
                    "Once the queue step ends, the same recording can be started again");
            assertEquals(MTRState.State.RECORDING, MTRState.getCurrentState());
            assertTrue(TickControl.isSubTickStepping(fixture.server));
        } finally {
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

        void serverTick() throws Exception {
            int lastPhase = phaseClass.getField("ENTITY_MANAGEMENT").getInt(null);
            for (ServerLevel level : levels) {
                for (int phase = 0; phase <= lastPhase; phase++) {
                    handlerClass.getMethod("shouldTick", ServerLevel.class, int.class)
                            .invoke(handler, level, phase);
                }
            }
        }
    }
}
