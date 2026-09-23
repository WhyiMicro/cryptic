package imicro.cryptic.mixin;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Where the server thinks you are, and which way it thinks you are facing.
 *
 * These are the values the client last sent, kept so it can tell whether
 * anything has changed since. They matter to anything predicting what the
 * server is about to do: an etherwarp is cast from the server's idea of you,
 * not from the client's, and while you are moving the two are a fraction of a
 * block apart — enough for a predicted landing to be visibly off the one that
 * arrives.
 *
 * NoammAddons (CC0) reads the same five fields for the same reason.
 */
@Mixin(LocalPlayer.class)
public interface LocalPlayerAccessor {
    @Accessor("xLast")
    double cryptic$serverX();

    @Accessor("yLast")
    double cryptic$serverY();

    @Accessor("zLast")
    double cryptic$serverZ();

    @Accessor("yRotLast")
    float cryptic$serverYRot();

    @Accessor("xRotLast")
    float cryptic$serverXRot();
}
