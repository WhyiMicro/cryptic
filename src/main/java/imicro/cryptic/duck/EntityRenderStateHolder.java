package imicro.cryptic.duck;

import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * The entity a render state was built from.
 *
 * Deliberately outside `imicro.cryptic.mixin`: that package is claimed whole by
 * `cryptic.mixins.json`, and Mixin refuses to let anything load a class from a
 * package it owns by direct reference. An interface a feature has to import
 * cannot live there.
 *
 * A render state is deliberately a flat copy with no way back to its entity,
 * which is right for the game and unhelpful for a feature that has to answer
 * "whose is this?" while the model is being posed. The reference is stashed as
 * the state is filled in and read back where it is needed.
 *
 * Held weakly in effect: the field is overwritten every frame the entity is
 * drawn and the state itself is owned by the renderer, so nothing here keeps an
 * entity alive past the world it belongs to.
 */
public interface EntityRenderStateHolder {
    @Nullable
    Entity cryptic$entity();

    void cryptic$setEntity(@Nullable Entity entity);
}
