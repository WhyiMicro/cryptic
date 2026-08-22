package imicro.cryptic.mixin;

import imicro.cryptic.feature.BetterGlow;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Lets Cryptic answer with its own post-effect chain when one is asked for. */
@Mixin(ShaderManager.class)
public abstract class ShaderManagerMixin {
    @ModifyVariable(method = "getPostChain", at = @At("HEAD"), argsOnly = true)
    private Identifier cryptic$replaceChain(Identifier id) {
        return BetterGlow.chainFor(id);
    }
}
