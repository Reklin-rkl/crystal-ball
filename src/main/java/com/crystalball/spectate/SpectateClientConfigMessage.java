package com.crystalball.spectate;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client -&gt; server: the streaming limits this client accepts.
 * <p>
 * The server owns the chunk/entity streamer, but the settings are configurable on both sides
 * ({@code crystal_ball-server.toml} and {@code crystal_ball-client.toml}). The effective value is
 * {@code min(server value, client value)} with {@code -1} meaning "no limit", so a client can lower
 * the amount of streamed data without being able to raise the server's own cap.
 */
public class SpectateClientConfigMessage {

    private final int entityRadius;
    private final int maxStreamedEntities;

    public SpectateClientConfigMessage(int entityRadius, int maxStreamedEntities) {
        this.entityRadius = entityRadius;
        this.maxStreamedEntities = maxStreamedEntities;
    }

    public int getEntityRadius() {
        return entityRadius;
    }

    public int getMaxStreamedEntities() {
        return maxStreamedEntities;
    }

    public static void encode(SpectateClientConfigMessage message, FriendlyByteBuf buffer) {
        buffer.writeVarInt(message.entityRadius);
        buffer.writeVarInt(message.maxStreamedEntities);
    }

    public static SpectateClientConfigMessage decode(FriendlyByteBuf buffer) {
        return new SpectateClientConfigMessage(buffer.readVarInt(), buffer.readVarInt());
    }

    public static void handle(SpectateClientConfigMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer sender = context.getSender();
        if (sender != null) {
            context.enqueueWork(() -> SpectateRemoteView.onClientCaps(sender, message.entityRadius,
                    message.maxStreamedEntities));
        }
        context.setPacketHandled(true);
    }
}
