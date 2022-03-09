package com.eriksonn.createaeronautics.contraptions;

import com.eriksonn.createaeronautics.blocks.airship_assembler.AirshipAssemblerTileEntity;
import com.eriksonn.createaeronautics.dimension.AirshipDimensionManager;
import com.eriksonn.createaeronautics.index.CAEntityTypes;
import com.eriksonn.createaeronautics.mixins.ContraptionHolderAccessor;
import com.eriksonn.createaeronautics.network.NetworkMain;
import com.eriksonn.createaeronautics.network.packet.*;
import com.eriksonn.createaeronautics.utils.AbstractContraptionEntityExtension;
import com.eriksonn.createaeronautics.utils.Matrix3dExtension;
import com.eriksonn.createaeronautics.world.FakeAirshipClientWorld;
import com.jozufozu.flywheel.util.transform.MatrixTransformStack;
import com.mojang.blaze3d.matrix.MatrixStack;
import com.simibubi.create.AllMovementBehaviours;
import com.simibubi.create.content.contraptions.components.structureMovement.*;
import com.simibubi.create.content.contraptions.components.structureMovement.render.ContraptionRenderDispatcher;
import com.simibubi.create.foundation.collision.Matrix3d;
import com.simibubi.create.foundation.utility.AngleHelper;
import com.simibubi.create.foundation.utility.VecHelper;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.command.impl.data.EntityDataAccessor;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.nbt.*;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.ITickableTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityType;
import net.minecraft.util.Direction;
import net.minecraft.util.Hand;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Quaternion;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.Difficulty;
import net.minecraft.world.DimensionType;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fml.network.PacketDistributor;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;


public class AirshipContraptionEntity extends AbstractContraptionEntity {

    float time = 0;

