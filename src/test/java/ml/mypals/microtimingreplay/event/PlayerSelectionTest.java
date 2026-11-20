package ml.mypals.microtimingreplay.event;

import com.mojang.brigadier.CommandDispatcher;
import ml.mypals.microtimingreplay.MicroTimingReplay;
import ml.mypals.microtimingreplay.command.MTRCommand;
import ml.mypals.microtimingreplay.profile.ProfileManager;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Display.BlockDisplay;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

/** Exercises command selection and the same dye callbacks that Minecraft invokes for both players. */
class PlayerSelectionTest {
    private static ClassLoader previousLoader;

    @BeforeAll
    static void initialize() {
        previousLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(PlayerSelectionTest.class.getClassLoader());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        SelectionEventHandler.register();
    }

    @AfterAll
    static void restoreLoader() {
        Thread.currentThread().setContextClassLoader(previousLoader);
    }

    @Test
    void changingProfilesDoesNotRedirectEitherPlayersDraft(@TempDir Path worldRoot) throws Exception {
        var server = mock(MinecraftServer.class);
        var level = mock(ServerLevel.class);
        var playerList = mock(PlayerList.class);
        when(server.getWorldPath(LevelResource.ROOT)).thenReturn(worldRoot);
        when(server.getPlayerList()).thenReturn(playerList);
        when(level.getServer()).thenReturn(server);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        var alice = player(level, server);
        var bob = player(level, server);
        when(playerList.getPlayers()).thenReturn(List.of(alice, bob));
        var commands = new CommandDispatcher<CommandSourceStack>();
        MTRCommand.register(commands, null, Commands.CommandSelection.ALL);
        var oldServer = MicroTimingReplay.server;
        MicroTimingReplay.server = server;
        try {
            assertNotNull(ProfileManager.createProfile("alpha"));
            assertNotNull(ProfileManager.createProfile("beta"));
            var a = new BlockPos(2, 64, 2);
            var b = new BlockPos(10, 64, 10);
            assertEquals(1, commands.execute("mtr profile info alpha", alice.createCommandSourceStack()));
            clickFirst(alice, level, a);
            assertEquals(1, commands.execute("mtr profile info beta", bob.createCommandSourceStack()));
            clickFirst(bob, level, b);
            // A preview that only has one real corner must not be confirmable.
            use(alice, level, a, true);
            assertTrue(ProfileManager.loadProfile("alpha").getAreas().isEmpty());
            use(alice, level, a.east(), false);
            use(alice, level, a.east(), true);
            assertEquals(a.getX(), ProfileManager.loadProfile("alpha").getArea("1").x1);
            assertTrue(ProfileManager.loadProfile("beta").getAreas().isEmpty());
            // Bob changes his own target mid-draft. The old first corner cannot follow him.
            assertEquals(1, commands.execute("mtr profile info alpha", bob.createCommandSourceStack()));
            use(bob, level, b.east(), false);
            use(bob, level, b.east(), true);
            assertEquals(1, ProfileManager.loadProfile("alpha").getAreas().size());
            assertTrue(ProfileManager.loadProfile("beta").getAreas().isEmpty());
        } finally {
            SelectionEventHandler.clearSelection(alice);
            SelectionEventHandler.clearSelection(bob);
            ProfileManager.deleteProfile("alpha");
            ProfileManager.deleteProfile("beta");
            MicroTimingReplay.server = oldServer;
        }
    }

    @Test
    void previewMetadataRendersWhileClientWorldIsFrozen() {
        assumeTrue(FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT);
        var clock = new TickRateManager();
        clock.setFrozen(true);
        clock.tick();
        var clientWorld = mock(Level.class);
        when(clientWorld.isClientSide()).thenReturn(true);
        when(clientWorld.tickRateManager()).thenReturn(clock);

        var serverMarker = new BlockDisplay(EntityType.BLOCK_DISPLAY, mock(ServerLevel.class));
        serverMarker.setBlockState(Blocks.YELLOW_STAINED_GLASS.defaultBlockState());
        serverMarker.setGlowingTag(true);
        var preview = new BlockDisplay(EntityType.BLOCK_DISPLAY, clientWorld);
        var packet = serverMarker.getEntityData().getNonDefaultValues();
        assertNotNull(packet);
        preview.getEntityData().assignValues(packet);
        assertNotNull(preview.renderState());
        assertNotNull(preview.blockRenderState());
        assertTrue(preview.blockRenderState().blockState().is(Blocks.YELLOW_STAINED_GLASS));
        assertEquals(0, preview.tickCount);
    }

    private static ServerPlayer player(ServerLevel level, MinecraftServer server) throws ReflectiveOperationException {
        var player = mock(ServerPlayer.class, call -> {
            if (call.getMethod().getName().equals("level")) return level;
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
        });
        var source = mock(CommandSourceStack.class);
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        var worldField = net.minecraft.world.entity.Entity.class.getDeclaredField("level");
        worldField.setAccessible(true);
        worldField.set(player, level);
        when(player.createCommandSourceStack()).thenReturn(source);
        when(player.hasPermissions(2)).thenReturn(true);
        var dye = new ItemStack(Items.PURPLE_DYE);
        when(player.getMainHandItem()).thenReturn(dye);
        when(player.getItemInHand(InteractionHand.MAIN_HAND)).thenReturn(dye);
        player.connection = mock(ServerGamePacketListenerImpl.class);
        when(source.hasPermission(2)).thenReturn(true);
        when(source.getServer()).thenReturn(server);
        when(source.getLevel()).thenReturn(level);
        when(source.getPlayer()).thenReturn(player);
        when(source.getEntity()).thenReturn(player);
        when(source.isPlayer()).thenReturn(true);
        return player;
    }

    private static void clickFirst(ServerPlayer player, ServerLevel level, BlockPos pos) {
        when(player.isShiftKeyDown()).thenReturn(false);
        AttackBlockCallback.EVENT.invoker().interact(player, level, InteractionHand.MAIN_HAND, pos, Direction.UP);
    }

    private static void use(ServerPlayer player, ServerLevel level, BlockPos pos, boolean confirm) {
        when(player.isShiftKeyDown()).thenReturn(confirm);
        var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        UseBlockCallback.EVENT.invoker().interact(player, level, InteractionHand.MAIN_HAND, hit);
    }
}
