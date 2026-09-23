package imicro.cryptic.mixin;

import imicro.cryptic.feature.CameraTweaks;
import imicro.cryptic.feature.ClassColors;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
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

    @Shadow
    public abstract float getYRot();

    /**
     * Fixes the mouse lag while riding an entity, for Camera.
     *
     * Mojang's MC-206540, and the fix is isXander's from Debugify (LGPL-3.0) —
     * read rather than copied, since what it amounts to is turning the rider
     * with the mount in the same tick instead of a tick behind it.
     */
    @Inject(method = "onPassengerTurned", at = @At("HEAD"))
    private void cryptic$fixRidingInput(Entity passenger, CallbackInfo info) {
        if (!CameraTweaks.fixesRidingInput() || !passenger.isAlwaysTicking()) return;

        passenger.setYBodyRot(getYRot());
        float difference = Mth.wrapDegrees(passenger.getYRot() - getYRot());
        float clamped = Mth.clamp(difference, -180.0F, 180.0F);
        passenger.yRotO += clamped - difference;
        passenger.setYRot(passenger.getYRot() + clamped - difference);
        passenger.setYHeadRot(passenger.getYRot());
    }
}
