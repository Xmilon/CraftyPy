package net.xmilon.craftpy.lifecycle;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.xmilon.craftpy.CraftPy;
import net.xmilon.craftpy.block.SlottedBlock;
import net.xmilon.craftpy.block.SlottedItem;
import net.xmilon.craftpy.python.PyRuntime;
import net.xmilon.craftpy.registry.SlotRegistry;
import net.xmilon.craftpy.util.NbtJson;

/**
 * Forwards game events to Python {@code @on(...)} handlers.
 * <p>
 * Rules: events are only sent from the logical server (the integrated server and the client
 * both post most events), only when some loaded project/module handles that event, and they are
 * notifications - Python runs asynchronously, so handlers cannot cancel the game event.
 * Project content is reported by its CraftPy id ({@code project:id}), not its slot id.
 */
public class EventBridge {

    /** Drives the Python scheduler (run_later / run_every / @every), but only while tasks are pending. */
    @SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        PyRuntime runtime = PyRuntime.get();
        if (runtime.hasScheduledTasks()) {
            runtime.tick(event.getServer().getTickCount());
        }
    }

    @SubscribeEvent
    public static void onBlockInteract(PlayerInteractEvent.RightClickBlock event) {
        JsonObject context = new JsonObject();
        context.add("player", wrapPlayer(event.getEntity()));
        context.add("block", wrapBlock(event.getLevel().getBlockState(event.getPos())));
        context.add("item", wrapItem(event.getItemStack()));
        context.addProperty("hand", event.getHand().name());
        addPosition(context, event.getPos(), event.getLevel());
        fire("block_interact", event.getLevel(), context);
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof Level level)) {
            return;
        }
        JsonObject context = new JsonObject();
        context.add("player", wrapPlayer(event.getPlayer()));
        context.add("block", wrapBlock(event.getState()));
        addPosition(context, event.getPos(), level);
        fire("block_break", level, context);
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof Level level) || !(event.getEntity() instanceof Player player)) {
            return;
        }
        JsonObject context = new JsonObject();
        context.add("player", wrapPlayer(player));
        context.add("block", wrapBlock(event.getPlacedBlock()));
        addPosition(context, event.getPos(), level);
        fire("block_place", level, context);
    }

    @SubscribeEvent
    public static void onItemUse(PlayerInteractEvent.RightClickItem event) {
        JsonObject context = new JsonObject();
        context.add("player", wrapPlayer(event.getEntity()));
        context.add("item", wrapItem(event.getItemStack()));
        // Older handlers read ctx.item_id.
        context.addProperty("item_id", itemId(event.getItemStack()));
        context.addProperty("hand", event.getHand().name());
        fire("item_use", event.getLevel(), context);
    }

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        playerEvent("player_join", event.getEntity());
    }

    @SubscribeEvent
    public static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        playerEvent("player_leave", event.getEntity());
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        playerEvent("player_respawn", event.getEntity());
    }

    @SubscribeEvent
    public static void onChat(ServerChatEvent event) {
        JsonObject context = new JsonObject();
        context.add("player", wrapPlayer(event.getPlayer()));
        context.addProperty("message", event.getRawText());
        fire("chat", event.getPlayer().level(), context);
    }

    @SubscribeEvent
    public static void onItemCraft(PlayerEvent.ItemCraftedEvent event) {
        JsonObject context = new JsonObject();
        context.add("player", wrapPlayer(event.getEntity()));
        context.add("item", wrapItem(event.getCrafting()));
        fire("item_craft", event.getEntity().level(), context);
    }

    @SubscribeEvent
    public static void onItemPickup(ItemEntityPickupEvent.Post event) {
        JsonObject context = new JsonObject();
        context.add("player", wrapPlayer(event.getPlayer()));
        context.add("item", wrapItem(event.getOriginalStack()));
        fire("item_pickup", event.getPlayer().level(), context);
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity entity = event.getEntity();
        JsonObject context = new JsonObject();
        context.add("entity", wrapEntity(entity));
        context.addProperty("cause", event.getSource().getMsgId());
        Entity attacker = event.getSource().getEntity();
        if (attacker instanceof Player killer) {
            context.add("killer", wrapPlayer(killer));
        } else if (attacker != null) {
            context.add("attacker", wrapEntity(attacker));
        }
        if (entity instanceof Player player) {
            context.add("player", wrapPlayer(player));
            fire("player_death", entity.level(), context);
        }
        fire("entity_death", entity.level(), context);
    }

    @SubscribeEvent
    public static void onHurt(LivingDamageEvent.Post event) {
        LivingEntity entity = event.getEntity();
        JsonObject context = new JsonObject();
        context.add("entity", wrapEntity(entity));
        context.addProperty("damage", event.getNewDamage());
        context.addProperty("cause", event.getSource().getMsgId());
        Entity attacker = event.getSource().getEntity();
        if (attacker instanceof Player player) {
            context.add("attacker_player", wrapPlayer(player));
        }
        if (attacker != null) {
            context.add("attacker", wrapEntity(attacker));
        }
        if (entity instanceof Player player) {
            context.add("player", wrapPlayer(player));
        }
        fire("entity_hurt", entity.level(), context);
    }

    private static void playerEvent(String name, Player player) {
        JsonObject context = new JsonObject();
        context.add("player", wrapPlayer(player));
        fire(name, player.level(), context);
    }

    /** Sends one event to the worker and applies the shared global_state it returns. */
    private static void fire(String event, Level level, JsonObject context) {
        if (level == null || level.isClientSide() || !SlotRegistry.handlesEvent(event)) {
            return;
        }
        if (level instanceof ServerLevel serverLevel) {
            context.add("global_state", NbtJson.nbtToJson(GlobalState.get(serverLevel).getData()));
        }
        PyRuntime.get().triggerEvent(event, context).thenAccept(result -> {
            if (result == null) {
                return;
            }
            if (level instanceof ServerLevel serverLevel && result.has("global_state")
                    && result.get("global_state").isJsonObject()) {
                serverLevel.getServer().execute(() -> GlobalState.get(serverLevel).setData(
                        (net.minecraft.nbt.CompoundTag) NbtJson.jsonToNbt(result.get("global_state"))));
            }
            if (result.has("log") && result.get("log").isJsonArray() && level instanceof ServerLevel serverLevel) {
                // print() output and handler errors also reach the editor console.
                result.getAsJsonArray("log").forEach(line -> {
                    CraftPy.LOGGER.info("[Python {}] {}", event, line.getAsString());
                    net.xmilon.craftpy.network.CraftPyNetwork.broadcastConsole(serverLevel.getServer(),
                            "[" + event + "] " + line.getAsString());
                });
            }
        });
    }

    public static JsonObject wrapPlayer(Player player) {
        JsonObject obj = new JsonObject();
        obj.addProperty("name", player.getName().getString());
        obj.addProperty("uuid", player.getUUID().toString());
        obj.addProperty("x", player.getX());
        obj.addProperty("y", player.getY());
        obj.addProperty("z", player.getZ());
        obj.addProperty("dimension", player.level().dimension().location().toString());
        obj.addProperty("health", player.getHealth());
        obj.addProperty("max_health", player.getMaxHealth());
        obj.addProperty("food", player.getFoodData().getFoodLevel());
        obj.addProperty("level", player.experienceLevel);
        obj.add("held_item", wrapItem(player.getMainHandItem()));
        if (player instanceof ServerPlayer serverPlayer) {
            obj.addProperty("gamemode", serverPlayer.gameMode.getGameModeForPlayer().getName());
        }
        return obj;
    }

    public static JsonObject wrapEntity(Entity entity) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        obj.addProperty("name", entity.getName().getString());
        obj.addProperty("uuid", entity.getUUID().toString());
        obj.addProperty("x", entity.getX());
        obj.addProperty("y", entity.getY());
        obj.addProperty("z", entity.getZ());
        obj.addProperty("dimension", entity.level().dimension().location().toString());
        if (entity instanceof LivingEntity living) {
            obj.addProperty("health", living.getHealth());
            obj.addProperty("max_health", living.getMaxHealth());
        }
        return obj;
    }

    static JsonObject wrapBlock(BlockState state) {
        JsonObject obj = new JsonObject();
        obj.addProperty("name", state.getBlock().getName().getString());
        if (state.getBlock() instanceof SlottedBlock slotted) {
            obj.addProperty("id", projectId(SlotRegistry.holderForBlock(slotted.slotIndex())));
            obj.addProperty("craftpy", true);
        } else {
            obj.addProperty("id", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            obj.addProperty("craftpy", false);
        }
        return obj;
    }

    public static JsonObject wrapItem(ItemStack stack) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", itemId(stack));
        obj.addProperty("count", stack.getCount());
        obj.addProperty("name", stack.getHoverName().getString());
        obj.addProperty("empty", stack.isEmpty());
        return obj;
    }

    /** {@code project:id} for CraftPy content, the registry id otherwise. */
    static String itemId(ItemStack stack) {
        if (stack.getItem() instanceof SlottedItem slotted) {
            return projectId(SlotRegistry.holderForItem(slotted.slotIndex()));
        }
        if (stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof SlottedBlock slotted) {
            return projectId(SlotRegistry.holderForBlock(slotted.slotIndex()));
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /** Slot keys are {@code BLOCK:project:id}; Python sees {@code project:id}. */
    static String projectId(String holder) {
        int colon = holder.indexOf(':');
        if (colon > 0 && (holder.startsWith("BLOCK:") || holder.startsWith("ITEM:"))) {
            return holder.substring(colon + 1);
        }
        return holder;
    }

    private static void addPosition(JsonObject context, BlockPos pos, Level level) {
        context.addProperty("x", pos.getX());
        context.addProperty("y", pos.getY());
        context.addProperty("z", pos.getZ());
        context.addProperty("dimension", level.dimension().location().toString());
    }
}
