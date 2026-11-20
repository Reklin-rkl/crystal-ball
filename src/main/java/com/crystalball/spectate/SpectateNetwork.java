package com.crystalball.spectate;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import com.crystalball.CrystalBallMod;

/**
 * Dedicated network channel of the Crystal Ball mod.
 * <p>
 * Kept on a dedicated channel so it never interferes with other network code in the mod or
 * the workspace cannot shift message ids around.
 * <ul>
 *     <li>{@link SpectateActionMessage} client -&gt; server: alpha / beta pressed while the orb is in
 *     hand (or while the camera is already looking through somebody else).</li>
 *     <li>{@link SpectateStateMessage} server -&gt; client: which player the camera should follow.</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = CrystalBallMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class SpectateNetwork {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(CrystalBallMod.MODID, "spectate"), () -> PROTOCOL_VERSION, PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private SpectateNetwork() {
    }

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        CHANNEL.registerMessage(0, SpectateActionMessage.class, SpectateActionMessage::encode, SpectateActionMessage::decode,
                SpectateActionMessage::handle);
        CHANNEL.registerMessage(2, SpectateChunkRequestMessage.class, SpectateChunkRequestMessage::encode,
                SpectateChunkRequestMessage::decode, SpectateChunkRequestMessage::handle);
        CHANNEL.registerMessage(3, SpectateEntityRequestMessage.class, SpectateEntityRequestMessage::encode,
                SpectateEntityRequestMessage::decode, SpectateEntityRequestMessage::handle);
        CHANNEL.registerMessage(1, SpectateStateMessage.class, SpectateStateMessage::encode, SpectateStateMessage::decode,
                SpectateStateMessage::handle);
    }

    /** Client side helper: tells the server that the alpha or beta key was pressed. */
    public static void sendAction(int action) {
        CHANNEL.sendToServer(new SpectateActionMessage(action));
    }

    /** Client side helper: asks the server for the given chunks (packed {@code ChunkPos.asLong} values). */
    public static void sendChunkRequest(int mode, long[] chunks) {
        CHANNEL.sendToServer(new SpectateChunkRequestMessage(mode, chunks));
    }

    /** Client side helper: tells the server whether the watched player's entity reached this client. */
    public static void sendEntityStatus(int action) {
        CHANNEL.sendToServer(new SpectateEntityRequestMessage(action));
    }

    /** Server side helper: updates the camera state of a single viewer. */
    public static void sendToPlayer(ServerPlayer player, SpectateStateMessage message) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }
}
