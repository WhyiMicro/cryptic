package imicro.cryptic.duck;

import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * The entity a render state was built from, for features that need to know whose model is being
 * posed. Lives outside the mixin package because Mixin forbids loading classes from it directly.
 */
public interface EntityRenderStateHolder {
    @Nullable
    Entity cryptic$entity();

    void cryptic$setEntity(@Nullable Entity entity);
}
