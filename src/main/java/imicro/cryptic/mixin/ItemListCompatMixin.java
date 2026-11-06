package imicro.cryptic.mixin;

import imicro.cryptic.feature.StorageOverlay;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Constructor;

/**
 * SkyBlock Item List lays itself out around the menu: its list right of the menu's bounds, its
 * favourites left of them. For the storage overlay those bounds are the overlay's own, so the list
 * sits beside the pages instead of over them. Skipped when that mod isn't installed.
 */
@Pseudo
@Mixin(targets = "com.operationpotato.itemlist.api.impl.SupportedScreenManagerImpl", remap = false)
public abstract class ItemListCompatMixin {
    private static Constructor<?> cryptic$boundsConstructor;

    @Inject(
        method = "getBounds(Lnet/minecraft/client/gui/screens/Screen;II)Lcom/operationpotato/itemlist/api/supportedscreen/ScreenBounds;",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void cryptic$storageBounds(Screen screen, int width, int height, CallbackInfoReturnable cir) {
        int[] bounds = StorageOverlay.itemListBounds(screen);
        if (bounds == null) return;
        try {
            if (cryptic$boundsConstructor == null) {
                cryptic$boundsConstructor = Class.forName("com.operationpotato.itemlist.api.supportedscreen.ScreenBounds")
                    .getConstructor(int.class, int.class);
            }
            cir.setReturnValue(cryptic$boundsConstructor.newInstance(bounds[0], bounds[1]));
        } catch (ReflectiveOperationException ignored) {
            // A changed API: the list keeps its own idea of the bounds.
        }
    }
}
