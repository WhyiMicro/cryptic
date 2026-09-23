package imicro.cryptic.mixin;

import imicro.cryptic.duck.EntityRenderStateHolder;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Carries the entity on its own render state, for {@link EntityRenderStateHolder}. */
@Mixin(EntityRenderState.class)
public abstract class EntityRenderStateMixin implements EntityRenderStateHolder {
    @Unique
    @Nullable
    private Entity cryptic$entity;

    @Override
    @Nullable
    public Entity cryptic$entity() {
        return cryptic$entity;
    }

    @Override
    public void cryptic$setEntity(@Nullable Entity entity) {
        this.cryptic$entity = entity;
    }
}