    public Quaternion quat = Quaternion.ONE;
    public Vector3d velocity;
    public AirshipContraption airshipContraption;
    public int plotId = 0;
    public PhysicsManager physicsManager;
    Map<BlockPos, BlockState> sails;
    public Map<UUID, ControlledContraptionEntity> subContraptions = new HashMap<>();
    public Vector3d centerOfMassOffset = Vector3d.ZERO;
    public static final DataParameter<CompoundNBT> physicsDataAccessor = EntityDataManager.defineId(AirshipContraptionEntity.class, DataSerializers.COMPOUND_TAG);

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(physicsDataAccessor, new CompoundNBT());
    }

    public AirshipContraptionEntity(EntityType<?> type, World world) {
        super(type, world);
        sails = new HashMap<>();
        physicsManager = new PhysicsManager(this);
        System.out.println("New airship entity");
    }

    public static AirshipContraptionEntity create(World world, AirshipContraption contraption) {
        AirshipContraptionEntity entity = new AirshipContraptionEntity((EntityType) CAEntityTypes.AIRSHIP_CONTRAPTION.get(), world);
        entity.setContraption(contraption);

        entity.airshipContraption = contraption;
        AirshipManager.INSTANCE.tryAddEntity(0, entity);

        return entity;

    }

    // Whether or not this contraption has been airshipInitialized
    public boolean airshipInitialized = false;

    public boolean invalid = false;
    public boolean syncNextTick = false;

    @Override
    public void tickContraption() {
        AirshipAssemblerTileEntity controller = getController();
        airshipContraption = (AirshipContraption) contraption;

        if (controller != null)
            controller.attach(this);

        physicsManager.tick();

        if (!airshipInitialized) {
            initFakeClientWorld();
        }

        if (level.isClientSide) {
            profiler.startTick();
            fakeClientWorld.tick(() -> true);
            fakeClientWorld.tickEntities();

            for(ControlledContraptionEntity contraptionEntity : subContraptions.values()) {
                contraptionEntity.tick();
            }

            profiler.endTick();

            if (invalid) {
                ContraptionRenderDispatcher.invalidate(airshipContraption);
                invalid = false;
            }
        }

        if (!airshipInitialized) {
            airshipInitialized = true;
            syncNextTick = true;
        }

        if (syncNextTick) {
            syncPacket();
            syncPacket();
            syncNextTick = false;
        }



        contraption.getContraptionWorld().tickBlockEntities();


        if(!level.isClientSide) {
            serverUpdate();
        }
        //Vector3d particlePos = toGlobalVector(new Vector3d(0,0,0),0);
        //level.addParticle(new RedstoneParticleData(1,1,1,1),particlePos.x,particlePos.y,particlePos.z,0,0,0);
        //this.getContraption().getContraptionWorld().tickBlockEntities();

    }

    private void putDoubleArray(CompoundNBT tag, String key, double[] array) {
        ListNBT list = new ListNBT();
        for (double d : array) {
            list.add(DoubleNBT.valueOf(d));
        }
        tag.put(key, list);
    }

    private double[] readDoubleArray(CompoundNBT tag, String key) {
        INBT[] boxed = tag.getList(key, Constants.NBT.TAG_DOUBLE).toArray(new INBT[0]);
        double[] unboxed = new double[boxed.length];
        for(int i = 0; i < boxed.length; i++) {
            unboxed[i] = ((DoubleNBT) boxed[i]).getAsDouble();
        }
        return unboxed;
    }

    @Override
    public void onSyncedDataUpdated(DataParameter<?> pKey) {
        if (pKey == physicsDataAccessor) {
            CompoundNBT tag = this.entityData.get((DataParameter<CompoundNBT>) pKey);

            physicsManager.globalVelocity = physicsManager.arrayToVec(readDoubleArray(tag, "velocity"));
            physicsManager.angularMomentum = physicsManager.arrayToVec(readDoubleArray(tag, "angularMomentum"));
            physicsManager.orientation = physicsManager.arrayToQuat(readDoubleArray(tag, "orientation"));
            physicsManager.principalRotation = physicsManager.arrayToQuat(readDoubleArray(tag, "principalRotation"));
            physicsManager.principalInertia = readDoubleArray(tag, "principalInertia");
        }
    }

    @Override
    public void onRemovedFromWorld() {
        subContraptions.forEach((uuid, contraptionEntity) -> {
            contraptionEntity.disassemble();
            serverDestroySubContraption(contraptionEntity);
        });
        super.onRemovedFromWorld();
    }

    FakeAirshipClientWorld fakeClientWorld;

    public void serverUpdate() {
        // stcDestroySubContraption and remove from the hashmap all subcontraptions that arent alive
        Set<UUID> keyset = subContraptions.keySet();

        for (UUID uuid : keyset) {
            ControlledContraptionEntity subContraption = subContraptions.get(uuid);
            if (!subContraption.isAlive()) {
                subContraptions.remove(uuid);
                serverDestroySubContraption(subContraption);
            } else {
                serverUpdateSubContraption(subContraption);
            }
        }

        CompoundNBT tag = new CompoundNBT();
        putDoubleArray(tag, "velocity", physicsManager.vecToArray(physicsManager.globalVelocity));
        putDoubleArray(tag, "angularMomentum", physicsManager.vecToArray(physicsManager.angularMomentum));
        putDoubleArray(tag, "orientation", physicsManager.quatToArray(physicsManager.orientation));
        putDoubleArray(tag, "principalRotation", physicsManager.quatToArray(physicsManager.principalRotation));
        putDoubleArray(tag, "principalInertia", physicsManager.principalInertia);
        this.entityData.set(physicsDataAccessor, tag);
    }

    private void serverDestroySubContraption(ControlledContraptionEntity subContraption) {
        notifyClients(new AirshipDestroySubcontraptionPacket(plotId, subContraption.getUUID()));
    }

    public void serverUpdateSubContraption(ControlledContraptionEntity subContraption) {
        notifyClients(new AirshipUpdateSubcontraptionPacket(plotId, subContraption.serializeNBT(), subContraption.getUUID()));
    }

    public void initFakeClientWorld() {
        if (level.isClientSide) {
            profiler = new Profiler(() -> 0, () -> 0, false);
            RegistryKey<World> dimension = level.dimension();
            DimensionType dimensionType = level.dimensionType();
            ClientWorld.ClientWorldInfo clientWorldInfo = new ClientWorld.ClientWorldInfo(Difficulty.PEACEFUL, false, true);
            fakeClientWorld = new FakeAirshipClientWorld(
                    this,
                    Minecraft.getInstance().getConnection(),
                    clientWorldInfo,
                    dimension,
                    dimensionType,
                    0, () -> profiler,
                    null, false, 0
            );
            AirshipManager.INSTANCE.AllClientAirships.put(plotId, this);

            airshipContraption.maybeInstancedTileEntities.clear();
            airshipContraption.specialRenderedTileEntities.clear();
            airshipContraption.presentTileEntities.clear();
        }
    }

    public void syncPacket() {
        if (!level.isClientSide) {

            // iterate over all non air blocks in a 10 radius
            for (int x = -10; x < 10; x++) {
                for (int y = -10; y < 10; y++) {
                    for (int z = -10; z < 10; z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        ServerWorld serverLevel = AirshipDimensionManager.INSTANCE.getWorld();

                        BlockState state = serverLevel.getBlockState(pos.offset(AirshipManager.getPlotPosFromId(plotId)));
                        if (!state.getBlock().is(Blocks.AIR)) {
                            TileEntity te = serverLevel.getBlockEntity(pos.offset(AirshipManager.getPlotPosFromId(plotId)));
                            if (te instanceof ITickableTileEntity) {
                                ((ITickableTileEntity) te).tick();
                            }
                            stcHandleBlockUpdate(pos);
                        }
                    }
                }
            }
        }
    }

    public void stcHandleBlockUpdate(BlockPos localPos) {
        if (!airshipInitialized) return;
        BlockPos plotPos = AirshipManager.getPlotPosFromId(plotId);

        // Server level!
        ServerWorld serverLevel = AirshipDimensionManager.INSTANCE.getWorld();

        // get block state
        BlockPos pos = plotPos.offset(localPos);
        BlockState state = serverLevel.getBlockState(pos);

        CompoundNBT thisBlockNBT = new CompoundNBT();


        thisBlockNBT.putInt("x", pos.getX() - plotPos.getX());
        thisBlockNBT.putInt("y", pos.getY());
        thisBlockNBT.putInt("z", pos.getZ() - plotPos.getZ());
        thisBlockNBT.put("state", NBTUtil.writeBlockState(state));

        TileEntity blockEntity = state.hasTileEntity() ? serverLevel.getBlockEntity(pos) : null;
        if (blockEntity != null) {
            thisBlockNBT.put("be", blockEntity.serializeNBT());
            addTileData(blockEntity, pos.offset(-plotPos.getX(), -plotPos.getY(), -plotPos.getZ()), state);
            handleControllingSubcontraption(blockEntity, pos);
        }

        thisBlockNBT.putInt("plotId", plotId);

        AirshipContraptionBlockUpdatePacket packet = new AirshipContraptionBlockUpdatePacket(thisBlockNBT);
        notifyClients(packet);

        airshipContraption.setBlockState(localPos, state, blockEntity);
    }

    private void notifyClients(Object packet) {
        NetworkMain.CHANNEL.send(PacketDistributor.TRACKING_CHUNK.with(() -> level.getChunkAt(
                new BlockPos(position())
        )), packet);
    }

    public AirshipAssemblerTileEntity getController() {
        BlockPos controllerPos = AirshipManager.getPlotPosFromId(plotId);
        World w = AirshipDimensionManager.INSTANCE.getWorld();
        if (!w.isLoaded(controllerPos))
            return null;
        TileEntity te = w.getBlockEntity(controllerPos);
        if (!(te instanceof AirshipAssemblerTileEntity))
            return null;
        return (AirshipAssemblerTileEntity) te;
    }


    @Override
    public void readSpawnData(PacketBuffer additionalData) {
        readAdditional(additionalData.readNbt(), true);
    }

    @Override
    protected void readAdditional(CompoundNBT compound, boolean spawnPacket) {
        super.readAdditional(compound, spawnPacket);
        plotId = compound.getInt("PlotId");
        physicsManager.readAdditional(compound, spawnPacket);
    }

    @Override
    protected void writeAdditional(CompoundNBT compound, boolean spawnPacket) {
        super.writeAdditional(compound, spawnPacket);
        compound.putInt("PlotId", plotId);
        physicsManager.writeAdditional(compound, spawnPacket);
    }

    @Override
    public AirshipRotationState getRotationState() {
        AirshipRotationState crs = new AirshipRotationState();
        crs.matrix = new Matrix3d();
        Vector3d I = PhysicsManager.rotateQuatReverse(new Vector3d(1, 0, 0), quat);
        Vector3d J = PhysicsManager.rotateQuatReverse(new Vector3d(0, 1, 0), quat);
        Vector3d K = PhysicsManager.rotateQuatReverse(new Vector3d(0, 0, 1), quat);
        ((Matrix3dExtension) crs.matrix).createaeronautics$set(I, J, K);
        crs.matrix.transpose();
        return crs;
    }

    public Vector3d reverseRotation(Vector3d localPos, float partialTicks) {
        return PhysicsManager.rotateQuatReverse(localPos, physicsManager.getPartialOrientation(partialTicks));
    }

    public Vector3d applyRotation(Vector3d localPos, float partialTicks) {
        return PhysicsManager.rotateQuat(localPos, physicsManager.getPartialOrientation(partialTicks));
    }

    public Vector3d toGlobalVector(Vector3d localVec, float partialTicks) {
        Vector3d rotationOffset = VecHelper.getCenterOf(BlockPos.ZERO);
        localVec = localVec.subtract(rotationOffset).subtract(centerOfMassOffset);
        //localVec = localVec.subtract(rotationOffset);
        localVec = applyRotation(localVec, partialTicks);
        localVec = localVec.add(rotationOffset)
                .add(getAnchorVec());
        return localVec;
    }

    public Vector3d toLocalVector(Vector3d globalVec, float partialTicks) {
        Vector3d rotationOffset = VecHelper.getCenterOf(BlockPos.ZERO);
        globalVec = globalVec.subtract(getAnchorVec())
                .subtract(rotationOffset);
        globalVec = reverseRotation(globalVec, partialTicks);
        globalVec = globalVec.add(rotationOffset);
        //return globalVec;
        return globalVec.add(centerOfMassOffset);
    }

    protected StructureTransform makeStructureTransform() {
        BlockPos offset = new BlockPos(this.getAnchorVec().subtract(centerOfMassOffset));
        return new StructureTransform(offset, 0.0F, 0, 0.0F);
    }

    @Override
    public Vector3d getAnchorVec() {
        return position();
    }

    protected float getStalledAngle() {
        return 0.0f;
    }

    protected void handleStallInformation(float x, float y, float z, float angle) {

    }

    public boolean handlePlayerInteraction2(PlayerEntity player, BlockPos localPos, Direction side,
                                            Hand interactionHand) {
        return true;
    }

    public boolean handlePlayerInteraction(PlayerEntity player, BlockPos localPos, Direction side,
                                           Hand interactionHand) {
        int indexOfSeat = contraption.getSeats()
                .indexOf(localPos);
        if (indexOfSeat == -1 && player instanceof ServerPlayerEntity) {
            BlockPos dimensionPos = localPos.offset(AirshipManager.getPlotPosFromId(plotId));
            World worldIn = AirshipDimensionManager.INSTANCE.getWorld();
            BlockState state = worldIn.getBlockState(dimensionPos);

            try {
                state.getBlock().use(state, worldIn, dimensionPos, player, interactionHand, null);
            } catch (Exception e) {

            }
            return true;
        }
        // Eject potential existing passenger
        Entity toDismount = null;
        for (Map.Entry<UUID, Integer> entry : contraption.getSeatMapping()
                .entrySet()) {
            if (entry.getValue() != indexOfSeat)
                continue;
            for (Entity entity : getPassengers()) {
                if (!entry.getKey()
                        .equals(entity.getUUID()))
                    continue;
                if (entity instanceof PlayerEntity)
                    return false;
                toDismount = entity;
            }
        }

        if (toDismount != null && !level.isClientSide) {
            Vector3d transformedVector = getPassengerPosition(toDismount, 1);
            toDismount.stopRiding();
            if (transformedVector != null)
                toDismount.teleportTo(transformedVector.x, transformedVector.y, transformedVector.z);
        }

        if (level.isClientSide)
            return true;
        addSittingPassenger(player, indexOfSeat);
        return true;
    }

    @OnlyIn(Dist.CLIENT)
    public void doLocalTransforms(float partialTicks, MatrixStack[] matrixStacks) {
        float angleInitialYaw = 0.0f;
        float angleYaw = this.getViewYRot(partialTicks);
        float anglePitch = this.getViewXRot(partialTicks);
        //angleYaw=anglePitch=0;
        MatrixStack[] var6 = matrixStacks;
        int var7 = matrixStacks.length;

        int var8;
        Quaternion Q = physicsManager.getPartialOrientation(partialTicks);
        Q.conj();
        for (var8 = 0; var8 < var7; ++var8) {
            MatrixStack stack = var6[var8];
            stack.translate(0.5, 0.5, 0.5);
            stack.mulPose(Q);
            stack.translate(-centerOfMassOffset.x, -centerOfMassOffset.y, -centerOfMassOffset.z);
            stack.translate(-0.5, -0.5, -0.5);
            //stack.translate(-0.5D, 0.0D, -0.5D);
        }

        MatrixStack[] var12 = matrixStacks;
        var8 = matrixStacks.length;
        //Quaternion conj = currentQuaternion.copy();
        //conj.conj();
        for (int var13 = 0; var13 < var8; ++var13) {
            MatrixStack stack = var12[var13];

            //MatrixTransformStack.of(stack).nudge(this.getId()).centre().rotateY((double)angleYaw).rotateZ((double)anglePitch).rotateY((double)angleInitialYaw).multiply(CurrentAxis,Math.toDegrees(CurrentAxisAngle)).unCentre();
            MatrixTransformStack.of(stack).nudge(this.getId()).centre().rotateY((double) angleYaw).rotateZ((double) anglePitch).rotateY((double) angleInitialYaw).unCentre();
        }

    }

    Profiler profiler;

    public void addTileData(TileEntity te, BlockPos pos, BlockState state) {
        Block block = state.getBlock();
        MovementBehaviour movementBehaviour = AllMovementBehaviours.of(block);

        if (te == null)
            return;

        te.getBlockState();

        if (movementBehaviour == null || !movementBehaviour.hasSpecialInstancedRendering()) {
            if (!airshipContraption.maybeInstancedTileEntities.contains(te)) {
                for (int i = 0; i < airshipContraption.maybeInstancedTileEntities.size(); i++) {
                    if (airshipContraption.maybeInstancedTileEntities.get(i).getBlockPos().offset(0, -64, 0).equals(pos)) {
                        airshipContraption.maybeInstancedTileEntities.remove(i);
                        i--;
                    }
                }
                airshipContraption.maybeInstancedTileEntities.add(te);
            }
        }

        airshipContraption.presentTileEntities.put(pos, te);
        if (!airshipContraption.specialRenderedTileEntities.contains(te)) {
            for (int i = 0; i < airshipContraption.specialRenderedTileEntities.size(); i++) {
                if (airshipContraption.specialRenderedTileEntities.get(i).getBlockPos().offset(0, -64, 0).equals(pos)) {
                    airshipContraption.specialRenderedTileEntities.remove(i);
                    i--;
                }
            }
            airshipContraption.specialRenderedTileEntities.add(te);
        }
    }

    public void handle(AirshipContraptionBlockUpdateInfo info) {
        fakeClientWorld.setBlock(
                info.pos,
                info.state,
                1
        );

        if (info.tileEntityNBT != null) {

            TileEntityType<?> type = ForgeRegistries.TILE_ENTITIES.getValue(new ResourceLocation(info.tileEntityNBT.getString("id")));
            if (type == null) return;
            TileEntity te = type.create();
            if (te == null) return;

            te.setLevelAndPosition(fakeClientWorld, info.pos);
            te.handleUpdateTag(info.state, info.tileEntityNBT);
            te.load(info.state, info.tileEntityNBT);

            fakeClientWorld.setBlockEntity(info.pos, te);
            addTileData(te, info.pos.offset(0, -AirshipManager.getPlotPosFromId(plotId).getY(), 0), info.state);
        }
    }

    public void handleControllingSubcontraption(TileEntity be, BlockPos pos) {

        if (!(be instanceof IControlContraption)) return;

        IControlContraption controllingContraption = (IControlContraption) be;

        if (controllingContraption instanceof ContraptionHolderAccessor) {
            ControlledContraptionEntity contraptionEntity = ((ContraptionHolderAccessor) be).getMovedContraption();

            if (contraptionEntity != null) {
                if (!subContraptions.containsKey(contraptionEntity.getUUID())) {
                    stcSubContraptionAddition(contraptionEntity, pos, contraptionEntity.getUUID());
                }

                subContraptions.put(contraptionEntity.getUUID(), contraptionEntity);
                ((AbstractContraptionEntityExtension) contraptionEntity).createAeronautics$setOriginalPosition(contraptionEntity.position());
            }
        }
    }

    private void stcSubContraptionAddition(ControlledContraptionEntity contraptionEntity, BlockPos pos, UUID uuid) {
        notifyClients(new AirshipAddSubcontraptionPacket(plotId, contraptionEntity.serializeNBT(), pos, uuid));
    }

    public void addSubcontraptionClient(CompoundNBT nbt, UUID uuid, BlockPos pos) {
        BlockPos plotPos = AirshipManager.getPlotPosFromId(plotId);

        CompoundNBT controllerTag = nbt.getCompound("Controller");
        controllerTag.put("X", DoubleNBT.valueOf(controllerTag.getDouble("X") - plotPos.getX()));
        controllerTag.put("Z", DoubleNBT.valueOf(controllerTag.getDouble("Z") - plotPos.getZ()));

        Entity entity = EntityType.create(nbt, fakeClientWorld).orElse(null);
        if (entity == null) return;

        ControlledContraptionEntity contraptionEntity = (ControlledContraptionEntity) entity;

        contraptionEntity.move(-plotPos.getX(), 0, -plotPos.getZ());
        ContraptionHandler.addSpawnedContraptionsToCollisionList(contraptionEntity, level);

        fakeClientWorld.addFreshEntity(contraptionEntity);
        subContraptions.put(uuid, contraptionEntity);
    }

    public void updateSubcontraptionClient(UUID uuid, CompoundNBT nbt) {
        ControlledContraptionEntity contraptionEntity = subContraptions.get(uuid);
        if (contraptionEntity == null) {
            addSubcontraptionClient(nbt, uuid, null);
            contraptionEntity = subContraptions.get(uuid);
        }

        BlockPos plotPos = AirshipManager.getPlotPosFromId(plotId);
        ListNBT posList = nbt.getList("Pos", Constants.NBT.TAG_DOUBLE);
        posList.set(0, DoubleNBT.valueOf(posList.getDouble(0) - plotPos.getX()));
        posList.set(2, DoubleNBT.valueOf(posList.getDouble(2) - plotPos.getZ()));

        CompoundNBT controllerTag = nbt.getCompound("Controller");
        controllerTag.put("X", DoubleNBT.valueOf(controllerTag.getDouble("X") - plotPos.getX()));
        controllerTag.put("Z", DoubleNBT.valueOf(controllerTag.getDouble("Z") - plotPos.getZ()));

        contraptionEntity.deserializeNBT(nbt);
    }

    public void destroySubcontraptionClient(UUID uuid) {
        ControlledContraptionEntity contraptionEntity = subContraptions.get(uuid);
        if (contraptionEntity == null) return;

        contraptionEntity.disassemble();
        subContraptions.remove(uuid);
    }

    public static class AirshipRotationState extends ContraptionRotationState {
        public static final ContraptionRotationState NONE = new ContraptionRotationState();

        float xRotation = 0;
        float yRotation = 0;
        float zRotation = 0;
        float secondYRotation = 0;
        Matrix3d matrix;

        public Matrix3d asMatrix() {
            if (matrix != null)
                return matrix;

            matrix = new Matrix3d().asIdentity();
            if (xRotation != 0)
                matrix.multiply(new Matrix3d().asXRotation(AngleHelper.rad(-xRotation)));
            if (yRotation != 0)
                matrix.multiply(new Matrix3d().asYRotation(AngleHelper.rad(yRotation)));
            if (zRotation != 0)
                matrix.multiply(new Matrix3d().asZRotation(AngleHelper.rad(-zRotation)));
            return matrix;
        }

        public boolean hasVerticalRotation() {
            return true;
        }

        public float getYawOffset() {
            return secondYRotation;
        }
    }
}