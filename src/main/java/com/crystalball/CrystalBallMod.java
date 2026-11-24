package com.crystalball;

import com.crystalball.init.CrystalBallItems;

import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(CrystalBallMod.MODID)
public class CrystalBallMod {

    public static final String MODID = "crystal_ball";
    public static final Logger LOGGER = LogManager.getLogger("crystal_ball");

    public CrystalBallMod() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        CrystalBallItems.REGISTRY.register(bus);
        // The server config drives the streaming; the client config caps what this client accepts and
        // is announced to the server (min(server, client), -1 = no limit).
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, CrystalBallConfig.SPEC, "crystal_ball-server.toml");
        if (FMLEnvironment.dist.isClient()) {
            ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, CrystalBallClientConfig.SPEC,
                    "crystal_ball-client.toml");
        }
    }
}
