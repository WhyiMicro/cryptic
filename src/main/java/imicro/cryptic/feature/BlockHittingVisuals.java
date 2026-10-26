package imicro.cryptic.feature;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** Shared checks for Cryptic's client-only first- and third-person block pose. */
public final class BlockHittingVisuals {
    private static final boolean EXTERNAL_BLOCKING_RENDERER_PRESENT =
        FabricLoader.getInstance().isModLoaded("bring-blocking-back");

    private BlockHittingVisuals() {
    }

    /** Runs per humanoid per frame, so the cheap field checks come first. */
    private static boolean isEnabled() {
        return !EXTERNAL_BLOCKING_RENDERER_PRESENT
            && Animations.module.getEnabled()
            && Animations.blockHitting.getValue();
    }

    /** Render states are reused for every player, so the entity id has to match the local player's. */
    public static boolean isBlockingLocalPlayer(AvatarRenderState state) {
        if (!isEnabled()) return false;

        Minecraft minecraft = Minecraft.getInstance();
        Avatar localPlayer = minecraft.player;
        return localPlayer != null
            && state.id == localPlayer.getId()
            && holdsBlockingSword(minecraft, localPlayer, localPlayer.getMainHandItem());
    }

    public static boolean isBlockingLocalPlayer(AvatarRenderState state, HumanoidArm renderedArm) {
        if (!isEnabled()) return false;

        Minecraft minecraft = Minecraft.getInstance();
        Avatar localPlayer = minecraft.player;
        return localPlayer != null
            && state.id == localPlayer.getId()
            && renderedArm == localPlayer.getMainArm()
            && holdsBlockingSword(minecraft, localPlayer, localPlayer.getMainHandItem());
    }

    public static boolean isBlockingWith(LivingEntity entity, ItemStack stack) {
        return isEnabled() && holdsBlockingSword(Minecraft.getInstance(), entity, stack);
    }

    private static boolean holdsBlockingSword(Minecraft minecraft, LivingEntity entity, ItemStack stack) {
        return !stack.isEmpty()
            && stack.is(ItemTags.SWORDS)
            && entity.getOffhandItem().isEmpty()
            && minecraft.options.keyUse.isDown();
    }
}
