package ml.mypals.microtimingreplay.profile;

import ml.mypals.microtimingreplay.MicroTimingReplay;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.nbt.NbtIo;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ProfileManager {
    private record AreaCache(File file, long modified, long size, List<MTRProfile.Area> areas) {}

    private static final Map<String, AreaCache> AREA_CACHE = new HashMap<>();

    public static void init() {
    }

    public static MTRProfile createProfile(String name) {
        if (hasProfile(name)) {
            return null;
        }
        MTRProfile profile = new MTRProfile(name);
        return trySaveProfile(profile) ? profile : null;
    }

    public static void saveProfile(MTRProfile profile) {
        trySaveProfile(profile);
    }

    public static boolean trySaveProfile(MTRProfile profile) {
        Path path = WorldScopedStorage.getProfileFile(profile.getName()).toPath();
        try {
            Path staged = Files.createTempFile(path.getParent(), "profile-", ".tmp");
            try {
                NbtIo.writeCompressed(profile.writeNBT(), staged);
                try {
                    Files.move(staged, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(staged, path, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(staged);
            }
            AREA_CACHE.remove(profile.getName());
            return true;
        } catch (IOException e) {
            MicroTimingReplay.LOGGER.error("Failed to save profile: {}", profile.getName(), e);
            return false;
        }
    }

    public static List<String> listAreaNames(String name) {
        return listAreas(name).stream().map(area -> area.name).toList();
    }

    public static List<MTRProfile.Area> listAreas(String name) {
        return findAreas(name).orElse(List.of());
    }

    /** Loads area metadata independently of recorded frames. A valid profile can have no areas. */
    public static Optional<List<MTRProfile.Area>> findAreas(String name) {
        File file = WorldScopedStorage.getProfileFile(name);
        if (!file.isFile()) {
            AREA_CACHE.remove(name);
            return Optional.empty();
        }
        AreaCache cached = AREA_CACHE.get(name);
        long modified = file.lastModified();
        long size = file.length();
        if (cached != null && cached.file().equals(file) && cached.modified() == modified && cached.size() == size) {
            return Optional.of(cached.areas());
        }

        CollectFields fields = new CollectFields(new FieldSelector(ListTag.TYPE, "areas"));
        try {
            NbtIo.parseCompressed(file.toPath(), fields, NbtAccounter.unlimitedHeap());
            List<MTRProfile.Area> areas = fields.getResult() instanceof CompoundTag metadata
                    ? metadata.getListOrEmpty("areas").compoundStream().map(MTRProfile.Area::readNBT).toList()
                    : List.of();
            AREA_CACHE.put(name, new AreaCache(file, modified, size, areas));
            return Optional.of(areas);
        } catch (IOException e) {
            MicroTimingReplay.LOGGER.error("Failed to load areas for profile: {}", name, e);
            return Optional.empty();
        }
    }

    public static MTRProfile loadProfile(String name) {
        Path path = WorldScopedStorage.getProfileFile(name).toPath();
        if (!path.toFile().exists()) {
            return null;
        }
        try {
            CompoundTag tag = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
            return MTRProfile.readNBT(tag);
        } catch (IOException e) {
            MicroTimingReplay.LOGGER.error("Failed to load profile: {}", name, e);
            return null;
        }
    }

    public static boolean deleteProfile(String name) {
        boolean deleted = WorldScopedStorage.deleteProfile(name);
        if (deleted) {
            AREA_CACHE.remove(name);
        }
        return deleted;
    }

    public static boolean hasProfile(String name) {
        return WorldScopedStorage.hasProfile(name);
    }

    public static List<String> listProfiles() {
        return WorldScopedStorage.listProfileNames();
    }

    public static List<String> listLegacyProfiles() {
        return WorldScopedStorage.listLegacyProfileNames();
    }

    public static boolean migrateLegacyProfile(String name) {
        boolean migrated = WorldScopedStorage.migrateLegacyProfile(name);
        if (migrated) {
            AREA_CACHE.remove(name);
        }
        return migrated;
    }
}
