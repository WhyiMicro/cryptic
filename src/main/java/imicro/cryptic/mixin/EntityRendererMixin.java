package imicro.cryptic.mixin;

import imicro.cryptic.feature.BetterGlow;
import imicro.cryptic.feature.ClassNames;
import imicro.cryptic.feature.CustomNametags;
import imicro.cryptic.feature.HiddenMobs;
import imicro.cryptic.feature.WitherOutline;
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

/** Feeds Cryptic's own decisions into the render state an entity is drawn from. */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T extends Entity, S extends EntityRenderState> {
    /** Un-hides the dungeon mobs Hypixel spawns invisible, as they are drawn. */
    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void cryptic$revealHiddenMobs(T entity, S state, float partialTick, CallbackInfo info) {
        HiddenMobs.reveal(entity);
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

        if (state.nameTag == null) return;
        if (entity instanceof Player player && ClassNames.replacesNameTag(player)) {
            state.nameTag = null;
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
        int outlineColor = WitherOutline.outlineColorFor(entity);
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
