package net.xmilon.craftpy;

import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.xmilon.craftpy.content.KineticInventory;
import net.xmilon.craftpy.content.KineticRecipe;
import net.xmilon.craftpy.python.Declarations;
import net.xmilon.craftpy.registry.KineticSlots;
import net.xmilon.craftpy.registry.SlotRegistry;

@GameTestHolder("craftpy")
@PrefixGameTestTemplate(false)
public final class KineticGameTests {
    @GameTest(template = "empty") public static void inventoryConservation(GameTestHelper helper) {
        var inventory = new KineticInventory();
        inventory.insertItem(0, new ItemStack(Items.IRON_ORE, 2), false);
        inventory.setStackInSlot(1, new ItemStack(Items.IRON_INGOT, 64));
        helper.assertTrue(!inventory.finish(new ItemStack(Items.IRON_INGOT)), "Full output must block");
        helper.assertTrue(inventory.getStackInSlot(0).getCount() == 2, "Blocked output consumed input");
        inventory.extractItem(1, 2, false);
        helper.assertTrue(inventory.finish(new ItemStack(Items.IRON_INGOT, 2)), "Valid output did not finish");
        helper.assertTrue(inventory.getStackInSlot(0).getCount() == 1 && inventory.getStackInSlot(1).getCount() == 64, "Inventory conservation");
        helper.assertTrue(inventory.insertItem(1, new ItemStack(Items.DIAMOND), false).getCount() == 1, "Automation inserted output");
        var saved = inventory.serializeNBT(helper.getLevel().registryAccess());
        saved.putInt("Size", 9999);
        var restored = new KineticInventory(); restored.deserializeNBT(helper.getLevel().registryAccess(), saved);
        helper.assertTrue(restored.getSlots() == 2, "NBT changed the fixed inventory slot count");
        helper.assertTrue(restored.getStackInSlot(0).getCount() == 1 && restored.getStackInSlot(1).getCount() == 64, "Inventory NBT lost stacks");
        restored.setStackInSlot(1, new ItemStack(Items.GOLD_INGOT));
        helper.assertTrue(!restored.finish(new ItemStack(Items.IRON_INGOT)) && restored.getStackInSlot(0).getCount() == 1, "Mismatched output consumed input");
        helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 240) public static void nativeConnectivity(GameTestHelper helper) {
        if (!KineticSlots.available()) { StandaloneMachines.check(helper); return; }
        Connectivity.check(helper);
    }
    @GameTest(template = "empty", timeoutTicks = 240) public static void boundedStandaloneTopology(GameTestHelper helper) {
        if (KineticSlots.available()) { helper.succeed(); return; }
        StandaloneMachines.checkBudget(helper);
    }
    /** The fixture itself also respects optional class loading. */
    private static final class Connectivity {
        static void check(GameTestHelper helper) {
            var payload = JsonParser.parseString("{\"declarations\":{\"blocks\":["
                    + "{\"id\":\"motor\",\"definition\":{\"ext\":[{\"type\":\"kinetic\",\"role\":\"generator\",\"speed\":-64,\"axis\":\"x\",\"capacity\":32}]}},"
                    + "{\"id\":\"consumer\",\"definition\":{\"ext\":[{\"type\":\"kinetic\",\"role\":\"processor\",\"axis\":\"x\",\"impact\":8}]}}]}}" ).getAsJsonObject();
            var declarations = Declarations.parse(payload, "fixture.py");
            SlotRegistry.applyAll("kinetic_fixture", declarations);
            var motorId = net.minecraft.resources.ResourceLocation.parse(SlotRegistry.resolve("kinetic_fixture:motor", SlotRegistry.Kind.BLOCK));
            var consumerId = net.minecraft.resources.ResourceLocation.parse(SlotRegistry.resolve("kinetic_fixture:consumer", SlotRegistry.Kind.BLOCK));
            helper.setBlock(1, 2, 1, KineticSlots.configuredState(net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(motorId)));
            var shaft = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse("create:shaft"));
            helper.setBlock(2, 2, 1, shaft.defaultBlockState().setValue(BlockStateProperties.AXIS, net.minecraft.core.Direction.Axis.X));
            helper.setBlock(3, 2, 1, KineticSlots.configuredState(net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(consumerId)));
            helper.runAfterDelay(30, () -> {
                var consumer = (net.xmilon.craftpy.compat.create.KineticMachine) helper.getBlockEntity(new BlockPos(3, 2, 1));
                helper.assertTrue(consumer.getSpeed() == -64, "Create shaft did not propagate reversed speed: " + consumer.getSpeed());
                helper.assertTrue(!consumer.isOverStressed(), "Sufficient capacity incorrectly overstressed");
                consumer.inventory.insertItem(0, new ItemStack(Items.IRON_ORE, 2), false);
                var tag = consumer.saveWithFullMetadata(helper.getLevel().registryAccess());
                var restored = new net.xmilon.craftpy.compat.create.KineticMachine(consumer.getBlockPos(), consumer.getBlockState());
                restored.loadWithComponents(tag, helper.getLevel().registryAccess());
                helper.assertTrue(restored.inventory.getStackInSlot(0).getCount() == 2, "Machine NBT lost inventory");
                recipe(helper, 128);
                helper.runAfterDelay(20, () -> {
                    helper.assertTrue(consumer.inventory.getStackInSlot(0).getCount() == 2 && consumer.inventory.getStackInSlot(1).isEmpty(), "Insufficient rotation processed input");
                    recipe(helper, 32); consumer.inventory.setStackInSlot(1, new ItemStack(Items.IRON_INGOT, 64));
                    helper.runAfterDelay(20, () -> {
                        helper.assertTrue(consumer.inventory.getStackInSlot(0).getCount() == 2, "Full output consumed input");
                        consumer.inventory.extractItem(1, 64, false);
                        helper.runAfterDelay(30, () -> {
                            helper.assertTrue(consumer.inventory.getStackInSlot(0).isEmpty() && consumer.inventory.getStackInSlot(1).getCount() == 4, "Native recipe lost/duplicated output");
                            consumer.inventory.insertItem(0, new ItemStack(Items.IRON_ORE, 2), false);
                            String stressed = net.xmilon.craftpy.util.Json.COMPACT.toJson(payload).replace("\"capacity\":32", "\"capacity\":1");
                            // Reparse fixture declarations; no live project or player inventory is involved.
                            SlotRegistry.applyAll("kinetic_fixture", Declarations.parse(JsonParser.parseString(stressed).getAsJsonObject(), "fixture.py"));
                            helper.runAfterDelay(20, () -> {
                                helper.assertTrue(consumer.isOverStressed() && consumer.getSpeed() == 0, "Create did not stop an overstressed network");
                                helper.assertTrue(consumer.inventory.getStackInSlot(0).getCount() == 2, "Overstress consumed input");
                                SlotRegistry.releaseProject("kinetic_fixture");
                                helper.runAfterDelay(30, () -> {
                                    helper.assertTrue(consumer.getSpeed() == 0 && consumer.inventory.getStackInSlot(0).getCount() == 2, "Unload changed inventory or still supplies power");
                                    SlotRegistry.applyAll("kinetic_fixture", declarations);
                                    helper.runAfterDelay(30, () -> {
                                        helper.assertTrue(consumer.getSpeed() == -64 && consumer.inventory.getStackInSlot(0).isEmpty() && consumer.inventory.getStackInSlot(1).getCount() == 8, "Reload did not resume native processing");
                                        var pos = helper.absolutePos(new BlockPos(3, 2, 1));
                                        var context = new net.minecraft.world.item.context.UseOnContext(helper.getLevel(), null, net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY,
                                                new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos), net.minecraft.core.Direction.UP, pos, false));
                                        ((com.simibubi.create.content.equipment.wrench.IWrenchable) consumer.getBlockState().getBlock()).onWrenched(consumer.getBlockState(), context);
                                        helper.runAfterDelay(10, () -> {
                                            helper.assertTrue(helper.getBlockState(new BlockPos(3, 2, 1)).getValue(BlockStateProperties.AXIS) != net.minecraft.core.Direction.Axis.X && consumer.getSpeed() == 0, "Wrench did not rotate and detach the consumer");
                                            helper.assertTrue(consumer.inventory.getStackInSlot(1).getCount() == 8, "Wrench lost inventory");
                                            SlotRegistry.releaseProject("kinetic_fixture"); helper.succeed();
                                        });
                                    });
                                });
                            });
                        });
                    });
                });
            });
        }
        private static void recipe(GameTestHelper helper, float speed) {
            var manager = helper.getLevel().getRecipeManager();
            var id = net.minecraft.resources.ResourceLocation.parse("craftpy:fixture_processing");
            var recipes = new java.util.ArrayList<net.minecraft.world.item.crafting.RecipeHolder<?>>(manager.getRecipes());
            recipes.removeIf(recipe -> recipe.id().equals(id));
            recipes.add(new net.minecraft.world.item.crafting.RecipeHolder<>(id, new KineticRecipe(net.minecraft.world.item.crafting.Ingredient.of(Items.IRON_ORE), new ItemStack(Items.IRON_INGOT, 2), 10, speed)));
            manager.replaceRecipes(recipes);
        }
    }
}
