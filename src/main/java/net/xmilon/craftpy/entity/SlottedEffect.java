package net.xmilon.craftpy.entity;

import java.util.function.BiConsumer;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.xmilon.craftpy.CraftPyLog;
import net.xmilon.craftpy.content.Defs;
import net.xmilon.craftpy.lifecycle.EventBridge;
import net.xmilon.craftpy.lifecycle.ItemAttributes;
import net.xmilon.craftpy.registry.SlotRegistry;

/**
 * A Python effect ({@code pceffect_N}): color, category and attribute modifiers come from the live definition of its
 * slot; once a second it fires {@code effect_tick} for {@code @on.effect_tick} handlers. Name and icon are in the
 * generated resource pack.
 */
public class SlottedEffect extends MobEffect {
    private final int index;

    public SlottedEffect(int index) {
        super(MobEffectCategory.NEUTRAL, 0x8899ff);
        this.index = index;
    }

    private Defs.EffectDef def() {
        return SlotRegistry.effectDef(index);
    }

    /** {@code project:id} of this effect, as Python sees it. */
    public String contentId() {
        return EventBridge.projectId(SlotRegistry.holderFor(SlotRegistry.Kind.EFFECT, index));
    }

    @Override
    public int getColor() {
        Defs.EffectDef def = def();
        return def != null ? def.color : super.getColor();
    }

    @Override
    public MobEffectCategory getCategory() {
        Defs.EffectDef def = def();
        if (def == null) {
            return MobEffectCategory.NEUTRAL;
        }
        return switch (def.category) {
            case "beneficial", "good", "positive" -> MobEffectCategory.BENEFICIAL;
            case "harmful", "bad", "negative" -> MobEffectCategory.HARMFUL;
            default -> MobEffectCategory.NEUTRAL;
        };
    }

    @Override
    public boolean isBeneficial() {
        return getCategory() == MobEffectCategory.BENEFICIAL;
    }

    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return duration % 20 == 0 && def() != null;
    }

    @Override
    public boolean applyEffectTick(LivingEntity entity, int amplifier) {
        EventBridge.effectTick(entity, contentId(), amplifier);
        return true;
    }

    private ResourceLocation modifierId(int i) {
        return ResourceLocation.fromNamespaceAndPath(CraftPyLog.MODID, "effect." + index + "." + i);
    }

    @Override
    public void createModifiers(int amplifier, BiConsumer<Holder<Attribute>, AttributeModifier> output) {
        Defs.EffectDef def = def();
        if (def == null) {
            return;
        }
        for (int i = 0; i < def.attributes.size(); i++) {
            var spec = def.attributes.get(i);
            Holder<Attribute> attribute = ItemAttributes.attribute(spec.attribute());
            if (attribute != null) { // an attribute of a mod that is not installed is left out
                output.accept(attribute, new AttributeModifier(modifierId(i), spec.amount() * (amplifier + 1),
                        ItemAttributes.operation(spec.operation())));
            }
        }
    }

    @Override
    public void addAttributeModifiers(AttributeMap attributes, int amplifier) {
        createModifiers(amplifier, (attribute, modifier) -> {
            AttributeInstance instance = attributes.getInstance(attribute);
            if (instance != null) {
                instance.removeModifier(modifier.id());
                instance.addPermanentModifier(modifier);
            }
        });
    }

    @Override
    public void removeAttributeModifiers(AttributeMap attributes) {
        // by id on every attribute: the definition may have changed since the modifiers were added
        net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE.holders().forEach(attribute -> {
            AttributeInstance instance = attributes.getInstance(attribute);
            if (instance != null) {
                for (int i = 0; i < 16; i++) {
                    instance.removeModifier(modifierId(i));
                }
            }
        });
    }
}
