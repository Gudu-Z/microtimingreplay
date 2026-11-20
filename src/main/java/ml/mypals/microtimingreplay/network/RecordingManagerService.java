package ml.mypals.microtimingreplay.network;

import ml.mypals.microtimingreplay.MTRState;
import ml.mypals.microtimingreplay.compat.TickControl;
import ml.mypals.microtimingreplay.event.SelectionEventHandler;
import ml.mypals.microtimingreplay.profile.MTRProfile;
import ml.mypals.microtimingreplay.profile.ProfileManager;
import ml.mypals.microtimingreplay.util.MTRComponent;
import net.minecraft.FileUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static ml.mypals.microtimingreplay.network.MTRPayloads.*;

final class RecordingManagerService {
    private RecordingManagerService() {}

    private record Result(boolean success, Component feedback) {
        static Result ok(String key, String fallback, Object... args) {
            return new Result(true, MTRComponent.translatable(key, fallback, args));
        }

        static Result fail(String key, String fallback, Object... args) {
            return new Result(false, MTRComponent.translatable(key, fallback, args));
        }
    }

    static ManagerS2C handle(ServerPlayer player, ManagerC2S request) {
        if (!MTRNetworking.isAllowed(player)) {
            return new ManagerS2C(request.requestId(), false, List.of(), "", List.of(), "", "",
                    0, 0, false, false, false,
                    MTRComponent.translatable("mtr.manager.denied", "You do not have permission to manage recordings."));
        }
        MinecraftServer server = player.level().getServer();
        List<String> profiles = new ArrayList<>(ProfileManager.listProfiles());
        profiles.sort(String::compareTo);
        Result result = apply(player, server, profiles, request);
        String editing = Objects.toString(SelectionEventHandler.getEditingProfile(player), "");
        MTRProfile recording = MTRState.getActiveProfile();
        String selected = profiles.contains(request.profile()) ? request.profile()
                : profiles.contains(editing) ? editing : profiles.isEmpty() ? "" : profiles.getFirst();
        List<ManagerArea> areas = selected.isEmpty() ? List.of() : ProfileManager.listAreas(selected).stream()
                .map(area -> new ManagerArea(area.name, area.dimension,
                        new BlockPos(area.x1, area.y1, area.z1), new BlockPos(area.x2, area.y2, area.z2)))
                .toList();
        boolean frozen = TickControl.isFrozen(server);
        boolean canStep = frozen && !server.tickRateManager().isSteppingForward() && mayStep(player);
        return new ManagerS2C(request.requestId(), true, profiles, selected, areas, editing,
                recording == null ? "" : recording.getName(), MTRState.getRecordingCompletedTicks(),
                MTRState.getRecordingTargetTicks(), frozen, canStep, result.success(), result.feedback());
    }

    private static Result apply(ServerPlayer player, MinecraftServer server, List<String> profiles, ManagerC2S request) {
        String name = request.profile();
        if (request.action() == ManagerAction.BROWSE) {
            return new Result(true, Component.empty());
        }
        if (request.action() == ManagerAction.STEP) {
            if (!mayStep(player)) {
                return Result.fail("mtr.manager.step_denied", "You do not have permission to use /tick step.");
            }
            if (request.ticks() != 1 || server.tickRateManager().isSteppingForward()
                    || !TickControl.stepIfFrozen(server, 1)) {
                return Result.fail("mtr.manager.step_unavailable", "The world must be frozen with no pending step.");
            }
            return Result.ok("mtr.manager.stepped", "Requested one world tick.");
        }
        if (request.action() == ManagerAction.CREATE) {
            if (!name.matches("[A-Za-z0-9._+-]+") || name.endsWith(".") || !FileUtil.isPathPortable(Path.of(name))) {
                return Result.fail("mtr.manager.invalid_name", "Choose a valid profile name.");
            }
            if (ProfileManager.hasProfile(name)) {
                return Result.fail("commands.mtr.profile.already_exists", "Profile already exists: %s", name);
            }
            MTRProfile created = ProfileManager.createProfile(name);
            if (created == null) {
                return Result.fail("mtr.manager.create_failed", "Could not save profile %s.", name);
            }
            profiles.add(name);
            profiles.sort(String::compareTo);
            SelectionEventHandler.selectProfile(player, created);
            return Result.ok("commands.mtr.profile.created", "Profile created: %s", name);
        }
        if (request.action() == ManagerAction.END_EDIT) {
            if (!name.equals(SelectionEventHandler.getEditingProfile(player))) {
                return Result.fail("mtr.manager.edit_mismatch", "This profile is not being edited.");
            }
            SelectionEventHandler.clearSelection(player);
            return Result.ok("commands.mtr.profile.hid_markers", "Hid area markers for profile: %s", name);
        }
        if (!profiles.contains(name)) {
            return Result.fail("commands.mtr.profile.not_found", "Profile not found: %s", name);
        }
        if (request.action() == ManagerAction.STOP) {
            MTRProfile active = MTRState.getActiveProfile();
            if (active == null || !active.getName().equals(name)) {
                return Result.fail("mtr.manager.stop_mismatch", "This profile is not the active recording.");
            }
            MTRState.stopRecording();
            return Result.ok("commands.mtr.record.stopped_profile", "Stopped recording profile: %s", name);
        }
        if (request.action() == ManagerAction.START) {
            if (!MTRState.startRecording(name, server, request.ticks())) {
                return Result.fail("commands.mtr.record.start_failed",
                        "Failed to start recording (profile not found or a recording or replay is active)");
            }
            return Result.ok("commands.mtr.record.started_ticks", "Started recording for %d ticks", request.ticks());
        }

        var metadata = ProfileManager.findAreas(name);
        if (metadata.isEmpty()) {
            return Result.fail("commands.mtr.profile.not_found", "Profile not found: %s", name);
        }
        List<MTRProfile.Area> areas = metadata.get();
        if (request.action() == ManagerAction.EDIT) {
            SelectionEventHandler.selectProfile(player, name, areas);
            return Result.ok("mtr.manager.editing", "Editing %s", name);
        }
        MTRProfile.Area area = areas.stream().filter(a -> a.name.equals(request.area())).findFirst().orElse(null);
        if (area != null && !area.dimension.equals(player.level().dimension().location().toString())) {
            return Result.fail("mtr.manager.other_dimension", "Area %s is in %s.", area.name, area.dimension);
        }
        if (area == null || !SelectionEventHandler.highlightArea(player, name, area)) {
            return Result.fail("commands.mtr.area.not_found", "Area not found: %s", request.area());
        }
        return Result.ok("mtr.manager.highlighted", "Highlighting %s · %s", name, request.area());
    }

    private static boolean mayStep(ServerPlayer player) {
        var source = player.createCommandSourceStack();
        var parsed = player.level().getServer().getCommands().getDispatcher().parse("tick step 1", source);
        // Brigadier applies the currently registered command requirements, including mod overrides.
        return !parsed.getReader().canRead() && parsed.getContext().getLastChild().getCommand() != null;
    }
}
