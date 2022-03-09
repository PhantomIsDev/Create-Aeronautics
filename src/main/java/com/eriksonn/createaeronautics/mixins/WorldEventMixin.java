package com.eriksonn.createaeronautics.mixins;

import com.eriksonn.createaeronautics.contraptions.AirshipManager;
import com.eriksonn.createaeronautics.dimension.AirshipDimensionManager;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.profiler.IProfiler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.DimensionType;
import net.minecraft.world.IWorld;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.listener.IChunkStatusListener;
import net.minecraft.world.gen.ChunkGenerator;
import net.minecraft.world.server.ServerWorld;
import net.minecraft.world.spawner.ISpecialSpawner;
import net.minecraft.world.storage.IServerWorldInfo;
import net.minecraft.world.storage.ISpawnWorldInfo;
import net.minecraft.world.storage.SaveFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

@Mixin(World.class)
public abstract class WorldEventMixin {

    @Inject(method = "markAndNotifyBlock", remap = false, at = @At("HEAD"))
    public void markAndNotifyBlock(BlockPos pos, @Nullable Chunk chunk, BlockState blockstate, BlockState pState, int pFlags, int pRecursionLeft, CallbackInfo ci) {
        if (((Object) this) instanceof ServerWorld && ((ServerWorld) (Object) this).dimension() == AirshipDimensionManager.WORLD_ID) {
            int id = AirshipManager.getIdFromPlotPos(pos);
            try {
                AirshipManager.INSTANCE.AllAirships.get(id).stcHandleBlockUpdate(pos.offset(-64, -64, -64));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

}

