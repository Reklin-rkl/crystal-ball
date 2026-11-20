package com.crystalball.client;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.RemotePlayer;
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
import com.crystalball.spectate.SpectateRemoteView;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import com.crystalball.CrystalBallMod;
import com.crystalball.spectate.SpectateChunkRequestMessage;
import com.crystalball.spectate.SpectateEntityRequestMessage;
import com.crystalball.spectate.SpectateNetwork;

import javax.annotation.Nullable;
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

    private static float savedYaw;
    private static float savedPitch;
    private static CameraType savedCameraType = CameraType.FIRST_PERSON;
    private static boolean savedNoGravity = false;

    private static int logTimer = 0;

    
    private static int restoreTicks = -1;
    private static int waitingTicks = 0;
    private static boolean presentReported = false;
    private static boolean crossDimension = false;

    /** Entity id of the client side camera anchor used for cross dimension watching. */
    private static final int CAMERA_ANCHOR_ID = -420000;

    @Nullable
    private static LivingEntity cameraAnchor;

    /** Real (unshifted) target position and rotation as sent by the server. */
    private static double stateX;
    private static double stateY;
    private static double stateZ;
    private static float anchorYaw;
    private static float anchorPitch;
    private static int hiddenEntityId = -1;

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
        crossDimension = crossDimensionFlag;
        stateX = x;
        stateY = y;
        stateZ = z;
        anchorYaw = yaw;
        anchorPitch = pitch;
        if (active) {
            watching = true;
            targetId = target;
            targetName = name == null ? "" : name;
            targetEntityId = entityId;
            lastKnownX = x;
            lastKnownY = y;
            lastKnownZ = z;
            restoreTicks = -1;
        } else {
            watching = false;
            targetId = null;
            targetName = "";
            targetEntityId = -1;
            waitingTicks = 0;
            presentReported = false;
            deactivate();
            restoreTicks = 0;
        }
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
        if (!watching || targetId == null) {
            if (cameraActive) {
                deactivate();
            }
            tickRestore(minecraft);
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
            hideWatchedEntity(minecraft);
            if (cameraActive) {
                maintain(minecraft, anchor);
            } else {
                activate(minecraft, anchor);
            }
        } else if (cameraActive) {
            deactivate();
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
        if (++logTimer >= 100) {
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

    /** Disconnect: never leave a foreign camera entity behind. */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggedOutEvent event) {
        reset();
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
        if (cameraAnchor == null || cameraAnchor.level != minecraft.level) {
            cameraAnchor = createAnchor(minecraft);
            if (cameraAnchor == null) {
                return null;
            }
            cameraAnchor.setId(CAMERA_ANCHOR_ID);
            cameraAnchor.setNoGravity(true);
            if (cameraAnchor instanceof AbstractClientPlayer clone) {
                // Must be in the level or LevelRenderer never renders the camera entity, which is why
                // third person showed no body at all. The clone is a RemotePlayer, so ticking it is safe.
                minecraft.level.addPlayer(CAMERA_ANCHOR_ID, clone);
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
        return cameraAnchor;
    }

    /**
     * Creates the camera entity. A client side clone of the watched player is preferred: it renders
     * the player model (skin) in third person, while vanilla hides the camera entity's own model in
     * first person (so no face filling the view and no invisible body). The clone is deliberately NOT
     * added to the level: a level entity would be ticked and a bare player/armor stand crashes without
     * vanilla initialisation. Only position, rotation and eye height are read from it.
     */
    @Nullable
    private static LivingEntity createAnchor(Minecraft minecraft) {
        if (minecraft.level == null) {
            return null;
        }
        if (targetId != null && minecraft.getConnection() != null) {
            PlayerInfo info = minecraft.getConnection().getPlayerInfo(targetId);
            if (info != null) {
                RemotePlayer clone = new RemotePlayer(minecraft.level, info.getProfile());
                clone.setInvisible(false);
                return clone;
            }
        }
        ArmorStand stand = new ArmorStand(minecraft.level, stateX, stateY, stateZ);
        stand.setInvisible(true);
        return stand;
    }

    /** Hides the real target entity while its client side clone carries the camera (same dimension). */
    private static void hideWatchedEntity(Minecraft minecraft) {
        if (hiddenEntityId >= 0 || minecraft.level == null || crossDimension) {
            return;
        }
        AbstractClientPlayer watched = resolveTarget(minecraft);
        if (watched != null) {
            watched.setInvisible(true);
            hiddenEntityId = watched.getId();
        }
    }

    private static void restoreWatchedEntity(Minecraft minecraft) {
        if (hiddenEntityId < 0) {
            return;
        }
        if (minecraft.level != null) {
            Entity watched = minecraft.level.getEntity(hiddenEntityId);
            if (watched != null) {
                watched.setInvisible(false);
            }
        }
        hiddenEntityId = -1;
    }
    private static void removeAnchor(Minecraft minecraft) {
        restoreWatchedEntity(minecraft);
        if (cameraAnchor instanceof AbstractClientPlayer
                && cameraAnchor.level instanceof net.minecraft.client.multiplayer.ClientLevel clientLevel) {
            clientLevel.removeEntity(cameraAnchor.getId(), Entity.RemovalReason.DISCARDED);
        }
        cameraAnchor = null;
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
        cameraActive = false;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.setCameraEntity(minecraft.player);
            minecraft.player.input = new KeyboardInput(minecraft.options);
            minecraft.player.setNoGravity(savedNoGravity);
            // Hand the chunk window and the render area back before anything else, so the player's own
            // surroundings are readable again immediately (missing chunks are pushed by the server).
            forceChunkCenter(minecraft, minecraft.player.getX(), minecraft.player.getZ());
            forceViewArea(minecraft, minecraft.player.getX(), minecraft.player.getZ());
        }
        minecraft.gameRenderer.setRenderHand(true);
        minecraft.smartCull = true;
        minecraft.options.setCameraType(savedCameraType);
        removeAnchor(minecraft);
    }

    private static void reset() {
        watching = false;
        targetId = null;
        targetName = "";
        targetEntityId = -1;
        restoreTicks = -1;
        waitingTicks = 0;
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
        removeAnchor(Minecraft.getInstance());
    }

    private static void zeroInput(Minecraft minecraft) {
        Input empty = new Input();
        // Keep sneaking so the player does not fall off the edge simply because they looked away.
        empty.shiftKeyDown = minecraft.player.input != null && minecraft.player.input.shiftKeyDown;
        minecraft.player.input = empty;
    }

    @Nullable
    private static AbstractClientPlayer resolveTarget(Minecraft minecraft) {
        if (minecraft.level == null || targetId == null) {
            return null;
        }
        if (targetEntityId >= 0) {
            Entity entity = minecraft.level.getEntity(targetEntityId);
            if (entity instanceof AbstractClientPlayer player && targetId.equals(player.getUUID())) {
                return player;
            }
        }
        for (AbstractClientPlayer player : minecraft.level.players()) {
            if (targetId.equals(player.getUUID())) {
                return player;
            }
        }
        // Offline mode / mixed premium-offline servers can hand out different UUIDs to the two sides
        // (the server session knows the offline UUID while the client profile may carry another one),
        // which made resolveTarget fail even though the player entity was right there. Fall back to
        // the name in that case - the name is always identical and the entity id already matched.
        if (!targetName.isEmpty()) {
            if (targetEntityId >= 0) {
                Entity entity = minecraft.level.getEntity(targetEntityId);
                if (entity instanceof AbstractClientPlayer player
                        && targetName.equalsIgnoreCase(player.getGameProfile().getName())) {
                    return player;
                }
            }
            for (AbstractClientPlayer player : minecraft.level.players()) {
                if (targetName.equalsIgnoreCase(player.getGameProfile().getName())) {
                    return player;
                }
            }
        }
        return null;
    }
}