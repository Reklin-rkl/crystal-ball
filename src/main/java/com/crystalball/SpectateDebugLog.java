package com.crystalball;

import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Small, self contained debug log used to diagnose the remote viewing.
 * <p>
 * Minecraft's own {@code logs/latest.log} is shared by every game instance that is started from the
 * same game directory, so launching several clients from one installation overwrites it. This log
 * uses a distinct file per role (and per player name for clients) so every instance keeps its own
 * history:
 * <ul>
 *     <li>{@code logs/crystal_ball-client-<name>.log}</li>
 *     <li>{@code logs/crystal_ball-server.log}</li>
 * </ul>
 * A host has both files. The log is line buffered and appends across launches; every launch writes a
 * session header so multiple sessions can be told apart.
 */
public final class SpectateDebugLog {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    /** Log of the physical client (the viewer side and everything it sees). */
    public static final SpectateDebugLog CLIENT = new SpectateDebugLog("client");
    /** Log of the server side (sessions, streaming, player tracking). */
    public static final SpectateDebugLog SERVER = new SpectateDebugLog("server");

    private final String role;
    private final Object lock = new Object();

    @Nullable
    private BufferedWriter writer;
    private String label;

    private SpectateDebugLog(String role) {
        this.role = role;
        this.label = role;
    }

    /** Opens (or reopens) the log file. Safe to call twice; the old file is closed first. */
    public void init(Path file, String playerLabel) {
        synchronized (lock) {
            close();
            this.label = playerLabel == null || playerLabel.isEmpty() ? role : role + "/" + playerLabel;
            try {
                Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND, StandardOpenOption.WRITE);
                log("=== session start: Crystal Ball %s, %s, file=%s ===", version(), label, file.toAbsolutePath());
            } catch (IOException exception) {
                writer = null;
                CrystalBallMod.LOGGER.warn("Crystal Ball: could not open debug log {}", file, exception);
            }
        }
    }

    public void close() {
        synchronized (lock) {
            if (writer == null) {
                return;
            }
            try {
                writer.flush();
                writer.close();
            } catch (IOException ignored) {
                // Never let logging break the game.
            }
            writer = null;
        }
    }

    public void log(String format, Object... args) {
        synchronized (lock) {
            if (writer == null) {
                return;
            }
            try {
                String message = args.length == 0 ? format : String.format(Locale.ROOT, format, args);
                writer.write(LocalDateTime.now().format(TIME));
                writer.write(" [");
                writer.write(label);
                writer.write("] ");
                writer.write(message.replace('\n', ' ').replace('\r', ' '));
                writer.newLine();
                writer.flush();
            } catch (IOException exception) {
                writer = null;
            }
        }
    }

    /** Removes characters that are not safe in a file name and shortens the name. */
    public static String safeName(String name) {
        String cleaned = name == null ? "" : name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (cleaned.isEmpty()) {
            cleaned = "unknown";
        }
        return cleaned.length() > 32 ? cleaned.substring(0, 32) : cleaned;
    }

    private static String version() {
        try {
            return ModList.get().getModContainerById(CrystalBallMod.MODID)
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("unknown");
        } catch (RuntimeException ignored) {
            return "unknown";
        }
    }
}
