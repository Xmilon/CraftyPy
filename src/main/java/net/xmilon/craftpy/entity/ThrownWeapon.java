package net.xmilon.craftpy.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.xmilon.craftpy.content.Defs;
import net.xmilon.craftpy.content.SoundIds;
import net.xmilon.craftpy.registry.ModEntities;

/**
 * A weapon in flight (Throwable on an item): an arrow that is drawn as its item. It hurts like an arrow (damage by speed),
 * can pierce, sticks into the ground and is picked up again as the very stack that was thrown, name, enchantments and data
 * kept.
 */
public class ThrownWeapon extends AbstractArrow {
    private static final EntityDataAccessor<ItemStack> ITEM = SynchedEntityData.defineId(ThrownWeapon.class, EntityDataSerializers.ITEM_STACK);
    private String hitSound = "";

    public ThrownWeapon(EntityType<? extends ThrownWeapon> type, Level level) {
        super(type, level);
    }

    public ThrownWeapon(Level level, LivingEntity owner, ItemStack weapon, Defs.ItemDef.ThrowSpec spec) {
        super(ModEntities.THROWN.get(), owner, level, weapon.copyWithCount(1), null);
        entityData.set(ITEM, weapon.copyWithCount(1));
        // spec.damage is what a full-speed hit does; arrows multiply base damage by their speed
        setBaseDamage(spec.damage() / spec.speed());
        setNoGravity(!spec.gravity());
        hitSound = spec.hitSound();
        if (spec.pierce() > 0) {
            // the pierce level has no setter: write it where the arrow reads it from
            CompoundTag tag = new CompoundTag();
            super.addAdditionalSaveData(tag);
            tag.putByte("PierceLevel", (byte) spec.pierce());
            super.readAdditionalSaveData(tag);
        }
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(ITEM, ItemStack.EMPTY);
    }

    /** The weapon as it flies (drawn by the renderer). */
    public ItemStack item() {
        return entityData.get(ITEM);
    }

    @Override
    protected ItemStack getDefaultPickupItem() {
        // called while the entity is built, before the item is known
        return entityData.get(ITEM).isEmpty() ? new ItemStack(Items.STICK) : entityData.get(ITEM).copy();
    }

    @Override
    protected void onHitEntity(EntityHitResult hit) {
        super.onHitEntity(hit);
        if (!hitSound.isEmpty() && !level().isClientSide) {
            var sound = SoundIds.of(hitSound);
            if (sound != null) level().playSound(null, getX(), getY(), getZ(), sound, SoundSource.PLAYERS, 1f, 0.9f + random.nextFloat() * 0.2f);
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("HitSound", hitSound);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        hitSound = tag.getString("HitSound");
        entityData.set(ITEM, getPickupItem());
    }
}
