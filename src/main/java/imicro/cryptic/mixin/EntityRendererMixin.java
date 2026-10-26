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
import imicro.cryptic.feature.SmoothTeleport;
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

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T extends Entity, S extends EntityRenderState> {
    /**
     * Leaves an entity undrawn: a corpse Render Optimizer is done with, or a nametag Highlight
     * doesn't want. Answered here so a tag never shows for a frame.
     */
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void cryptic$skipHiddenEntities(T entity, Frustum frustum, double x, double y, double z, CallbackInfoReturnable<Boolean> info) {
        if (RenderOptimizer.hidesEntity(entity) || Highlight.hidesNameTag(entity) || HidePlayers.hides(entity) || MageBeam.hidesSheep(entity) || LividSolver.hides(entity)) {
            info.setReturnValue(false);
        }
    }

    /**
     * Un-hides the dungeon mobs Hypixel spawns invisible, and records which entity this state came
     * from (a render state has no link back).
     */
    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void cryptic$revealHiddenMobs(T entity, S state, float partialTick, CallbackInfo info) {
        HiddenMobs.reveal(entity);
        ((EntityRenderStateHolder) state).cryptic$setEntity(entity);
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void cryptic$hideReplacedNameTag(T entity, S state, float partialTick, CallbackInfo info) {
        Component customTag = CustomNametags.tagFor(entity);
        if (customTag != null) {
            state.nameTag = customTag;
            // Vanilla never works out a tag position for the player, so it's filled in here.
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
     * Moves the name tag and shadow with a resized model; both opt-in, since a normal-sized name is
     * often the point.
     */
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void cryptic$smoothTeleportModel(T entity, S state, float partialTick, CallbackInfo info) {
        if (entity != net.minecraft.client.Minecraft.getInstance().player) return;
        net.minecraft.world.phys.Vec3 position = SmoothTeleport.playerPosition(partialTick);
        if (position == null) return;
        state.x = position.x;
        state.y = position.y;
        state.z = position.z;
    }

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

    /** Outlines the entities Cryptic wants outlined, replacing vanilla's team-colour glow. */
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
            outlineColor = imicro.cryptic.feature.SlayerFeatures.outlineColorFor(entity);
        }
        if (outlineColor == EntityRenderState.NO_OUTLINE) {
            Integer dragon = imicro.cryptic.feature.WitherDragons.outlineColorFor(entity);
            if (dragon != null) outlineColor = dragon;
        }
        if (outlineColor == EntityRenderState.NO_OUTLINE) {
            outlineColor = SpiritBear.outlineColorFor(entity);
        }
        if (outlineColor == EntityRenderState.NO_OUTLINE) {
            outlineColor = imicro.cryptic.feature.ItemOutline.outlineColorFor(entity);
        }
        if (outlineColor != EntityRenderState.NO_OUTLINE) {
            state.outlineColor = outlineColor;
        }

        // Any outline carries Better Glow's fill strength in its alpha.
        if (state.outlineColor != EntityRenderState.NO_OUTLINE) {
            state.outlineColor = BetterGlow.applyFill(state.outlineColor);
        }
    }
}
