package imicro.cryptic.mixin;

import imicro.cryptic.feature.CameraTweaks;
import imicro.cryptic.feature.CrosshairEditor;
import imicro.cryptic.feature.DarkMode;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The HUD's own overlays: Camera's portal swirl and the Crosshair Editor. {@code Hud} is new in
 * 26.2 (the HUD half of the old {@code Gui}).
 *
 * The crosshair is drawn right after vanilla's, so the attack indicator stays vanilla's. Vanilla's
 * sprite is hidden inside {@code blitSprite} ({@link GuiGraphicsExtractorMixin}), since editing
 * this call lost to mods that wrap it.
 */
@Mixin(Hud.class)
public abstract class HudMixin {
    private static final String CROSSHAIR_BLIT =
        "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V";

    private static final String BLIT_SPRITE = CROSSHAIR_BLIT;

    /** Hotbar's gliding selection: ordinal one is the selection box (zero is the bar). */
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "extractItemHotbar", at = @At(value = "INVOKE", target = BLIT_SPRITE, ordinal = 1))
    private void cryptic$slideSelection(GuiGraphicsExtractor graphics, com.mojang.blaze3d.pipeline.RenderPipeline pipeline, net.minecraft.resources.Identifier sprite,
                                        int x, int y, int width, int height, com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        float offset = imicro.cryptic.feature.Hotbar.selectionOffset();
        if (offset == 0f) {
            original.call(graphics, pipeline, sprite, x, y, width, height);
            return;
        }
        graphics.pose().pushMatrix();
        graphics.pose().translate(offset, 0f);
        original.call(graphics, pipeline, sprite, x, y, width, height);
        graphics.pose().popMatrix();
    }

    /** Hotbar's raised held item and slot numbers; the seed is the slot plus one. */
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
        method = "extractItemHotbar",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Hud;extractSlot(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IILnet/minecraft/client/DeltaTracker;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;I)V")
    )
    private void cryptic$liftSlot(Hud hud, GuiGraphicsExtractor graphics, int x, int y, DeltaTracker deltaTracker,
                                  net.minecraft.world.entity.player.Player player, net.minecraft.world.item.ItemStack stack, int seed,
                                  com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        float lift = imicro.cryptic.feature.Hotbar.lift(seed);
        if (lift == 0f) {
            original.call(hud, graphics, x, y, deltaTracker, player, stack, seed);
        } else {
            graphics.pose().pushMatrix();
            graphics.pose().translate(0f, -lift);
            original.call(hud, graphics, x, y, deltaTracker, player, stack, seed);
            graphics.pose().popMatrix();
        }
        imicro.cryptic.feature.Hotbar.drawNumber(graphics, x, y, seed);
    }

    /** Status Bars: the game's hearts, armour and hunger, each left out as set. */
    @Inject(method = "extractHearts", at = @At("HEAD"), cancellable = true)
    private void cryptic$hideHearts(GuiGraphicsExtractor graphics, net.minecraft.world.entity.player.Player player, int xLeft, int yLineBase,
                                    int healthRowHeight, int heartOffsetIndex, float maxHealth, int currentHealth, int oldHealth,
                                    int absorption, boolean blink, CallbackInfo info) {
        if (imicro.cryptic.feature.StatusBars.hidesHearts()) info.cancel();
    }

    @Inject(method = "extractArmor", at = @At("HEAD"), cancellable = true)
    private static void cryptic$hideArmour(GuiGraphicsExtractor graphics, net.minecraft.world.entity.player.Player player, int yLineBase,
                                           int numHealthRows, int healthRowHeight, int xLeft, CallbackInfo info) {
        if (imicro.cryptic.feature.StatusBars.hidesArmour()) info.cancel();
    }

    @Inject(method = "extractFood", at = @At("HEAD"), cancellable = true)
    private void cryptic$hideHunger(GuiGraphicsExtractor graphics, net.minecraft.world.entity.player.Player player, int yLineBase, int xRight, CallbackInfo info) {
        if (imicro.cryptic.feature.StatusBars.hidesHunger()) info.cancel();
    }

    /** Status Bars' experience number stands in for the experience bar and its level. */
    @com.llamalad7.mixinextras.injector.WrapWithCondition(
        method = "extractHotbarAndDecorations",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/contextualbar/ContextualBar;extractExperienceLevel(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;I)V")
    )
    private boolean cryptic$hideLevel(GuiGraphicsExtractor graphics, net.minecraft.client.gui.Font font, int level) {
        return !imicro.cryptic.feature.StatusBars.hidesVanillaXp();
    }

    @com.llamalad7.mixinextras.injector.WrapWithCondition(
        method = "extractHotbarAndDecorations",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/contextualbar/ContextualBar;extractBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V")
    )
    private boolean cryptic$hideXpBarBack(net.minecraft.client.gui.contextualbar.ContextualBar bar, GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        return !(bar instanceof net.minecraft.client.gui.contextualbar.ExperienceBar && imicro.cryptic.feature.StatusBars.hidesVanillaXp());
    }

    @com.llamalad7.mixinextras.injector.WrapWithCondition(
        method = "extractHotbarAndDecorations",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/contextualbar/ContextualBar;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V")
    )
    private boolean cryptic$hideXpBar(net.minecraft.client.gui.contextualbar.ContextualBar bar, GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        return !(bar instanceof net.minecraft.client.gui.contextualbar.ExperienceBar && imicro.cryptic.feature.StatusBars.hidesVanillaXp());
    }

    /** Every action bar passes Status Bars first: it reads the stats and takes them off. */
    @org.spongepowered.asm.mixin.injection.ModifyVariable(method = "setOverlayMessage", at = @At("HEAD"), argsOnly = true)
    private net.minecraft.network.chat.Component cryptic$tidyActionBar(net.minecraft.network.chat.Component message) {
        return imicro.cryptic.feature.StatusBars.onActionBar(message);
    }

    /** Custom Scoreboard draws its own sidebar. */
    @Inject(method = "extractScoreboardSidebar", at = @At("HEAD"), cancellable = true)
    private void cryptic$hideSidebar(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo info) {
        if (imicro.cryptic.feature.CustomScoreboard.replacesSidebar()) info.cancel();
    }
    /** Item Protection's mark on a protected hotbar item, under it and over it. */
    @Inject(method = "extractSlot", at = @At("HEAD"))
    private void cryptic$markUnderSlot(GuiGraphicsExtractor graphics, int x, int y, DeltaTracker deltaTracker,
                                       net.minecraft.world.entity.player.Player player, net.minecraft.world.item.ItemStack stack, int seed, CallbackInfo info) {
        imicro.cryptic.feature.ItemProtection.drawSlot(graphics, stack, x, y, false, true);
    }

    @Inject(method = "extractSlot", at = @At("RETURN"))
    private void cryptic$markOverSlot(GuiGraphicsExtractor graphics, int x, int y, DeltaTracker deltaTracker,
                                      net.minecraft.world.entity.player.Player player, net.minecraft.world.item.ItemStack stack, int seed, CallbackInfo info) {
        imicro.cryptic.feature.ItemProtection.drawSlot(graphics, stack, x, y, true, true);
    }

    /** Better Chat's peek key: the closed chat drawn as an open one, every line fully shown. */
    @org.spongepowered.asm.mixin.injection.ModifyArg(
        method = "extractChat",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIILnet/minecraft/client/gui/components/ChatComponent$DisplayMode;Z)V"),
        index = 5
    )
    private net.minecraft.client.gui.components.ChatComponent.DisplayMode cryptic$peekChat(net.minecraft.client.gui.components.ChatComponent.DisplayMode mode) {
        return imicro.cryptic.feature.BetterChat.peeking() ? net.minecraft.client.gui.components.ChatComponent.DisplayMode.FOREGROUND : mode;
    }

    @Inject(method = "extractBossOverlay", at = @At("HEAD"), cancellable = true)
    private void cryptic$hideBossBar(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo info) {
        if (imicro.cryptic.feature.RenderOptimizer.hidesBossBar()) info.cancel();
    }

    @Inject(method = "extractVignette", at = @At("HEAD"), cancellable = true)
    private void cryptic$hideVignette(GuiGraphicsExtractor graphics, net.minecraft.world.entity.Entity camera, CallbackInfo info) {
        if (imicro.cryptic.feature.RenderOptimizer.hidesVignette()) info.cancel();
    }

    @Inject(method = "extractEffects", at = @At("HEAD"), cancellable = true)
    private void cryptic$hideEffectIcons(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo info) {
        if (imicro.cryptic.feature.RenderOptimizer.hidesEffectIcons()) info.cancel();
    }

    @Inject(method = "extractPortalOverlay", at = @At("HEAD"), cancellable = true)
    private void cryptic$hidePortalOverlay(GuiGraphicsExtractor graphics, float alpha, CallbackInfo info) {
        if (CameraTweaks.hidesPortalOverlay()) info.cancel();
    }

    /** Ordinal zero is the crosshair; one and two are the attack indicator. */
    @Inject(method = "extractCrosshair", at = @At(value = "INVOKE", target = CROSSHAIR_BLIT, ordinal = 0, shift = At.Shift.AFTER))
    private void cryptic$drawCustomCrosshair(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo info) {
        CrosshairEditor.draw(graphics);
    }

    /** Vanilla returns early in third person; this covers that case. */
    @Inject(method = "extractCrosshair", at = @At("HEAD"))
    private void cryptic$drawThirdPersonCrosshair(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo info) {
        CrosshairEditor.drawThirdPerson(graphics);
    }
    /**
     * Dark Mode's sheet of black, at the start of the HUD pass (only the world) or the end
     * (everything). The same points NoammAddons uses.
     */
    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void cryptic$darkenWorld(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo info) {
        DarkMode.drawUnderHud(graphics);
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void cryptic$darkenHud(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo info) {
        DarkMode.drawOverHud(graphics);
    }
}
