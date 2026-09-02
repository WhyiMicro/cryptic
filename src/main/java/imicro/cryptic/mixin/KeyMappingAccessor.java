package imicro.cryptic.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads what a key mapping is currently bound to.
 *
 * {@link KeyMapping#click} is addressed by key rather than by mapping, so
 * queueing a click onto one means knowing its key — and the field is protected
 * with no getter. Reading it rather than assuming left mouse is what keeps the
 * Auto Clicker working for somebody who has rebound attack.
 */
@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {
    @Accessor("key")
    InputConstants.Key cryptic$key();
}
