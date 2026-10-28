package imicro.cryptic.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.CustomData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * An item's custom data without the copy {@code copyTag()} makes. Read only: it's the item's own,
 * and Hypixel's items are read every frame for their id.
 */
@Mixin(CustomData.class)
public interface CustomDataAccessor {
    @Accessor("tag")
    CompoundTag cryptic$tag();
}
