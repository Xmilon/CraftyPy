package net.xmilon.craftpy;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.xmilon.craftpy.project.*;
import net.xmilon.craftpy.registry.SlotRegistry;

@GameTestHolder("craftpy") @PrefixGameTestTemplate(false)
public final class BundledProjectGameTests {
    @GameTest(template="empty", batch="collection", timeoutTicks=10000) public static void packagedCollectionLoads(GameTestHelper helper) {
        helper.onEachTick(() -> java.util.concurrent.locks.LockSupport.parkNanos(2_000_000L));
        var eligible = ModuleLoader.list().stream().filter(m -> m.primary && m.error == null && ModuleSide.of(m.side).runsOnServer()).map(m -> m.id).toList();
        helper.assertTrue(eligible.containsAll(java.util.List.of("welcome", "ruby_rush", "sky_islands", "workshop")), "Core distribution missing");
        eligible.forEach(id -> ModuleLoader.setEnabled(id, true));
        helper.succeedWhen(() -> {
            for (String id : eligible) helper.assertTrue(ModuleLoader.isLoaded(id) && SlotRegistry.isProjectActive(id), "Waiting for packaged collection: " + id);
            eligible.forEach(id -> ModuleLoader.setEnabled(id, false));
        });
    }

    @GameTest(template="empty", batch="modules", timeoutTicks=5000) public static void packagedWorkshopLoads(GameTestHelper helper) {
        helper.onEachTick(() -> java.util.concurrent.locks.LockSupport.parkNanos(2_000_000L));
        var modules=ModuleLoader.list();
        helper.assertTrue(modules.stream().noneMatch(m -> m.id.equals("mod_mashup")), "Rejected showcase is still distributed");
        helper.assertTrue(modules.stream().filter(m -> m.primary).count() >= 13, "Packaged examples missing");
        ModuleLoader.setEnabled("workshop",true);
        boolean[] workshopChecked={false};
        helper.succeedWhen(() -> {
            if (!workshopChecked[0]) {
            helper.assertTrue(ModuleLoader.isLoaded("workshop") && SlotRegistry.isProjectActive("workshop"), "Waiting for packaged module application");
            helper.assertTrue(SlotRegistry.resolve("workshop:battery",SlotRegistry.Kind.BLOCK).startsWith("craftpy:pckinetic_"), "Battery consumed ordinary block slots");
            helper.assertTrue(helper.getLevel().getRecipeManager().byKey(net.minecraft.resources.ResourceLocation.parse("workshop:gravel")).isPresent(), "Packaged native recipe was not discovered");
            workshopChecked[0]=true;
            ModuleLoader.setEnabled("workshop",false);
            if(net.neoforged.fml.ModList.get().isLoaded("curios")) ModuleLoader.setEnabled("curios_charms",true);
            }
            if(net.neoforged.fml.ModList.get().isLoaded("curios")) {
                helper.assertTrue(ModuleLoader.isLoaded("curios_charms"),"Waiting for packaged Curios module");
                try {
                    var api=Class.forName("top.theillusivec4.curios.api.CuriosApi");
                    var slots=(java.util.Map<?,?>)api.getMethod("getPlayerSlots",net.minecraft.world.level.Level.class).invoke(null,helper.getLevel());
                    helper.assertTrue(slots.containsKey("ring"),"Waiting for Curios player ring slot reload");
                    String id=SlotRegistry.resolve("curios_charms:luck_ring",SlotRegistry.Kind.ITEM);
                    var item=net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(id));
                    var itemSlots=(java.util.Map<?,?>)api.getMethod("getItemStackSlots",net.minecraft.world.item.ItemStack.class,net.minecraft.world.level.Level.class).invoke(null,new net.minecraft.world.item.ItemStack(item),helper.getLevel());
                    helper.assertTrue(itemSlots.containsKey("ring"),"Waiting for native Curios item tags");
                } catch(ReflectiveOperationException error) { throw new AssertionError("Installed Curios API changed",error); }
                ModuleLoader.setEnabled("curios_charms",false);
            }
        });
    }
}
