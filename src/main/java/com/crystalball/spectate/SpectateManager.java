package com.crystalball.spectate;

import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server side bookkeeping of the Crystal Ball.
 * <p>
 * Important: the server never changes the viewer's game mode, never teleports the viewer and never
 * touches the vanilla spectator camera. Watching is a <b>client side camera effect</b>: the server
 * only remembers "viewer V is currently watching target T", validates that the target is still
 * watchable (online, alive, not a spectator and in the same dimension as the viewer) and tells the
 * viewer's client through {@link SpectateStateMessage} which player it should render from.
 * <p>
 * Because of that the viewer keeps their own body, game mode, position, inventory and hitbox - they
 * can still be seen, attacked, pushed or killed while watching, exactly as requested.
 * <p>
 * The cost of this design is that the client can only render a target it has actually loaded: the
 * target has to be inside the viewer's loaded/visible area (roughly the server view distance).
 * Cross dimension watching is impossible without moving the player, so it is refused with a message.
 */
public final class SpectateManager {

    /** All currently active watching relations, keyed by viewer UUID. */
    private static final Map<UUID, WatchSession> SESSIONS = new LinkedHashMap<>();

    /** Reverse index: target UUID -> viewer UUIDs currently watching that target. */
    private static final Map<UUID, Set<UUID>> WATCHERS = new HashMap<>();

    /** Ticks until the periodic client re-sync (keeps a reconnecting/respawning client in step). */
    private static int resyncTimer = 0;

    private SpectateManager() {
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    public static boolean isWatching(UUID viewer) {
        return SESSIONS.containsKey(viewer);
    }

    @Nullable
    public static WatchSession session(UUID viewer) {
        return SESSIONS.get(viewer);
    }

    public static Collection<WatchSession> sessions() {
        return Collections.unmodifiableCollection(new ArrayList<>(SESSIONS.values()));
    }

    public static Set<UUID> watchersOf(UUID target) {
        Set<UUID> viewers = WATCHERS.get(target);
        return viewers == null ? Collections.emptySet() : Collections.unmodifiableSet(new LinkedHashSet<>(viewers));
    }


    // ------------------------------------------------------------------
    // Item driven actions (network messages)
    // ------------------------------------------------------------------

    /** Handles a key press forwarded by the client. {@code action} is one of the SpectateActionMessage constants. */
    public static void onItemAction(ServerPlayer player, int action) {
        if (player == null) {
            return;
        }
        if (action == SpectateActionMessage.ACTION_BETA) {
            cycleNext(player);
        } else if (action == SpectateActionMessage.ACTION_ALPHA) {
            returnToSelf(player);
        }
    }

    /** Beta: cycle the point of view to the next watchable player. */
    public static void cycleNext(ServerPlayer viewer) {
        MinecraftServer server = viewer.getServer();
        if (server == null) {
            return;
        }
        List<ServerPlayer> candidates = candidateTargets(server, viewer);
        if (candidates.isEmpty()) {
            if (otherPlayersOnline(server, viewer.getUUID()).isEmpty()) {
                // Nobody else at all: exactly the two Terraria messages.
                message(viewer, server.isDedicatedServer() ? "alone" : "reflection");
            } else {
                message(viewer, "same_dimension_none");
            }
            return;
        }
        WatchSession current = SESSIONS.get(viewer.getUUID());
        int index = -1;
        if (current != null) {
            for (int i = 0; i < candidates.size(); i++) {
                if (candidates.get(i).getUUID().equals(current.target())) {
                    index = i;
                    break;
                }
            }
        }
        start(viewer, candidates.get((index + 1) % candidates.size()));
    }

    /** Alpha: stop watching and put the point of view back on the player themself. */
    public static void returnToSelf(ServerPlayer viewer) {
        if (!SESSIONS.containsKey(viewer.getUUID())) {
            message(viewer, "already_self");
            return;
        }
        stop(viewer.getUUID());
        message(viewer, "returned");
    }

    // ------------------------------------------------------------------
    // Relation life cycle
    // ------------------------------------------------------------------

    /** Starts (or retargets) a watching relation. Returns {@code false} when the request was refused. */
    public static boolean start(ServerPlayer viewer, ServerPlayer target) {
        if (viewer == null || target == null) {
            return false;
        }
        UUID viewerId = viewer.getUUID();
        UUID targetId = target.getUUID();
        if (viewerId.equals(targetId)) {
            message(viewer, "self_target");
            return false;
        }
        if (!isWatchable(target)) {
            message(viewer, "not_watchable", target.getDisplayName());
            return false;
        }
        

        WatchSession existing = SESSIONS.get(viewerId);
        if (existing != null) {
            unwatch(existing.target(), viewerId);
            WatchSession updated = existing.withTarget(targetId, target.getGameProfile().getName());
            SESSIONS.put(viewerId, updated);
        } else {
            SESSIONS.put(viewerId, new WatchSession(viewerId, viewer.getGameProfile().getName(), targetId,
                    target.getGameProfile().getName()));
        }
        rememberWatcher(targetId, viewerId);
        SpectateRemoteView.onStart(viewer, target);
        pushState(viewer);
        message(viewer, "now_watching", target.getDisplayName());
        return true;
    }

    /** Ends the watching relation of the given viewer (if any). */
    public static boolean stop(UUID viewerId) {
        WatchSession session = SESSIONS.remove(viewerId);
        if (session == null) {
            return false;
        }
        unwatch(session.target(), viewerId);
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            ServerPlayer viewer = server.getPlayerList().getPlayer(viewerId);
            if (viewer != null) {
                ServerPlayer targetPlayer = server.getPlayerList().getPlayer(session.target());
                SpectateRemoteView.onStop(viewer, targetPlayer);
                pushState(viewer);
                // Snap the client onto the authoritative server position; while watching, the body may
                // have been moved (knockback, pistons, ...) without the client noticing it.
                if (viewer.connection != null) {
                    viewer.connection.teleport(viewer.getX(), viewer.getY(), viewer.getZ(), viewer.getYRot(), viewer.getXRot());
                }
            }
        }
        return true;
    }

