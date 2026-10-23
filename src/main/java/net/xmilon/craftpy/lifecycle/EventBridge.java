package net.xmilon.craftpy.lifecycle;

import net.xmilon.craftpy.CraftPyLog;
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
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
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

    /** A player right clicks an entity (main hand, on the server): the handler may cancel the entity's own reaction. */
    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide() || event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND
                || !SlotRegistry.handlesEvent("entity_interact")) {
            return;
        }
        JsonObject context = new JsonObject();
        context.add("player", wrapPlayer(event.getEntity()));
        context.add("entity", wrapEntity(event.getTarget()));
        context.add("item", wrapItem(event.getItemStack()));
        context.addProperty("sneaking", event.getEntity().isShiftKeyDown());
        JsonObject answer = ask("entity_interact", event.getLevel(), context);
        if (answer != null && answer.has("cancel")) {
            event.setCanceled(true);
            event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
        }
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
        JsonObject answer = ask("block_break", level, context);
        if (answer != null && answer.has("cancel")) {
            event.setCanceled(true);
            return;
        }
        // The same break seen from the tool in the player's hand (Extension.on_mine).
        ItemStack held = event.getPlayer().getMainHandItem();
        if (!held.isEmpty()) {
            JsonObject mine = context.deepCopy();
            mine.add("item", wrapItem(held));
            answer = ask("item_mine", level, mine);
            if (answer != null && answer.has("cancel")) {
                event.setCanceled(true);
            }
        }
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
        JsonObject answer = ask("item_use", event.getLevel(), context);
        if (answer != null && answer.has("cancel")) {
            event.setCanceled(true);
        }
    }

    /** A player hits something: Extension.on_hit. The handler may change the damage or cancel the hit. */
    @SubscribeEvent
    public static void onItemHit(LivingIncomingDamageEvent event) {
        if (!(event.getSource().getEntity() instanceof Player player) || player.level().isClientSide()) {
            return;
        }
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            return;
        }
        JsonObject context = new JsonObject();
        context.add("player", wrapPlayer(player));
        context.add("entity", wrapEntity(event.getEntity()));
        context.add("item", wrapItem(held));
        context.addProperty("damage", event.getAmount());
        JsonObject answer = ask("item_hit", player.level(), context);
        if (answer == null) {
            return;
        }
        if (answer.has("cancel")) {
            event.setCanceled(true);
        } else if (answer.has("damage")) {
            event.setAmount(Math.max(0f, answer.get("damage").getAsFloat()));
        }
    }

    /** Once a second for every player holding something: Extension.on_hold_tick. */
    @SubscribeEvent
    public static void onHoldTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player.level().isClientSide() || player.tickCount % 20 != 0 || player.getMainHandItem().isEmpty()
                || !SlotRegistry.handlesEvent("item_hold_tick")) {
            return;
        }
        JsonObject context = new JsonObject();
        context.add("player", wrapPlayer(player));
        context.add("item", wrapItem(player.getMainHandItem()));
        fire("item_hold_tick", player.level(), context);
    }

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            sendIdeKey(player);
        }
        playerEvent("player_join", event.getEntity());
    }

    @SubscribeEvent
    public static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        net.xmilon.craftpy.keys.ServerKeyActions.forgetPlayer(event.getEntity().getUUID());
        net.xmilon.craftpy.server.ProjectRequestHandler.forgetPlayer(event.getEntity().getUUID());
        net.xmilon.craftpy.server.FileLocks.release(event.getEntity().getUUID().toString());
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
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player
                && event.getCrafting().is(net.xmilon.craftpy.registry.ModItems.PYTHON_COMPUTER.get())
                && !player.getData(net.xmilon.craftpy.registry.ModAttachments.IDE_UNLOCKED)) {
            player.setData(net.xmilon.craftpy.registry.ModAttachments.IDE_UNLOCKED, true);
            sendIdeKey(player);
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "CraftPy: the quick IDE key is unlocked (see Controls)."), true);
        }
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

    /** Once a second while a Python effect is active ({@code @on.effect_tick(effect=...)}). Server thread. */
    public static void effectTick(LivingEntity entity, String effectId, int amplifier) {
        if (!SlotRegistry.handlesEvent("effect_tick")) {
            return;
        }
        JsonObject context = new JsonObject();
        context.add("entity", wrapEntity(entity));
        if (entity instanceof Player player) {
            context.add("player", wrapPlayer(player));
        }
        JsonObject effect = new JsonObject();
        effect.addProperty("id", effectId);
        context.add("effect", effect);
        context.addProperty("level", amplifier + 1);
        for (var instance : entity.getActiveEffects()) {
            if (instance.getEffect().value() instanceof net.xmilon.craftpy.entity.SlottedEffect slotted
                    && slotted.contentId().equals(effectId)) {
                context.addProperty("seconds", instance.getDuration() / 20);
            }
        }
        fire("effect_tick", entity.level(), context);
    }

    private static void playerEvent(String name, Player player) {
        JsonObject context = new JsonObject();
        context.add("player", wrapPlayer(player));
        fire(name, player.level(), context);
    }

    /**
     * Like {@link #fire} for events a handler may cancel or change: the server thread waits for Python (still serving
     * its host calls) for at most the configured event budget. Returns the worker's answer ({@code cancel}, {@code damage}),
     * or null when nobody handles the event, Python is too slow, or it failed - then the game carries on unchanged.
     */
    static JsonObject ask(String event, Level level, JsonObject context) {
        if (!(level instanceof ServerLevel serverLevel) || !SlotRegistry.handlesEvent(event)) {
            return null;
        }
        long trace = EventTrace.begin(event, context, true);
        var future = PyRuntime.get().triggerEvent(event, context);
        long budgetMs = 50;
        try {
            budgetMs = Math.max(50, net.xmilon.craftpy.Config.get(net.xmilon.craftpy.Config.EVENT_TIMEOUT_MS));
        } catch (RuntimeException ignored) {
            // config not loaded: default budget
        }
        long deadline = System.nanoTime() + budgetMs * 1_000_000L;
        var server = serverLevel.getServer();
        if (server.isSameThread()) {
            server.managedBlock(() -> future.isDone() || System.nanoTime() > deadline);
        }
        JsonObject result = future.getNow(null);
        if (result == null) {
            EventTrace.finish(trace, 0, 0);
            CraftPyLog.LOGGER.warn("Python did not answer {} within {} ms; the game event carries on", event, budgetMs);
            return null;
        }
        EventTrace.finish(trace, result.has("handlers") ? result.get("handlers").getAsInt() : 0,
                result.has("ms") ? result.get("ms").getAsDouble() : 0);
        if (result.has("log") && result.get("log").isJsonArray()) {
            result.getAsJsonArray("log").forEach(line -> {
                CraftPyLog.LOGGER.info("[Python {}] {}", event, line.getAsString());
                net.xmilon.craftpy.network.CraftPyNetwork.broadcastConsole(server, "[" + event + "] " + line.getAsString());
            });
        }
        return result;
    }

    /** Sends one event to the worker and applies the shared global_state it returns. */
    public static void fire(String event, Level level, JsonObject context) {
        if (level == null || level.isClientSide()) {
            return;
        }
        if (!context.has("tick")) context.addProperty("tick", level.getGameTime());
        boolean handled = SlotRegistry.handlesEvent(event);
        long trace = EventTrace.begin(event, context, handled);
        if (!handled) {
            return;
        }
        if (level instanceof ServerLevel serverLevel) {
            context.add("global_state", NbtJson.nbtToJson(GlobalState.get(serverLevel).getData()));
        }
        PyRuntime.get().triggerEvent(event, context).thenAccept(result -> {
            if (result == null) {
                EventTrace.finish(trace, 0, 0);
                return;
            }
            EventTrace.finish(trace, result.has("handlers") ? result.get("handlers").getAsInt() : 0,
                    result.has("ms") ? result.get("ms").getAsDouble() : 0);
            if (level instanceof ServerLevel serverLevel && result.has("global_state")
                    && result.get("global_state").isJsonObject()) {
                serverLevel.getServer().execute(() -> GlobalState.get(serverLevel).setData(
                        (net.minecraft.nbt.CompoundTag) NbtJson.jsonToNbt(result.get("global_state"))));
            }
            if (result.has("log") && result.get("log").isJsonArray() && level instanceof ServerLevel serverLevel) {
                // print() output and handler errors also reach the editor console.
                result.getAsJsonArray("log").forEach(line -> {
                    CraftPyLog.LOGGER.info("[Python {}] {}", event, line.getAsString());
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
            obj.addProperty("operator", serverPlayer.hasPermissions(2));
            obj.addProperty("gamemode", serverPlayer.gameMode.getGameModeForPlayer().getName());
        }
        return obj;
    }

    public static JsonObject wrapEntity(Entity entity) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", net.xmilon.craftpy.content.NativeContent.logicalId(entity));
        obj.addProperty("native_type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
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

    public static JsonObject wrapBlock(BlockState state) {
        JsonObject obj = new JsonObject();
        obj.addProperty("name", state.getBlock().getName().getString());
        if (state.getBlock() instanceof SlottedBlock slotted) {
            obj.addProperty("id", projectId(SlotRegistry.holderForBlock(slotted.slotIndex())));
            obj.addProperty("craftpy", true);
        } else {
            obj.addProperty("id", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            obj.addProperty("craftpy", false);
        }
        int kinetic = net.xmilon.craftpy.registry.KineticSlots.index(state.getBlock());
        if (kinetic >= 0) { obj.addProperty("id", projectId(SlotRegistry.holderFor(SlotRegistry.Kind.KINETIC, kinetic))); obj.addProperty("craftpy", true); }
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
        if (stack.getItem() instanceof BlockItem blockItem) {
            int kinetic = net.xmilon.craftpy.registry.KineticSlots.index(blockItem.getBlock());
            if (kinetic >= 0) return projectId(SlotRegistry.holderFor(SlotRegistry.Kind.KINETIC, kinetic));
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /** Slot keys are {@code BLOCK:project:id}; Python sees {@code project:id}. */
    /** Tells the player whether the quick IDE key works for them. */
    private static void sendIdeKey(net.minecraft.server.level.ServerPlayer player) {
        net.xmilon.craftpy.network.CraftPyNetwork.sendTo(player, net.xmilon.craftpy.network.ProjectAction.IDE_KEY, true,
                String.valueOf(player.getData(net.xmilon.craftpy.registry.ModAttachments.IDE_UNLOCKED)), "");
    }

    public static String projectId(String holder) {
        int colon = holder.indexOf(':');
        if (colon > 0 && holder.indexOf(':', colon + 1) > 0) {
            try {
                SlotRegistry.Kind.valueOf(holder.substring(0, colon));
                return holder.substring(colon + 1);
            } catch (IllegalArgumentException notAKind) {
                return holder;
            }
        }
        return holder;
    }

    public static void addPosition(JsonObject context, BlockPos pos, Level level) {
        context.addProperty("x", pos.getX());
        context.addProperty("y", pos.getY());
        context.addProperty("z", pos.getZ());
        context.addProperty("dimension", level.dimension().location().toString());
    }
}
