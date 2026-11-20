package com.crystalball.spectate;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * World scoped persistent data of the Crystal Ball mod.
 * <p>
 * Only the configurable permission levels of the three player facing sub commands live here.
 * Watching itself is a purely ephemeral, client side camera effect and therefore needs no saved
 * state at all (no game mode, no teleport, nothing that could break if the server crashes).
 */
public class SpectateSavedData extends SavedData {

    public static final String DATA_NAME = "crystal_ball_spectate";

    private static final String KEY_PERMISSIONS = "Permissions";

    private final Map<String, Integer> permissions = new LinkedHashMap<>();

    public static SpectateSavedData load(CompoundTag tag) {
        SpectateSavedData data = new SpectateSavedData();
        CompoundTag permissionsTag = tag.getCompound(KEY_PERMISSIONS);
        for (String key : permissionsTag.getAllKeys()) {
            if (permissionsTag.contains(key, Tag.TAG_INT)) {
                data.permissions.put(key.toLowerCase(Locale.ROOT), permissionsTag.getInt(key));
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        CompoundTag permissionsTag = new CompoundTag();
        permissions.forEach(permissionsTag::putInt);
        tag.put(KEY_PERMISSIONS, permissionsTag);
        return tag;
    }

    public int getPermission(String command, int fallback) {
        return permissions.getOrDefault(command, fallback);
    }

    public void setPermission(String command, int level) {
        permissions.put(command.toLowerCase(Locale.ROOT), level);
        setDirty();
    }

    public void clearPermissions() {
        permissions.clear();
        setDirty();
    }

    public Map<String, Integer> getPermissionsView() {
        return Collections.unmodifiableMap(permissions);
    }
}
