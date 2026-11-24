package com.crystalball.spectate;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import com.mojang.datafixers.util.Pair;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundAddMobPacket;
import net.minecraft.network.protocol.game.ClientboundAddPlayerPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import com.crystalball.CrystalBallMod;
import com.crystalball.SpectateDebugLog;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server side "remote world" streamer: pushes the world around the watched player to the viewer.
 * <p>
 * This is a <b>push</b> design (the v4 version asked the client to pull, which turned out to be the
 * part that silently failed): every server tick a bounded number of chunks around the target is sent
 * to the viewer, and the set of already sent chunks is remembered per session, so the traffic is
 * incremental. The client only has to keep its chunk window centered on the camera, which it does
 * with the public {@code ClientChunkCache#updateViewCenter}.
 * <p>
 * On top of the chunk streaming this class
 * <ul>
 *     <li>adds the target entity when the vanilla tracker does not track it (players are only tracked
 *     within {@code (view-distance - 1) * 16} blocks) and keeps its position/rotation updated,</li>
 *     <li>keeps the viewer's body in lockstep with the server (periodic position packets), so
 *     falling / digging under the player cannot desync the two views,</li>
 *     <li>re-sends the area around the player's own body after the session ends, so returning is
 *     seamless even if the client chunk cache had to give something up.</li>
 * </ul>
 */
public final class SpectateRemoteView {

    /** Chunk packets sent per server tick while streaming (the client renders them as they arrive). */


    private static final int BURST_TICKS = 10;

    private static int streamRadius() {
        return com.crystalball.CrystalBallConfig.STREAM_RADIUS.get();
    }

    private static int chunksPerTick(boolean burst) {
        return burst ? com.crystalball.CrystalBallConfig.BURST_CHUNKS_PER_TICK.get()
                : com.crystalball.CrystalBallConfig.CHUNKS_PER_TICK.get();
    }

    /**
     * Non-player entities are streamed for this radius around the watched player.
     * <p>
     * The server config and the client config both define it; the smaller one wins and {@code -1}
     * means "no limit" (so the other side decides). A client that has not announced its limits yet
     * is treated as unlimited, which keeps the server config authoritative.
     */
    private static double entityRadius(ServerPlayer viewer) {
        ClientCaps caps = clientCaps(viewer);
        return effectiveCap(com.crystalball.CrystalBallConfig.ENTITY_RADIUS.get(), caps.entityRadius());
    }

    private static int maxStreamedEntities(ServerPlayer viewer) {
        ClientCaps caps = clientCaps(viewer);
        return effectiveCap(com.crystalball.CrystalBallConfig.MAX_ENTITIES.get(), caps.maxStreamedEntities());
    }

    /** {@code -1} = unlimited on both sides. */
    private static int effectiveCap(int serverValue, int clientValue) {
        if (serverValue < 0) {
            return clientValue;
        }
        if (clientValue < 0) {
            return serverValue;
        }
        return Math.min(serverValue, clientValue);
    }

    private static ClientCaps clientCaps(ServerPlayer viewer) {
        ClientCaps caps = CLIENT_CAPS.get(viewer.getUUID());
        return caps == null ? ClientCaps.UNLIMITED : caps;
    }

    /** Chunk packets sent per tick while restoring the area around the viewer's own body. */
    private static final int RESTORE_CHUNKS_PER_TICK = 32;

    /** Radius (in chunks) restored around the viewer's body after a session. */
    private static final int RESTORE_RADIUS = 5;

    private static final int LOG_INTERVAL = 100;

    /** All slots that {@code ClientboundSetEquipmentPacket} can carry. */
    private static final EquipmentSlot[] EQUIPMENT_SLOTS = EquipmentSlot.values();

    /**
     * Cross dimension watching moves the streamed world far away from the viewer's own loaded area.
     * The client chunk cache is keyed by chunk coordinates only (it does not know dimensions), so
     * streaming another dimension onto the same coordinates would mix both worlds (water next to
     * lava, ice inside the nether, ...). Offsetting the whole remote world avoids that entirely.
     * The client camera anchor applies the same offset, see {@code SpectateClientController}.
     */
    /**
     * Distance in chunks between the viewer's own body and the offset copy of the remote world.
     * <ul>
     *     <li>far enough (1024 chunks = 16,384 blocks) that the client never has its own dimension's
     *     chunks anywhere near the offset world - no mixing, no matter where the world is;</li>
     *     <li>small enough that the absolute coordinates stay in the range where float rendering with
     *     entity interpolation is still smooth (a 1,000,000 block offset used to look jittery).</li>
     * </ul>
     */


    private static final Map<UUID, ViewState> STATES = new HashMap<>();
    private static final Map<UUID, RestoreState> RESTORES = new HashMap<>();

    /**
     * Streaming limits announced by the client config ({@link com.crystalball.CrystalBallClientConfig}).
     * {@code -1} means "no client limit" so the server config decides.
     */
    private static final Map<UUID, ClientCaps> CLIENT_CAPS = new HashMap<>();

    private record ClientCaps(int entityRadius, int maxStreamedEntities) {
        static final ClientCaps UNLIMITED = new ClientCaps(-1, -1);
    }

    /** Cache of chunks that had to be re-packed into the viewer's dimension (cross dimension watch). */
    private static final Map<ChunkCacheKey, CachedPacket> PACKET_CACHE = new HashMap<>();
    private static final int PACKET_CACHE_TICKS = 40;
    private static final int PACKET_CACHE_MAX = 2048;
    private static long tickCounter;

    /**
     * Cache key of a re-packed chunk. {@code sourcePos} is kept as well so a block change can drop
     * every cached copy of the chunk (all destination offsets / vertical windows) without knowing
     * which viewer requested it.
     */
    private record ChunkCacheKey(ResourceKey<Level> source, ResourceKey<Level> destination, long sourcePos,
            long chunkPos, int sectionOffsetY) {
    }

    private static final class CachedPacket {
        final ClientboundLevelChunkWithLightPacket packet;
        final long tick;

        CachedPacket(ClientboundLevelChunkWithLightPacket packet, long tick) {
            this.packet = packet;
            this.tick = tick;
        }
    }

    private static final class ViewState {
        @Nullable
        ResourceKey<Level> dimension;
        int offsetXChunks;
        int offsetZChunks;
        /** Section index shift while re-packing chunks (destIndex = srcIndex + sectionOffsetY). */
        int sectionOffsetY;
        /** Vertical shift applied to the camera anchor for dimensions with a taller section range. */
        double yShift;
        /** Vertical window base with hysteresis, and the fast re-stream burst counter. */
        int verticalBase;
        boolean verticalBaseSet;
        int burstTicks;
        int lastCandidateCount;
        /** Entities currently streamed to the viewer: entity id -> last sent (offset) position. */
        final Map<Integer, SentEntity> entities = new HashMap<>();
        final Map<Long, Long> resendCooldown = new HashMap<>();
        @Nullable
        ChunkPos center;
        final LongOpenHashSet sent = new LongOpenHashSet();
        int entityId = -1;
        boolean injected;
        /** null = client has not told us yet, TRUE = client has the entity, FALSE = client is missing it. */
        Boolean clientHasEntity;
        double lastX;
        double lastY;
        double lastZ;
        float lastYaw;
        float lastPitch;
        float lastHeadYaw;
        int rotationTicks;
        double syncedX;
        double syncedY;
        double syncedZ;
        float syncedYaw;
        float syncedPitch;
        int logTimer;
        int syncTicks;
        int sentTotal;
        int unloadedTotal;
        /** Last entity id set announced to the client (sorted) so it is only resent when it changes. */
        int[] lastSentStreamedIds = new int[0];
        /** Turns between two periodic debug log lines. */
        int debugTicks;
    }

    private static final class RestoreState {
        final LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        int ticks;
    }

    /** Last position/rotation sent for a streamed non-player entity. */
    private static final class SentEntity {
        double x;
        double y;
        double z;
        float yaw;
        float pitch;
        int age;

        SentEntity(double x, double y, double z, float yaw, float pitch) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    private SpectateRemoteView() {
    }

    // ------------------------------------------------------------------
    // Session hooks
    // ------------------------------------------------------------------

    public static void onStart(ServerPlayer viewer, ServerPlayer target) {
        ViewState previous = STATES.get(viewer.getUUID());
        clearEntity(viewer, previous);
        // Retargeting keeps the same viewer, but the entities around the previous target are not
        // around the new one: drop them explicitly, otherwise their client side copies stay in the
        // world forever (ghost mobs / ghost players after switching with the beta key).
        clearStreamedEntities(viewer, previous);
        ViewState state = new ViewState();
        state.dimension = target.level.dimension();
        state.center = target.chunkPosition();
        state.offsetXChunks = 0;
        state.offsetZChunks = 0;
        applyOffsets(viewer, target, state, viewer.level != target.level);
        state.syncedX = viewer.getX();
        state.syncedY = viewer.getY();
        state.syncedZ = viewer.getZ();
        state.syncedYaw = viewer.getYRot();
        state.syncedPitch = viewer.getXRot();
        state.burstTicks = BURST_TICKS;
        STATES.put(viewer.getUUID(), state);
        sendCenter(viewer, offsetCenter(state));
        // The previous session may have streamed entities around another target; start with an empty
        // set so the client never applies streamed-only fixes to stale ids.
        SpectateNetwork.sendStreamedEntities(viewer, new int[0]);
        SpectateDebugLog.SERVER.log("stream start: viewer=%s target=%s dim=%s center=(%d,%d) offset=(%d,%d) sectionOffset=%d yShift=%.2f cross=%s",
                viewer.getGameProfile().getName(), target.getGameProfile().getName(), state.dimension.location(),
                state.center.x, state.center.z, state.offsetXChunks, state.offsetZChunks, state.sectionOffsetY,
                state.yShift, viewer.level != target.level);
    }

    // ------------------------------------------------------------------
    // Client announced limits (client half of the configuration)
    // ------------------------------------------------------------------

    /** Stores the streaming limits announced by a client. Values are clamped, never trusted. */
    public static void onClientCaps(ServerPlayer viewer, int entityRadius, int maxStreamedEntities) {
        if (viewer == null) {
            return;
        }
        int radius = sanitizeCap(entityRadius);
        int max = sanitizeCap(maxStreamedEntities);
        CLIENT_CAPS.put(viewer.getUUID(), new ClientCaps(radius, max));
        SpectateDebugLog.SERVER.log("client caps: player=%s entityRadius=%d maxStreamedEntities=%d",
                viewer.getGameProfile().getName(), radius, max);
    }

    /** Drops the announced limits of a viewer that left (they are sent again on the next join). */
    public static void forgetClientCaps(UUID viewer) {
        CLIENT_CAPS.remove(viewer);
    }

    private static int sanitizeCap(int value) {
        if (value < 0) {
            // Every negative value means "no client side limit"; only the server config applies.
            return -1;
        }
        return Math.min(value, 4096);
    }

    private static void applyOffsets(ServerPlayer viewer, ServerPlayer target, ViewState state, boolean crossDimension) {
        if (!crossDimension) {
            state.offsetXChunks = 0;
            state.offsetZChunks = 0;
            return;
        }
        ChunkPos viewerChunk = viewer.chunkPosition();
        ChunkPos targetChunk = target.chunkPosition();
        state.offsetXChunks = viewerChunk.x + com.crystalball.CrystalBallConfig.CROSS_DIMENSION_DISTANCE_CHUNKS.get() - targetChunk.x;
        state.offsetZChunks = viewerChunk.z + com.crystalball.CrystalBallConfig.CROSS_DIMENSION_DISTANCE_CHUNKS.get() - targetChunk.z;
    }

    private static ChunkPos offsetCenter(ViewState state) {
        ChunkPos center = state.center == null ? new ChunkPos(0, 0) : state.center;
        return new ChunkPos(center.x + state.offsetXChunks, center.z + state.offsetZChunks);
    }

    /** World X of the client side camera anchor (already shifted into the offset world). */
    public static double anchorX(ServerPlayer viewer, ServerPlayer target) {
        ViewState state = STATES.get(viewer.getUUID());
        return target.getX() + (state == null ? 0 : state.offsetXChunks) * 16.0D;
    }

    /** World Y of the client side camera anchor (shifted when only a window of sections fits). */
    public static double anchorY(ServerPlayer viewer, ServerPlayer target) {
        ViewState state = STATES.get(viewer.getUUID());
        return target.getEyeY() + (state == null ? 0.0D : state.yShift);
    }

    /** World Z of the client side camera anchor (already shifted into the offset world). */
    public static double anchorZ(ServerPlayer viewer, ServerPlayer target) {
        ViewState state = STATES.get(viewer.getUUID());
        return target.getZ() + (state == null ? 0 : state.offsetZChunks) * 16.0D;
    }

    public static void tick(ServerPlayer viewer, ServerPlayer target) {
        ViewState state = STATES.get(viewer.getUUID());
        if (state == null) {
            return;
        }
        if (!(target.level instanceof ServerLevel targetLevel)) {
            return;
        }

        tickCounter++;
        ResourceKey<Level> dimension = target.level.dimension();
        boolean cross = viewer.level != target.level;
        boolean mappingChanged = false;
        if (state.dimension == null || !state.dimension.equals(dimension) || (state.offsetXChunks != 0) != cross) {
            clearEntity(viewer, state);
            clearStreamedEntities(viewer, state);
            state.dimension = dimension;
            applyOffsets(viewer, target, state, cross);
            state.verticalBaseSet = false;
            state.center = target.chunkPosition();
            state.sent.clear();
            mappingChanged = true;
        }
        ChunkPos targetChunk = target.chunkPosition();
        if (state.center == null || !state.center.equals(targetChunk)) {
            state.center = targetChunk;
            sendCenter(viewer, offsetCenter(state));
            pruneSent(state);
        }

        // Vertical window mapping. When the target's dimension is taller than the viewer's client
        // level (e.g. watching the overworld from the nether: 24 vs 8 sections) only a window of the
        // source sections fits. The window is centred on the target's current section and the camera
        // anchor is shifted accordingly, so the deep overworld (y < 0) becomes visible in the nether.
        if (cross) {
            int sourceCount = targetLevel.getSectionsCount();
            int destCount = viewer.getLevel().getSectionsCount();
            int sourceMin = targetLevel.getMinBuildHeight();
            int destMin = viewer.getLevel().getMinBuildHeight();
            int sectionOffset;
            double shift;
            if (sourceCount <= destCount) {
                sectionOffset = (sourceMin - destMin) / 16;
                shift = 0.0D;
                state.verticalBaseSet = false;
            } else {
                int targetSection = (Mth.floor(target.getY()) - sourceMin) >> 4;
                // Hysteresis: only re-centre near the window edge. Re-centring whenever the target
                // crossed a section boundary made the camera shift and the already streamed chunks
                // disagree for a moment (the world looked a few blocks off / the camera sat inside a
                // wall). One section of margin keeps the window stable while walking or falling.
                boolean recentre = !state.verticalBaseSet;
                if (!recentre) {
                    int relative = targetSection - state.verticalBase;
                    recentre = relative < 1 || relative >= destCount - 1;
                }
                if (recentre) {
                    state.verticalBase = Mth.clamp(targetSection - (destCount / 2 - 1), 0, sourceCount - destCount);
                    state.verticalBaseSet = true;
                }
                sectionOffset = -state.verticalBase;
                shift = destMin - sourceMin - state.verticalBase * 16.0D;
            }
            if (state.sectionOffsetY != sectionOffset || state.yShift != shift) {
                mappingChanged = true;
            }
            state.sectionOffsetY = sectionOffset;
            state.yShift = shift;
        } else {
            if (state.sectionOffsetY != 0 || state.yShift != 0.0D) {
                mappingChanged = true;
            }
            state.sectionOffsetY = 0;
            state.yShift = 0.0D;
            state.verticalBaseSet = false;
        }

        // When the mapping changed (dimension change or re-centred vertical window) the client still
        // holds chunks translated with the OLD mapping at the same chunk coordinates. Forget them so
        // the camera can never end up inside stale blocks, then stream everything again in a fast
        // burst - this is what makes portal transitions not take seconds to become correct.
        if (mappingChanged) {
            sendCenter(viewer, offsetCenter(state));
            state.sent.clear();
            state.burstTicks = BURST_TICKS;
            SpectateDebugLog.SERVER.log("stream remap: viewer=%s target=%s dim=%s center=%s offset=(%d,%d) sectionOffset=%d yShift=%.2f",
                    viewer.getGameProfile().getName(), target.getGameProfile().getName(),
                    state.dimension.location(), offsetCenter(state), state.offsetXChunks, state.offsetZChunks,
                    state.sectionOffsetY, state.yShift);
        }

        int sentNow = pushChunks(viewer, targetLevel, state);
        if (state.burstTicks > 0) {
            state.burstTicks--;
        }
        // The viewer camera is a client side clone of the target, so no player entity injection is
        // needed anymore (it used to duplicate the model in third person).
        syncBody(viewer, state);
        streamEntities(viewer, target, targetLevel, state);

        if (++state.logTimer >= LOG_INTERVAL) {
            state.logTimer = 0;
            CrystalBallMod.LOGGER.info(
                    "Crystal Ball stream: target={} center=({},{}) sent={} (+{}/tick) unloaded={} injected={} cand={} pos=({},{},{}) anchor=({},{},{}) shiftY={} ents={}",
                    target.getGameProfile().getName(), targetChunk.x, targetChunk.z, state.sent.size(), sentNow,
                    state.unloadedTotal, state.injected, state.lastCandidateCount, target.getX(), target.getY(), target.getZ(), anchorX(viewer, target), anchorY(viewer, target), anchorZ(viewer, target), state.yShift, describeEntities(state));
        }
        if (++state.debugTicks >= 20) {
            state.debugTicks = 0;
            SpectateDebugLog.SERVER.log(
                    "stream tick: viewer=%s target=%s dim=%s pos=%.1f,%.1f,%.1f center=%s offset=(%d,%d) sectionOffset=%d yShift=%.2f sent=%d candidates=%d entities=[%s]",
                    viewer.getGameProfile().getName(), target.getGameProfile().getName(),
                    targetLevel.dimension().location(), target.getX(), target.getY(), target.getZ(),
                    offsetCenter(state), state.offsetXChunks, state.offsetZChunks, state.sectionOffsetY, state.yShift,
                    state.sent.size(), state.lastCandidateCount, describeEntityNames(targetLevel, state));
        }
    }

    public static void onStop(ServerPlayer viewer, @Nullable ServerPlayer target) {
        ViewState state = STATES.remove(viewer.getUUID());
        SpectateDebugLog.SERVER.log("stream stop: viewer=%s target=%s streamed=%d sentChunks=%d",
                viewer.getGameProfile().getName(), target == null ? "?" : target.getGameProfile().getName(),
                state == null ? -1 : state.entities.size(), state == null ? -1 : state.sent.size());
        clearEntity(viewer, state);
        clearStreamedEntities(viewer, state);
        // If we injected the entity while the vanilla tracker also considers it tracked, re-add it so
        // the client keeps seeing the player through the normal tracker instead of losing it.
        if (state != null && state.injected && target != null && isVanillaTracked(viewer, target)) {
            viewer.connection.send(new ClientboundAddPlayerPacket(target));
        }
        if (viewer.connection == null) {
            return;
        }
        ChunkPos own = viewer.chunkPosition();
        viewer.connection.send(new ClientboundSetChunkCacheCenterPacket(own.x, own.z));
        // Re-send the area around the player's own body; streamed chunks may have displaced it.
        RestoreState restore = new RestoreState();
        for (int dx = -RESTORE_RADIUS; dx <= RESTORE_RADIUS; dx++) {
            for (int dz = -RESTORE_RADIUS; dz <= RESTORE_RADIUS; dz++) {
                restore.queue.enqueue(ChunkPos.asLong(own.x + dx, own.z + dz));
            }
        }
        RESTORES.put(viewer.getUUID(), restore);
    }

    public static void tickRestores(MinecraftServer server) {
        if (RESTORES.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, RestoreState>> iterator = RESTORES.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, RestoreState> entry = iterator.next();
            ServerPlayer viewer = server.getPlayerList().getPlayer(entry.getKey());
            RestoreState restore = entry.getValue();
            if (viewer == null || viewer.connection == null || restore.queue.isEmpty() || ++restore.ticks > 80) {
                iterator.remove();
                continue;
            }
            ServerLevel level = viewer.getLevel();
            int budget = RESTORE_CHUNKS_PER_TICK;
            while (budget > 0 && !restore.queue.isEmpty()) {
                long key = restore.queue.dequeueLong();
                ChunkPos pos = new ChunkPos(key);
                LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
                if (chunk == null) {
                    continue;
                }
                viewer.connection.send(
                        new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null, true));
                budget--;
            }
            if (restore.queue.isEmpty()) {
                iterator.remove();
            }
        }
    }

    public static void clear() {
        STATES.clear();
        RESTORES.clear();
        CLIENT_CAPS.clear();
        PACKET_CACHE.clear();
    }

    // ------------------------------------------------------------------
    // Chunks
    // ------------------------------------------------------------------

    private static int pushChunks(ServerPlayer viewer, ServerLevel level, ViewState state) {
        ChunkPos center = state.center;
        if (center == null) {
            return 0;
        }
        int budget = chunksPerTick(state.burstTicks > 0);
        int unloaded = 0;
        int sentNow = 0;
        int offsetX = state.offsetXChunks;
        int offsetZ = state.offsetZChunks;
        for (int dx = -streamRadius(); dx <= streamRadius() && budget > 0; dx++) {
            for (int dz = -streamRadius(); dz <= streamRadius() && budget > 0; dz++) {
                int x = center.x + dx;
                int z = center.z + dz;
                long key = ChunkPos.asLong(x + offsetX, z + offsetZ);
                if (state.sent.contains(key)) {
                    continue;
                }
                LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null) {
                    unloaded++;
                    continue;
                }
                ClientboundLevelChunkWithLightPacket packet = packetFor(viewer, level, chunk, offsetX, offsetZ, state.sectionOffsetY);
                if (packet == null) {
                    continue;
                }
                viewer.connection.send(packet);
                state.sent.add(key);
                state.sentTotal++;
                budget--;
                sentNow++;
            }
        }
        state.unloadedTotal = unloaded;
        return sentNow;
    }

    private static void pruneSent(ViewState state) {
        ChunkPos center = state.center;
        if (center == null) {
            return;
        }
        LongArrayList expired = new LongArrayList();
        LongIterator iterator = state.sent.iterator();
        int offsetX = state.offsetXChunks;
        int offsetZ = state.offsetZChunks;
        while (iterator.hasNext()) {
            long key = iterator.nextLong();
            ChunkPos pos = new ChunkPos(key);
            if (Math.abs(pos.x - (center.x + offsetX)) > streamRadius()
                    || Math.abs(pos.z - (center.z + offsetZ)) > streamRadius()) {
                expired.add(key);
            }
        }
        for (int i = 0; i < expired.size(); i++) {
            state.sent.remove(expired.getLong(i));
        }
        // The resend cooldown only has to survive a few seconds; keep it from growing while a viewer
        // watches a busy area for a long time.
        long now = tickCounter;
        state.resendCooldown.entrySet().removeIf(entry -> now - entry.getValue() > 600L);
    }

    /**
     * Kept for the client driven restore path: the viewer may ask for chunks around its own body
     * after a session ended (MODE_SELF), which is answered here.
     */
    public static void onChunkRequest(ServerPlayer viewer, int mode, long[] requested) {
        if (viewer == null || requested == null || requested.length == 0 || viewer.connection == null) {
            return;
        }
        MinecraftServer server = viewer.getServer();
        if (server == null) {
            return;
        }
        WatchSession session = SpectateManager.session(viewer.getUUID());
        ServerLevel level;
        ChunkPos focus;
        int maxRadius;
        if (mode == SpectateChunkRequestMessage.MODE_SELF || session == null) {
            level = viewer.getLevel();
            focus = viewer.chunkPosition();
            maxRadius = Math.max(RESTORE_RADIUS + 2, server.getPlayerList().getViewDistance() + 2);
        } else {
            ServerPlayer target = server.getPlayerList().getPlayer(session.target());
            if (target == null || !(target.level instanceof ServerLevel targetLevel)) {
                return;
            }
            level = targetLevel;
            focus = target.chunkPosition();
            maxRadius = streamRadius() + 1;
        }
        int sent = 0;
        for (long packed : requested) {
            if (sent >= 32) {
                break;
            }
            ChunkPos pos = new ChunkPos(packed);
            if (Math.abs(pos.x - focus.x) > maxRadius || Math.abs(pos.z - focus.z) > maxRadius) {
                continue;
            }
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
            if (chunk == null) {
                continue;
            }
            viewer.connection.send(new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null, true));
            sent++;
        }
    }

    // ------------------------------------------------------------------
    // Cross dimension chunk re-packing (no portals)
    // ------------------------------------------------------------------

    private static ClientboundLevelChunkWithLightPacket packetFor(ServerPlayer viewer, ServerLevel source, LevelChunk chunk,
            int offsetX, int offsetZ, int sectionOffsetY) {
        ServerLevel destination = viewer.getLevel();
        if (source == destination && offsetX == 0 && offsetZ == 0) {
            return new ClientboundLevelChunkWithLightPacket(chunk, source.getLightEngine(), null, null, true);
        }
        ChunkPos offsetPos = new ChunkPos(chunk.getPos().x + offsetX, chunk.getPos().z + offsetZ);
        ChunkCacheKey key = new ChunkCacheKey(source.dimension(), destination.dimension(), chunk.getPos().toLong(),
                offsetPos.toLong(), sectionOffsetY);
        CachedPacket cached = PACKET_CACHE.get(key);
        if (cached != null && tickCounter - cached.tick <= PACKET_CACHE_TICKS) {
            return cached.packet;
        }
        ClientboundLevelChunkWithLightPacket translated = translateChunk(source, destination, chunk, offsetX, offsetZ,
                sectionOffsetY);
        if (translated != null) {
            if (PACKET_CACHE.size() > PACKET_CACHE_MAX) {
                PACKET_CACHE.clear();
            }
            PACKET_CACHE.put(key, new CachedPacket(translated, tickCounter));
        }
        return translated;
    }

    /**
     * No-portal cross dimension rendering.
     * <p>
     * The client has a single {@code ClientLevel} and parses every chunk with that dimension's section
     * count (overworld 24, nether 8, end 16), so chunks of another dimension cannot be handed over
     * directly. Here a source chunk is copied section by section into a synthetic chunk that belongs
     * to the viewer's dimension: source section {@code i} goes to {@code i + (sourceMinY - destMinY) / 16},
     * which keeps every block at the same world Y (Minecraft dimensions share the Y axis), so the
     * camera needs no offset at all and simply looks at the target's real position.
     * <p>
     * Light data is omitted on purpose (empty masks): the client then keeps its default light for the
     * new sections, which makes the remote terrain clearly visible instead of pitch black.
     */
    @Nullable
    private static ClientboundLevelChunkWithLightPacket translateChunk(ServerLevel source, ServerLevel destination, LevelChunk src,
            int offsetX, int offsetZ, int sectionOffsetY) {
        try {
            LevelChunk dst = new LevelChunk(destination,
                    new ChunkPos(src.getPos().x + offsetX, src.getPos().z + offsetZ));
            LevelChunkSection[] srcSections = src.getSections();
            LevelChunkSection[] dstSections = dst.getSections();
            int offset = sectionOffsetY;
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            for (int i = 0; i < srcSections.length; i++) {
                int j = i + offset;
                if (j < 0 || j >= dstSections.length) {
                    continue;
                }
                buffer.clear();
                srcSections[i].write(buffer);
                dstSections[j].read(buffer);
            }
            buffer.clear();
            return new ClientboundLevelChunkWithLightPacket(dst, destination.getLightEngine(), new BitSet(), new BitSet(), true);
        } catch (Throwable throwable) {
            CrystalBallMod.LOGGER.error("Crystal Ball: could not re-pack chunk {} of {}", src.getPos(),
                    source.dimension().location(), throwable);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Entity
    // ------------------------------------------------------------------

    /** Called when the client reports whether it currently has the watched player's entity. */
    public static void onEntityStatus(ServerPlayer viewer, int action) {
        ViewState state = STATES.get(viewer.getUUID());
        WatchSession session = SpectateManager.session(viewer.getUUID());
        if (state == null || session == null) {
            return;
        }
        MinecraftServer server = viewer.getServer();
        ServerPlayer target = server == null ? null : server.getPlayerList().getPlayer(session.target());
        if (target == null) {
            return;
        }
        if (action == SpectateEntityRequestMessage.ACTION_PRESENT) {
            state.clientHasEntity = Boolean.TRUE;
            return;
        }
        if (viewer.level != target.level) {
            // Cross dimension uses the client side camera anchor, no entity injection is needed.
            return;
        }
        state.clientHasEntity = Boolean.FALSE;
        if (!state.injected) {
            injectEntity(viewer, target, state);
        }
    }

    private static void injectEntity(ServerPlayer viewer, ServerPlayer target, ViewState state) {
        if (state.injected && state.entityId >= 0 && state.entityId != target.getId()) {
            viewer.connection.send(new ClientboundRemoveEntitiesPacket(state.entityId));
        }
        viewer.connection.send(new ClientboundAddPlayerPacket(target));
        state.entityId = target.getId();
        state.injected = true;
        state.lastX = target.getX();
        state.lastY = target.getY();
        state.lastZ = target.getZ();
        state.lastYaw = target.getYRot();
        state.lastPitch = target.getXRot();
        state.lastHeadYaw = target.getYHeadRot();
        state.rotationTicks = 0;
        // Force the orientation once explicitly: with an injected entity the client otherwise keeps
        // whatever rotation it got until the target moves again, which showed up as "the camera looks
        // the wrong way right after starting to watch a standing player".
        viewer.connection.send(new ClientboundMoveEntityPacket.Rot(target.getId(), packDegrees(target.getYRot()),
                packDegrees(target.getXRot()), target.isOnGround()));
    }

    private static void updateEntity(ServerPlayer viewer, ServerPlayer target, ViewState state) {
        if (state.entityId != target.getId()) {
            if (state.injected && state.entityId >= 0) {
                viewer.connection.send(new ClientboundRemoveEntitiesPacket(state.entityId));
            }
            state.injected = false;
            state.clientHasEntity = null;
            state.entityId = target.getId();
        }
        boolean needInject;
        if (state.injected) {
            needInject = true;
        } else if (state.clientHasEntity != null) {
            // Trust the client: it knows whether the entity actually reached it. This is what fixes
            // the LAN host case, where the vanilla tracker claims to track a nearby player but the
            // host client never receives the entity.
            needInject = !state.clientHasEntity;
        } else {
            needInject = !isVanillaTracked(viewer, target);
        }
        if (!state.injected && needInject) {
            injectEntity(viewer, target, state);
        }
        if (state.injected) {
            sendEntityMovement(viewer, target, state);
        }
    }

    private static void sendEntityMovement(ServerPlayer viewer, ServerPlayer target, ViewState state) {
        // Always send an absolute teleport instead of relative delta packets.
        // Relative movement packets are applied on top of whatever position the client entity
        // currently has, so a single divergence (portal teleport, entity re-add, interpolation frame,
        // ...) leaves a permanent offset: the observed player then renders tens of blocks away from
        // their real position and never snaps back, while /tp (a big delta -> our absolute teleport
        // path) "fixes" it. An absolute teleport is authoritative, is still interpolated by the client
        // (lerpTo with 3 steps) and costs only a few bytes more per tick.
        viewer.connection.send(new ClientboundTeleportEntityPacket(target));
        state.lastX = target.getX();
        state.lastY = target.getY();
        state.lastZ = target.getZ();
        state.lastYaw = target.getYRot();
        state.lastPitch = target.getXRot();
        if (Math.abs(target.getYHeadRot() - state.lastHeadYaw) > 0.01F) {
            state.lastHeadYaw = target.getYHeadRot();
            viewer.connection.send(new ClientboundRotateHeadPacket(target, packDegrees(target.getYHeadRot())));
        }
    }

    /**
     * Called when a block near a watched player changes (mining, placing, ...). The client only
     * receives block updates for chunks around its own body, so a far away viewer would keep seeing
     * the old terrain - when the target digs a tunnel the camera ends up inside blocks that the
     * viewer still renders, which looks like the camera going through walls. Dropping the chunk from
     * the "already sent" set makes the streamer push a fresh translated copy next tick.
     */
    public static void onBlockChanged(ServerLevel level, BlockPos pos) {
        if (level == null || STATES.isEmpty()) {
            return;
        }
        ChunkPos changed = new ChunkPos(pos);
        long sourcePos = changed.toLong();
        boolean watched = false;
        for (ViewState state : STATES.values()) {
            if (state.center == null || state.dimension == null || !state.dimension.equals(level.dimension())) {
                continue;
            }
            watched = true;
            break;
        }
        if (!watched) {
            return;
        }
        // The cross dimension copies of this chunk are cached for a few ticks; a stale copy would
        // swallow the block change (the packet is resent from the cache and marked as sent).
        PACKET_CACHE.keySet().removeIf(key -> key.source().equals(level.dimension()) && key.sourcePos() == sourcePos);
        long now = tickCounter;
        int cooldown = com.crystalball.CrystalBallConfig.BLOCK_RESEND_COOLDOWN.get();
        for (ViewState state : STATES.values()) {
            if (state.center == null || state.dimension == null || !state.dimension.equals(level.dimension())) {
                continue;
            }
            if (Math.abs(changed.x - state.center.x) > streamRadius()
                    || Math.abs(changed.z - state.center.z) > streamRadius()) {
                continue;
            }
            long key = ChunkPos.asLong(changed.x + state.offsetXChunks, changed.z + state.offsetZChunks);
            Long last = state.resendCooldown.get(key);
            // Only refresh the timestamp when the chunk is really pushed again. Updating it before
            // this check meant a continuously changing chunk (flowing water, redstone, ...) was
            // never resent at all.
            if (last != null && now - last < cooldown) {
                continue;
            }
            state.resendCooldown.put(key, now);
            state.sent.remove(key);
        }
    }
    // ------------------------------------------------------------------
    // Entity streaming (mobs, items, ...)
    // ------------------------------------------------------------------

    /**
     * Streams the non-player entities around the watched player, so a viewer does not just see empty
     * terrain. Everything is sent in the viewer's coordinate space: cross dimension sessions add the
     * chunk offset and the vertical window shift, exactly like the chunks do. Entities that vanilla
     * already sends to the viewer (its own tracking range) are skipped to avoid duplicates.
     */
    private static void streamEntities(ServerPlayer viewer, ServerPlayer target, ServerLevel level, ViewState state) {
        if (viewer.connection == null) {
            return;
        }
        double offX = state.offsetXChunks * 16.0D;
        double offZ = state.offsetZChunks * 16.0D;
        double offY = state.yShift;
        boolean sameDimension = viewer.level == level;
        if (sameDimension && !com.crystalball.CrystalBallConfig.ENTITY_SAME_DIMENSION.get()) {
            clearStreamedEntities(viewer, state);
            notifyStreamedEntities(viewer, state);
            return;
        }
        double vanillaRange = vanillaTrackingRange(viewer);
        double radius = entityRadius(viewer);
        int maxEntities = maxStreamedEntities(viewer);
        List<Entity> around = collectEntities(viewer, target, level, radius, sameDimension ? vanillaRange : -1.0D);
        if (maxEntities >= 0 && around.size() > maxEntities) {
            around.sort(Comparator.comparingDouble(entity -> entity.distanceToSqr(target)));
            around = new ArrayList<>(around.subList(0, maxEntities));
        }
        java.util.HashSet<Integer> seen = new java.util.HashSet<>();
        state.lastCandidateCount = around.size();
        for (Entity entity : around) {
            int id = entity.getId();
            seen.add(id);
            double ex = entity.getX() + offX;
            double ey = entity.getY() + offY;
            double ez = entity.getZ() + offZ;
if (ey < viewer.getLevel().getMinBuildHeight() || ey >= viewer.getLevel().getMaxBuildHeight()) {
                // Outside the vertical window of the viewer's client level: it could not be rendered.
                continue;
            }
            SentEntity sent = state.entities.get(id);
            if (sent == null) {
                addStreamedEntity(viewer, entity, ex, ey, ez);
                state.entities.put(id, new SentEntity(ex, ey, ez, entity.getYRot(), entity.getXRot()));
                SpectateDebugLog.SERVER.log("stream entity +: viewer=%s id=%d type=%s name=%s pos=%.1f,%.1f,%.1f",
                        viewer.getGameProfile().getName(), id, Registry.ENTITY_TYPE.getKey(entity.getType()),
                        entity.getName().getString(), ex, ey, ez);
                continue;
            }
            double dx = ex - sent.x;
            double dy = ey - sent.y;
            double dz = ez - sent.z;
            boolean rotated = Math.abs(entity.getYRot() - sent.yaw) > 0.01F
                    || Math.abs(entity.getXRot() - sent.pitch) > 0.01F;
            if (Math.abs(dx) > 7.0D || Math.abs(dy) > 7.0D || Math.abs(dz) > 7.0D) {
                // Absolute resync: relative packets would leave the entity offset forever.
                viewer.connection.send(new ClientboundRemoveEntitiesPacket(id));
                addStreamedEntity(viewer, entity, ex, ey, ez);
            } else if (Math.abs(dx) > 1.0E-4D || Math.abs(dy) > 1.0E-4D || Math.abs(dz) > 1.0E-4D || rotated) {
                viewer.connection.send(new ClientboundMoveEntityPacket.PosRot(id, (short) Mth.floor(dx * 4096.0D),
                        (short) Mth.floor(dy * 4096.0D), (short) Mth.floor(dz * 4096.0D),
                        packDegrees(entity.getYRot()), packDegrees(entity.getXRot()), entity.isOnGround()));
            } else if (sent.age % 40 == 0) {
                // Periodic metadata refresh (item changed, pose, on fire, ...).
                viewer.connection.send(new ClientboundSetEntityDataPacket(id, entity.getEntityData(), true));
                if (entity instanceof LivingEntity living) {
                    // Equipment has no change packet of its own here: resend it with the metadata so
                    // armour / held items (skeleton bow, ...) cannot get out of sync.
                    sendEquipment(viewer, living);
                }
            }
            sent.x = ex;
            sent.y = ey;
            sent.z = ez;
            sent.yaw = entity.getYRot();
            sent.pitch = entity.getXRot();
            sent.age++;
        }
        Iterator<Map.Entry<Integer, SentEntity>> iterator = state.entities.entrySet().iterator();
        double vanillaRangeSq = vanillaRange * vanillaRange;
        while (iterator.hasNext()) {
            Map.Entry<Integer, SentEntity> entry = iterator.next();
            if (seen.contains(entry.getKey())) {
                continue;
            }
            Entity streamed = level.getEntity(entry.getKey());
            // Only drop entities that vanilla does not track for the viewer. Removing a vanilla tracked
            // entity would delete the real one from the client (it would flicker away until the
            // vanilla tracker notices), which happened when a streamed entity walked into range.
            boolean vanillaHandlesIt = sameDimension && streamed != null
                    && viewer.distanceToSqr(streamed) <= vanillaRangeSq;
            if (!vanillaHandlesIt) {
                viewer.connection.send(new ClientboundRemoveEntitiesPacket(entry.getKey()));
            }
            SpectateDebugLog.SERVER.log("stream entity -: viewer=%s id=%d vanillaHandles=%s",
                    viewer.getGameProfile().getName(), entry.getKey(), vanillaHandlesIt);
            iterator.remove();
        }
        notifyStreamedEntities(viewer, state);
    }

    /**
     * Tells the client which entity ids are currently streamed, so it can apply fixes that must only
     * touch streamed copies (multipart part positions, for example).
     */
    private static void notifyStreamedEntities(ServerPlayer viewer, ViewState state) {
        if (viewer.connection == null) {
            return;
        }
        int[] ids = new int[state.entities.size()];
        int index = 0;
        for (Integer id : state.entities.keySet()) {
            ids[index++] = id;
        }
        Arrays.sort(ids);
        if (Arrays.equals(ids, state.lastSentStreamedIds)) {
            return;
        }
        state.lastSentStreamedIds = ids;
        SpectateNetwork.sendStreamedEntities(viewer, ids);
    }

    /**
     * Collects the entities to stream. With a negative radius ("no limit") every entity of the level
     * is considered: building an AABB for "unlimited" would walk every chunk section of the
     * dimension, which is far more expensive than iterating the loaded entity list.
     */
    private static List<Entity> collectEntities(ServerPlayer viewer, ServerPlayer target, ServerLevel level, double radius,
            double vanillaRange) {
        if (radius >= 0.0D) {
            return level.getEntities(target, target.getBoundingBox().inflate(radius),
                    entity -> isStreamable(viewer, target, entity, vanillaRange));
        }
        List<Entity> found = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (isStreamable(viewer, target, entity, vanillaRange)) {
                found.add(entity);
            }
        }
        return found;
    }

    private static boolean isStreamable(ServerPlayer viewer, ServerPlayer target, Entity entity, double vanillaRange) {
        if (entity == viewer || entity == target || !entity.isAlive() || entity.isSpectator()
                || entity.getUUID().equals(target.getUUID()) || entity.getUUID().equals(viewer.getUUID())) {
            return false;
        }
        if (entity instanceof net.minecraftforge.entity.PartEntity<?>
                || entity instanceof net.minecraft.world.entity.boss.EnderDragonPart) {
            // Multipart hit boxes are not standalone entities on the client: the parent entity creates
            // them in its constructor and the owning mod syncs their positions separately. Streaming
            // them as entities creates bogus copies (a swarm of ghost dragons / naga heads).
            return false;
        }
        if (vanillaRange > 0.0D && entity.getType() == EntityType.ENDER_DRAGON) {
            // Vanilla tracks the ender dragon for every player in the End on its own.
            return false;
        }
        if (com.crystalball.CrystalBallConfig.SKIP_VANILLA_TRACKED.get() && vanillaRange > 0.0D
                && viewer.distanceToSqr(entity) <= vanillaRange * vanillaRange) {
            // Vanilla already streams this entity to the viewer's own body.
            return false;
        }
        return true;
    }

    /**
     * Adds one streamed entity at an absolute (already offset) position.
     * <p>
     * The spawn packet has to match the entity type, because mods override the matching
     * {@code recreateFromPacket} method: mobs use {@code ClientboundAddMobPacket} (which also carries
     * the head yaw and runs, for example, Twilight Forest's multipart id assignment), players use
     * {@code ClientboundAddPlayerPacket} (the player type has no client factory) and everything else
     * uses {@code ClientboundAddEntityPacket}. Both constructors only accept an entity, so the packet
     * is built from its buffer to allow the offset position.
     */
    private static void addStreamedEntity(ServerPlayer viewer, Entity entity, double ex, double ey, double ez) {
        if (entity instanceof Player) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            buffer.writeVarInt(entity.getId());
            buffer.writeUUID(entity.getUUID());
            buffer.writeDouble(ex);
            buffer.writeDouble(ey);
            buffer.writeDouble(ez);
            buffer.writeByte(packDegrees(entity.getYRot()));
            buffer.writeByte(packDegrees(entity.getXRot()));
            viewer.connection.send(new ClientboundAddPlayerPacket(buffer));
        } else if (entity instanceof LivingEntity living) {
            viewer.connection.send(createAddMobPacket(living, ex, ey, ez));
        } else {
            // The constructor order is (xRot, yRot), not (yRot, xRot).
            viewer.connection.send(new ClientboundAddEntityPacket(entity.getId(), entity.getUUID(), ex, ey, ez,
                    entity.getXRot(), entity.getYRot(), entity.getType(), 0, Vec3.ZERO));
        }
        viewer.connection.send(new ClientboundSetEntityDataPacket(entity.getId(), entity.getEntityData(), true));
        if (entity instanceof LivingEntity living) {
            viewer.connection.send(new ClientboundRotateHeadPacket(entity, packDegrees(living.getYHeadRot())));
            // Without the equipment packet a streamed skeleton has no bow and a zombie no armour,
            // because the spawn packet only creates the bare entity.
            sendEquipment(viewer, living);
        }
    }

    /** Builds a {@code ClientboundAddMobPacket} at an offset position (the entity constructor cannot offset). */
    private static ClientboundAddMobPacket createAddMobPacket(LivingEntity living, double x, double y, double z) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeVarInt(living.getId());
        buffer.writeUUID(living.getUUID());
        buffer.writeVarInt(Registry.ENTITY_TYPE.getId(living.getType()));
        buffer.writeDouble(x);
        buffer.writeDouble(y);
        buffer.writeDouble(z);
        buffer.writeByte(packDegrees(living.getYRot()));
        buffer.writeByte(packDegrees(living.getXRot()));
        buffer.writeByte(packDegrees(living.getYHeadRot()));
        Vec3 motion = living.getDeltaMovement();
        buffer.writeShort((int) (Mth.clamp(motion.x, -3.9D, 3.9D) * 8000.0D));
        buffer.writeShort((int) (Mth.clamp(motion.y, -3.9D, 3.9D) * 8000.0D));
        buffer.writeShort((int) (Mth.clamp(motion.z, -3.9D, 3.9D) * 8000.0D));
        return new ClientboundAddMobPacket(buffer);
    }

    /** Sends every equipment slot (empty stacks clear a slot), like vanilla does for tracked mobs. */
    private static void sendEquipment(ServerPlayer viewer, LivingEntity living) {
        if (viewer.connection == null) {
            return;
        }
        List<Pair<EquipmentSlot, ItemStack>> slots = new ArrayList<>(EQUIPMENT_SLOTS.length);
        for (EquipmentSlot slot : EQUIPMENT_SLOTS) {
            slots.add(Pair.of(slot, living.getItemBySlot(slot)));
        }
        viewer.connection.send(new ClientboundSetEquipmentPacket(living.getId(), slots));
    }

    /**
     * Forwards the red hurt flash to viewers that currently stream the hurt entity. The health value
     * itself travels with the periodic metadata refresh; the entity event is what starts the 10 tick
     * red overlay on the client.
     */
    public static void onLivingHurt(LivingEntity entity) {
        if (entity == null || STATES.isEmpty() || entity.level.isClientSide) {
            return;
        }
        MinecraftServer server = entity.getServer();
        if (server == null) {
            return;
        }
        int entityId = entity.getId();
        for (Map.Entry<UUID, ViewState> entry : STATES.entrySet()) {
            if (!entry.getValue().entities.containsKey(entityId)) {
                continue;
            }
            ServerPlayer viewer = server.getPlayerList().getPlayer(entry.getKey());
            if (viewer == null || viewer.connection == null) {
                continue;
            }
            viewer.connection.send(new ClientboundEntityEventPacket(entity, (byte) 2));
            viewer.connection.send(new ClientboundSetEntityDataPacket(entityId, entity.getEntityData(), true));
        }
    }
    /** Short description of the streamed entities for the periodic log (diagnostics only). */
    private static String describeEntities(ViewState state) {
        if (state.entities.isEmpty()) {
            return "0";
        }
        StringBuilder builder = new StringBuilder();
        builder.append(state.entities.size()).append('[');
        int count = 0;
        for (Integer id : state.entities.keySet()) {
            if (count++ > 0) {
                builder.append(',');
            }
            builder.append(id);
            if (count >= 12) {
                builder.append(",...");
                break;
            }
        }
        builder.append(']');
        return builder.toString();
    }

    /** Human readable list of the streamed entities (id, type and player name) for the debug log. */
    private static String describeEntityNames(ServerLevel level, ViewState state) {
        if (state.entities.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (Integer id : state.entities.keySet()) {
            if (count++ > 0) {
                builder.append(',');
            }
            Entity entity = level.getEntity(id);
            if (entity == null) {
                builder.append(id).append("=gone");
            } else {
                builder.append(id).append('=').append(Registry.ENTITY_TYPE.getKey(entity.getType()));
                if (entity instanceof Player player) {
                    builder.append('(').append(player.getGameProfile().getName()).append(')');
                }
            }
            if (count >= 10) {
                builder.append(",...");
                break;
            }
        }
        return builder.toString();
    }
    private static void clearStreamedEntities(ServerPlayer viewer, @Nullable ViewState state) {
        if (state == null || state.entities.isEmpty()) {
            return;
        }
        if (viewer.connection != null) {
            // Same rule as in streamEntities: never delete an entity from the client when vanilla
            // also tracks it for the viewer, otherwise the real (vanilla) copy would disappear.
            boolean sameDimension = state.dimension != null && viewer.level.dimension().equals(state.dimension);
            double rangeSq = vanillaTrackingRange(viewer);
            rangeSq *= rangeSq;
            ServerLevel level = viewer.getServer() == null || state.dimension == null ? null
                    : viewer.getServer().getLevel(state.dimension);
            List<Integer> toRemove = new ArrayList<>(state.entities.size());
            for (Integer id : state.entities.keySet()) {
                Entity entity = level == null ? null : level.getEntity(id);
                boolean vanillaHandlesIt = sameDimension && entity != null
                        && viewer.distanceToSqr(entity) <= rangeSq;
                if (!vanillaHandlesIt) {
                    toRemove.add(id);
                }
            }
            if (!toRemove.isEmpty()) {
                int[] ids = new int[toRemove.size()];
                for (int i = 0; i < ids.length; i++) {
                    ids[i] = toRemove.get(i);
                }
                viewer.connection.send(new ClientboundRemoveEntitiesPacket(ids));
            }
        }
        state.entities.clear();
    }
    /** Tells the client to drop every streamed chunk around the current center (stale mapping). */
    private static void forgetBubble(ServerPlayer viewer, ViewState state) {
        if (viewer.connection == null || state.center == null) {
            return;
        }
        ChunkPos center = offsetCenter(state);
        for (int dx = -streamRadius() - 1; dx <= streamRadius() + 1; dx++) {
            for (int dz = -streamRadius() - 1; dz <= streamRadius() + 1; dz++) {
                viewer.connection.send(new ClientboundForgetLevelChunkPacket(center.x + dx, center.z + dz));
            }
        }
    }
    /** Distance within which vanilla's own player tracker already sends an entity to this viewer. */
    private static double vanillaTrackingRange(ServerPlayer viewer) {
        MinecraftServer server = viewer.getServer();
        int viewDistance = server == null ? 10 : server.getPlayerList().getViewDistance();
        int limit = Math.min(EntityType.PLAYER.clientTrackingRange() * 16, Math.max(1, viewDistance - 1) * 16);
        return Math.max(8.0D, limit);
    }

    private static boolean isVanillaTracked(ServerPlayer viewer, ServerPlayer target) {
        if (viewer.level != target.level) {
            return false;
        }
        double range = vanillaTrackingRange(viewer);
        return viewer.distanceToSqr(target) <= range * range;
    }

    // ------------------------------------------------------------------
    // Viewer body sync
    // ------------------------------------------------------------------

    /**
     * While watching, the client's chunk window points at the camera, so the client cannot simulate
     * collisions around its own body. Any server side movement of the body (falling into a dug hole,
     * knockback, pistons, ...) would otherwise drift away from the client. Whenever the body actually
     * moved on the server, the client is put back in lockstep.
     */
    private static void syncBody(ServerPlayer viewer, ViewState state) {
        if (viewer.connection == null) {
            return;
        }
        boolean moved = Math.abs(viewer.getX() - state.syncedX) > 1.0E-4D
                || Math.abs(viewer.getY() - state.syncedY) > 1.0E-4D
                || Math.abs(viewer.getZ() - state.syncedZ) > 1.0E-4D;
        boolean rotated = Math.abs(viewer.getYRot() - state.syncedYaw) > 0.01F
                || Math.abs(viewer.getXRot() - state.syncedPitch) > 0.01F;
        if (!moved && !rotated) {
            // Safety net: resend the authoritative position every half second even when the server
            // thinks nothing changed, because the client body has no collision data around it while
            // watching and can drift on its own.
            if (++state.syncTicks < 10) {
                return;
            }
        }
        state.syncTicks = 0;
        state.syncedX = viewer.getX();
        state.syncedY = viewer.getY();
        state.syncedZ = viewer.getZ();
        state.syncedYaw = viewer.getYRot();
        state.syncedPitch = viewer.getXRot();
        state.burstTicks = BURST_TICKS;
        viewer.connection.teleport(viewer.getX(), viewer.getY(), viewer.getZ(), viewer.getYRot(), viewer.getXRot());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void sendCenter(ServerPlayer viewer, ChunkPos pos) {
        if (viewer.connection != null) {
            viewer.connection.send(new ClientboundSetChunkCacheCenterPacket(pos.x, pos.z));
        }
    }

    private static void clearEntity(ServerPlayer viewer, @Nullable ViewState state) {
        if (state != null && state.injected && state.entityId >= 0 && viewer != null && viewer.connection != null) {
            viewer.connection.send(new ClientboundRemoveEntitiesPacket(state.entityId));
        }
    }

    private static byte packDegrees(float degrees) {
        return (byte) ((int) (degrees * 256.0F / 360.0F));
    }
}