package com.crystalball.spectate;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import com.crystalball.client.SpectateClientController;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Server -> client message telling the viewer whose eyes the camera should use (or that the
 * watching relation ended).
 * <p>
 * It carries the target's current entity id and position as well: the id is used to resolve the
 * entity on the client (a target that respawned gets a fresh id, which the periodic re-sync fixes),
 * and the position lets the client start requesting the remote world data immediately, even before
 * the entity itself has been streamed to it.
 */
public class SpectateStateMessage {

    private static final UUID NO_TARGET = new UUID(0L, 0L);

    private final boolean active;
    private final UUID target;
    private final String targetName;
    private final int entityId;
    private final double x;
    private final double y;
    private final double z;
    private final boolean crossDimension;
    private final float yaw;
    private final float pitch;

    private SpectateStateMessage(boolean active, UUID target, String targetName, int entityId, double x, double y, double z,
            boolean crossDimension, float yaw, float pitch) {
        this.active = active;
        this.target = target;
        this.targetName = targetName;
        this.entityId = entityId;
        this.x = x;
        this.y = y;
        this.z = z;
        this.crossDimension = crossDimension;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public static SpectateStateMessage inactive() {
        return new SpectateStateMessage(false, NO_TARGET, "", -1, 0.0D, 0.0D, 0.0D, false, 0.0F, 0.0F);
    }

    public static SpectateStateMessage active(UUID target, String targetName, int entityId, double x, double y, double z,
            boolean crossDimension, float yaw, float pitch) {
        return new SpectateStateMessage(true, target, targetName == null ? "" : targetName, entityId, x, y, z, crossDimension,
                yaw, pitch);
    }

    public boolean isActive() {
        return active;
    }

    public UUID getTarget() {
        return target;
    }

    public String getTargetName() {
        return targetName;
    }

    public int getEntityId() {
        return entityId;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public boolean isCrossDimension() {
        return crossDimension;
    }

    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public double getZ() {
        return z;
    }

    public static void encode(SpectateStateMessage message, FriendlyByteBuf buffer) {
        buffer.writeBoolean(message.active);
        if (message.active) {
            buffer.writeUUID(message.target);
            buffer.writeUtf(message.targetName);
            buffer.writeVarInt(message.entityId);
            buffer.writeDouble(message.x);
            buffer.writeDouble(message.y);
            buffer.writeDouble(message.z);
            buffer.writeBoolean(message.crossDimension);
            buffer.writeFloat(message.yaw);
            buffer.writeFloat(message.pitch);
        }
    }

    public static SpectateStateMessage decode(FriendlyByteBuf buffer) {
        if (!buffer.readBoolean()) {
            return inactive();
        }
        return active(buffer.readUUID(), buffer.readUtf(), buffer.readVarInt(), buffer.readDouble(), buffer.readDouble(),
                buffer.readDouble(), buffer.readBoolean(), buffer.readFloat(), buffer.readFloat());
    }

    public static void handle(SpectateStateMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> SpectateClientController.onState(message.active,
                message.target, message.targetName, message.entityId, message.x, message.y, message.z,
                message.crossDimension, message.yaw, message.pitch)));
        context.setPacketHandled(true);
    }
}