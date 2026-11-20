package com.crystalball.spectate;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundAddPlayerPacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import com.crystalball.CrystalBallMod;

import javax.annotation.Nullable;
import java.util.BitSet;
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

    /** Non-player entities are streamed for this radius around the watched player. */
    private static double entityRadius() {
        return com.crystalball.CrystalBallConfig.ENTITY_RADIUS.get();
    }
    private static int maxStreamedEntities() {
        return com.crystalball.CrystalBallConfig.MAX_ENTITIES.get();
    }

    /** Chunk packets sent per tick while restoring the area around the viewer's own body. */
    private static final int RESTORE_CHUNKS_PER_TICK = 32;

    /** Radius (in chunks) restored around the viewer's body after a session. */
    private static final int RESTORE_RADIUS = 5;

    private static final int LOG_INTERVAL = 100;

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

    /** Cache of chunks that had to be re-packed into the viewer's dimension (cross dimension watch). */
    private static final Map<ChunkCacheKey, CachedPacket> PACKET_CACHE = new HashMap<>();
    private static final int PACKET_CACHE_TICKS = 40;
    private static final int PACKET_CACHE_MAX = 2048;
    private static long tickCounter;

    private record ChunkCacheKey(ResourceKey<Level> source, ResourceKey<Level> destination, long chunkPos, int sectionOffsetY) {
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
        final Map<Long, Integer> resendCooldown = new HashMap<>();
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
        clearEntity(viewer, STATES.get(viewer.getUUID()));
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
    }

    public static void onStop(ServerPlayer viewer, @Nullable ServerPlayer target) {
        ViewState state = STATES.remove(viewer.getUUID());
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
        ChunkCacheKey key = new ChunkCacheKey(source.dimension(), destination.dimension(), offsetPos.toLong(), sectionOffsetY);
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
        for (ViewState state : STATES.values()) {
            if (state.center == null || state.dimension == null || !state.dimension.equals(level.dimension())) {
                continue;
            }
            if (Math.abs(changed.x - state.center.x) > streamRadius()
                    || Math.abs(changed.z - state.center.z) > streamRadius()) {
                continue;
            }
            long key = ChunkPos.asLong(changed.x + state.offsetXChunks, changed.z + state.offsetZChunks);
            Integer last = state.resendCooldown.put(key, (int) tickCounter);
            int cooldown = com.crystalball.CrystalBallConfig.BLOCK_RESEND_COOLDOWN.get();
            if (last != null && (int) tickCounter - last < cooldown) {
                continue;
            }
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
            return;
        }
        int viewDistance = 10;
        MinecraftServer server = viewer.getServer();
        if (server != null) {
            viewDistance = server.getPlayerList().getViewDistance();
        }
        double vanillaRange = Math.max(8.0D, Math.min(EntityType.PLAYER.clientTrackingRange() * 16.0D,
                Math.max(1, viewDistance - 1) * 16.0D));
        List<Entity> around = level.getEntities(target, target.getBoundingBox().inflate(entityRadius()),
                entity -> isStreamable(viewer, target, entity, sameDimension ? vanillaRange : -1.0D));
        if (around.size() > maxStreamedEntities()) {
            around.sort(java.util.Comparator.comparingDouble(entity -> entity.distanceToSqr(target)));
            around = around.subList(0, maxStreamedEntities());
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
            }
            sent.x = ex;
            sent.y = ey;
            sent.z = ez;
            sent.yaw = entity.getYRot();
            sent.pitch = entity.getXRot();
            sent.age++;
        }
        Iterator<Map.Entry<Integer, SentEntity>> iterator = state.entities.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, SentEntity> entry = iterator.next();
            if (!seen.contains(entry.getKey())) {
                viewer.connection.send(new ClientboundRemoveEntitiesPacket(entry.getKey()));
                iterator.remove();
            }
        }
    }

    private static boolean isStreamable(ServerPlayer viewer, ServerPlayer target, Entity entity, double vanillaRange) {
        if (entity == viewer || entity == target || !entity.isAlive() || entity.isSpectator()
                || entity.getUUID().equals(target.getUUID()) || entity.getUUID().equals(viewer.getUUID())) {
            return false;
        }
        if (entity instanceof net.minecraft.world.entity.boss.EnderDragonPart) {
            // Dragon hit boxes are separate entities but share the dragon type; streaming them created
            // a swarm of ghost dragons on the viewer.
            return false;
        }
        if (entity.getType() == EntityType.PLAYER) {
            // Players are streamed like any other entity: in cross dimension sessions vanilla sends
            // nothing, and in the same dimension the range check below already skips everyone that
            // vanilla tracks itself, so only far away players get a copy here.
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
     * Players cannot be created from {@code ClientboundAddEntityPacket} (the player type has no client
     * factory); vanilla sends them with {@code ClientboundAddPlayerPacket}, which only has an
     * Entity-based constructor and therefore no way to offset the position. The packet's buffer
     * constructor is used directly for that case, so other players can be shown in the offset world.
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
        } else {
            viewer.connection.send(new ClientboundAddEntityPacket(entity.getId(), entity.getUUID(), ex, ey, ez,
                    entity.getYRot(), entity.getXRot(), entity.getType(), 0, Vec3.ZERO));
        }
        viewer.connection.send(new ClientboundSetEntityDataPacket(entity.getId(), entity.getEntityData(), true));
        if (entity instanceof LivingEntity living) {
            viewer.connection.send(new ClientboundRotateHeadPacket(entity, packDegrees(living.getYHeadRot())));
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
    private static void clearStreamedEntities(ServerPlayer viewer, ViewState state) {
        if (state == null || state.entities.isEmpty()) {
            return;
        }
        if (viewer.connection != null) {
            int[] ids = new int[state.entities.size()];
            int index = 0;
            for (Integer id : state.entities.keySet()) {
                ids[index++] = id;
            }
            viewer.connection.send(new ClientboundRemoveEntitiesPacket(ids));
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
    private static boolean isVanillaTracked(ServerPlayer viewer, ServerPlayer target) {
        if (viewer.level != target.level) {
            return false;
        }
        MinecraftServer server = viewer.getServer();
        if (server == null) {
            return false;
        }
        int viewDistance = server.getPlayerList().getViewDistance();
        int limit = Math.min(EntityType.PLAYER.clientTrackingRange() * 16, Math.max(1, viewDistance - 1) * 16);
        double range = Math.max(8.0D, limit);
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