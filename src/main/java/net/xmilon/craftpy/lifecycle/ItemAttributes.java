package net.xmilon.craftpy.lifecycle;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.ItemAttributeModifierEvent;
import net.xmilon.craftpy.CraftPyLog;
import net.xmilon.craftpy.block.SlottedItem;
import net.xmilon.craftpy.content.Defs;

/**
 * Attack damage, attack speed, knockback and armor values of items that got Tool / Weapon / Armor extensions.
 * Slot items are registered once, so their attributes are added here, when the game asks for them.
 */
public final class ItemAttributes {
    // The ids vanilla uses for a weapon's damage and speed, so the tooltip shows "7 Attack Damage" as usual.
    private static final ResourceLocation ATTACK_DAMAGE = ResourceLocation.withDefaultNamespace("base_attack_damage");
    private static final ResourceLocation ATTACK_SPEED = ResourceLocation.withDefaultNamespace("base_attack_speed");

    private ItemAttributes() {
    }

    @SubscribeEvent
    public static void onModifiers(ItemAttributeModifierEvent event) {
        if (!(event.getItemStack().getItem() instanceof SlottedItem item)) {
            return;
        }
        Defs.ItemDef def = item.definition();
        if (def == null) {
            return;
        }
        if (def.weapon != null) {
            hit(event, def.weapon.damage(), def.weapon.attackSpeed(), def.weapon.knockback());
        } else if (def.tool != null) {
            hit(event, def.tool.damage(), def.tool.attackSpeed(), 0f);
        }
        if (def.armor != null) {
            EquipmentSlot slot = item.getEquipmentSlot(event.getItemStack());
            EquipmentSlotGroup group = EquipmentSlotGroup.bySlot(slot);
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(CraftPyLog.MODID, "armor." + def.armor.slot());
            event.replaceModifier(Attributes.ARMOR, add(id, def.armor.defense()), group);
            event.replaceModifier(Attributes.ARMOR_TOUGHNESS, add(id, def.armor.toughness()), group);
            event.replaceModifier(Attributes.KNOCKBACK_RESISTANCE, add(id, def.armor.knockbackResistance()), group);
        }
        int index = 0;
        for (Defs.ItemDef.AttributeSpec spec : def.attributes) {
            Holder<Attribute> attribute = CuriosCompat.isCurioSlot(spec.slot()) ? null : attribute(spec.attribute()); // curio slots: CuriosCompat
            if (attribute != null) { // an attribute of a mod that is not installed is simply not applied
                var id = ResourceLocation.fromNamespaceAndPath(CraftPyLog.MODID, "ext." + index);
                event.addModifier(attribute, new AttributeModifier(id, spec.amount(), operation(spec.operation())), group(spec.slot()));
            }
            index++;
        }
    }

    /** Damage and speed are given the way the tooltip shows them (a diamond sword hits for 7); the player has 1 and 4. */
    private static void hit(ItemAttributeModifierEvent event, float damage, float attackSpeed, float knockback) {
        event.replaceModifier(Attributes.ATTACK_DAMAGE, add(ATTACK_DAMAGE, damage - 1.0), EquipmentSlotGroup.MAINHAND);
        event.replaceModifier(Attributes.ATTACK_SPEED, add(ATTACK_SPEED, attackSpeed - 4.0), EquipmentSlotGroup.MAINHAND);
        if (knockback > 0f) {
            event.replaceModifier(Attributes.ATTACK_KNOCKBACK,
                    add(ResourceLocation.fromNamespaceAndPath(CraftPyLog.MODID, "weapon_knockback"), knockback),
                    EquipmentSlotGroup.MAINHAND);
        }
    }

    /** The attribute holder for an id such as {@code irons_spellbooks:fire_spell_power}, null when no mod provides it. */
    static Holder<Attribute> attribute(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id.contains(":") ? id : "minecraft:" + id);
        return location == null ? null : BuiltInRegistries.ATTRIBUTE.getHolder(location).orElse(null);
    }

    static EquipmentSlotGroup group(String name) {
        for (EquipmentSlotGroup group : EquipmentSlotGroup.values()) {
            if (group.getSerializedName().equals(name)) {
                return group;
            }
        }
        return EquipmentSlotGroup.MAINHAND;
    }

    static AttributeModifier.Operation operation(String name) {
        return switch (name) {
            case "add_multiplied_base" -> AttributeModifier.Operation.ADD_MULTIPLIED_BASE;
            case "add_multiplied_total" -> AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
            default -> AttributeModifier.Operation.ADD_VALUE;
        };
    }

    private static AttributeModifier add(ResourceLocation id, double amount) {
        return new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_VALUE);
    }
}
