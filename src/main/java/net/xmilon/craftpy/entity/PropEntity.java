package net.xmilon.craftpy.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * An item set down in the world as itself (Placeable on an item): a backpack on the floor, a lantern on a table. It is drawn
 * with the item's model, and it <em>is</em> the item stack: everything the stack carries (a window's slots, energy and
 * liquids kept by {@code ui.open_screen(..., hand=)} in its custom data, its name, enchantments) stays in it while it stands
 * and comes back with it. A player picks it up by hitting it, or with shift + right click and an empty hand; a plain right
 * click is {@code @on.entity_interact(entity=the_item)} in Python. Nothing else hurts it.
 */
public class PropEntity extends Entity {
    private static final EntityDataAccessor<ItemStack> ITEM = SynchedEntityData.defineId(PropEntity.class, EntityDataSerializers.ITEM_STACK);
    private static final EntityDataAccessor<Float> WIDTH = SynchedEntityData.defineId(PropEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> HEIGHT = SynchedEntityData.defineId(PropEntity.class, EntityDataSerializers.FLOAT);

    public PropEntity(EntityType<? extends PropEntity> type, Level level) {
        super(type, level);
    }

    /** A prop of one item of {@code stack} (its data copied) standing at {@code pos}, turned to {@code yaw}. */
    public static PropEntity of(EntityType<? extends PropEntity> type, Level level, ItemStack stack, Vec3 pos, float yaw, float width, float height) {
        PropEntity prop = new PropEntity(type, level);
        prop.setItem(stack.copyWithCount(1));
        prop.entityData.set(WIDTH, clamp(width));
        prop.entityData.set(HEIGHT, clamp(height));
        prop.refreshDimensions();
        prop.moveTo(pos.x, pos.y, pos.z, yaw, 0);
        return prop;
    }

    private static float clamp(float size) {
        return Math.max(0.125f, Math.min(4f, size));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(ITEM, ItemStack.EMPTY);
        builder.define(WIDTH, 0.6f);
        builder.define(HEIGHT, 0.6f);
    }

    /** The very stack it stands for: windows keep their data in it, so changes stay with the item. */
    public ItemStack item() {
        return entityData.get(ITEM);
    }

    public void setItem(ItemStack stack) {
        entityData.set(ITEM, stack);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (WIDTH.equals(key) || HEIGHT.equals(key)) refreshDimensions();
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return EntityDimensions.scalable(entityData.get(WIDTH), entityData.get(HEIGHT));
    }

    @Override
    public void tick() {
        super.tick();
        if (item().isEmpty() && !level().isClientSide) {
            discard();
            return;
        }
        // it rests where it was put and falls when the floor goes, like an item frame that is not on a wall
        if (!onGround()) {
            setDeltaMovement(getDeltaMovement().add(0, -0.04, 0).scale(0.98));
            move(MoverType.SELF, getDeltaMovement());
        } else {
            setDeltaMovement(Vec3.ZERO);
        }
    }

    @Override
    public boolean isPickable() {
        return !isRemoved();
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (level().isClientSide || isRemoved() || !(source.getEntity() instanceof Player player)) return false;
        pickUp(player);
        return true;
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (player.isShiftKeyDown() && player.getItemInHand(hand).isEmpty()) {
            if (!level().isClientSide) pickUp(player);
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        return InteractionResult.PASS;
    }

    /** The stack goes back to the player (or drops at their feet when there is no room). */
    public void pickUp(Player player) {
        ItemStack stack = item();
        setItem(ItemStack.EMPTY);
        discard();
        // windows open on it close first (saving into the stack), so no slot can be emptied after the stack left
        for (Player viewer : level().players()) {
            if (viewer.containerMenu instanceof net.xmilon.craftpy.menu.MachineMenu menu && !menu.stillValid(viewer)) viewer.closeContainer();
        }
        level().playSound(null, getX(), getY(), getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.4f, 1f);
        if (!player.getInventory().add(stack)) player.drop(stack, false);
    }

    @Override
    public ItemStack getPickResult() {
        return item().copy();
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        setItem(ItemStack.parseOptional(registryAccess(), tag.getCompound("Item")));
        if (tag.contains("Width")) entityData.set(WIDTH, clamp(tag.getFloat("Width")));
        if (tag.contains("Height")) entityData.set(HEIGHT, clamp(tag.getFloat("Height")));
        refreshDimensions();
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        if (!item().isEmpty()) tag.put("Item", item().save(registryAccess()));
        tag.putFloat("Width", entityData.get(WIDTH));
        tag.putFloat("Height", entityData.get(HEIGHT));
    }
}
