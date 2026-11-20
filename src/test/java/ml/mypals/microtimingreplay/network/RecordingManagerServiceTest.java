package ml.mypals.microtimingreplay.network;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import ml.mypals.microtimingreplay.MTRState;
import ml.mypals.microtimingreplay.MicroTimingReplay;
import ml.mypals.microtimingreplay.profile.MTRProfile;
import ml.mypals.microtimingreplay.profile.ProfileManager;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static ml.mypals.microtimingreplay.network.MTRPayloads.ManagerAction.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecordingManagerServiceTest {
    private static ClassLoader previousLoader;
    private MinecraftServer server, previousServer;
    private ServerPlayer player;
    private CommandSourceStack source;
    private ServerTickRateManager ticks;

    @BeforeAll
    static void bootstrap() {
        previousLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(RecordingManagerServiceTest.class.getClassLoader());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterAll
    static void restoreLoader() {
        Thread.currentThread().setContextClassLoader(previousLoader);
    }

    @BeforeEach
    void fixture(@TempDir Path world) throws ReflectiveOperationException {
        previousServer = MicroTimingReplay.server;
        server = mock(MinecraftServer.class);
        var players = mock(PlayerList.class);
        when(players.getPlayers()).thenReturn(List.of());
        when(server.getPlayerList()).thenReturn(players);
        when(server.getAllLevels()).thenReturn(List.of());
        when(server.getWorldPath(LevelResource.ROOT)).thenReturn(world);
        ticks = new ServerTickRateManager(server);
        when(server.tickRateManager()).thenReturn(ticks);
        var level = mock(ServerLevel.class);
        when(level.getServer()).thenReturn(server);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        player = mock(ServerPlayer.class, call -> call.getMethod().getName().equals("level") ? level : RETURNS_DEFAULTS.answer(call));
        // The Fabric runtime may finalize the covariant level() method, which reads Entity.level.
        var worldField = net.minecraft.world.entity.Entity.class.getDeclaredField("level");
        worldField.setAccessible(true);
        worldField.set(player, level);
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        source = mock(CommandSourceStack.class);
        when(source.getServer()).thenReturn(server);
        when(source.getLevel()).thenReturn(level);
        when(source.getPlayer()).thenReturn(player);
        when(player.createCommandSourceStack()).thenReturn(source);

        var tree = new CommandDispatcher<CommandSourceStack>();
        tree.register(Commands.literal("tick").requires(s -> s.hasPermission(3))
                .then(Commands.literal("step")
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(1)).executes(c -> 1))));
        var commands = mock(Commands.class);
        when(commands.getDispatcher()).thenReturn(tree);
        when(server.getCommands()).thenReturn(commands);
        MicroTimingReplay.server = server;
        // Treat both profiles as existing recordings, bypassing the initial world backup.
        for (String name : List.of("clock", "piston")) {
            var profile = new MTRProfile(name);
            profile.setTicksRecorded(4);
            ProfileManager.saveProfile(profile);
        }
    }

    @AfterEach
    void restoreServer() {
        try {
            MTRState.stopRecording();
            ProfileManager.deleteProfile("clock");
            ProfileManager.deleteProfile("piston");
        } finally {
            MicroTimingReplay.server = previousServer;
        }
    }

    @Test
    void deniedPlayerCannotReadProfilesOrStopAnExistingRecording() {
        assertTrue(MTRState.startRecording("clock", server, 20));
        for (var action : List.of(BROWSE, STOP)) {
            var response = request(action, "clock");
            assertFalse(response.allowed());
            assertFalse(response.success());
            assertEquals(19, response.requestId());
            assertTrue(response.profiles().isEmpty());
            assertTrue(response.areas().isEmpty());
            assertTrue(response.recordingProfile().isEmpty());
            assertTrue(response.editingProfile().isEmpty());
        }
        assertEquals("clock", MTRState.getActiveProfile().getName());
    }

    @Test
    void worldStepUsesTheTickCommandPermissionAndAdvancesOnlyOneTick() {
        when(player.hasPermissions(2)).thenReturn(true);
        when(source.hasPermission(2)).thenReturn(true);
        ticks.setFrozen(true);
        var denied = request(STEP, "clock");
        assertTrue(denied.allowed());
        assertTrue(denied.frozen());
        assertFalse(denied.canStep());
        assertFalse(denied.success());
        assertEquals(0, ticks.frozenTicksToRun());

        when(source.hasPermission(3)).thenReturn(true);
        var permitted = request(STEP, "clock");
        assertTrue(permitted.success());
        assertTrue(ticks.isFrozen());
        assertEquals(1, ticks.frozenTicksToRun());
    }

    @Test
    void stopOnlyAffectsTheNamedActiveRecording() {
        when(player.hasPermissions(2)).thenReturn(true);
        assertTrue(MTRState.startRecording("clock", server, 20));
        var wrong = request(STOP, "piston");
        assertFalse(wrong.success());
        assertEquals("clock", wrong.recordingProfile());
        assertEquals("piston", wrong.selected());
        assertEquals("clock", MTRState.getActiveProfile().getName());
        assertTrue(request(STOP, "clock").success());
        assertEquals(MTRState.State.IDLE, MTRState.getCurrentState());
    }

    private MTRPayloads.ManagerS2C request(MTRPayloads.ManagerAction action, String profile) {
        return RecordingManagerService.handle(player, new MTRPayloads.ManagerC2S(19, action, profile, "", 1));
    }
}
