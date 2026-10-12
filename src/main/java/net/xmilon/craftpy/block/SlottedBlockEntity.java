package net.xmilon.craftpy.block;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.xmilon.craftpy.python.PyRuntime;
import net.xmilon.craftpy.registry.ModBlockEntities;
import net.xmilon.craftpy.registry.SlotRegistry;
import net.xmilon.craftpy.util.Json;
import net.xmilon.craftpy.util.NbtJson;

import javax.annotation.Nullable;

public class SlottedBlockEntity extends BlockEntity {
    private CompoundTag pythonState = new CompoundTag();

    public SlottedBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SLOTTED_BLOCK_ENTITY.get(), pos, state);
    }

    public void tick(Level level, BlockPos pos, BlockState state) {
        if (level.isClientSide) return;
        
        SlottedBlock block = (SlottedBlock) state.getBlock();
        String holder = SlotRegistry.holderForBlock(block.slotIndex());
        if (holder == null) return;

        JsonObject context = new JsonObject();
        context.addProperty("pos", pos.getX() + "," + pos.getY() + "," + pos.getZ());
        context.addProperty("block_id", holder);
        context.add("state", NbtJson.nbtToJson(pythonState));

        PyRuntime.get().triggerEvent("block_tick", context).thenAccept(result -> {
             if (result.has("state") && result.get("state").isJsonObject()) {
                 pythonState = (CompoundTag) NbtJson.jsonToNbt(result.get("state"));
                 setChanged();
             }
        });
    }

    public void interact(Player player, BlockPos pos, BlockState state) {
        SlottedBlock block = (SlottedBlock) state.getBlock();
        String holder = SlotRegistry.holderForBlock(block.slotIndex());
        if (holder == null) return;

        JsonObject context = new JsonObject();
        context.addProperty("pos", pos.getX() + "," + pos.getY() + "," + pos.getZ());
        context.addProperty("block_id", holder);
        context.add("state", NbtJson.nbtToJson(pythonState));
        context.addProperty("player", player.getName().getString());

        PyRuntime.get().triggerEvent("block_interact", context).thenAccept(result -> {
            if (result.has("state") && result.get("state").isJsonObject()) {
                pythonState = (CompoundTag) NbtJson.jsonToNbt(result.get("state"));
                setChanged();
            }
        });
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("PythonState", pythonState);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("PythonState", Tag.TAG_COMPOUND)) {
            pythonState = tag.getCompound("PythonState");
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
