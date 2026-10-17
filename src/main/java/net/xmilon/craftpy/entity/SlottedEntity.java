package net.xmilon.craftpy.entity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.xmilon.craftpy.content.Defs;
import net.xmilon.craftpy.lifecycle.EventBridge;
import net.xmilon.craftpy.registry.SlotRegistry;

/**
 * A Python mob: one of the {@code pcentity_N} (passive) / {@code pcmonster_N} (hostile) types registered at startup.
 * Stats and AI follow the live definition of its slot; how it looks (name, texture, model, size) is synced to clients
 * in one string and saved with the mob, so a mob of an unloaded project still looks the same.
 */
public class SlottedEntity extends PathfinderMob {
    private static final EntityDataAccessor<String> LOOK = SynchedEntityData.defineId(SlottedEntity.class, EntityDataSerializers.STRING);

    private final SlotRegistry.Kind kind;
    private final int index;
    private Defs.EntityDef applied;
    private Look look = Look.EMPTY;

    /** How the mob is drawn. Ids are full resource locations; an empty texture means the model's vanilla skin. */
    public record Look(String name, String texture, String model, String animation, float scale, float width, float height) {
        static final Look EMPTY = new Look("", "", "humanoid", "", 1f, 0.6f, 1.8f);

        static Look parse(String json) {
            try {
                JsonObject o = JsonParser.parseString(json).getAsJsonObject();
                return new Look(Defs.s(o, "name", ""), Defs.s(o, "texture", ""), Defs.s(o, "model", "humanoid"),
                        Defs.s(o, "animation", ""), Defs.f(o, "scale", 1f), Defs.f(o, "width", 0.6f), Defs.f(o, "height", 1.8f));
            } catch (RuntimeException e) {
                return EMPTY;
            }
        }

        String json() {
            JsonObject o = new JsonObject();
            o.addProperty("name", name);
            o.addProperty("texture", texture);
            o.addProperty("model", model);
            o.addProperty("animation", animation);
            o.addProperty("scale", scale);
            o.addProperty("width", width);
            o.addProperty("height", height);
            return o.toString();
        }

        public boolean geckoModel() {
            return model.endsWith(".geo.json");
        }

        /** What the definition says, with project files turned into ids of the project's namespace. */
        static Look of(Defs.EntityDef def, String projectId) {
            String model = def.model.isBlank() ? "humanoid" : def.model.trim();
            String animation = "";
            if (model.endsWith(".geo.json")) {
                model = asset(model, projectId, "geo/", "");
                String file = def.animation.isBlank()
                        ? model.substring(model.lastIndexOf('/') + 1).replace(".geo.json", ".animation.json") : def.animation;
                animation = asset(file, projectId, "animations/", "");
            }
            String texture = def.texture.isBlank() ? "" : asset(def.texture.trim(), projectId, "textures/", ".png");
            return new Look(def.name, texture, model.toLowerCase(java.util.Locale.ROOT), animation, def.scale, def.width,
                    def.height);
        }

        /** {@code goblin.png} = {@code project:textures/goblin.png}; {@code minecraft:entity/pig/pig} gets folder and ending. */
        static String asset(String value, String projectId, String folder, String ending) {
            int colon = value.indexOf(':');
            String ns = colon < 0 ? projectId : value.substring(0, colon);
            String path = colon < 0 ? value : value.substring(colon + 1);
            if (!path.startsWith(folder)) {
                path = folder + path;
            }
            if (!ending.isEmpty() && !path.endsWith(ending)) {
                path = path + ending;
            }
            return ns + ":" + path;
        }
    }

    public SlottedEntity(EntityType<? extends SlottedEntity> type, Level level, SlotRegistry.Kind kind, int index) {
        super(type, level);
        this.kind = kind;
        this.index = index;
    }

    /** The slot key ({@code ENTITY:project:id}); Python sees {@code project:id}. */
    public String holder() {
        return SlotRegistry.holderFor(kind, index);
    }

    public boolean hostile() {
        return kind == SlotRegistry.Kind.MONSTER;
    }

    public Look look() {
        return look;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(LOOK, "");
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (LOOK.equals(key)) {
            look = Look.parse(entityData.get(LOOK));
            refreshDimensions();
        }
    }

    @Override
    public void tick() {
        if (!level().isClientSide && tickCount % 20 == 1) {
            Defs.EntityDef def = SlotRegistry.entityDef(kind, index);
            if (def != null && def != applied) {
                apply(def);
            }
        }
        super.tick();
    }

    /** Takes over a (new or reloaded) definition: stats, AI and look. */
    private void apply(Defs.EntityDef def) {
        boolean fresh = applied == null && entityData.get(LOOK).isEmpty();
        applied = def;
        base(Attributes.MAX_HEALTH, def.health);
        base(Attributes.MOVEMENT_SPEED, def.speed);
        base(Attributes.ATTACK_DAMAGE, def.damage);
        base(Attributes.ARMOR, def.armor);
        base(Attributes.FOLLOW_RANGE, def.followRange);
        if (fresh || getHealth() > getMaxHealth()) {
            setHealth(getMaxHealth());
        }
        goalSelector.removeAllGoals(goal -> true);
        targetSelector.removeAllGoals(goal -> true);
        goalSelector.addGoal(0, new FloatGoal(this));
        if (hostile()) {
            goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.0, false));
            targetSelector.addGoal(1, new HurtByTargetGoal(this));
            targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
        } else {
            goalSelector.addGoal(1, new PanicGoal(this, 1.25));
        }
        goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, hostile() ? 0.8 : 1.0));
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 6f));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));
        entityData.set(LOOK, Look.of(def, EventBridge.projectId(holder()).split(":", 2)[0]).json());
    }

    private void base(net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute, double value) {
        AttributeInstance instance = getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    @Override
    protected EntityDimensions getDefaultDimensions(Pose pose) {
        return look == Look.EMPTY ? super.getDefaultDimensions(pose) : EntityDimensions.scalable(look.width(), look.height());
    }

    @Override
    protected Component getTypeName() {
        return look.name().isEmpty() ? super.getTypeName() : Component.literal(look.name());
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return hostile(); // passive mobs stay like farm animals
    }

    @Override
    protected boolean shouldDespawnInPeaceful() {
        return hostile();
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("CraftPyLook", entityData.get(LOOK));
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("CraftPyLook")) {
            entityData.set(LOOK, tag.getString("CraftPyLook"));
        }
    }

    /** The texture to draw, or {@code fallback} (the model's vanilla skin) when the mob has none. */
    public ResourceLocation texture(ResourceLocation fallback) {
        ResourceLocation id = look.texture().isEmpty() ? null : ResourceLocation.tryParse(look.texture());
        return id != null ? id : fallback;
    }
}
