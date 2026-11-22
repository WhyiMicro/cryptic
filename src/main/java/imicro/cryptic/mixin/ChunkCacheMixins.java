package imicro.cryptic.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import imicro.cryptic.chunkcache.ChunkSquare;
import imicro.cryptic.chunkcache.ChunkStorageSquare;
import imicro.cryptic.chunkcache.FakeChunkManager;
import imicro.cryptic.chunkcache.FakeLight;
import imicro.cryptic.chunkcache.FakeLightQueue;
import imicro.cryptic.chunkcache.FakeLightSink;
import imicro.cryptic.chunkcache.SodiumChunks;
import imicro.cryptic.feature.ChunkCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Chunk Cache: hands out its fake chunks where vanilla has none, answers for their light, and
 * copies each real chunk as vanilla lets go of it. Ported from SBO's Chunk Cache.
 */
public final class ChunkCacheMixins {
    private ChunkCacheMixins() {}

    @Mixin(ClientChunkCache.class)
    public static abstract class Chunks implements ChunkStorageSquare {
        @Shadow @Final private LevelChunk emptyChunk;

        @Shadow
        private static int calculateStorageRange(int viewRange) {
            throw new AssertionError();
        }

        // The chunks vanilla holds. Moving the centre or shrinking the square loses chunks without
        // a call to drop(), so it is followed here to copy those first.
        @Unique
        private final ChunkSquare cryptic$storage = new ChunkSquare();

        @Override
        public ChunkSquare crypticStorageSquare() {
            return cryptic$storage;
        }

        @Unique
        private FakeChunkManager cryptic$manager() {
            return ChunkCache.managerFor((ClientChunkCache) (Object) this);
        }

        @Inject(method = "<init>", at = @At("RETURN"))
        private void cryptic$initStorage(ClientLevel level, int serverChunkRadius, CallbackInfo info) {
            cryptic$storage.update(0, 0, calculateStorageRange(serverChunkRadius), null);
        }

        // Every block lookup comes through here, so a chunk vanilla has is passed on at once.
        @ModifyReturnValue(
            method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/LevelChunk;",
            at = @At("RETURN")
        )
        private LevelChunk cryptic$fakeChunk(LevelChunk original, int x, int z, ChunkStatus status, boolean orEmpty) {
            if (original != (orEmpty ? emptyChunk : null)) return original;
            FakeChunkManager manager = cryptic$manager();
            if (manager == null) return original;
            LevelChunk fake = manager.getChunk(x, z);
            return fake != null ? fake : original;
        }

        @Inject(
            method = "replaceWithPacketData",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientChunkCache$Storage;getIndex(II)I")
        )
        private void cryptic$beforeRealChunk(
            int x, int z, FriendlyByteBuf buffer, Map<Heightmap.Types, long[]> heightmaps,
            Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> blockEntities, CallbackInfoReturnable<LevelChunk> info
        ) {
            FakeChunkManager manager = cryptic$manager();
            if (manager != null) manager.unload(x, z, true);
        }

        @Inject(method = "drop", at = @At("HEAD"))
        private void cryptic$saveDroppedChunk(ChunkPos pos, CallbackInfo info) {
            FakeChunkManager manager = cryptic$manager();
            if (manager != null) manager.saveRealChunk(pos.x(), pos.z());
        }

        @Inject(method = "updateViewCenter", at = @At("HEAD"))
        private void cryptic$saveChunksBeforeMove(int x, int z, CallbackInfo info) {
            FakeChunkManager manager = cryptic$manager();
            cryptic$storage.updateCenter(x, z, manager == null ? null : manager::saveRealChunk);
        }

        @Inject(method = "updateViewRadius", at = @At("HEAD"))
        private void cryptic$saveChunksBeforeResize(int viewRange, CallbackInfo info) {
            FakeChunkManager manager = cryptic$manager();
            cryptic$storage.updateRadius(calculateStorageRange(viewRange), manager == null ? null : manager::saveRealChunk);
        }

        @Inject(method = {"drop", "updateViewCenter", "updateViewRadius"}, at = @At("RETURN"))
        private void cryptic$fakeUnloadedChunks(CallbackInfo info) {
            FakeChunkManager manager = cryptic$manager();
            if (manager != null) manager.showPending();
        }

        @Inject(
            method = "updateViewRadius",
            at = @At(
                value = "FIELD",
                target = "Lnet/minecraft/client/multiplayer/ClientChunkCache;storage:Lnet/minecraft/client/multiplayer/ClientChunkCache$Storage;",
                opcode = Opcodes.PUTFIELD,
                shift = At.Shift.AFTER
            )
        )
        private void cryptic$remarkFakeChunks(int viewRange, CallbackInfo info) {
            FakeChunkManager manager = cryptic$manager();
            if (manager != null) manager.remarkAllLoaded();
        }

        /** F3's chunk line. */
        @ModifyReturnValue(method = "gatherStats", at = @At("RETURN"))
        private String cryptic$debugStats(String original) {
            return cryptic$manager() == null ? original : original + " " + ChunkCache.debugString();
        }
    }

    /** One light layer's engine: answers for fake chunks, which it has no data for. */
    @Mixin(LightEngine.class)
    public static abstract class Light implements FakeLightSink {
        @Unique
        private volatile FakeLight cryptic$fakeLight;

        @Unique
        private boolean cryptic$skyLight;

