package ml.mypals.microtimingreplay.event;

import ml.mypals.microtimingreplay.MTRState;
import ml.mypals.microtimingreplay.marker.MTRMarker;
import ml.mypals.microtimingreplay.profile.MTRProfile;
import ml.mypals.microtimingreplay.profile.ProfileManager;
import ml.mypals.microtimingreplay.replay.ReplayManager;
import ml.mypals.microtimingreplay.util.MTRComponent;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display.BlockDisplay;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SelectionEventHandler {
    private static final Map<UUID, Selection> selections = new HashMap<>();

    private static final class Selection {
        final String profile;
        ServerLevel level;
        BlockPos first, second;
        BlockPos drawnFirst, drawnSecond;
        final List<Integer> savedMarkers = new ArrayList<>();
        int draftMarker;
        int highlightMarker;
        String highlightProfile;
        int highlightUntil;

        Selection(String profile, ServerLevel level) {
            this.profile = profile;
            this.level = level;
        }

        int corners() {
            return (first == null ? 0 : 1) + (second == null ? 0 : 1);
        }

        void clearDraft(ServerPlayer player) {
            hide(player, draftMarker);
            draftMarker = 0;
            first = second = drawnFirst = drawnSecond = null;
        }

        void clearSaved(ServerPlayer player) {
            if (!savedMarkers.isEmpty()) {
                player.connection.send(new ClientboundRemoveEntitiesPacket(
                        savedMarkers.stream().mapToInt(Integer::intValue).toArray()));
                savedMarkers.clear();
            }
        }

        void clearHighlight(ServerPlayer player) {
            hide(player, highlightMarker);
            highlightMarker = 0;
            highlightProfile = null;
        }
    }

    private SelectionEventHandler() {}

    public static String getEditingProfile(ServerPlayer player) {
        Selection selection = selections.get(player.getUUID());
        return selection == null ? null : selection.profile;
    }

    public static void selectProfile(ServerPlayer player, MTRProfile profile) {
        selectProfile(player, profile.getName(), profile.getAreas());
    }

    public static void selectProfile(ServerPlayer player, String name, List<MTRProfile.Area> areas) {
        if (name.equals(getEditingProfile(player))) return;
        clearSelection(player);
        Selection selection = new Selection(name, player.level());
        selections.put(player.getUUID(), selection);
        showSavedAreas(player, selection, areas);
    }

    public static void clearSelection(ServerPlayer player) {
        Selection selection = selections.remove(player.getUUID());
        if (selection == null) return;
        selection.clearSaved(player);
        selection.clearDraft(player);
        selection.clearHighlight(player);
    }

    /** Browsing a saved area does not change the destination of an unfinished selection. */
    public static boolean highlightArea(ServerPlayer player, String profileName, MTRProfile.Area area) {
        if (area == null || !area.dimension.equals(player.level().dimension().location().toString())) return false;
        Selection selection = selections.computeIfAbsent(player.getUUID(), id -> new Selection(null, player.level()));
        syncLevel(player, selection);
        selection.clearHighlight(player);
        selection.highlightMarker = showArea(player, area, true);
        selection.highlightProfile = profileName;
        selection.highlightUntil = player.level().getServer().getTickCount() + 200;
        return true;
    }

    public static boolean profileBusy(String name) {
        MTRProfile active = MTRState.getActiveProfile();
        return (active != null && name.equals(active.getName())) || ReplayManager.isRunning(name);
    }

    public static void refreshProfile(MinecraftServer server, MTRProfile profile) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Selection selection = selections.get(player.getUUID());
            if (selection == null) continue;
            if (profile.getName().equals(selection.highlightProfile)) selection.clearHighlight(player);
            if (profile.getName().equals(selection.profile)) showSavedAreas(player, selection, profile.getAreas());
        }
    }

    public static void forgetProfile(MinecraftServer server, String name) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Selection selection = selections.get(player.getUUID());
            if (selection == null) continue;
            if (name.equals(selection.profile)) clearSelection(player);
            else if (name.equals(selection.highlightProfile)) selection.clearHighlight(player);
        }
    }

    private static int showArea(ServerPlayer player, MTRProfile.Area area, boolean highlight) {
        return MTRMarker.showArea(player, new BlockPos(area.x1, area.y1, area.z1), new BlockPos(area.x2, area.y2, area.z2),
                (highlight ? Blocks.YELLOW_STAINED_GLASS : Blocks.PURPLE_STAINED_GLASS).defaultBlockState(),
                highlight ? ChatFormatting.GOLD : ChatFormatting.LIGHT_PURPLE, highlight ? 0.025 : 0.01);
    }

    private static void showSavedAreas(ServerPlayer player, Selection selection, List<MTRProfile.Area> areas) {
        selection.clearSaved(player);
        String dimension = player.level().dimension().location().toString();
        for (MTRProfile.Area area : areas) {
            if (dimension.equals(area.dimension)) selection.savedMarkers.add(showArea(player, area, false));
        }
    }

    private static void hide(ServerPlayer player, int marker) {
        if (marker != 0) player.connection.send(new ClientboundRemoveEntitiesPacket(marker));
    }

    private static void syncLevel(ServerPlayer player, Selection selection) {
        if (selection.level == player.level()) return;
        selection.clearDraft(player);
        selection.clearHighlight(player);
        selection.clearSaved(player);
        selection.level = player.level();
        if (selection.profile != null) {
            showSavedAreas(player, selection, ProfileManager.listAreas(selection.profile));
        }
    }

    private static void drawDraft(ServerPlayer player, Selection selection) {
        if (selection.corners() == 0) return;
        BlockPos first = selection.first;
        BlockPos second = selection.second;
        if (first == null || second == null) {
            HitResult hit = player.pick(4, 0, false);
            BlockPos cursor = hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK
                    ? block.getBlockPos() : BlockPos.containing(hit.getLocation());
            if (first == null) first = cursor;
            else second = cursor;
        }
        if (first.equals(selection.drawnFirst) && second.equals(selection.drawnSecond)) return;
        hide(player, selection.draftMarker);
        selection.draftMarker = MTRMarker.showArea(player, first, second,
                Blocks.MAGENTA_STAINED_GLASS.defaultBlockState(), ChatFormatting.LIGHT_PURPLE, 0.015);
        selection.drawnFirst = first;
        selection.drawnSecond = second;
    }

    private static void tick(ServerPlayer player, int tick) {
        Selection selection = selections.get(player.getUUID());
        if (!player.hasPermissions(2)) {
            if (selection != null) clearSelection(player);
            return;
        }
        if (selection != null) {
            syncLevel(player, selection);
            if (selection.highlightMarker != 0 && tick - selection.highlightUntil >= 0) selection.clearHighlight(player);
            if (selection.profile == null && selection.highlightMarker == 0) selections.remove(player.getUUID());
            drawDraft(player, selection);
        }
        if (tick % 20 == 0 && (player.getMainHandItem().is(Items.PURPLE_DYE) || player.getOffhandItem().is(Items.PURPLE_DYE))) {
            player.displayClientMessage(selection == null || selection.profile == null
                    ? MTRComponent.translatable("mtr.selection.no_profile", "Select a recording profile to edit its areas")
                    : MTRComponent.translatable("mtr.selection.status", "Editing %s · %s/2 corners set", selection.profile, selection.corners()), true);
        }
    }

    private static void save(ServerPlayer player, Selection selection) {
        String name = selection.profile;
        if (selection.corners() != 2) {
            player.sendSystemMessage(MTRComponent.translatable("mtr.selection.incomplete", "%s · Select both corners before saving", name));
            return;
        }
        if (profileBusy(name)) {
            player.sendSystemMessage(MTRComponent.translatable("mtr.selection.busy", "Stop recording or replaying %s before editing its areas", name));
            return;
        }
        MTRProfile profile = ProfileManager.loadProfile(name);
        if (profile == null) {
            clearSelection(player);
            player.sendSystemMessage(MTRComponent.translatable("commands.mtr.profile.not_found", "Profile not found: %s", name));
            return;
        }
        String areaName = profile.addArea(null, selection.first, selection.second, player.level().dimension().location().toString());
        if (areaName == null || !ProfileManager.trySaveProfile(profile)) {
            player.sendSystemMessage(MTRComponent.translatable("mtr.selection.save_failed", "Could not save areas for %s", name));
            return;
        }
        selection.clearDraft(player);
        refreshProfile(player.level().getServer(), profile);
        player.sendSystemMessage(MTRComponent.translatable("mtr.selection.saved", "Saved to %s · Area %s", name, areaName));
    }

    private static InteractionResult interact(ServerPlayer player, BlockPos pos, boolean first) {
        Selection selection = selections.get(player.getUUID());
        if (selection == null || selection.profile == null || !player.hasPermissions(2)) return InteractionResult.PASS;
        syncLevel(player, selection);
        if (player.isShiftKeyDown()) {
            if (first) {
                selection.clearDraft(player);
                player.sendSystemMessage(MTRComponent.translatable("mtr.selection.cleared", "%s · Draft selection cleared", selection.profile));
            } else save(player, selection);
        } else {
            if (first) selection.first = pos.immutable();
            else selection.second = pos.immutable();
            player.sendSystemMessage(MTRComponent.translatable("mtr.selection.corner", "%s · Corner %s set at %s",
                    selection.profile, first ? 1 : 2, pos.toShortString()));
        }
        return InteractionResult.SUCCESS;
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) tick(player, server.getTickCount());
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (player instanceof ServerPlayer serverPlayer && player.getItemInHand(hand).is(Items.PURPLE_DYE))
                return interact(serverPlayer, hit.getBlockPos(), false);
            return InteractionResult.PASS;
        });
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            if (player instanceof ServerPlayer serverPlayer && player.getItemInHand(hand).is(Items.PURPLE_DYE))
                return interact(serverPlayer, pos, true);
            return InteractionResult.PASS;
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            Selection selection = selections.get(newPlayer.getUUID());
            if (selection != null) selection.level = null; // Rebuild private previews after the client resets its world.
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> selections.remove(handler.getPlayer().getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> selections.clear());
        // These two tags identify persisted previews from versions before client-only markers.
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof BlockDisplay && (entity.getTags().contains("mtr_area_marker")
                    || entity.getTags().contains("mtr_dynamic_marker"))) entity.discard();
        });
    }
}
