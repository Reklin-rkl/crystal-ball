package com.crystalball.client;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.RemotePlayer;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Client side body used as camera anchor while watching a player.
 * <p>
 * It deliberately uses a fresh UUID instead of the watched player's UUID:
 * <ul>
 *     <li>two entities with the same UUID in one client level trigger "Duplicate entity UUID" and
 *     corrupt the client's UUID index (the real player can then be replaced by the clone in UUID
 *     lookups);</li>
 *     <li>it would also be added to {@code ClientLevel#players()} as a second copy of the target.</li>
 * </ul>
 * The target's skin, cape and player model are still used by resolving {@link PlayerInfo} from the
 * watched player's UUID.
 */
public class SpectateAnchorPlayer extends RemotePlayer {

    private final UUID skinOwner;

    public SpectateAnchorPlayer(ClientLevel level, GameProfile profile, UUID skinOwner) {
        super(level, profile);
        this.skinOwner = skinOwner;
    }

    @Nullable
    @Override
    protected PlayerInfo getPlayerInfo() {
        if (Minecraft.getInstance().getConnection() == null) {
            return null;
        }
        return Minecraft.getInstance().getConnection().getPlayerInfo(skinOwner);
    }
}
