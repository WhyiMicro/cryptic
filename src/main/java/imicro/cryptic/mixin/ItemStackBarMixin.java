package imicro.cryptic.mixin;

import imicro.cryptic.feature.Mining;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A drill's fuel as its durability bar, sized and coloured the way vanilla does durability. */
@Mixin(ItemStack.class)
public abstract class ItemStackBarMixin {
    @Inject(method = "isBarVisible", at = @At("HEAD"), cancellable = true)
    private void cryptic$fuelBarVisible(CallbackInfoReturnable<Boolean> info) {
        if (Mining.fuelFraction((ItemStack) (Object) this) != null) info.setReturnValue(true);
    }

    @Inject(method = "getBarWidth", at = @At("HEAD"), cancellable = true)
    private void cryptic$fuelBarWidth(CallbackInfoReturnable<Integer> info) {
        Float fraction = Mining.fuelFraction((ItemStack) (Object) this);
        if (fraction != null) info.setReturnValue(Math.round(13.0F * fraction));
    }

    @Inject(method = "getBarColor", at = @At("HEAD"), cancellable = true)
    private void cryptic$fuelBarColor(CallbackInfoReturnable<Integer> info) {
        Float fraction = Mining.fuelFraction((ItemStack) (Object) this);
        if (fraction != null) info.setReturnValue(Mth.hsvToRgb(fraction / 3.0F, 1.0F, 1.0F));
    }
}
