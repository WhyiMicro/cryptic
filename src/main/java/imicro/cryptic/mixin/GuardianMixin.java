package imicro.cryptic.mixin;

import com.llamalad7.mixinextras.injector.WrapWithCondition;
import imicro.cryptic.feature.SlayerFeatures;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** No bubbles along a beam Slayer Features draws itself; the client spawns them on its own. */
@Mixin(Guardian.class)
public abstract class GuardianMixin {
    @WrapWithCondition(
        method = "aiStep",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V")
    )
    private boolean cryptic$keepBubbles(Level level, ParticleOptions particle, double x, double y, double z, double dx, double dy, double dz) {
        return !SlayerFeatures.hidesBeam((Guardian) (Object) this);
    }
}
