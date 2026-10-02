package imicro.cryptic.mixin;

import imicro.cryptic.duck.EntityRenderStateHolder;
import imicro.cryptic.feature.BetterGlow;
import imicro.cryptic.feature.ClassNames;
import imicro.cryptic.feature.CustomNametags;
import imicro.cryptic.feature.CustomScale;
import imicro.cryptic.feature.HiddenMobs;
import imicro.cryptic.feature.HidePlayers;
import imicro.cryptic.feature.MageBeam;
import imicro.cryptic.feature.LividSolver;
import imicro.cryptic.feature.SpiritBear;
import imicro.cryptic.feature.CarryManager;
import imicro.cryptic.feature.Highlight;
import imicro.cryptic.feature.RenderOptimizer;
import imicro.cryptic.feature.WitherOutline;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Feeds Cryptic's own decisions into the render state an entity is drawn from. */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T extends Entity, S extends EntityRenderState> {
    /**
     * Leaves an entity undrawn: a corpse Render Optimizer has finished with,
     * or the nametag of a mob Highlight is not interested in. Refusing to
     * render is cheaper than rendering it invisibly, and answering here rather
     * than deleting the entity on a tick means a tag never shows for a frame
     * before it goes.
     */
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void cryptic$skipHiddenEntities(T entity, Frustum frustum, double x, double y, double z, CallbackInfoReturnable<Boolean> info) {
        if (RenderOptimizer.hidesEntity(entity) || Highlight.hidesNameTag(entity) || HidePlayers.hides(entity) || MageBeam.hidesSheep(entity) || LividSolver.hides(entity)) {
            info.setReturnValue(false);
        }
    }

    /**
     * Un-hides the dungeon mobs Hypixel spawns invisible, as they are drawn,
     * and notes which entity this state was built from.
     *
     * The note is what lets a feature posing a model know whose model it is: a
     * render state is a flat copy with no way back to its entity, and the
     * model-setup call it ends up in is given nothing else.
     */
    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void cryptic$revealHiddenMobs(T entity, S state, float partialTick, CallbackInfo info) {
        HiddenMobs.reveal(entity);
        ((EntityRenderStateHolder) state).cryptic$setEntity(entity);
    }

    /** Drops the vanilla name tag for players whose label Cryptic draws itself. */
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void cryptic$hideReplacedNameTag(T entity, S state, float partialTick, CallbackInfo info) {
        Component customTag = CustomNametags.tagFor(entity);
        if (customTag != null) {
            state.nameTag = customTag;
            // Vanilla only works out where a tag hangs for entities it decided
            // to name, and the player is never one of them, so the attachment
            // it left empty has to be filled in as well.
            if (state.nameTagAttachment == null) {
                state.nameTagAttachment = entity.getAttachments()
                    .getNullable(EntityAttachment.NAME_TAG, 0, entity.getYRot(partialTick));
            }
            return;
        }

        if (Highlight.hidesOwnNameTag(entity)) {
            state.nameTag = null;
            return;
        }

        if (state.nameTag == null) return;
        if (entity instanceof Player player && ClassNames.replacesNameTag(player)) {
            state.nameTag = null;
        }
    }

    /**
     * Moves the two things pinned to a resized model with it.
     *
     * The name tag hangs at a height worked out from the real model and the
     * shadow is cast at the real model's width, so neither follows a scale
     * applied to the model alone — a shrunk player wears their name a body
     * length above their head until this runs. Both are opt-in, because a name
     * that keeps its own size is often the point of shrinking somebody.
     */
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void cryptic$scaleAttachments(T entity, S state, float partialTick, CallbackInfo info) {
        if (!CustomScale.appliesTo(entity)) return;

        float scale = CustomScale.factor();
        if (CustomScale.scalesNametags() && state.nameTagAttachment != null) {
            state.nameTagAttachment = state.nameTagAttachment.scale(scale);
        }
        if (CustomScale.scalesShadow()) {
            state.shadowRadius *= scale;
        }
    }

    /**
     * Outlines the entities Cryptic wants outlined.
     *
     * Vanilla has just filled this in from the entity's team color, and only
     * for entities the server is making glow, so the assignment is replaced
     * rather than added to.
     */
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void cryptic$applyCustomOutline(T entity, S state, float partialTick, CallbackInfo info) {
        if (RenderOptimizer.hidesEntityFire()) {
            state.displayFireAnimation = false;
        }

        int outlineColor = WitherOutline.outlineColorFor(entity);
        if (outlineColor == EntityRenderState.NO_OUTLINE) {
            outlineColor = Highlight.outlineColorFor(entity);
        }
        if (outlineColor == EntityRenderState.NO_OUTLINE) {
            outlineColor = CarryManager.outlineColorFor(entity);
        }
        if (outlineColor == EntityRenderState.NO_OUTLINE) {
            outlineColor = SpiritBear.outlineColorFor(entity);
        }
        if (outlineColor != EntityRenderState.NO_OUTLINE) {
            state.outlineColor = outlineColor;
        }

        // Whatever ends up outlined, ours or the server's, carries Better
        // Glow's fill strength in the alpha the outline pass reads.
        if (state.outlineColor != EntityRenderState.NO_OUTLINE) {
            state.outlineColor = BetterGlow.applyFill(state.outlineColor);
        }
    }
}