    /**
     * Called when a viewer takes damage. The client freezes its body while watching, so being knocked
     * back would quickly desync the two views; ending the relation immediately keeps them consistent.
     */
    public static void onViewerHurt(ServerPlayer viewer) {
        if (SESSIONS.containsKey(viewer.getUUID())) {
            stop(viewer.getUUID());
            message(viewer, "damaged");
        }
    }

    /** Called when a viewer dies, respawns or disconnects: the relation is dropped without a message. */
    public static void onViewerLeft(ServerPlayer viewer) {
        if (SESSIONS.containsKey(viewer.getUUID())) {
            stop(viewer.getUUID());
        }
    }

    /** Ends every relation that is watching {@code target} (target died / left / changed dimension). */
    public static void stopWatchersOf(UUID target, String messageKey) {
        Set<UUID> viewers = WATCHERS.get(target);
        if (viewers == null || viewers.isEmpty()) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        for (UUID viewerId : new ArrayList<>(viewers)) {
            ServerPlayer viewer = server == null ? null : server.getPlayerList().getPlayer(viewerId);
            String targetName = SESSIONS.containsKey(viewerId) ? SESSIONS.get(viewerId).targetName() : "";
            stop(viewerId);
            if (viewer != null) {
                message(viewer, messageKey, targetName);
            }
        }
    }

    /** Ends every relation (server shutdown). */
    public static void stopAll() {
        for (UUID viewerId : new ArrayList<>(SESSIONS.keySet())) {
            stop(viewerId);
        }
    }

    /** Drops the in-memory state. */
    public static void clear() {
        SESSIONS.clear();
        WATCHERS.clear();
        SpectateRemoteView.clear();
        resyncTimer = 0;
    }

    // ------------------------------------------------------------------
    // Per-tick maintenance
    // ------------------------------------------------------------------

