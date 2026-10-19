package net.xmilon.craftpy;

import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.minecraft.world.level.material.Fluids;
import net.xmilon.craftpy.content.*;
import net.xmilon.craftpy.registry.*;
import net.xmilon.craftpy.python.Declarations;

@GameTestHolder("craftpy") @PrefixGameTestTemplate(false)
public final class ResourcesGameTests {
    @GameTest(template="empty") public static void capabilitiesAndPersistence(GameTestHelper helper) {
        var payload = JsonParser.parseString("""
            {"declarations":{"blocks":[{"id":"storage","definition":{"ext":[
            {"type":"kinetic","role":"processor"},{"type":"energy_storage","capacity":1000,"max_receive":500,"max_extract":200},
            {"type":"fluid_tank","capacity":2000,"allowed":["minecraft:water"]}]}}]}}
            """).getAsJsonObject();
        SlotRegistry.applyAll("resource_fixture", Declarations.parse(payload, "fixture.py"));
        StandaloneMachines.place(helper, "resource_fixture:storage", 1, 2, 1); StandaloneMachines.place(helper, "resource_fixture:storage", 3, 2, 1);
        var level = helper.getLevel(); var a = helper.absolutePos(new BlockPos(1,2,1)); var b = helper.absolutePos(new BlockPos(3,2,1));
        var energyA = level.getCapability(Capabilities.EnergyStorage.BLOCK, a, Direction.EAST); var energyB = level.getCapability(Capabilities.EnergyStorage.BLOCK, b, Direction.WEST);
        helper.assertTrue(energyA != null && energyB != null, "Native FE capabilities missing");
        helper.assertTrue(energyA.receiveEnergy(900, true) == 500 && energyA.getEnergyStored() == 0, "FE simulation mutated storage");
        energyA.receiveEnergy(500,false); energyA.receiveEnergy(500,false);
        var fluidA = level.getCapability(Capabilities.FluidHandler.BLOCK, a, Direction.EAST); var fluidB = level.getCapability(Capabilities.FluidHandler.BLOCK, b, Direction.WEST);
        helper.assertTrue(fluidA != null && fluidB != null, "Fluid capability missing");
        helper.assertTrue(fluidA.fill(new FluidStack(Fluids.LAVA,1000), IFluidHandler.FluidAction.EXECUTE) == 0, "Tank ignored filter");
        helper.assertTrue(fluidA.fill(new FluidStack(Fluids.WATER,3000), IFluidHandler.FluidAction.SIMULATE) == 2000 && fluidA.getFluidInTank(0).isEmpty(), "Fluid simulation mutated storage");
        fluidA.fill(new FluidStack(Fluids.WATER,2000), IFluidHandler.FluidAction.EXECUTE);
        var request = new com.google.gson.JsonObject(); var from = new com.google.gson.JsonArray(); var to = new com.google.gson.JsonArray();
        for(int n: new int[]{a.getX(),a.getY(),a.getZ()}) from.add(n); for(int n: new int[]{b.getX(),b.getY(),b.getZ()}) to.add(n);
        request.add("source",from); request.add("target",to); request.addProperty("source_side","east"); request.addProperty("target_side","west"); request.addProperty("kind","energy"); request.addProperty("amount",500); request.addProperty("simulate",true);
        helper.assertTrue(ResourceAccess.transfer(level, request).get("moved").getAsInt() == 200 && energyA.getEnergyStored()==1000, "Energy transfer simulation failed");
        request.addProperty("simulate",false); ResourceAccess.transfer(level,request);
        helper.assertTrue(energyA.getEnergyStored()==800 && energyB.getEnergyStored()==200,"Energy was created or lost");
        request.addProperty("kind","fluid"); ResourceAccess.transfer(level,request);
        helper.assertTrue(fluidA.getFluidInTank(0).getAmount()==1500 && fluidB.getFluidInTank(0).getAmount()==500,"Fluid was created or lost");
        var be = level.getBlockEntity(a); var saved = be.saveWithFullMetadata(level.registryAccess());
        var restored = new net.xmilon.craftpy.block.NativeMachine(a,be.getBlockState()); restored.loadWithComponents(saved,level.registryAccess());
        helper.assertTrue(restored.machineState().energy.getEnergyStored()==800 && restored.machineState().fluid.getFluidAmount()==1500,"Shared NBT did not survive backend restoration");
        var named = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PAPER,3);
        named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Preserved component"));
        ((MachineAccess)be).machineState().inventory.setStackInSlot(1,named);
        request.addProperty("kind","item"); request.addProperty("slot",1); request.addProperty("amount",2);
        helper.assertTrue(ResourceAccess.transfer(level,request).get("moved").getAsInt()==2,"Native item transfer failed");
        var targetItems=((MachineAccess)level.getBlockEntity(b)).machineState().inventory;
        helper.assertTrue(targetItems.getStackInSlot(0).getCount()==2 && targetItems.getStackInSlot(0).get(net.minecraft.core.component.DataComponents.CUSTOM_NAME).getString().equals("Preserved component"),"Transfer lost item components");
        var drops=new java.util.ArrayList<net.minecraft.world.item.ItemStack>(); drops.add(new net.minecraft.world.item.ItemStack(be.getBlockState().getBlock()));
        var loot=new net.minecraft.world.level.storage.loot.LootParams.Builder(level).withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_ENTITY,be);
        MachineInteractions.preserveResources(drops,loot);
        var dropTag=drops.getFirst().get(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA).copyTag();
        var recovered=new net.xmilon.craftpy.block.NativeMachine(a,be.getBlockState()); recovered.loadWithComponents(dropTag,level.registryAccess());
        helper.assertTrue(recovered.machineState().energy.getEnergyStored()==800 && recovered.machineState().fluid.getFluidAmount()==1500,"Block drop lost stored resources");
        helper.assertTrue(recovered.machineState().inventory.getStackInSlot(0).isEmpty() && recovered.machineState().inventory.getStackInSlot(1).isEmpty(),"Block drop duplicates separately dropped inventory");
        if(KineticSlots.available()) checkCreateRestore(helper,a,be.getBlockState(),restored.saveWithFullMetadata(level.registryAccess()));
        if (net.neoforged.fml.ModList.get().isLoaded("mekanism")) {
            var energyPos = helper.absolutePos(new BlockPos(3,2,3)); var fluidPos = helper.absolutePos(new BlockPos(1,2,3));
            level.setBlock(energyPos,net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse("mekanism:basic_energy_cube")).defaultBlockState(),3);
            level.setBlock(fluidPos,net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse("mekanism:basic_fluid_tank")).defaultBlockState(),3);
            helper.runAfterDelay(5, () -> { checkMekanism(helper,a,request); SlotRegistry.releaseProject("resource_fixture"); helper.succeed(); });
        } else { SlotRegistry.releaseProject("resource_fixture"); helper.succeed(); }
    }
    private static void checkMekanism(GameTestHelper helper, BlockPos source, com.google.gson.JsonObject request) {
        var level = helper.getLevel();
        for (String kind : java.util.List.of("energy", "fluid")) {
            var pos=helper.absolutePos(kind.equals("energy") ? new BlockPos(3,2,3) : new BlockPos(1,2,3));
            var target = new com.google.gson.JsonArray(); for (int n : new int[]{pos.getX(),pos.getY(),pos.getZ()}) target.add(n);
            request.add("target",target); request.addProperty("kind",kind); request.addProperty("amount",100);
            if(kind.equals("energy")) configureMekanismInput(level.getBlockEntity(pos));
            Direction selected=null;
            for(Direction face:Direction.values()) {
                if(kind.equals("energy")) {
                    var cap=level.getCapability(Capabilities.EnergyStorage.BLOCK,pos,face);
                    if(cap!=null && cap.receiveEnergy(100,true)>0) { selected=face; break; }
                } else {
                    var cap=level.getCapability(Capabilities.FluidHandler.BLOCK,pos,face);
                    if(cap!=null && cap.fill(new FluidStack(Fluids.WATER,100),IFluidHandler.FluidAction.SIMULATE)>0) { selected=face; break; }
                }
            }
            helper.assertTrue(selected!=null,"No accepting Mekanism face: " + kind + " " + ResourceAccess.snapshot(level,pos,null));
            request.addProperty("target_side",selected.getName());
            var before = ResourceAccess.snapshot(level, source, Direction.EAST);
            int moved = ResourceAccess.transfer(level,request).get("moved").getAsInt();
            helper.assertTrue(moved == 100,"Mekanism did not accept native " + kind);
            var targetData = ResourceAccess.snapshot(level,pos,null);
            if(kind.equals("energy")) helper.assertTrue(targetData.get("energy").getAsInt()==100 && before.get("energy").getAsInt()-ResourceAccess.snapshot(level,source,Direction.EAST).get("energy").getAsInt()==100,"Mekanism FE conservation");
            else helper.assertTrue(targetData.getAsJsonArray("fluids").get(0).getAsJsonObject().get("amount").getAsInt()==100,"Mekanism fluid conservation");
        }
    }
    private static void configureMekanismInput(net.minecraft.world.level.block.entity.BlockEntity tile) {
        try {
            var transmission=Class.forName("mekanism.common.lib.transmitter.TransmissionType");
            var relative=Class.forName("mekanism.api.RelativeSide");
            var dataType=Class.forName("mekanism.common.tile.component.config.DataType");
            Object energy=transmission.getField("ENERGY").get(null), top=relative.getField("TOP").get(null), input=dataType.getField("INPUT").get(null);
            Object component=tile.getClass().getMethod("getConfig").invoke(tile);
            Object config=component.getClass().getMethod("getConfig",transmission).invoke(component,energy);
            config.getClass().getMethod("setDataType",dataType,relative).invoke(config,input,top);
            component.getClass().getMethod("sideChanged",transmission,relative).invoke(component,energy,top);
        } catch(ReflectiveOperationException error) { throw new AssertionError("Installed Mekanism side-configuration API changed",error); }
    }
    private static void checkCreateRestore(GameTestHelper helper,BlockPos pos,net.minecraft.world.level.block.state.BlockState state,net.minecraft.nbt.CompoundTag tag) {
        var restored = net.xmilon.craftpy.compat.create.CreateIntegration.entity(pos,state); restored.loadWithComponents(tag,helper.getLevel().registryAccess());
        var m=(MachineAccess)restored; helper.assertTrue(m.machineState().energy.getEnergyStored()==800 && m.machineState().fluid.getFluidAmount()==1500,"Core-to-Create storage conversion failed");
    }
}
