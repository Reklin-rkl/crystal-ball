package com.crystalball.client;

import com.crystalball.init.CrystalBallItems;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraftforge.client.settings.IKeyConflictContext;

/**
 * Limits when the Crystal Ball's mouse keys are active.
 * <p>
 * This is not cosmetic: Forge resolves a mouse click to exactly one {@code KeyMapping}
 * ({@code KeyMapping#click} uses {@code KeyBindingMap#lookupActive}), so a mod mapping that is
 * always active on the left mouse button can steal the click from vanilla's attack key. After
 * {@code Options#load} rebuilds the key map ({@code KeyMapping#resetMapping}, which iterates a
 * HashMap), the order is not guaranteed and the attack key may end up behind the mod mapping - the
 * player then cannot swing or attack at all, even without the orb in hand.
 * <p>
 * With these contexts the mappings are only active when they should own the button, so vanilla gets
 * the click otherwise:
 * <ul>
 *     <li>alpha (return the view): only while the camera is actually watching somebody;</li>
 *     <li>beta (cycle/start): while watching, or while the orb is held (to start watching).</li>
 * </ul>
 */
public final class CrystalBallKeyContext implements IKeyConflictContext {

    /** True when the beta mapping may own the button while the orb is held. */
    private final boolean allowWhileHolding;

    private CrystalBallKeyContext(boolean allowWhileHolding) {
        this.allowWhileHolding = allowWhileHolding;
    }

    /** Active only while the camera is watching somebody (alpha). */
    public static final CrystalBallKeyContext WATCHING_ONLY = new CrystalBallKeyContext(false);

    /** Active while watching somebody or while the Crystal Ball is held (beta). */
    public static final CrystalBallKeyContext WATCHING_OR_HOLDING = new CrystalBallKeyContext(true);

    @Override
    public boolean isActive() {
        if (SpectateClientController.isCameraActive()) {
            return true;
        }
        if (!allowWhileHolding) {
            return false;
        }
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        Item crystalBall = CrystalBallItems.CRYSTAL_BALL.get();
        return player.getMainHandItem().is(crystalBall) || player.getOffhandItem().is(crystalBall);
    }

    @Override
    public boolean conflicts(IKeyConflictContext other) {
        // Only conflict with itself: the whole point is to be inactive whenever vanilla should get
        // the button, and the controls screen should not flag a permanent conflict with attack/use.
        return this == other;
    }
}
