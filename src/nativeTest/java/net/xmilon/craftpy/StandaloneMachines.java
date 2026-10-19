package net.xmilon.craftpy;

import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.xmilon.craftpy.content.MachineAccess;
import net.xmilon.craftpy.registry.KineticSlots;
import net.xmilon.craftpy.registry.SlotRegistry;
import net.xmilon.craftpy.python.Declarations;

final class StandaloneMachines {
    static void checkBudget(GameTestHelper helper) {
        int previousBudget = Config.ROTATION_BUDGET.get(); Config.ROTATION_BUDGET.set(16);
        var json = JsonParser.parseString("""
            {"declarations":{"blocks":[
            {"id":"motor","definition":{"ext":[{"type":"kinetic","role":"generator","axis":"x","speed":64,"capacity":4}]}},
            {"id":"second","definition":{"ext":[{"type":"kinetic","role":"generator","axis":"x","speed":64,"capacity":4}]}},
            {"id":"shaft","definition":{"ext":[{"type":"kinetic","role":"shaft","axis":"x"}]}},
            {"id":"mill","definition":{"ext":[{"type":"kinetic","role":"processor","axis":"x","impact":8}]}}
            ]}}
            """).getAsJsonObject();
        var definitions = Declarations.parse(json, "fixture.py"); SlotRegistry.applyAll("budget_fixture", definitions);
        place(helper, "budget_fixture:motor", 1, 2, 1);
        for (int x = 2; x < 10; x++) place(helper, "budget_fixture:shaft", x, 2, 1);
        place(helper, "budget_fixture:mill", 10, 2, 1);
        var mill = (MachineAccess) helper.getBlockEntity(new BlockPos(10, 2, 1));
        helper.assertTrue(mill.machineSpeed() == 0 && mill.rotationState().equals("rebuilding"), "Unresolved graph powered a consumer");
        helper.runAfterDelay(1, () -> {
            helper.assertTrue(mill.machineSpeed() == 0, "A bounded unfinished rebuild powered the network");
            helper.runAfterDelay(25, () -> {
                helper.assertTrue(mill.rotationState().equals("overstressed"), "Small-budget rebuild did not finish");
                place(helper, "budget_fixture:second", 9, 2, 1);
                helper.runAfterDelay(25, () -> {
                    helper.assertTrue(mill.machineSpeed() == 64, "Matching generators did not combine capacity");
                    var conflicting = json.deepCopy();
                    conflicting.getAsJsonObject("declarations").getAsJsonArray("blocks").get(1).getAsJsonObject().getAsJsonObject("definition").getAsJsonArray("ext").get(0).getAsJsonObject().addProperty("speed", -64);
                    SlotRegistry.applyAll("budget_fixture", Declarations.parse(conflicting, "fixture.py"));
                    helper.runAfterDelay(25, () -> {
                        helper.assertTrue(mill.machineSpeed() == 0 && mill.rotationState().equals("conflict"), "Conflicting generators did not stop the network: " + mill.snapshot());
                        SlotRegistry.applyAll("budget_fixture", definitions);
                        var pos = new BlockPos(8, 2, 1);
                        helper.setBlock(pos, helper.getBlockState(pos).setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS, net.minecraft.core.Direction.Axis.Z));
                        helper.runAfterDelay(25, () -> {
                            helper.assertTrue(mill.machineSpeed() == 0 && mill.rotationState().equals("overstressed"), "Axis rotation did not split source capacity");
                            Config.ROTATION_BUDGET.set(previousBudget); SlotRegistry.releaseProject("budget_fixture"); helper.succeed();
                        });
                    });
                });
            });
        });
    }

    static void check(GameTestHelper helper) {
        var json = JsonParser.parseString("""
            {"declarations":{"blocks":[
            {"id":"motor","definition":{"ext":[{"type":"kinetic","role":"generator","axis":"x","speed":-64,"capacity":32}]}},
            {"id":"shaft","definition":{"ext":[{"type":"kinetic","role":"shaft","axis":"x"}]}},
            {"id":"mill","definition":{"ext":[{"type":"kinetic","role":"processor","axis":"x","impact":8}]}}
            ]}}
            """).getAsJsonObject();
        var definitions = Declarations.parse(json, "fixture.py"); SlotRegistry.applyAll("standalone_fixture", definitions);
        place(helper, "standalone_fixture:motor", 1, 2, 1); place(helper, "standalone_fixture:shaft", 2, 2, 1); place(helper, "standalone_fixture:mill", 3, 2, 1);
        var recipes = new java.util.ArrayList<net.minecraft.world.item.crafting.RecipeHolder<?>>(helper.getLevel().getRecipeManager().getRecipes());
        recipes.add(new net.minecraft.world.item.crafting.RecipeHolder<>(net.minecraft.resources.ResourceLocation.parse("craftpy:standalone_test"),
                new net.xmilon.craftpy.content.KineticRecipe(net.minecraft.world.item.crafting.Ingredient.of(Items.IRON_ORE), new ItemStack(Items.IRON_INGOT, 2), 10, 32)));
        helper.getLevel().getRecipeManager().replaceRecipes(recipes);
        helper.runAfterDelay(30, () -> {
            var mill = (MachineAccess) helper.getBlockEntity(new BlockPos(3, 2, 1));
            helper.assertTrue(mill.machineSpeed() == -64 && mill.rotationState().equals("ready"), "Standalone shafts did not transmit reversed speed: " + mill.snapshot());
            mill.machineState().inventory.insertItem(0, new ItemStack(Items.IRON_ORE, 2), false);
            helper.runAfterDelay(30, () -> {
                helper.assertTrue(mill.machineState().inventory.getStackInSlot(0).isEmpty() && mill.machineState().inventory.getStackInSlot(1).getCount() == 4, "Standalone processing conservation failed");
                var overloaded = JsonParser.parseString(json.toString().replace("\"capacity\":32", "\"capacity\":1")).getAsJsonObject();
                SlotRegistry.applyAll("standalone_fixture", Declarations.parse(overloaded, "fixture.py"));
                helper.runAfterDelay(20, () -> {
                    helper.assertTrue(mill.machineSpeed() == 0 && mill.rotationState().equals("overstressed"), "Standalone overload did not stop network");
                    SlotRegistry.applyAll("standalone_fixture", definitions);
                    helper.runAfterDelay(20, () -> {
                        helper.assertTrue(mill.machineSpeed() == -64, "Definition reload failed to resume network");
                        helper.setBlock(2, 2, 1, net.minecraft.world.level.block.Blocks.AIR);
                        helper.runAfterDelay(10, () -> {
                            helper.assertTrue(mill.machineSpeed() == 0, "Removed shaft still transmits rotation");
                            helper.assertTrue(mill.machineState().inventory.getStackInSlot(1).getCount() == 4, "Topology change lost output");
                            SlotRegistry.releaseProject("standalone_fixture"); helper.succeed();
                        });
                    });
                });
            });
        });
    }
    static void place(GameTestHelper helper, String id, int x, int y, int z) {
        var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse(SlotRegistry.resolve(id, SlotRegistry.Kind.BLOCK)));
        helper.setBlock(x, y, z, KineticSlots.configuredState(block));
    }
}
