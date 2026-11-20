package com.crystalball.spectate;

import java.util.UUID;

/**
 * One active watching relationship: {@code viewer} is looking through {@code target}'s eyes.
 * <p>
 * The relationship itself is only bookkeeping on the server (it powers the {@code viewers} /
 * {@code list} commands, the beta-key cycling and the cycle check). The actual camera is purely
 * client side, so the viewer never changes game mode and never leaves their position.
 */
public record WatchSession(UUID viewer, String viewerName, UUID target, String targetName) {

    /** Returns a copy of this session that watches a different player. */
    public WatchSession withTarget(UUID newTarget, String newTargetName) {
        return new WatchSession(viewer, viewerName, newTarget, newTargetName);
    }
}
