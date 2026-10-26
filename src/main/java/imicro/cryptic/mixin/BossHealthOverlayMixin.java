package imicro.cryptic.mixin;

import imicro.cryptic.feature.BloodCamp;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.world.BossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Writes a mob count into the Watcher's boss bar, for Blood Camp. Odin's hook. */
@Mixin(BossHealthOverlay.class)
public abstract class BossHealthOverlayMixin {
    @Inject(
        method = "extractBar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IILnet/minecraft/world/BossEvent;)V",
        at = @At("HEAD")
    )
    private void cryptic$watcherBar(GuiGraphicsExtractor graphics, int x, int y, BossEvent event, CallbackInfo ci) {
        BloodCamp.labelWatcherBar(event);
    }
}
