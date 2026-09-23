package imicro.cryptic.mixin;

import imicro.cryptic.feature.ArrowFix;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drops the bow-draw state on a shortbow, for Arrow Fix.
 *
 * The use state is cleared rather than the click cancelled: the shot itself is
 * the server's, made from a click Minecraft really sent, and only the animation
 * of drawing a string that was never drawn is thrown away.
 */
@Mixin(Player.class)
public abstract class PlayerMixin extends LivingEntity {
    protected PlayerMixin(EntityType<? extends LivingEntity> type, Level level) {
        super(type, level);
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void cryptic$dropShortbowPullback(CallbackInfo info) {
        if ((Player) (Object) this != Minecraft.getInstance().player) return;
        if (!ArrowFix.isShortbow(this.useItem)) return;
        this.useItem = ItemStack.EMPTY;
        this.useItemRemaining = 0;
    }
}
