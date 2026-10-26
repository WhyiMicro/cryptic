package imicro.cryptic.mixin;

import java.util.Comparator;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Two things the tab list overlay keeps to itself: the row order it draws in (Hypixel writes
 * headings with their values on the row below, which only works in display order), and the footer,
 * which has no getter.
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