        @Override
        public void crypticFakeLight(FakeLight light, boolean skyLight) {
            cryptic$skyLight = skyLight;
            cryptic$fakeLight = light;
        }

        @ModifyReturnValue(
            method = "getDataLayerData(Lnet/minecraft/core/SectionPos;)Lnet/minecraft/world/level/chunk/DataLayer;",
            at = @At("RETURN")
        )
        private DataLayer cryptic$fakeLayer(DataLayer original, SectionPos pos) {
            FakeLight light = cryptic$fakeLight;
            if (light == null) return original;
            DataLayer fake = light.layer(cryptic$skyLight, pos.x(), pos.y(), pos.z());
            return fake != null ? fake : original;
        }

        @ModifyReturnValue(method = "getLightValue(Lnet/minecraft/core/BlockPos;)I", at = @At("RETURN"))
        private int cryptic$fakeLevel(int original, BlockPos pos) {
            FakeLight light = cryptic$fakeLight;
            if (light == null) return original;
            DataLayer fake = light.layer(
                cryptic$skyLight,
                SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getY()),
                SectionPos.blockToSectionCoord(pos.getZ())
            );
            if (fake == null) return original;
            return fake.get(
                SectionPos.sectionRelative(pos.getX()),
                SectionPos.sectionRelative(pos.getY()),
                SectionPos.sectionRelative(pos.getZ())
            );
        }
    }

    /** Reports a fake column's light as ready; the chunk builders skip a column without. */
    @Mixin(LevelLightEngine.class)
    public static abstract class LightColumns implements FakeLightSink {
        @Unique
        private volatile FakeLight cryptic$fakeLight;

        @Override
        public void crypticFakeLight(FakeLight light, boolean skyLight) {
            cryptic$fakeLight = light;
        }

        @ModifyReturnValue(method = "lightOnInColumn", at = @At("RETURN"))
        private boolean cryptic$fakeColumnLit(boolean original, long sectionZeroNode) {
            if (original) return true;
            FakeLight light = cryptic$fakeLight;
            return light != null && light.lit(SectionPos.x(sectionZeroNode), SectionPos.z(sectionZeroNode));
        }
    }

    // After Sodium's own hooks, which is what the priority is for.
    @Mixin(value = ClientPacketListener.class, priority = 1100)
    public static abstract class Packets implements FakeLightQueue {
        @Shadow
        private ClientLevel level;

        @Unique
        private Runnable cryptic$fakeLightRemoval;

        @Override
        public void crypticQueueFakeLightRemoval(Runnable task) {
            // One that was never picked up runs now, so its light doesn't linger.
            if (cryptic$fakeLightRemoval != null) cryptic$fakeLightRemoval.run();
            cryptic$fakeLightRemoval = task;
        }

        @Override
        public void crypticFlushFakeLightRemoval() {
            Runnable removal = cryptic$fakeLightRemoval;
            cryptic$fakeLightRemoval = null;
            if (removal != null) removal.run();
        }

        // The chunk builders read light as it stands, from other threads, so the fake light has to
        // go in the very task that brings the real light. Vanilla runs its queue in batches.
        @ModifyArg(
            method = "handleLevelChunkWithLight",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;queueLightUpdate(Ljava/lang/Runnable;)V")
        )
        private Runnable cryptic$removeFakeLightWithRealLight(Runnable applyRealLight) {
            Runnable removal = cryptic$fakeLightRemoval;
            if (removal == null) return applyRealLight;
            cryptic$fakeLightRemoval = null;
            return () -> {
                removal.run();
                applyRealLight.run();
            };
        }

        // Sodium stops drawing the forgotten chunk as this method ends, after the fake one took its place.
        @Inject(method = "handleForgetLevelChunk", at = @At("RETURN"))
        private void cryptic$keepFakeChunkInSodium(ClientboundForgetLevelChunkPacket packet, CallbackInfo info) {
            if (!SodiumChunks.loaded || level == null) return;
            FakeChunkManager manager = ChunkCache.managerFor(level.getChunkSource());
            if (manager != null) manager.refresh(packet.pos().x(), packet.pos().z());
        }
    }

    // After Sodium's own hook.
    @Mixin(value = ClientLevel.class, priority = 1100)
    public static abstract class Unload {
        // Vanilla unloads a chunk that left its square late, when a fake one may already stand
        // there. It has just told the renderer the spot is empty, and Sodium is told the same here.
        @Inject(method = "unload", at = @At("HEAD"))
        private void cryptic$keepFakeChunk(LevelChunk chunk, CallbackInfo info) {
            FakeChunkManager manager = ChunkCache.managerFor(((ClientLevel) (Object) this).getChunkSource());
            if (manager != null) manager.refresh(chunk.getPos().x(), chunk.getPos().z());
        }
    }

    /** Vanilla caps the render distance at the server's; cached chunks reach as far as the player's own. */
    @Mixin(Options.class)
    public static abstract class Distance {
        @Shadow @Final
        private OptionInstance<Integer> renderDistance;

        @ModifyReturnValue(method = "getEffectiveRenderDistance", at = @At("RETURN"))
        private int cryptic$cachedDistance(int original) {
            return ChunkCache.viewDistance(original, renderDistance.get());
        }
    }

    @Mixin(Minecraft.class)
    public static abstract class Disconnect {
        @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At("HEAD"))
        private void cryptic$forgetChunks(Screen screen, boolean keepResourcePacks, boolean stopSound, CallbackInfo info) {
            ChunkCache.onDisconnect();
        }
    }
}
