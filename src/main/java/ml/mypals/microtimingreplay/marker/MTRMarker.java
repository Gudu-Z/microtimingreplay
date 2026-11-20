package ml.mypals.microtimingreplay.marker;

import com.mojang.math.Transformation;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;
import ml.mypals.microtimingreplay.util.DisplayUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display.BlockDisplay;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class MTRMarker {

    private MTRMarker() {}

    /**
     * Delegates so the tag has exactly one definition. It used to be spelled out here as
     * well, which meant readers and writers could drift apart without anything failing
     * loudly — markers would simply stop being cleared.
     */
    public static String sessionTag(String sessionId) {
        return DisplayUtils.sessionTag(sessionId);
    }

    public static void spawnTextDisplay(ServerLevel level, double x, double y, double z, Component text, float scale) {
        DisplayUtils.spawnTextDisplay(level, x, y, z, text, scale);
    }
    public static void spawnTextDisplay(ServerLevel level, Vec3 pos, Component text, float scale) {
        DisplayUtils.spawnTextDisplay(level, pos, text, scale);
    }
    public static void spawnBlockDisplay(ServerLevel level, Vec3 pos, BlockState blockState, float scale, ChatFormatting teamColor) {
        DisplayUtils.spawnBlockDisplay(level, pos, blockState, scale, teamColor);
    }

    public static void spawnBlockDisplay(ServerLevel level, BlockPos pos, BlockState blockState, float scale, ChatFormatting teamColor) {
        DisplayUtils.spawnBlockDisplay(level, pos, blockState, scale, teamColor);
    }

    /** A preview lives only on the requesting player's client, including vanilla clients. */
    public static int showArea(ServerPlayer player, BlockPos first, BlockPos second,
                               BlockState glass, ChatFormatting outline, double margin) {
        BlockDisplay marker = new BlockDisplay(EntityType.BLOCK_DISPLAY, player.level());
        marker.setPos(Math.min(first.getX(), second.getX()) - margin,
                Math.min(first.getY(), second.getY()) - margin, Math.min(first.getZ(), second.getZ()) - margin);
        var scale = new Vector3f((float) (Math.abs(first.getX() - second.getX()) + 1 + 2 * margin),
                (float) (Math.abs(first.getY() - second.getY()) + 1 + 2 * margin),
                (float) (Math.abs(first.getZ() - second.getZ()) + 1 + 2 * margin));
        marker.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), scale, new Quaternionf()));
        marker.setBlockState(glass);
        marker.setBrightnessOverride(new Brightness(15, 15));
        marker.setGlowingTag(true);
        marker.setGlowColorOverride(outline.getColor());
        marker.setNoGravity(true);

        int id = marker.getId();
        player.connection.send(new ClientboundAddEntityPacket(id, marker.getUUID(), marker.getX(), marker.getY(),
                marker.getZ(), 0, 0, EntityType.BLOCK_DISPLAY, 0, Vec3.ZERO, 0));
        player.connection.send(new ClientboundSetEntityDataPacket(id, marker.getEntityData().getNonDefaultValues()));
        return id;
    }

    public static void spawnBlockDisplay(ServerLevel level, Vec3 pos, BlockState blockState, Vector3f scale, ChatFormatting teamColor) {
        DisplayUtils.spawnBlockDisplay(level, pos, blockState, scale, teamColor);
    }
}
