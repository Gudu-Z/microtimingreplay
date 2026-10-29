package ml.mypals.microtimingreplay;

import io.netty.buffer.Unpooled;
import ml.mypals.microtimingreplay.event.SetBlockEvent;
import ml.mypals.microtimingreplay.network.MTRPayloads;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Loads the real 1.21.10 classes through Fabric so broken injections fail the build. */
class MinecraftCompatibilityTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        new MicroTimingReplay().onInitialize();
    }

    @Test
    void allMixinTargetsTransform() throws ClassNotFoundException {
        assertTrue(FabricLoader.getInstance().isModLoaded(MicroTimingReplay.MOD_ID));
        List<String> commonTargets = List.of(
                "net.minecraft.world.level.redstone.CollectingNeighborUpdater",
                "net.minecraft.world.level.redstone.NeighborUpdater",
                "net.minecraft.world.entity.Entity",
                "net.minecraft.world.entity.LivingEntity",
                "net.minecraft.world.entity.Display",
                "net.minecraft.world.level.gameevent.GameEventDispatcher",
                "net.minecraft.world.level.gameevent.vibrations.VibrationSystem$Ticker",
                "net.minecraft.world.level.block.entity.HopperBlockEntity",
                "net.minecraft.world.level.block.piston.PistonMovingBlockEntity",
                "net.minecraft.world.level.block.piston.PistonStructureResolver",
                "net.minecraft.world.level.Level",
                "net.minecraft.world.ticks.LevelTicks",
                "net.minecraft.server.level.ServerLevel",
                "net.minecraft.server.MinecraftServer",
                "net.minecraft.server.network.ServerGamePacketListenerImpl",
                "net.minecraft.network.Connection");
        for (String target : commonTargets) {
            Class.forName(target, false, getClass().getClassLoader());
        }
        assertTrue(Arrays.stream(net.minecraft.world.entity.Entity.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().contains("mtr$")),
                "Recording hooks must actually be injected into Entity");
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
            for (String target : List.of(
                    "net.minecraft.client.multiplayer.ClientLevel",
                    "net.minecraft.client.multiplayer.ClientPacketListener",
                    "net.minecraft.client.renderer.entity.EntityRenderDispatcher")) {
                Class.forName(target, false, getClass().getClassLoader());
            }
        }
    }

    @Test
    void blockRecordingRetainsStateAndOutcomeInNbt() {
        int oldState = Block.getId(Blocks.STONE.defaultBlockState());
        int newState = Block.getId(Blocks.REDSTONE_BLOCK.defaultBlockState());
        SetBlockEvent event = new SetBlockEvent(42, 3, 512, 12, 64, -8,
                oldState, newState, false, "minecraft:overworld");
        SetBlockEvent restored = SetBlockEvent.readNBT(event.writeNBT());
        assertEquals(oldState, restored.getOldStateId());
        assertEquals(newState, restored.getNewStateId());
        assertEquals(new BlockPos(12, 64, -8), restored.getPos());
        assertEquals("minecraft:overworld", restored.getDimension());
        assertEquals(3, restored.getBitFlag());
        assertFalse(restored.succeed);
    }

    @Test
    void timelineAndDetailsSurviveNetworkRoundTrip() {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            var timeline = new MTRPayloads.TimelineS2C("test", 1, List.of(
                    new MTRPayloads.TimelineRow(0, 100, 0, (byte) 0, 0xffffff, Component.literal("phase")),
                    new MTRPayloads.TimelineRow(1, 101, 1, (byte) 1, 0x00ff00, Component.literal("block"))));
            MTRPayloads.TimelineS2C.CODEC.encode(buffer, timeline);
            assertEquals(timeline, MTRPayloads.TimelineS2C.CODEC.decode(buffer));
            assertFalse(buffer.isReadable());

            var details = new MTRPayloads.DetailsS2C(1, Component.literal("details"),
                    List.of("Example.tick(Example.java:42)"), Optional.of(new BlockPos(12, 64, -8)));
            MTRPayloads.DetailsS2C.CODEC.encode(buffer, details);
            assertEquals(details, MTRPayloads.DetailsS2C.CODEC.decode(buffer));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }
}
