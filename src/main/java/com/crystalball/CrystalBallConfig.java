package com.crystalball;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Server side configuration of the Crystal Ball remote viewing.
 * <p>
 * The values control how much of the remote world is streamed: chunk radius, how many chunks are
 * pushed per tick (and how fast right after a dimension change), how many entities are streamed and
 * how far, the distance of the offset copy of another dimension, and how often a changed chunk may
 * be re-pushed.
 * <p>
 * {@code entityRadius} and {@code maxStreamedEntities} also exist in the client config; the streamer
 * uses the smaller of the two sides ({@code -1} = no limit so the other side wins).
 */
public final class CrystalBallConfig {

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.IntValue STREAM_RADIUS = BUILDER
            .comment("Radius in chunks of the streamed area around the watched player.")
            .defineInRange("streamRadius", 12, 4, 32);

    public static final ForgeConfigSpec.IntValue CHUNKS_PER_TICK = BUILDER
            .comment("How many chunks are pushed to the viewer per tick during normal streaming.")
            .defineInRange("chunksPerTick", 24, 4, 128);

    public static final ForgeConfigSpec.IntValue BURST_CHUNKS_PER_TICK = BUILDER
            .comment("How many chunks are pushed per tick right after a dimension change / window move.")
            .defineInRange("burstChunksPerTick", 64, 16, 256);

    public static final ForgeConfigSpec.IntValue CROSS_DIMENSION_DISTANCE_CHUNKS = BUILDER
            .comment("Distance in chunks between the viewer's body and the offset copy of another dimension.",
                    "Large enough that the two worlds can never overlap inside the client chunk cache.")
            .defineInRange("crossDimensionDistanceChunks", 1024, 64, 8192);

    public static final ForgeConfigSpec.IntValue ENTITY_RADIUS = BUILDER
            .comment("Radius in blocks around the watched player in which entities are streamed.",
                    "The client config can lower this value (min(server, client)); -1 = no limit.")
            .defineInRange("entityRadius", 48, -1, 512);

    public static final ForgeConfigSpec.IntValue MAX_ENTITIES = BUILDER
            .comment("Maximum number of entities streamed at once (closest to the target are kept).",
                    "The client config can lower this value (min(server, client)); -1 = no limit.")
            .defineInRange("maxStreamedEntities", 40, -1, 1000);

    public static final ForgeConfigSpec.BooleanValue ENTITY_SAME_DIMENSION = BUILDER
            .comment("Stream entities also in the same dimension (far away mobs that vanilla does not track).",
                    "Set to false if it ever causes duplicated entities with other mods.")
            .define("streamEntitiesSameDimension", true);

    public static final ForgeConfigSpec.BooleanValue SKIP_VANILLA_TRACKED = BUILDER
            .comment("Skip entities that vanilla already streams to the viewer (recommended).",
                    "Turn off to force streaming everything (may cause duplicated / jittering entities).")
            .define("skipVanillaTrackedEntities", true);

    public static final ForgeConfigSpec.IntValue BLOCK_RESEND_COOLDOWN = BUILDER
            .comment("Minimum ticks between two re-pushes of the same chunk after block/fluid changes.")
            .defineInRange("blockResendCooldownTicks", 5, 0, 40);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    private CrystalBallConfig() {
    }
}
