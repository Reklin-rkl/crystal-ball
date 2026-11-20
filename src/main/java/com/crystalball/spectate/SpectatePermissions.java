package com.crystalball.spectate;

import net.minecraft.server.MinecraftServer;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * Access to the user configurable permission levels of the three player facing sub commands
 * ({@code /spectate watch}, {@code /spectate stop} and {@code /spectate viewers}).
 * <p>
 * The values are stored in {@link SpectateSavedData} inside the world save, so they survive restarts
 * and are per world/server. A value of {@code 0} means "everybody may use it", {@code 2} means
 * "server operators only" (the same scale vanilla uses for {@code /gamemode} etc.).
 */
public final class SpectatePermissions {

    /** Permission level used when the value was never configured. */
    public static final int DEFAULT_LEVEL = 0;

    /** Minimum vanilla permission level for the administration commands ({@code list}, {@code permission}). */
    public static final int ADMIN_LEVEL = 2;

    public static final String WATCH = "watch";
    public static final String STOP = "stop";
    public static final String VIEWERS = "viewers";

    /** The sub commands whose permission level may be configured. */
    public static final String[] MUTABLE_COMMANDS = { WATCH, STOP, VIEWERS };

    private SpectatePermissions() {
    }

    public static SpectateSavedData data(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(SpectateSavedData::load, SpectateSavedData::new,
                SpectateSavedData.DATA_NAME);
    }

    public static int level(@Nullable MinecraftServer server, String command) {
        if (server == null) {
            return DEFAULT_LEVEL;
        }
        return data(server).getPermission(normalize(command), DEFAULT_LEVEL);
    }

    public static boolean isKnownCommand(String command) {
        String normalized = normalize(command);
        for (String known : MUTABLE_COMMANDS) {
            if (known.equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    public static String normalize(String command) {
        return command == null ? "" : command.toLowerCase(Locale.ROOT);
    }
}
