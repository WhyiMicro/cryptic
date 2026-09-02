package imicro.cryptic.mixin;

import imicro.cryptic.feature.Animations;
import imicro.cryptic.feature.NoJumpDelay;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The local player's own living tick: swing duration for the Animations module,
 * and the jump cooldown for No Jump Delay.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin extends Entity {
    protected LivingEntityMixin(EntityType<?> type, Level level) {
        super(type, level);
    }

    @Shadow public abstract boolean hasEffect(Holder<MobEffect> effect);
    @Shadow public abstract @Nullable MobEffectInstance getEffect(Holder<MobEffect> effect);

    @Shadow private int noJumpDelay;

    /**
     * Clears the ten-tick wait vanilla puts between jumps, for the local player
     * only — every other entity here is the server's account of one, and moving
     * it would only disagree with what the server sends next.
     *
     * At the head, because the first thing {@code aiStep} does is count the
     * cooldown down and the last thing it does with it is refuse to jump unless
     * it has reached zero. Putting it back to zero before either happens is the
     * whole feature.
     */
    @Inject(method = "aiStep", at = @At("HEAD"))
    private void cryptic$clearJumpDelay(CallbackInfo ci) {
        if (!NoJumpDelay.module.getEnabled()) return;

        var player = Minecraft.getInstance().player;
        if (player == null || !this.is(player)) return;

        this.noJumpDelay = 0;
    }

    @Inject(method = "getCurrentSwingDuration", at = @At("HEAD"), cancellable = true)
    private void cryptic$adjustSwingDuration(CallbackInfoReturnable<Integer> cir) {
        if (!Animations.module.getEnabled()) return;

        var player = Minecraft.getInstance().player;
        if (player == null || !this.is(player) || player.getMainHandItem().isEmpty()) return;

        int length;
        if (Animations.ignoreHaste.getValue()) {
            length = 6;
        } else if (hasEffect(MobEffects.HASTE)) {
            var haste = getEffect(MobEffects.HASTE);
            length = 6 - (1 + (haste == null ? 0 : haste.getAmplifier()));
        } else if (hasEffect(MobEffects.MINING_FATIGUE)) {
            var fatigue = getEffect(MobEffects.MINING_FATIGUE);
            length = 6 + (1 + (fatigue == null ? 0 : fatigue.getAmplifier())) * 2;
        } else {
            length = 6;
        }

        int adjusted = (int) (length * Math.exp(-Animations.swingSpeed.getValue()));
        cir.setReturnValue(Math.max(adjusted, 1));
    }
}
