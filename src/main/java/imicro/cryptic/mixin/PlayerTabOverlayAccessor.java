package imicro.cryptic.mixin;

import java.util.Comparator;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Two things the tab list overlay keeps to itself.
 *
 * The order rows are drawn in is the important one. Hypixel writes things into
 * the tab list that exist nowhere else a client can reach — the booster
 * cookie's remaining time among them — and writes them as a heading with the
 * value on the row below it. "The row below" only means anything in display
 * order, and the connection hands its players out in the order they happened to
 * connect; this comparator is what turns one into the other.
 *
 * The footer is a second, older place the same information sometimes appears,
 * and it has a setter and no getter.
 */
@Mixin(PlayerTabOverlay.class)
public interface PlayerTabOverlayAccessor {
    @Accessor("PLAYER_COMPARATOR")
    static Comparator<PlayerInfo> cryptic$ordering() {
        throw new UnsupportedOperationException();
    }

    @Accessor("footer")
    Component cryptic$footer();
}
