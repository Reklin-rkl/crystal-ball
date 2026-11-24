package com.crystalball.spectate;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import com.crystalball.client.SpectateClientController;

import java.util.function.Supplier;

/**
 * Server -&gt; client: the entity ids that are currently streamed for the session.
 * <p>
 * The client cannot tell streamed entities apart from entities vanilla sent itself, but some fixes
 * must only touch the streamed copies (for example positioning the client side parts of a multipart
 * entity, which the owning mod normally syncs only for vanilla tracking players).
 */
public class SpectateStreamedEntitiesMessage {

    private static final int MAX_IDS = 4096;

    private final int[] entityIds;

    public SpectateStreamedEntitiesMessage(int[] entityIds) {
        this.entityIds = entityIds == null ? new int[0] : entityIds;
    }

    public int[] getEntityIds() {
        return entityIds;
    }

    public static void encode(SpectateStreamedEntitiesMessage message, FriendlyByteBuf buffer) {
        int count = Math.min(message.entityIds.length, MAX_IDS);
        buffer.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            buffer.writeVarInt(message.entityIds[i]);
        }
    }

    public static SpectateStreamedEntitiesMessage decode(FriendlyByteBuf buffer) {
        int count = Math.min(buffer.readVarInt(), MAX_IDS);
        int[] ids = new int[count];
        for (int i = 0; i < count; i++) {
            ids[i] = buffer.readVarInt();
        }
        return new SpectateStreamedEntitiesMessage(ids);
    }

    public static void handle(SpectateStreamedEntitiesMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> SpectateClientController.onStreamedEntities(message.entityIds)));
        context.setPacketHandled(true);
    }
}
