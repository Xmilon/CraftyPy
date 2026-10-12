package net.xmilon.craftpy.lifecycle;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.xmilon.craftpy.block.SlottedItem;
import net.xmilon.craftpy.python.PyRuntime;
import net.xmilon.craftpy.registry.SlotRegistry;
import net.xmilon.craftpy.util.NbtJson;

public class EventBridge {

    @SubscribeEvent
    public static void onBlockInteract(PlayerInteractEvent.RightClickBlock event) {
        JsonObject context = new JsonObject();
        context.add("player", wrapPlayer(event.getEntity()));
        context.add("block", wrapBlock(event.getLevel().getBlockState(event.getPos())));
        context.addProperty("hand", event.getHand().name());
        
        if (event.getLevel() instanceof ServerLevel sl) {
            context.add("global_state", NbtJson.nbtToJson(GlobalState.get(sl).getData()));
        }
        
        PyRuntime.get().triggerEvent("block_interact", context).thenAccept(result -> {
            applyGlobalState(event.getLevel(), result);
            if (result.has("log")) {
                result.get("log").getAsJsonArray().forEach(line -> 
                    net.xmilon.craftpy.CraftPy.LOGGER.info("[Python Event] " + line.getAsString())
                );
            }
        });
    }

    private static void applyGlobalState(net.minecraft.world.level.Level level, com.google.gson.JsonObject result) {
        if (level instanceof ServerLevel sl && result.has("global_state") && result.get("global_state").isJsonObject()) {
            GlobalState.get(sl).setData((net.minecraft.nbt.CompoundTag) NbtJson.jsonToNbt(result.get("global_state")));
        }
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        JsonObject context = new JsonObject();
        context.add("player", wrapPlayer(event.getPlayer()));
        context.add("block", wrapBlock(event.getState()));
        
        if (event.getLevel() instanceof ServerLevel sl) {
            context.add("global_state", NbtJson.nbtToJson(GlobalState.get(sl).getData()));
        }

        PyRuntime.get().triggerEvent("block_break", context).thenAccept(result -> {
            if (event.getLevel() instanceof ServerLevel sl) {
                applyGlobalState(sl, result);
            }
            if (result.has("log")) {
                result.get("log").getAsJsonArray().forEach(line -> 
                    net.xmilon.craftpy.CraftPy.LOGGER.info("[Python Event] " + line.getAsString())
                );
            }
        });
    }

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        JsonObject context = new JsonObject();
        context.add("player", wrapPlayer(event.getEntity()));
        
        if (event.getEntity().level() instanceof ServerLevel sl) {
            context.add("global_state", NbtJson.nbtToJson(GlobalState.get(sl).getData()));
        }

        PyRuntime.get().triggerEvent("player_join", context).thenAccept(result -> {
            applyGlobalState(event.getEntity().level(), result);
            if (result.has("log")) {
                result.get("log").getAsJsonArray().forEach(line -> 
                    net.xmilon.craftpy.CraftPy.LOGGER.info("[Python Event] " + line.getAsString())
                );
            }
        });
    }

    @SubscribeEvent
    public static void onItemUse(PlayerInteractEvent.RightClickItem event) {
        ItemStack stack = event.getItemStack();
        if (stack.getItem() instanceof SlottedItem si) {
            JsonObject context = new JsonObject();
            context.add("player", wrapPlayer(event.getEntity()));
            context.addProperty("item_id", SlotRegistry.holderForItem(si.slotIndex()));
            context.addProperty("hand", event.getHand().name());
            
            if (event.getLevel() instanceof ServerLevel sl) {
                context.add("global_state", NbtJson.nbtToJson(GlobalState.get(sl).getData()));
            }

            PyRuntime.get().triggerEvent("item_use", context).thenAccept(result -> {
                applyGlobalState(event.getLevel(), result);
            });
        }
    }

    private static JsonObject wrapPlayer(Player player) {
        JsonObject obj = new JsonObject();
        obj.addProperty("name", player.getName().getString());
        obj.addProperty("uuid", player.getUUID().toString());
        return obj;
    }

    private static JsonObject wrapBlock(BlockState state) {
        JsonObject obj = new JsonObject();
        obj.addProperty("name", state.getBlock().getName().getString());
        return obj;
    }
}
