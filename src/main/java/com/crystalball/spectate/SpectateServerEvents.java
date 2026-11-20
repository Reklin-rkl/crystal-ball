package com.crystalball.spectate;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import com.crystalball.CrystalBallMod;

/**
 * Server side glue that keeps the watching relations consistent when players die, respawn,
 * disconnect or when the server shuts down.
 * <p>
 * No game mode or position is ever touched here: ending a relation only sends one small packet that
 * makes the client put its camera back on its own player.
 */
@Mod.EventBusSubscriber(modid = CrystalBallMod.MODID)
public final class SpectateServerEvents {

    private SpectateServerEvents() {
    }

    /** Re-validate all relations once per tick and re-sync the clients every second. */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            SpectateManager.tick(server);
        }
    }

    /** A player left: stop their own watching and stop everybody who was watching them. */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        SpectateManager.onViewerLeft(player);
        SpectateManager.stopWatchersOf(player.getUUID(), "target_left");
    }

    /** Taking damage ends the relation, so a knockback can never desync the remote view and the body. */
    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SpectateManager.onViewerHurt(player);
        }
    }

    /** A player died: a dead player cannot be watched and the camera of a dying viewer is reset. */
    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        SpectateManager.stopWatchersOf(player.getUUID(), "target_left");
        SpectateManager.onViewerLeft(player);
    }

    /** Respawn / end portal recreation creates a fresh ServerPlayer instance; do not keep relations. */
    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        SpectateManager.stopWatchersOf(player.getUUID(), "target_left");
        SpectateManager.onViewerLeft(player);
    }

    /**
     * A dimension change recreates the client level, which invalidates any camera / chunk window we
     * imposed. End the player's own watching session; sessions where they are only the target keep
     * running (the streamer simply re-packs chunks from the new dimension).
     */
    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SpectateManager.onViewerLeft(player);
        }
    }

    /** Mining / placing: relay the fresh terrain to viewers watching someone far away (or elsewhere). */
    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getWorld() instanceof ServerLevel level) {
            SpectateRemoteView.onBlockChanged(level, event.getPos());
        }
    }

    /** Fluids / redstone / falling blocks: also re-push the affected chunk (rate limited per chunk). */
    @SubscribeEvent
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (event.getWorld() instanceof ServerLevel level) {
            SpectateRemoteView.onBlockChanged(level, event.getPos());
        }
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getWorld() instanceof ServerLevel level) {
            SpectateRemoteView.onBlockChanged(level, event.getPos());
        }
    }

    /** Graceful shutdown: tell every client to put its camera back before the connection closes. */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        SpectateManager.stopAll();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        SpectateManager.clear();
    }
}
