package com.crystalball.client;

import com.crystalball.CrystalBallClientConfig;
import com.crystalball.CrystalBallMod;
import com.crystalball.SpectateDebugLog;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Client side lifecycle glue that is not a plain Forge bus listener.
 * <p>
 * The client configuration doubles as the cap the client accepts from the server, so every time the
 * user edits {@code config/crystal_ball-client.toml} in game the new values have to be announced to
 * the server again. The per instance debug log is opened here as well, because this is the first
 * point where the user name and the game directory are known.
 */
@Mod.EventBusSubscriber(modid = CrystalBallMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class CrystalBallClientEvents {

    private CrystalBallClientEvents() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        String name = minecraft.getUser() == null ? "client" : minecraft.getUser().getName();
        String uuid = minecraft.getUser() == null ? "" : minecraft.getUser().getUuid();
        String shortUuid = SpectateDebugLog.safeName(uuid);
        if (shortUuid.length() > 8) {
            shortUuid = shortUuid.substring(0, 8);
        }
        SpectateDebugLog.CLIENT.init(
                minecraft.gameDirectory.toPath().resolve("logs")
                        .resolve("crystal_ball-client-" + SpectateDebugLog.safeName(name) + "-" + shortUuid + ".log"),
                name);
        SpectateDebugLog.CLIENT.log("client ready: user=%s gameDir=%s", name, minecraft.gameDirectory);
        SpectateDebugLog.CLIENT.log("client config: entityRadius=%d maxStreamedEntities=%d",
                CrystalBallClientConfig.entityRadius(), CrystalBallClientConfig.maxStreamedEntities());
    }

    @SubscribeEvent
    public static void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == CrystalBallClientConfig.SPEC) {
            SpectateDebugLog.CLIENT.log("client config reloaded: entityRadius=%d maxStreamedEntities=%d",
                    CrystalBallClientConfig.entityRadius(), CrystalBallClientConfig.maxStreamedEntities());
            SpectateClientController.syncClientCaps();
        }
    }
}