    /**
     * Re-validates every relation once per server tick: the target has to stay online, alive, out of
     * spectator mode and in the same dimension as the viewer, otherwise the relation ends. Also
     * re-sends the client state every second so a client that reloaded its level cannot get stuck.
     */
    public static void tick(MinecraftServer server) {
        SpectateRemoteView.tickRestores(server);
        if (SESSIONS.isEmpty()) {
            return;
        }
        for (WatchSession session : new ArrayList<>(SESSIONS.values())) {
            ServerPlayer viewer = server.getPlayerList().getPlayer(session.viewer());
            if (viewer == null) {
                stop(session.viewer());
                continue;
            }
            ServerPlayer target = server.getPlayerList().getPlayer(session.target());
            if (!isWatchable(target)) {
                stop(session.viewer());
                message(viewer, "target_left", session.targetName());
                continue;
            }
            SpectateRemoteView.tick(viewer, target);
            // Push the camera state every tick: the client needs it for the cross dimension anchor, and
            // for same dimension sessions it keeps the view correct across portal/dimension transitions
            // (the client falls back to the anchor position while the target entity is not streamed yet).
            pushState(viewer);
        }
        if (++resyncTimer >= 20) {
            resyncTimer = 0;
            for (WatchSession session : new ArrayList<>(SESSIONS.values())) {
                ServerPlayer viewer = server.getPlayerList().getPlayer(session.viewer());
                if (viewer != null) {
                    pushState(viewer);
                }
            }
        }
    }

    /** Sends the current relation (or its absence) to the viewer's client. */
    public static void pushState(ServerPlayer viewer) {
        WatchSession session = SESSIONS.get(viewer.getUUID());
        if (session == null) {
            SpectateNetwork.sendToPlayer(viewer, SpectateStateMessage.inactive());
            return;
        }
        MinecraftServer server = viewer.getServer();
        ServerPlayer target = server == null ? null : server.getPlayerList().getPlayer(session.target());
        if (target == null) {
            SpectateNetwork.sendToPlayer(viewer, SpectateStateMessage.inactive());
            return;
        }
        boolean cross = viewer.level != target.level;
        double cameraX = cross ? SpectateRemoteView.anchorX(viewer, target) : target.getX();
        double cameraZ = cross ? SpectateRemoteView.anchorZ(viewer, target) : target.getZ();
        SpectateNetwork.sendToPlayer(viewer, SpectateStateMessage.active(session.target(), session.targetName(), target.getId(),
                cameraX, cross ? SpectateRemoteView.anchorY(viewer, target) : target.getEyeY(), cameraZ, cross, target.getYRot(), target.getXRot()));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Every player the viewer could switch to with the beta key, in a stable (name) order. */
    private static List<ServerPlayer> candidateTargets(MinecraftServer server, ServerPlayer viewer) {
        List<ServerPlayer> candidates = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            if (id.equals(viewer.getUUID()) || !isWatchable(player)) {
                continue;
            }
            

            candidates.add(player);
        }
        candidates.sort(Comparator.comparing(player -> player.getGameProfile().getName(), String.CASE_INSENSITIVE_ORDER));
        return candidates;
    }

    private static List<ServerPlayer> otherPlayersOnline(MinecraftServer server, UUID viewerId) {
        List<ServerPlayer> others = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!player.getUUID().equals(viewerId)) {
                others.add(player);
            }
        }
        return others;
    }

    private static boolean isWatchable(@Nullable ServerPlayer player) {
        return player != null && !player.isRemoved() && player.isAlive() && !player.isSpectator();
    }

    private static void rememberWatcher(UUID target, UUID viewer) {
        WATCHERS.computeIfAbsent(target, key -> new LinkedHashSet<>()).add(viewer);
    }

    private static void unwatch(UUID target, UUID viewer) {
        Set<UUID> viewers = WATCHERS.get(target);
        if (viewers != null) {
            viewers.remove(viewer);
            if (viewers.isEmpty()) {
                WATCHERS.remove(target);
            }
        }
    }

    private static void message(ServerPlayer player, String key, Object... args) {
        if (player == null) {
            return;
        }
        player.displayClientMessage(new TranslatableComponent("crystal_ball.message." + key, args), false);
    }
}
