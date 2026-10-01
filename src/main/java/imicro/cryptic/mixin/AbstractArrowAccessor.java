package imicro.cryptic.mixin;

import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Whether an arrow has landed.
 *
 * The flag is tracked and synced, but the getter is protected, and "has it
 * stopped moving" is not the same question — an arrow resting on the floor and
 * an arrow at the top of its arc both have a moment of standing still.
 */
@Mixin(AbstractArrow.class)
public interface AbstractArrowAccessor {
    @Invoker("isInGround")
    boolean cryptic$isInGround();
}
