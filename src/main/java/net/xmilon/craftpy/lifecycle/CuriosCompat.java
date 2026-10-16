package net.xmilon.craftpy.lifecycle;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.xmilon.craftpy.CraftPyLog;
import net.xmilon.craftpy.block.SlottedItem;
import net.xmilon.craftpy.content.Defs;
import net.xmilon.craftpy.registry.SlotRegistry;

/**
 * Curios slots for CraftPy items: {@code Attributes(..., slot="curios:ring")} (or {@code "curios"} for any curio slot)
 * applies while the item is worn there. Vanilla attribute modifiers only count in hands and armor slots, so a ring in a
 * Curios slot used to give nothing. Curios is optional: its API is found by name, and without Curios nothing happens.
 */
public final class CuriosCompat {
    private CuriosCompat() {
    }

    /** True for the slot names that mean a Curios slot. */
    public static boolean isCurioSlot(String slot) {
        return slot != null && (slot.equals("curios") || slot.startsWith("curios:"));
    }

    /** {@code curios:ring} applies in the ring slot, {@code curios} in every curio slot. */
    static boolean applies(String specSlot, String curioSlot) {
        return specSlot.equals("curios") || specSlot.equals("curios:" + curioSlot);
    }

    /** Called once at common setup: every item slot answers Curios with the modifiers of its current definition. */
    public static void register() {
        if (!ModList.get().isLoaded("curios")) {
            return;
        }
        try {
            Class<?> api = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            Class<?> curioItem = Class.forName("top.theillusivec4.curios.api.type.capability.ICurioItem");
            Class<?> slotContext = Class.forName("top.theillusivec4.curios.api.SlotContext");
            Method identifier = slotContext.getMethod("identifier");
            Method register = api.getMethod("registerCurio", net.minecraft.world.item.Item.class, curioItem);
            for (SlottedItem item : SlotRegistry.slotItems()) {
                InvocationHandler handler = (proxy, method, args) -> {
                    if (method.getName().equals("getAttributeModifiers") && args != null && args.length == 3
                            && args[1] instanceof ResourceLocation id && args[2] instanceof ItemStack) {
                        return modifiers(item, (String) identifier.invoke(args[0]), id);
                    }
                    if (method.isDefault()) {
                        return InvocationHandler.invokeDefault(proxy, method, args);
                    }
                    return switch (method.getName()) {
                        case "getStack" -> new ItemStack(item);
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "toString" -> "CraftPy curio " + item;
                        default -> null;
                    };
                };
                Object curio = Proxy.newProxyInstance(curioItem.getClassLoader(), new Class<?>[]{curioItem}, handler);
                register.invoke(null, item, curio);
            }
            CraftPyLog.LOGGER.info("Curios found: CraftPy items can give attributes in curio slots");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            CraftPyLog.LOGGER.warn("Curios is installed but its API did not match; curio-slot attributes are off: {}", e.toString());
        }
    }

    static Multimap<Holder<Attribute>, AttributeModifier> modifiers(SlottedItem item, String curioSlot, ResourceLocation slotId) {
        Multimap<Holder<Attribute>, AttributeModifier> out = LinkedHashMultimap.create();
        Defs.ItemDef def = item.definition();
        if (def == null) {
            return out;
        }
        int index = 0;
        for (Defs.ItemDef.AttributeSpec spec : def.attributes) {
            if (isCurioSlot(spec.slot()) && applies(spec.slot(), curioSlot)) {
                Holder<Attribute> attribute = ItemAttributes.attribute(spec.attribute());
                if (attribute != null) {
                    // the id carries the slot, so the same ring in two ring slots counts twice, like any curio
                    ResourceLocation id = ResourceLocation.fromNamespaceAndPath(slotId.getNamespace(), slotId.getPath() + ".craftpy." + index);
                    out.put(attribute, new AttributeModifier(id, spec.amount(), ItemAttributes.operation(spec.operation())));
                }
            }
            index++;
        }
        return out;
    }
}
