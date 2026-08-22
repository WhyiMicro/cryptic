package imicro.cryptic.mixin;

import imicro.cryptic.feature.ClassColors;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Recolors only known dungeon teammates when vanilla requests their glow color. */
@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true)
    private void cryptic$useDungeonClassColor(CallbackInfoReturnable<Integer> cir) {
        Integer color = ClassColors.getGlowColor((Entity) (Object) this);
        if (color != null) cir.setReturnValue(color);
    }

    @Inject(method = "isCurrentlyGlowing", at = @At("HEAD"), cancellable = true)
    private void cryptic$showLocalDungeonGlow(CallbackInfoReturnable<Boolean> cir) {
        if (ClassColors.shouldForceSelfGlow((Entity) (Object) this)) cir.setReturnValue(true);
    }
}
