package com.crystalball.client;

import com.mojang.authlib.GameProfile;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraftforge.entity.PartEntity;
import com.crystalball.CrystalBallClientConfig;
import com.crystalball.CrystalBallMod;
import com.crystalball.SpectateDebugLog;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import com.crystalball.spectate.SpectateChunkRequestMessage;
import com.crystalball.spectate.SpectateNetwork;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Client side camera controller - the "freecam style" half of the Crystal Ball.
 * <p>
 * The camera is pointed at the watched player with {@link Minecraft#setCameraEntity} (the same
 * vanilla mechanism the spectator camera uses) while <b>nothing about the local player changes</b>:
 * game mode, position, inventory and hitbox all stay, movement/jump/sneak input is neutralised, mouse
 * look is cancelled out every tick, the first person hand is hidden and chunk culling follows the
 * camera.
 * <p>
 * Remote world streaming (this is what makes watching at any distance work):
 * <ul>
 *     <li>the client's chunk window center is forced onto the watched player every tick, using the
 *     public {@link ClientChunkCache#updateViewCenter} - this is what the vanilla server normally does
 *     when a player walks into a new chunk, and it makes the client accept the streamed chunks;</li>
 *     <li>the client's chunk cache is enlarged once per world join
 *     ({@link ClientChunkCache#updateViewRadius}), so streaming a far away area cannot push the
 *     player's own chunks out of the cache - that is what used to cause holes and jitter on return;</li>
 *     <li>missing chunks are requested incrementally (5 ticks between requests while watching);</li>
 *     <li>when watching ends, the camera is put back first and the surroundings of the player are
 *     re-requested in {@code MODE_SELF}, so returning is immediate and complete;</li>
 *     <li>terrain fog is pulled in while watching so the edge of the streamed bubble is invisible.</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = CrystalBallMod.MODID, value = Dist.CLIENT)
public final class SpectateClientController {

    /** Max radius of streamed chunks, also used for the fog clamp. */
    private static final int MAX_STREAM_RADIUS = 12;

    /** Chunk cache radius used while watching (vanilla uses view distance + 3; this is much larger). */
    private static final int CACHE_VIEW_DISTANCE = 32;

    private static boolean watching = false;

    @Nullable
    private static UUID targetId;

    private static String targetName = "";
    private static int targetEntityId = -1;
    private static double lastKnownX;
    private static double lastKnownY;
    private static double lastKnownZ;

    private static boolean cameraActive = false;

    private static CameraType lastCameraType = CameraType.FIRST_PERSON;

    private static float savedYaw;
    private static float savedPitch;
    private static CameraType savedCameraType = CameraType.FIRST_PERSON;
    private static boolean savedNoGravity = false;

    private static int logTimer = 0;

    private static int restoreTicks = -1;
    private static int waitingTicks = 0;
    private static boolean crossDimension = false;

    /** Entity id of the client side camera anchor (a clone of the watched player). */
    private static final int CAMERA_ANCHOR_ID = -420000;

    @Nullable
    private static LivingEntity cameraAnchor;

    /**
     * UUID of the player {@link #cameraAnchor} is a clone of. The anchor carries the skin, so when
     * the beta key switches to another player the clone has to be rebuilt - otherwise third person
     * keeps showing the previous player's body.
     */
    @Nullable
    private static UUID anchorTargetId;

    /** Real (unshifted) target position and rotation as sent by the server. */
    private static double stateX;
    private static double stateY;
    private static double stateZ;
    private static float anchorYaw;
    private static float anchorPitch;

    /** Entity ids the server currently streams for this session. */
    private static int[] streamedEntityIds = new int[0];

    /** Streamed multipart entity ids whose client side parts were already snapped to the parent. */
    private static final Set<Integer> snappedMultipartIds = new HashSet<>();

    /** Ticks since the last active state message; the server sends one per tick while watching. */
    private static int stateTicks;

    /** Debug log timers (tracking diff every second, status line every five seconds). */
    private static int trackingTicks;
    private static int statusTicks;
    /** Players logged as hidden by the current session (to log each transition only once). */
    private static final Set<UUID> loggedHidden = new HashSet<>();
    /** Client side view of the other players, used to log entity tracking add/remove/jump events. */
    private static final Map<UUID, TrackedPlayer> trackedPlayers = new HashMap<>();

    private record TrackedPlayer(String name, int id, double x, double y, double z, boolean invisible,
            boolean anchor, boolean streamed) {
    }

    private SpectateClientController() {
    }

    public static boolean isCameraActive() {
        return cameraActive;
    }

    // ------------------------------------------------------------------
    // Server state
    // ------------------------------------------------------------------

    /** Called from {@link com.crystalball.spectate.SpectateStateMessage}. */
    public static void onState(boolean active, UUID target, String name, int entityId, double x, double y, double z,
            boolean crossDimensionFlag, float yaw, float pitch) {
        boolean targetChanged = active && (targetId == null || !targetId.equals(target));
        boolean stateChanged = !watching || targetChanged || crossDimension != crossDimensionFlag;
        crossDimension = crossDimensionFlag;
        stateX = x;
        stateY = y;
        stateZ = z;
        anchorYaw = yaw;
        anchorPitch = pitch;
        if (active) {
            if (stateChanged) {
                SpectateDebugLog.CLIENT.log("state active: target=%s uuid=%s entityId=%d pos=%.2f,%.2f,%.2f cross=%s yaw=%.1f pitch=%.1f",
                        name, target, entityId, x, y, z, crossDimensionFlag, yaw, pitch);
            }
            watching = true;
            stateTicks = 0;
            targetId = target;
            targetName = name == null ? "" : name;
            targetEntityId = entityId;
            lastKnownX = x;
            lastKnownY = y;
            lastKnownZ = z;
            restoreTicks = -1;
            if (targetChanged) {
                // The anchor is a clone of the watched player; marked as stale, updateAnchor rebuilds
                // it (with the new skin) and puts the camera on it again in the same tick.
                anchorTargetId = null;
                loggedHidden.clear();
                SpectateDebugLog.CLIENT.log("target changed: hiding state reset");
            }
        } else {
            SpectateDebugLog.CLIENT.log("state inactive: was target=%s cameraActive=%s", targetName, cameraActive);
            watching = false;
            targetId = null;
            targetName = "";
            targetEntityId = -1;
            waitingTicks = 0;
            streamedEntityIds = new int[0];
            snappedMultipartIds.clear();
            loggedHidden.clear();
            deactivate();
            restoreTicks = 0;
        }
    }

    /**
     * Called from {@link com.crystalball.spectate.SpectateStreamedEntitiesMessage}: the entity ids the
     * server currently streams for this session.
     */
    public static void onStreamedEntities(int[] ids) {
        streamedEntityIds = ids == null ? new int[0] : ids.clone();
        Set<Integer> current = new HashSet<>();
        for (int id : streamedEntityIds) {
            current.add(id);
        }
        snappedMultipartIds.retainAll(current);
        SpectateDebugLog.CLIENT.log("streamed entity ids: count=%d ids=%s", streamedEntityIds.length,
                java.util.Arrays.toString(streamedEntityIds));
    }

    // ------------------------------------------------------------------
    // Tick
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            reset();
            return;
        }
        debugTick(minecraft);
        if (!watching || targetId == null) {
            if (cameraActive) {
                deactivate();
            }
            tickRestore(minecraft);
            return;
        }
        if (++stateTicks > 100) {
            // The server pushes an active state every tick; if they stop (connection stall, session
            // dropped without a message) the tracking state must not block rendering forever.
            reset();
            return;
        }

        // The camera always follows the client side anchor, never the (possibly missing / lagging)
        // target entity. The anchor position/rotation comes straight from the server's per tick state,
        // so the camera is absolute and cannot drift behind the target or sit inside a wall because of
        // entity interpolation - this was what made "position wrong / clipping through walls" happen,
        // especially right after a portal trip.
        Entity anchor = updateAnchor(minecraft);
        if (anchor != null) {
            lastKnownX = anchor.getX();
            lastKnownY = anchor.getY();
            lastKnownZ = anchor.getZ();
            waitingTicks = 0;
            if (cameraActive) {
                maintain(minecraft, anchor);
            } else {
                activate(minecraft, anchor);
            }
        } else {
            waitingTicks++;
            if (cameraActive) {
                deactivate();
            }
        }
        if (!crossDimension && anchor == null && waitingTicks > 60 && waitingTicks % 80 == 0) {
            minecraft.player.displayClientMessage(
                    new TranslatableComponent("crystal_ball.message.out_of_range", targetName), true);
        }

        // Keep the client's chunk window on the watched player; the server pushes the chunks itself.
        // The center is re-applied every tick because the vanilla server may move it back to the body.
        forceChunkCenter(minecraft, lastKnownX, lastKnownZ);
        // Vanilla builds the renderable chunk area from the *player body* (LevelRenderer#setupRender
        // uses minecraft.player), which is why streaming alone could never show terrain far away.
        // Re-centering the view area on the camera is what actually makes remote terrain render.
        forceViewArea(minecraft, lastKnownX, lastKnownZ);
        // Forge multipart entities (Twilight Forest's Naga, Hydra, ...) keep their parts as client
        // side children; the owning mod only syncs their positions for vanilla tracking players, so
        // a streamed copy would leave its body at the world origin. Snap them onto the parent.
        syncStreamedMultipartParts(minecraft);
        if (++logTimer >= 600) {
            logTimer = 0;
            Entity byId = targetEntityId >= 0 ? minecraft.level.getEntity(targetEntityId) : null;
            CrystalBallMod.LOGGER.info(
                    "Crystal Ball client: watching={} camera={} target=({},{},{}) entityId={} byId={} uuid={} entityUuid={} players={} chunksMissing={}",
                    targetName, cameraActive, (int) lastKnownX, (int) lastKnownY, (int) lastKnownZ, targetEntityId,
                    byId == null ? "null" : byId.getClass().getSimpleName(), targetId,
                    byId == null ? "null" : byId.getUUID(), minecraft.level.players().size(),
                    countMissing(minecraft, lastKnownX, lastKnownZ, streamRadius(minecraft)));
        }
    }

    // ------------------------------------------------------------------
    // Diagnostics (separate per instance log file, see SpectateDebugLog)
    // ------------------------------------------------------------------

    private static void debugTick(Minecraft minecraft) {
        if (++trackingTicks >= 20) {
            trackingTicks = 0;
            logPlayerTracking(minecraft);
        }
        if (++statusTicks >= 100) {
            statusTicks = 0;
            logStatus(minecraft);
        }
    }

    /**
     * Logs which other players the client actually has in its level. This is the key diagnostic for
     * "player X cannot see player Y": it shows whether the entity never arrived (server tracking) or
     * arrived and was later hidden/removed (client side).
     */
    private static void logPlayerTracking(Minecraft minecraft) {
        if (minecraft.level == null) {
            return;
        }
        Map<UUID, TrackedPlayer> current = new HashMap<>();
        for (AbstractClientPlayer player : minecraft.level.players()) {
            if (player == minecraft.player) {
                continue;
            }
            boolean anchor = player == cameraAnchor;
            boolean streamed = false;
            for (int id : streamedEntityIds) {
                if (id == player.getId()) {
                    streamed = true;
                    break;
                }
            }
            double x = player.getX();
            double y = player.getY();
            double z = player.getZ();
            boolean invisible = player.isInvisible();
            TrackedPlayer previous = trackedPlayers.get(player.getUUID());
            if (previous == null) {
                SpectateDebugLog.CLIENT.log(
                        "tracking +: name=%s uuid=%s id=%d pos=%.1f,%.1f,%.1f invisible=%s removed=%s anchor=%s streamed=%s",
                        player.getGameProfile().getName(), player.getUUID(), player.getId(), x, y, z, invisible,
                        player.isRemoved(), anchor, streamed);
            } else {
                double dx = x - previous.x();
                double dy = y - previous.y();
                double dz = z - previous.z();
                if (dx * dx + dy * dy + dz * dz > 64.0D * 64.0D) {
                    SpectateDebugLog.CLIENT.log(
                            "tracking jump: name=%s uuid=%s id=%d from=%.1f,%.1f,%.1f to=%.1f,%.1f,%.1f",
                            player.getGameProfile().getName(), player.getUUID(), player.getId(),
                            previous.x(), previous.y(), previous.z(), x, y, z);
                }
                if (previous.invisible() != invisible) {
                    SpectateDebugLog.CLIENT.log("tracking invisible: name=%s uuid=%s invisible=%s",
                            player.getGameProfile().getName(), player.getUUID(), invisible);
                }
                if (previous.anchor() != anchor) {
                    SpectateDebugLog.CLIENT.log("tracking anchor: name=%s uuid=%s anchor=%s",
                            player.getGameProfile().getName(), player.getUUID(), anchor);
                }
            }
            current.put(player.getUUID(),
                    new TrackedPlayer(player.getGameProfile().getName(), player.getId(), x, y, z, invisible, anchor, streamed));
        }
        for (UUID uuid : trackedPlayers.keySet()) {
            if (!current.containsKey(uuid)) {
                TrackedPlayer previous = trackedPlayers.get(uuid);
                SpectateDebugLog.CLIENT.log("tracking -: name=%s uuid=%s id=%d lastPos=%.1f,%.1f,%.1f anchor=%s streamed=%s",
                        previous.name(), uuid, previous.id(), previous.x(), previous.y(), previous.z(),
                        previous.anchor(), previous.streamed());
            }
        }
        trackedPlayers.clear();
        trackedPlayers.putAll(current);
    }

    private static void logStatus(Minecraft minecraft) {
        Entity camera = minecraft.getCameraEntity();
        String user = minecraft.getUser() == null ? "?" : minecraft.getUser().getName();
        SpectateDebugLog.CLIENT.log(
                "status: user=%s dim=%s pos=%.1f,%.1f,%.1f watching=%s target=%s uuid=%s cameraId=%d cameraClass=%s anchorId=%d f5=%s streamed=%d missingChunks=%d players=%d",
                user, minecraft.level.dimension().location(),
                minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ(),
                watching, targetName, targetId, camera == null ? -1 : camera.getId(),
                camera == null ? "null" : camera.getClass().getSimpleName(),
                cameraAnchor == null ? -1 : cameraAnchor.getId(), minecraft.options.getCameraType(),
                streamedEntityIds.length,
                watching ? countMissing(minecraft, lastKnownX, lastKnownZ, streamRadius(minecraft)) : -1,
                minecraft.level.players().size());
    }

    /** Disconnect: never leave a foreign camera entity behind. */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggedOutEvent event) {
        reset();
    }

    /** Announce the client half of the configuration as soon as the connection is ready. */
    @SubscribeEvent
    public static void onLoggedIn(ClientPlayerNetworkEvent.LoggedInEvent event) {
        syncClientCaps();
    }

    /** Sends this client's streaming caps (entity radius / entity count) to the server. */
    public static void syncClientCaps() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null) {
            return;
        }
        SpectateNetwork.sendClientCaps(CrystalBallClientConfig.entityRadius(),
                CrystalBallClientConfig.maxStreamedEntities());
    }

    /**
     * Hides the real entity of the watched player while its client side clone carries the camera.
     * <p>
     * The clone is the camera entity, so vanilla renders it - that is the body seen in third person.
     * The real target entity is a second, network driven copy at the same position: in first person
     * its head sits right in front of the camera (the "model in your face" bug) and in third person
     * it duplicates the body. Cancelling its render is robust: unlike toggling the invisible flag it
     * cannot be reverted by the next metadata packet, and when the beta key switches the target the
     * old player is rendered normally again automatically.
     */
    @SubscribeEvent
    public static void onRenderPlayer(RenderPlayerEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!cameraActive || targetId == null || minecraft.player == null) {
            return;
        }
        // Never hide the local body and never hide anything when the camera has been moved away from
        // the anchor (a stale flag must not leak into normal rendering).
        if (event.getPlayer() == cameraAnchor || event.getPlayer() == minecraft.player
                || minecraft.getCameraEntity() != cameraAnchor) {
            return;
        }
        if (targetId.equals(event.getPlayer().getUUID())
                || (!targetName.isEmpty() && targetName.equalsIgnoreCase(event.getPlayer().getGameProfile().getName()))) {
            if (loggedHidden.add(event.getPlayer().getUUID())) {
                SpectateDebugLog.CLIENT.log("hide real player: name=%s uuid=%s id=%d pos=%.1f,%.1f,%.1f",
                        event.getPlayer().getGameProfile().getName(), event.getPlayer().getUUID(),
                        event.getPlayer().getId(), event.getPlayer().getX(), event.getPlayer().getY(),
                        event.getPlayer().getZ());
            }
            event.setCanceled(true);
        }
    }

    /**
     * Positions the client side parts of streamed {@link PartEntity} multipart entities.
     * <p>
     * Parts are not entities of their own: the parent creates them in its constructor and the owning
     * mod syncs their positions with its own packets, but only for players that vanilla already
     * tracks. A streamed copy is not vanilla tracked, so its parts stay at the world origin and crawl
     * towards the body at a few blocks per tick - the entity renders as a head without a body (this
     * is what happened to Twilight Forest's Naga). Parts of vanilla tracked entities (for example
     * ender dragon parts) are real client entities and are left alone.
     */
    private static void syncStreamedMultipartParts(Minecraft minecraft) {
        if (minecraft.level == null || streamedEntityIds.length == 0) {
            return;
        }
        for (int id : streamedEntityIds) {
            Entity entity = minecraft.level.getEntity(id);
            if (entity == null || !entity.isMultipartEntity()) {
                continue;
            }
            PartEntity<?>[] parts = entity.getParts();
            if (parts == null) {
                continue;
            }
            boolean firstSight = snappedMultipartIds.add(id);
            for (int i = 0; i < parts.length; i++) {
                PartEntity<?> part = parts[i];
                if (part == null || minecraft.level.getEntity(part.getId()) != null) {
                    continue;
                }
                double dx = part.getX() - entity.getX();
                double dy = part.getY() - entity.getY();
                double dz = part.getZ() - entity.getZ();
                if (firstSight || dx * dx + dy * dy + dz * dz > 128.0D * 128.0D) {
                    part.setPos(entity.getX() + 0.1D * i, entity.getY(), entity.getZ() + 0.1D * i);
                    part.setOldPosAndRot();
                }
            }
        }
    }

    /** While watching, pull the terrain fog in so the edge of the streamed chunk bubble is hidden. */
    @SubscribeEvent
    public static void onRenderFog(EntityViewRenderEvent.RenderFogEvent event) {
        if (!cameraActive || event.getMode() != FogRenderer.FogMode.FOG_TERRAIN) {
            return;
        }
        float maxVisible = streamRadius(Minecraft.getInstance()) * 16.0F - 24.0F;
        if (event.getFarPlaneDistance() > maxVisible) {
            event.setFarPlaneDistance(maxVisible);
            event.setNearPlaneDistance(Math.min(event.getNearPlaneDistance(), maxVisible * 0.55F));
        }
    }

    /**
     * No chunk dropping is needed any more: since v13 the remote world is streamed into an offset
     * area that is 1024 chunks away from the viewer's own body, so the client can never receive its
     * own dimension's chunks near the camera (the vanilla server only streams around the body).
     * (The old implementation dropped thousands of chunks every time a state update arrived, which
     * made the client unload/reload chunks constantly and caused a very visible jitter.)
     */

    // ------------------------------------------------------------------
    // Chunk window and streaming
    // ------------------------------------------------------------------

    private static int streamRadius(Minecraft minecraft) {
        return Math.min(MAX_STREAM_RADIUS, Math.max(2, minecraft.options.getEffectiveRenderDistance()));
    }

    private static ClientChunkCache chunkCache(Minecraft minecraft) {
        return (ClientChunkCache) minecraft.level.getChunkSource();
    }

    /** Forces the client's chunk window center to the given world position. */
    private static void forceChunkCenter(Minecraft minecraft, double x, double z) {
        if (minecraft.level == null) {
            return;
        }
        try {
            chunkCache(minecraft).updateViewCenter(Mth.floor(x) >> 4, Mth.floor(z) >> 4);
        } catch (RuntimeException ignored) {
            // Never let a chunk bookkeeping detail break the camera.
        }
    }

    /**
     * Enlarges the client chunk cache on first use. Vanilla sizes it for the player's own view
     * distance; a bigger circular buffer means streaming a distant area evicts (almost) nothing from
     * the player's own surroundings, which is what made returning from a far away target look broken.
     */
    private static void ensureBigChunkCache(Minecraft minecraft) {
        try {
            chunkCache(minecraft).updateViewRadius(CACHE_VIEW_DISTANCE);
        } catch (RuntimeException ignored) {
            // Cosmetic only: fall back to the vanilla sized cache.
        }
    }

    private static void tickRestore(Minecraft minecraft) {
        if (restoreTicks < 0) {
            return;
        }
        forceChunkCenter(minecraft, minecraft.player.getX(), minecraft.player.getZ());
        if (restoreTicks % 2 == 0) {
            int radius = Math.min(MAX_STREAM_RADIUS + 2, Math.max(2, minecraft.options.getEffectiveRenderDistance()) + 1);
            requestMissingChunks(minecraft, minecraft.player.getX(), minecraft.player.getZ(), radius,
                    SpectateChunkRequestMessage.MODE_SELF);
        }
        if (++restoreTicks > 40) {
            restoreTicks = -1;
        }
    }

    private static int countMissing(Minecraft minecraft, double x, double z, int radius) {
        if (minecraft.level == null) {
            return -1;
        }
        int centerChunkX = Mth.floor(x) >> 4;
        int centerChunkZ = Mth.floor(z) >> 4;
        int missing = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int chunkX = centerChunkX + dx;
                int chunkZ = centerChunkZ + dz;
                if (minecraft.level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) == null) {
                    missing++;
                }
            }
        }
        return missing;
    }

    private static void requestMissingChunks(Minecraft minecraft, double x, double z, int radius, int mode) {
        if (minecraft.level == null) {
            return;
        }
        int centerChunkX = Mth.floor(x) >> 4;
        int centerChunkZ = Mth.floor(z) >> 4;
        LongArrayList missing = new LongArrayList();
        outer:
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int chunkX = centerChunkX + dx;
                int chunkZ = centerChunkZ + dz;
                if (minecraft.level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) == null) {
                    missing.add(ChunkPos.asLong(chunkX, chunkZ));
                    if (missing.size() >= SpectateChunkRequestMessage.MAX_CHUNKS) {
                        break outer;
                    }
                }
            }
        }
        if (!missing.isEmpty()) {
            SpectateNetwork.sendChunkRequest(mode, missing.toLongArray());
        }
    }

    /**
     * Re-centers the renderable chunk area on the given position.
     * <p>
     * {@code LevelRenderer#setupRender} builds the {@link ViewArea} from {@code minecraft.player},
     * not from the camera entity, so a purely client side camera would otherwise only ever see the
     * chunks around the player's own body. Moving the view area onto the camera is the missing piece
     * that makes the streamed world around a distant player actually render.
     */
    private static void forceViewArea(Minecraft minecraft, double x, double z) {
        // The field is made public by the access transformer in META-INF/accesstransformer.cfg.
        // Reflection was silently blocked by the Java module system here, which is why the render
        // area kept following the player's body and no streamed terrain ever appeared.
        ViewArea area = minecraft.levelRenderer.viewArea;
        if (area != null) {
            area.repositionCamera(x, z);
        }
    }

    // ------------------------------------------------------------------
    // Camera handling
    // ------------------------------------------------------------------

    /** Creates (or refreshes) the client side camera anchor that walks in the offset remote world. */
    @Nullable
    private static Entity updateAnchor(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null) {
            return null;
        }
        boolean rebuild = cameraAnchor == null || cameraAnchor.level != minecraft.level
                || targetId == null || !targetId.equals(anchorTargetId);
        if (rebuild) {
            // Switching the watched player: drop the old clone (otherwise its body stays behind at
            // the previous target's position) and create a new one so the skin matches the target.
            SpectateDebugLog.CLIENT.log("anchor rebuild: target=%s uuid=%s oldAnchor=%s levelChanged=%s",
                    targetName, targetId, cameraAnchor == null ? "none" : cameraAnchor.getId(),
                    cameraAnchor != null && cameraAnchor.level != minecraft.level);
            discardAnchor(minecraft);
            cameraAnchor = createAnchor(minecraft);
            if (cameraAnchor == null) {
                return null;
            }
            anchorTargetId = targetId;
            cameraAnchor.setId(CAMERA_ANCHOR_ID);
            cameraAnchor.setNoGravity(true);
            if (cameraAnchor instanceof AbstractClientPlayer clone) {
                // Must be in the level or LevelRenderer never renders the camera entity, which is why
                // third person showed no body at all. The clone is a RemotePlayer, so ticking it is safe.
                minecraft.level.addPlayer(CAMERA_ANCHOR_ID, clone);
                // ClientLevel adds every AbstractClientPlayer it starts tracking to its players list.
                // Remove the anchor again: it is not a real player and must not show up in that list.
                minecraft.level.players().remove(clone);
            }
            SpectateDebugLog.CLIENT.log("anchor created: id=%d entityUuid=%s name=%s",
                    cameraAnchor.getId(), cameraAnchor.getUUID(), cameraAnchor.getName().getString());
            if (cameraActive) {
                // The old anchor was the camera entity a moment ago; put the camera on the new one
                // before the next frame renders (otherwise it would use a removed entity).
                minecraft.setCameraEntity(cameraAnchor);
            }
        }
        // The server already sends the anchor position for cross dimension sessions (target position
        // shifted into the offset world) and the target's rotation. Interpolate like a normal entity:
        // copy the previous transform into the "old" fields, then write the new one.
        cameraAnchor.xo = cameraAnchor.getX();
        cameraAnchor.yo = cameraAnchor.getY();
        cameraAnchor.zo = cameraAnchor.getZ();
        cameraAnchor.xRotO = cameraAnchor.getXRot();
        // Rotations interpolate linearly between yHeadRotO and yHeadRot, so a plain assignment makes
        // the camera take the long way round when the yaw crosses +-180 (179 -> -179 looks like a
        // full spin / flicker). Unwrap the new yaw towards the previous value: the stored angle may
        // leave [-180, 180], which is fine for rendering but keeps the interpolation short.
        float previousYaw = cameraAnchor.yHeadRot;
        float nextYaw = anchorYaw;
        while (nextYaw - previousYaw > 180.0F) {
            nextYaw -= 360.0F;
        }
        while (nextYaw - previousYaw < -180.0F) {
            nextYaw += 360.0F;
        }
        cameraAnchor.yHeadRotO = previousYaw;
        cameraAnchor.yRotO = previousYaw;
        cameraAnchor.yBodyRotO = previousYaw;
        cameraAnchor.setPos(stateX, stateY - cameraAnchor.getEyeHeight(), stateZ);
        // ArmorStand is a LivingEntity, and LivingEntity#getViewYRot() (what the Camera uses for the
        // horizontal view direction) reads yHeadRot, NOT yRot. Setting only yRot left the camera
        // pointing in the initial direction forever, which is exactly what "the observed player turns
        // but my view does not" looked like. yRot/yBodyRot are kept in sync for good measure.
        cameraAnchor.setYRot(nextYaw);
        cameraAnchor.yHeadRot = nextYaw;
        cameraAnchor.yBodyRot = nextYaw;
        cameraAnchor.setXRot(anchorPitch);
        // First person must not show the camera entity itself; the two third person views should.
        cameraAnchor.setInvisible(!(cameraAnchor instanceof AbstractClientPlayer) || minecraft.options.getCameraType().isFirstPerson());
        if (lastCameraType != minecraft.options.getCameraType()) {
            lastCameraType = minecraft.options.getCameraType();
            SpectateDebugLog.CLIENT.log("camera type changed: %s (anchor invisible=%s)",
                    lastCameraType, cameraAnchor.isInvisible());
        }
        return cameraAnchor;
    }

    /**
     * Creates the camera entity: a client side clone of the watched player. It renders the player
     * model (skin) in third person; in first person it is made invisible so it cannot block the view.
     * The clone is added to the level by {@link #updateAnchor} (otherwise the renderer never sees it)
     * and only position, rotation and eye height are written to it.
     * <p>
     * The clone gets a fresh UUID and resolves its skin from the watched player's UUID
     * ({@link SpectateAnchorPlayer}). Using the watched player's UUID directly created two entities
     * with the same UUID in the client level ("Duplicate entity UUID") and made the clone appear in
     * {@code ClientLevel#players()} as a second copy of the target.
     */
    @Nullable
    private static LivingEntity createAnchor(Minecraft minecraft) {
        if (minecraft.level == null) {
            return null;
        }
        if (targetId != null && minecraft.getConnection() != null) {
            String name = targetName.isEmpty() ? "CrystalBall" : targetName;
            GameProfile profile = new GameProfile(UUID.randomUUID(), name);
            SpectateAnchorPlayer clone = new SpectateAnchorPlayer(minecraft.level, profile, targetId);
            clone.setInvisible(false);
            SpectateDebugLog.CLIENT.log("anchor profile: name=%s cloneUuid=%s skinOwner=%s", name,
                    profile.getId(), targetId);
            return clone;
        }
        ArmorStand stand = new ArmorStand(minecraft.level, stateX, stateY, stateZ);
        stand.setInvisible(true);
        return stand;
    }

    /** Removes the client side camera anchor from the level (if there is one). */
    private static void discardAnchor(Minecraft minecraft) {
        if (cameraAnchor instanceof AbstractClientPlayer && cameraAnchor.level instanceof ClientLevel clientLevel) {
            clientLevel.removeEntity(cameraAnchor.getId(), Entity.RemovalReason.DISCARDED);
        }
        cameraAnchor = null;
        anchorTargetId = null;
    }

    private static void activate(Minecraft minecraft, Entity target) {
        ensureBigChunkCache(minecraft);
        savedYaw = minecraft.player.getYRot();
        savedPitch = minecraft.player.getXRot();
        savedCameraType = minecraft.options.getCameraType();
        // Do NOT force first person: F5 (third person / front view) stays fully usable while
        // watching, exactly like in vanilla spectator camera mode. The camera type is restored on
        // deactivate.
        minecraft.setCameraEntity(target);
        minecraft.gameRenderer.setRenderHand(false);
        minecraft.smartCull = false;
        // The client body has no collision data around it while the chunk window is on the camera,
        // so vanilla gravity would make it endlessly fall; the server stays authoritative and syncs.
        savedNoGravity = minecraft.player.isNoGravity();
        minecraft.player.setNoGravity(true);
        cameraActive = true;
        zeroInput(minecraft);
        SpectateDebugLog.CLIENT.log("camera activate: entityId=%d class=%s target=%s f5=%s",
                target.getId(), target.getClass().getSimpleName(), targetName, minecraft.options.getCameraType());
    }

    private static void maintain(Minecraft minecraft, Entity target) {
        if (minecraft.getCameraEntity() != target) {
            minecraft.setCameraEntity(target);
        }
        // No camera-type forcing here: F5 is handled by vanilla and works while watching (third
        // person also lets the camera collide with blocks instead of sitting inside a wall).
        if (minecraft.player.input instanceof KeyboardInput) {
            zeroInput(minecraft);
        }
        // Cancel out mouse look, which vanilla applies to the local player and not to the camera.
        minecraft.player.setYRot(savedYaw);
        minecraft.player.setXRot(savedPitch);
        minecraft.player.yRotO = savedYaw;
        minecraft.player.xRotO = savedPitch;
    }

    private static void deactivate() {
        boolean wasActive = cameraActive;
        cameraActive = false;
        SpectateDebugLog.CLIENT.log("camera deactivate: wasActive=%s target=%s", wasActive, targetName);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            if (wasActive) {
                minecraft.setCameraEntity(minecraft.player);
                minecraft.player.input = new KeyboardInput(minecraft.options);
                minecraft.player.setNoGravity(savedNoGravity);
            }
            // Hand the chunk window and the render area back before anything else, so the player's own
            // surroundings are readable again immediately (missing chunks are pushed by the server).
            // This is needed even when the camera never activated: the chunk window follows the target
            // as soon as a state arrives.
            forceChunkCenter(minecraft, minecraft.player.getX(), minecraft.player.getZ());
            forceViewArea(minecraft, minecraft.player.getX(), minecraft.player.getZ());
        }
        if (wasActive) {
            // Only touch these when the camera really took over; otherwise the player's own F5 choice
            // would be reset by an inactive state message.
            minecraft.gameRenderer.setRenderHand(true);
            minecraft.smartCull = true;
            minecraft.options.setCameraType(savedCameraType);
        }
        discardAnchor(minecraft);
    }

    private static void reset() {
        boolean hadSession = watching || cameraActive || targetId != null;
        if (hadSession) {
            SpectateDebugLog.CLIENT.log("reset: watching=%s cameraActive=%s target=%s", watching, cameraActive, targetName);
        }
        watching = false;
        targetId = null;
        targetName = "";
        targetEntityId = -1;
        restoreTicks = -1;
        waitingTicks = 0;
        stateTicks = 0;
        streamedEntityIds = new int[0];
        snappedMultipartIds.clear();
        loggedHidden.clear();
        trackedPlayers.clear();
        if (cameraActive) {
            cameraActive = false;
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.gameRenderer.setRenderHand(true);
            minecraft.smartCull = true;
            if (minecraft.player != null) {
                minecraft.setCameraEntity(minecraft.player);
                minecraft.player.input = new KeyboardInput(minecraft.options);
                minecraft.player.setNoGravity(savedNoGravity);
                forceChunkCenter(minecraft, minecraft.player.getX(), minecraft.player.getZ());
                forceViewArea(minecraft, minecraft.player.getX(), minecraft.player.getZ());
            }
            minecraft.options.setCameraType(savedCameraType);
        }
        discardAnchor(Minecraft.getInstance());
    }

    private static void zeroInput(Minecraft minecraft) {
        Input empty = new Input();
        // Keep sneaking so the player does not fall off the edge simply because they looked away.
        empty.shiftKeyDown = minecraft.player.input != null && minecraft.player.input.shiftKeyDown;
        minecraft.player.input = empty;
    }
}
