package imicro.cryptic.mixin;

import imicro.cryptic.feature.AntiAfk;
import imicro.cryptic.feature.Freecam;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The movement keys fly Freecam's camera, so the player stands still meanwhile. */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {
    @Inject(method = "tick", at = @At("TAIL"))
    private void cryptic$freecamStill(CallbackInfo ci) {
        if (Freecam.getActive()) {
            this.keyPresses = Input.EMPTY;
            this.moveVector = Vec2.ZERO;
            return;
        }
        // Anti-AFK's jump, sneak and strafe ride on the input, not the key mappings.
        Input keys = AntiAfk.input(this.keyPresses);
        if (keys == this.keyPresses) return;
        this.keyPresses = keys;
        float left = keys.left() == keys.right() ? 0.0F : keys.left() ? 1.0F : -1.0F;
        float forward = keys.forward() == keys.backward() ? 0.0F : keys.forward() ? 1.0F : -1.0F;
        this.moveVector = new Vec2(left, forward).normalized();
    }
}
