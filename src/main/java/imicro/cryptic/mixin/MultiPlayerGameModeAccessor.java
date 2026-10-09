package imicro.cryptic.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The pause between instant breaks, and the send of the held slot, for Breaker
 * Helper's Zero ping.
 */
@Mixin(MultiPlayerGameMode.class)
public interface MultiPlayerGameModeAccessor {
    @Accessor("destroyDelay")
    void cryptic$setDestroyDelay(int ticks);

    @Invoker("ensureHasSentCarriedItem")
    void cryptic$ensureHasSentCarriedItem();
}
