package com.crystalball.spectate;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import com.crystalball.CrystalBallMod;

import java.util.function.Supplier;

/**
 * Client -> server "a Crystal Ball key was pressed" message.
 * <p>
 * The client never decides what happens; it only reports which key was pressed while the item was
 * held. All validation (is the player allowed to watch, cycles, gamemode changes, teleports) happens
 * on the server in {@link SpectateManager}.
 */
public class SpectateActionMessage {

    public static final int ACTION_ALPHA = 0;
    public static final int ACTION_BETA = 1;

    private final int action;

    public SpectateActionMessage(int action) {
        this.action = action;
    }

    public int getAction() {
        return action;
    }

    public static void encode(SpectateActionMessage message, FriendlyByteBuf buffer) {
        buffer.writeVarInt(message.action);
    }

    public static SpectateActionMessage decode(FriendlyByteBuf buffer) {
        return new SpectateActionMessage(buffer.readVarInt());
    }

    public static void handle(SpectateActionMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer sender = context.getSender();
        if (sender != null) {
            context.enqueueWork(() -> {
                try {
                    SpectateManager.onItemAction(sender, message.action);
                } catch (RuntimeException exception) {
                    // A single broken request must never take the server tick down.
                    CrystalBallMod.LOGGER.error("Crystal Ball: handling a spectate action failed", exception);
                }
            });
        }
        context.setPacketHandled(true);
    }
}
