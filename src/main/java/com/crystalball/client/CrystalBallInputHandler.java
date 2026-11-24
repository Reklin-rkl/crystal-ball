package com.crystalball.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import com.crystalball.CrystalBallMod;
import com.crystalball.init.CrystalBallItems;
import com.crystalball.spectate.SpectateActionMessage;
import com.crystalball.spectate.SpectateNetwork;
import org.lwjgl.glfw.GLFW;

/**
 * Client side input interception for the Crystal Ball.
 * <p>
 * Requirement: the alpha/beta keys must be caught <b>before</b> vanilla logic runs, and only when the
 * Crystal Ball is actually in hand (or while the camera is already looking through somebody else).
 * <p>
 * For mouse buttons this is done in {@link InputEvent.RawMouseEvent}, which Forge fires inside
 * {@code MouseHandler#onPress} <i>before</i> {@code KeyMapping#set}/{@code KeyMapping#click} are
 * called. Cancelling it therefore stops the vanilla attack/use action completely - no arm swing, no
 * block breaking, no block placement.
 * <p>
 * The alpha key is special: it only owns the mouse button while the camera is actually watching
 * somebody. While the orb is merely held, left click has to remain a normal attack / air swing, so
 * alpha-bound mouse presses are not cancelled and their click counter is drained without sending the
 * action.
 * <p>
 * Keyboard rebinds cannot be cancelled that early ({@code KeyInputEvent} is not cancellable), but a
 * keyboard key is not a vanilla action either, so it is enough to poll
 * {@link KeyMapping#consumeClick()} once per client tick.
 */
@Mod.EventBusSubscriber(modid = CrystalBallMod.MODID, value = Dist.CLIENT)
public final class CrystalBallInputHandler {

    private CrystalBallInputHandler() {
    }

    @SubscribeEvent
    public static void onRawMouse(InputEvent.RawMouseEvent event) {
        if (event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null || minecraft.getOverlay() != null) {
            // Never swallow clicks that belong to a GUI or to the loading overlay.
            return;
        }
        InputConstants.Key key = InputConstants.Type.MOUSE.getOrCreate(event.getButton());
        int action = boundAction(key);
        if (action < 0) {
            return;
        }
        // Alpha ("return the view") only matters while the camera is actually watching somebody.
        // While the orb is merely held, a left click must stay a normal attack / air swing, so the
        // click is not swallowed here; the click counter is drained without sending the action below.
        if (action == SpectateActionMessage.ACTION_ALPHA && !SpectateClientController.isCameraActive()) {
            return;
        }
        if (!intercepts(minecraft)) {
            return;
        }
        // Catch the press before vanilla turns it into an attack/use action.
        event.setCanceled(true);
        SpectateNetwork.sendAction(action);
    }

    /** While the camera looks through somebody else, no block may be attacked or placed by this body. */
    @SubscribeEvent
    public static void onClickInput(InputEvent.ClickInputEvent event) {
        if (SpectateClientController.isCameraActive()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            drain();
            return;
        }
        boolean intercepting = minecraft.screen == null && minecraft.getOverlay() == null && intercepts(minecraft);
        if (!intercepting) {
            // Do not let clicks that happened while the item was not held fire later.
            drain();
            return;
        }
        // In case a vanilla mouse button was already held down before the orb was taken into the hand
        // (for example continuous block breaking), keep it released while we own that button.
        // Alpha only owns the left button while the camera is active: while the orb is merely held the
        // left button has to keep working as a normal attack.
        if (SpectateClientController.isCameraActive()
                && isMouseBound(CrystalBallKeyMappings.ALPHA, GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
            minecraft.options.keyAttack.setDown(false);
        }
        if (isMouseBound(CrystalBallKeyMappings.BETA, GLFW.GLFW_MOUSE_BUTTON_RIGHT)) {
            minecraft.options.keyUse.setDown(false);
        }
        // Keyboard-bound alpha always works. A mouse-bound alpha only acts while watching; otherwise
        // the click was a normal attack and its click counter is just discarded.
        boolean alphaMouseBound = CrystalBallKeyMappings.ALPHA.getKey().getType() == InputConstants.Type.MOUSE;
        if (!alphaMouseBound || SpectateClientController.isCameraActive()) {
            while (CrystalBallKeyMappings.ALPHA.consumeClick()) {
                SpectateNetwork.sendAction(SpectateActionMessage.ACTION_ALPHA);
            }
        } else {
            while (CrystalBallKeyMappings.ALPHA.consumeClick()) {
                // Normal attack click: do not send anything.
            }
        }
        while (CrystalBallKeyMappings.BETA.consumeClick()) {
            SpectateNetwork.sendAction(SpectateActionMessage.ACTION_BETA);
        }
    }

    /**
     * The keys belong to the orb while it is held, and they also stay active while the camera is
     * already looking through somebody else - that way the player can always press alpha to come back
     * even if the orb is no longer in their hand (for example after using {@code /spectate watch}).
     */
    private static boolean intercepts(Minecraft minecraft) {
        return SpectateClientController.isCameraActive() || isHoldingCrystalBall(minecraft.player);
    }

    private static int boundAction(InputConstants.Key key) {
        if (CrystalBallKeyMappings.ALPHA.getKey().equals(key)) {
            return SpectateActionMessage.ACTION_ALPHA;
        }
        if (CrystalBallKeyMappings.BETA.getKey().equals(key)) {
            return SpectateActionMessage.ACTION_BETA;
        }
        return -1;
    }

    private static boolean isMouseBound(KeyMapping mapping, int button) {
        return mapping.getKey().getType() == InputConstants.Type.MOUSE && mapping.getKey().getValue() == button;
    }

    private static boolean isHoldingCrystalBall(Player player) {
        Item crystalBall = CrystalBallItems.CRYSTAL_BALL.get();
        return player.getMainHandItem().is(crystalBall) || player.getOffhandItem().is(crystalBall);
    }

    private static void drain() {
        while (CrystalBallKeyMappings.ALPHA.consumeClick()) {
            // discard
        }
        while (CrystalBallKeyMappings.BETA.consumeClick()) {
            // discard
        }
    }
}
