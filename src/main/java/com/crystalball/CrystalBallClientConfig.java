package com.crystalball;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Client side configuration of the Crystal Ball remote viewing.
 * <p>
 * These values cap what the server streams to this client. Both sides are taken into account with
 * {@code min(server value, client value)} (and {@code -1} meaning "no limit"), so a client with a
 * weak connection can ask for fewer entities than the server would send, while the server can still
 * apply its own upper bound for everybody.
 * <p>
 * The values are announced to the server when the player joins and whenever this file is reloaded
 * ({@link com.crystalball.client.CrystalBallClientEvents}).
 */
public final class CrystalBallClientConfig {

    public static final int DEFAULT_ENTITY_RADIUS = 48;
    public static final int DEFAULT_MAX_ENTITIES = 40;

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.IntValue ENTITY_RADIUS = BUILDER
            .comment("Client side cap (in blocks) for the radius around the watched player in which",
                    "entities are streamed to this client.",
                    "The server uses min(server entityRadius, this value). -1 = no limit.")
            .defineInRange("entityRadius", DEFAULT_ENTITY_RADIUS, -1, 512);

    public static final ForgeConfigSpec.IntValue MAX_ENTITIES = BUILDER
            .comment("Client side cap for the number of entities streamed to this client at once.",
                    "The server uses min(server maxStreamedEntities, this value). -1 = no limit.")
            .defineInRange("maxStreamedEntities", DEFAULT_MAX_ENTITIES, -1, 1000);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    private CrystalBallClientConfig() {
    }

    /** Configured entity radius, {@code -1} for "no limit"; the default is used before the config is loaded. */
    public static int entityRadius() {
        return value(ENTITY_RADIUS, DEFAULT_ENTITY_RADIUS);
    }

    /** Configured entity count cap, {@code -1} for "no limit"; the default is used before the config is loaded. */
    public static int maxStreamedEntities() {
        return value(MAX_ENTITIES, DEFAULT_MAX_ENTITIES);
    }

    private static int value(ForgeConfigSpec.IntValue option, int fallback) {
        try {
            return option.get();
        } catch (RuntimeException notLoaded) {
            // The config is only loaded on the physical client; asking before that must not crash.
            return fallback;
        }
    }
}
