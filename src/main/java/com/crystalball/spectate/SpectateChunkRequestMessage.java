package com.crystalball.spectate;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client -> server: "I am missing these chunks around a point, please send them".
 * <p>
 * MODE_TARGET requests the world around the watched player, MODE_SELF requests the world around the
 * viewer's own body (used right after a session ends to restore its surroundings).
 */
public class SpectateChunkRequestMessage {

    public static final int MODE_TARGET = 0;
    public static final int MODE_SELF = 1;

    /** Hard cap so a malicious client cannot ask for unbounded work. */
    public static final int MAX_CHUNKS = 128;

    private final int mode;
    private final long[] chunks;

    public SpectateChunkRequestMessage(int mode, long[] chunks) {
        this.mode = mode;
        this.chunks = chunks;
    }

    public int getMode() {
        return mode;
    }

    public long[] getChunks() {
        return chunks;
    }

    public static void encode(SpectateChunkRequestMessage message, FriendlyByteBuf buffer) {
        int count = Math.min(message.chunks.length, MAX_CHUNKS);
        buffer.writeVarInt(message.mode);
        buffer.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            buffer.writeLong(message.chunks[i]);
        }
    }

    public static SpectateChunkRequestMessage decode(FriendlyByteBuf buffer) {
        int mode = buffer.readVarInt();
        int count = Math.min(buffer.readVarInt(), MAX_CHUNKS);
        long[] chunks = new long[count];
        for (int i = 0; i < count; i++) {
            chunks[i] = buffer.readLong();
        }
        return new SpectateChunkRequestMessage(mode, chunks);
    }

    public static void handle(SpectateChunkRequestMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer sender = context.getSender();
        if (sender != null) {
            context.enqueueWork(() -> SpectateRemoteView.onChunkRequest(sender, message.mode, message.chunks));
        }
        context.setPacketHandled(true);
    }
}