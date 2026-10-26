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

@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true)
    private void cryptic$useDungeonClassColor(CallbackInfoReturnable<Integer> cir) {
        Integer color = ClassColors.getGlowColor((Entity) (Object) this);
        if (color != null) cir.setReturnValue(color);
    }

    @Inject(method = "isCurrentlyGlowing", at = @At("HEAD"), cancellable = true)
    private void cryptic$showLocalDungeonGlow(CallbackInfoReturnable<Boolean> cir) {
        Boolean glow = ClassColors.glowOverride((Entity) (Object) this);
        if (glow != null) cir.setReturnValue(glow);
    }

    @Shadow
    public abstract float getYRot();

    /**
     * Fixes mouse lag while riding (MC-206540), for Camera: the rider turns with the mount in the
     * same tick. Debugify's fix, read rather than copied.
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
