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

    /**
     * These checks run once per rendered humanoid per frame, so the plain field
     * reads that decide whether the pose can apply at all are evaluated before
     * anything touches the client, an entity, or an item tag.
     */
    private static boolean isEnabled() {
        return !EXTERNAL_BLOCKING_RENDERER_PRESENT
            && Animations.module.getEnabled()
            && Animations.blockHitting.getValue();
    }

    /**
     * Render states are reused for every visible player. Comparing entity ids is
     * therefore essential: checking only for AvatarRenderState would make every
     * player use Cryptic's local block pose while the use key is held.
     */
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

    /** The same rule for one specific held stack, used by the first-person renderer. */
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
