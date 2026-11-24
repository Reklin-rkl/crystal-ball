package com.crystalball.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ClientRegistry;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import com.crystalball.CrystalBallMod;
import org.lwjgl.glfw.GLFW;

/**
 * The two re-bindable keys of the Crystal Ball.
 * <p>
 * They are ordinary {@link KeyMapping}s, so the player can change them in the vanilla controls screen.
 * The defaults are the mouse buttons asked for in the specification: alpha = left button,
 * beta = right button. Their {@link CrystalBallKeyContext} keeps them inactive whenever vanilla
 * should receive the click (see that class for why this matters), so the left mouse button remains
 * a normal attack while the orb is merely held.
 */
@Mod.EventBusSubscriber(modid = CrystalBallMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class CrystalBallKeyMappings {

    public static final String CATEGORY = "key.categories.crystal_ball";

    /** Alpha: return the point of view to the player themself. Default: left mouse button. */
    public static final KeyMapping ALPHA = new KeyMapping("key.crystal_ball.alpha",
            CrystalBallKeyContext.WATCHING_ONLY, InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_LEFT, CATEGORY);

    /** Beta: cycle the point of view through the other players. Default: right mouse button. */
    public static final KeyMapping BETA = new KeyMapping("key.crystal_ball.beta",
            CrystalBallKeyContext.WATCHING_OR_HOLDING, InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_RIGHT, CATEGORY);

    private CrystalBallKeyMappings() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ClientRegistry.registerKeyBinding(ALPHA);
            ClientRegistry.registerKeyBinding(BETA);
        });
    }
}
