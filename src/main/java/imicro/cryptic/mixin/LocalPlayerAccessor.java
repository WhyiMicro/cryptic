package imicro.cryptic.mixin;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Where the server thinks you are and which way you face: what the client last sent. An etherwarp
 * is cast from these, so predictions use them. NoammAddons reads the same fields.
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

    /** What the client believes it last sent, so a rotation it sets itself isn't sent on. */
    @Accessor("yRotLast")
    void cryptic$setServerYRot(float yRot);

    @Accessor("xRotLast")
    void cryptic$setServerXRot(float xRot);
}
