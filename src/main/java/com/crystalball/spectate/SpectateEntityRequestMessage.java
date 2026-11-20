package com.crystalball.spectate;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client -> server message about the watched player's entity on the client.
 * <p>
 * Vanilla only sends a player entity to another player within
 * {@code min(clientTrackingRange * 16, (view-distance - 1) * 16)} blocks. That rule is distance
 * based, but in a LAN/host setup it can silently fail (the entity never shows up on the host client
 * even when the players stand next to each other), which made the camera never activate. Instead of
 * guessing from the distance, the client simply reports the truth:
 * {@link #ACTION_MISSING} means "I do not have that entity, please inject it",
 * {@link #ACTION_PRESENT} means "I have it, everything is fine".
 */
public class SpectateEntityRequestMessage {

    public static final int ACTION_MISSING = 0;
    public static final int ACTION_PRESENT = 1;

    private final int action;

    public SpectateEntityRequestMessage(int action) {
        this.action = action;
    }

    public int getAction() {
        return action;
    }

    public static void encode(SpectateEntityRequestMessage message, FriendlyByteBuf buffer) {
        buffer.writeVarInt(message.action);
    }

    public static SpectateEntityRequestMessage decode(FriendlyByteBuf buffer) {
        return new SpectateEntityRequestMessage(buffer.readVarInt());
    }

    public static void handle(SpectateEntityRequestMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer sender = context.getSender();
        if (sender != null) {
            context.enqueueWork(() -> SpectateRemoteView.onEntityStatus(sender, message.action));
        }
        context.setPacketHandled(true);
    }
}