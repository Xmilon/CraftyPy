package net.xmilon.craftpy.menu;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;
import net.xmilon.craftpy.content.Defs;
import net.xmilon.craftpy.network.ScreenValuesPayload;
import net.xmilon.craftpy.registry.ModMenus;

/**
 * The window of a machine block that declared {@code Screen(...)}, or of a window Python opened for an entity or a player. The
 * {@link ScreenLayout} says where the slots are (the source's slots, the player inventory); the {@link MenuSource} owns the
 * contents. The numbers the screen draws (energy, liquid, progress) reach the client through data slots, which carry 16 bits
 * each, so every value is split in two. The free values Python sets (bars and labels bound with {@code value:key}) travel in
 * their own small packet, only for the keys the layout uses and only when they changed.
 */
public class MachineMenu extends AbstractContainerMenu {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z][a-z0-9_]{0,24})}");

    private final MenuSource source;
    private final Player viewer;
    public final String title;
    public final ScreenLayout layout;
    private final int machineSlots;
    /** energy, energy capacity, progress %, then amount, capacity and fluid id of each tank */
    private final int[] values = new int[3 + 3 * net.xmilon.craftpy.content.Tanks.MAX];
    /** Client: the values Python set, as last received. */
    private final Map<String, JsonPrimitive> free = new LinkedHashMap<>();
    private final Set<String> watched;
    private String lastSent;

    /** Client side: the title and the layout come in the open packet; contents arrive in slot updates. */
    public MachineMenu(int containerId, Inventory inventory, FriendlyByteBuf data) {
        this(containerId, inventory, null, data.readUtf(64), parse(data.readUtf(ScreenLayout.MAX_JSON + 100)));
    }

    private static ScreenLayout parse(String json) {
        try {
            return ScreenLayout.parse(JsonParser.parseString(json).getAsJsonObject(), ScreenLayout.defaults(true, true, true));
        } catch (RuntimeException bad) {
            return ScreenLayout.defaults(true, true, true);
        }
    }

    public MachineMenu(int containerId, Inventory inventory, MenuSource source, String title, ScreenLayout layout) {
        super(ModMenus.MACHINE.get(), containerId);
        this.source = source;
        this.viewer = inventory.player;
        this.title = title;
        this.layout = layout;
        this.watched = watchedKeys(layout);
        // the source's slots in the order of the layout; the client counts the same way and keeps the contents the server sends
        IItemHandler items = source != null ? source.inventory() : new ItemStackHandler(Defs.InventorySpec.MAX_SLOTS);
        IItemHandler inert = new ItemStackHandler(1);
        int counted = 0;
        for (ScreenLayout.SlotRef ref : layout.slotRefs()) {
            int index = source != null ? source.inventory().slotOf(ref.group(), ref.n()) : counted;
            counted++;
            if (index < 0) {
                // the window names a slot the block does not have: a dead square, so both sides still count the same
                addSlot(new SlotItemHandler(inert, 0, ref.x(), ref.y()) {
                    @Override
                    public boolean mayPlace(ItemStack stack) {
                        return false;
                    }

                    @Override
                    public boolean mayPickup(Player player) {
                        return false;
                    }
                });
            } else {
                addSlot(new SlotItemHandler(items, index, ref.x(), ref.y()));
            }
        }
        machineSlots = slots.size();
        int locked = source instanceof VirtualSource held ? held.lockedSlot() : -1;
        for (ScreenLayout.Piece piece : layout.pieces) {
            if (piece.type().equals("lg_player")) {
                for (int row = 0; row < 3; row++) {
                    for (int column = 0; column < 9; column++) {
                        addSlot(playerSlot(inventory, column + row * 9 + 9, piece.x() + column * 18, piece.y() + row * 18, locked));
                    }
                }
                for (int column = 0; column < 9; column++) {
                    addSlot(playerSlot(inventory, column, piece.x() + column * 18, piece.y() + 58, locked));
                }
                break; // one player inventory
            }
        }
        for (int i = 0; i < values.length; i++) {
            final int index = i;
            // two data slots per value (low and high 16 bits)
            addDataSlot(new DataSlot() {
                @Override
                public int get() {
                    return MachineMenu.this.source == null ? values[index] & 0xFFFF : fresh(index) & 0xFFFF;
                }

                @Override
                public void set(int value) {
                    values[index] = values[index] & 0xFFFF0000 | value & 0xFFFF;
                }
            });
            addDataSlot(new DataSlot() {
                @Override
                public int get() {
                    return MachineMenu.this.source == null ? values[index] >>> 16 : fresh(index) >>> 16;
                }

                @Override
                public void set(int value) {
                    values[index] = values[index] & 0xFFFF | value << 16;
                }
            });
        }
    }

    /** A slot of the player's inventory; the one that holds the item whose window this is cannot be taken (like a shulker box). */
    private static Slot playerSlot(Inventory inventory, int index, int x, int y, int locked) {
        if (index != locked) {
            return new Slot(inventory, index, x, y);
        }
        return new Slot(inventory, index, x, y) {
            @Override
            public boolean mayPickup(Player player) {
                return false;
            }

            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;
            }
        };
    }

    /** The tank a gauge is bound to: {@code fluid} is 0, {@code fluid:2} is 2. */
    public static int tankOf(String bind) {
        if (bind.startsWith("fluid:")) {
            try {
                return Math.max(0, Math.min(net.xmilon.craftpy.content.Tanks.MAX - 1, Integer.parseInt(bind.substring(6))));
            } catch (NumberFormatException bad) {
                return 0;
            }
        }
        return 0;
    }

    /** The keys of free values the layout reads: {@code value:key} bindings and {@code {key}} in labels. */
    private static Set<String> watchedKeys(ScreenLayout layout) {
        Set<String> keys = new LinkedHashSet<>();
        for (ScreenLayout.Piece piece : layout.pieces) {
            if (piece.bind().startsWith("value:")) {
                keys.add(piece.bind().substring("value:".length()));
            }
            if (piece.type().equals("lg_label")) {
                Matcher m = PLACEHOLDER.matcher(piece.text());
                while (m.find()) {
                    keys.add(m.group(1));
                }
            }
        }
        keys.remove("title");
        keys.remove("inventory");
        return keys;
    }

    public MenuSource source() {
        return source;
    }

    private int fresh(int index) {
        return switch (index) {
            case 0 -> source.energy();
            case 1 -> source.energyCapacity();
            case 2 -> source.progressPercent();
            default -> {
                int tank = (index - 3) / 3;
                yield switch ((index - 3) % 3) {
                    case 0 -> source.fluidAmount(tank);
                    case 1 -> source.fluidCapacity(tank);
                    default -> source.fluidId(tank);
                };
            }
        };
    }

    public int energy() {
        return values[0];
    }

    public int energyCapacity() {
        return values[1];
    }

    public int fluidAmount(int tank) {
        return values[3 + 3 * tank];
    }

    public int fluidCapacity(int tank) {
        return values[4 + 3 * tank];
    }

    public net.minecraft.world.level.material.Fluid fluid(int tank) {
        return BuiltInRegistries.FLUID.byId(values[5 + 3 * tank]);
    }

    /** 0..100: how far the machine's current recipe is. */
    public int progress() {
        return Math.max(0, Math.min(100, values[2]));
    }

    /** How full the thing a piece is bound to is, 0..1; {@code max} is the full value of a {@code value:key} binding. */
    public double fraction(String bind, double max) {
        if (bind.startsWith("value:")) {
            return max <= 0 ? 0 : Math.max(0, Math.min(1, ScreenValues.number(free.get(bind.substring("value:".length()))) / max));
        }
        if (bind.startsWith("fluid")) {
            int tank = tankOf(bind);
            return fluidCapacity(tank) <= 0 ? 0 : (double) fluidAmount(tank) / fluidCapacity(tank);
        }
        return switch (bind) {
            case "energy" -> energyCapacity() <= 0 ? 0 : (double) energy() / energyCapacity();
            case "progress" -> progress() / 100.0;
            default -> 0;
        };
    }

    /** What a label reads: its text with {@code {title}}, {@code {inventory}} and {@code {key}} of a free value filled in. */
    public String label(String text, String inventoryTitle) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String replacement = key.equals("title") ? title : key.equals("inventory") ? inventoryTitle : ScreenValues.text(free.get(key));
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Client: the values Python set arrived. */
    public void receiveValues(String json) {
        try {
            Map<String, JsonPrimitive> next = new LinkedHashMap<>();
            if (ScreenValues.merge(next, JsonParser.parseString(json).getAsJsonObject())) {
                free.clear();
                free.putAll(next);
            }
        } catch (RuntimeException bad) {
            // a malformed packet changes nothing
        }
    }

    @Override
    public void broadcastChanges() {
        if (source != null && viewer instanceof ServerPlayer) {
            source.tick(); // called every tick for an open window: container slots move liquid and energy
        }
        super.broadcastChanges();
        if (source != null && !watched.isEmpty() && viewer instanceof ServerPlayer player) {
            JsonObject now = new JsonObject();
            source.values().forEach((key, value) -> {
                if (watched.contains(key)) now.add(key, value);
            });
            String json = now.toString();
            if (!json.equals(lastSent)) {
                lastSent = json;
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, new ScreenValuesPayload(containerId, json));
            }
        }
    }

    /**
     * A button piece was clicked (its number in the layout): the project's Python hears {@code screen_button} with the player,
     * the button's action name and where the window belongs. Only the project that owns the window is asked.
     */
    @Override
    public boolean clickMenuButton(Player player, int id) {
        var buttons = layout.buttons();
        if (source == null || id < 0 || id >= buttons.size() || !(player instanceof ServerPlayer server) || !source.stillValid(player)) {
            return false;
        }
        String owner = source.owner();
        if (owner.isEmpty()) {
            return false;
        }
        JsonObject context = new JsonObject();
        context.add("player", net.xmilon.craftpy.lifecycle.EventBridge.wrapPlayer(server));
        context.addProperty("button", buttons.get(id).bind());
        context.addProperty("owner", owner);
        source.describe(context);
        net.xmilon.craftpy.lifecycle.EventBridge.fire("screen_button", server.level(), context);
        return true;
    }

    /** The number keys and the off-hand key must not move the item whose window this is either. */
    @Override
    public void clicked(int slotId, int button, net.minecraft.world.inventory.ClickType clickType, Player player) {
        if (clickType == net.minecraft.world.inventory.ClickType.SWAP && source instanceof VirtualSource held
                && (button == held.lockedSlot() || (button == 40 && held.lockedSlot() == 40))) {
            return;
        }
        super.clicked(slotId, button, clickType, player);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (source != null) {
            source.closed();
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        boolean moved;
        if (index < machineSlots) {
            moved = moveItemStackTo(stack, machineSlots, slots.size(), true);
        } else {
            // into the first machine slots that take it (inputs, fuel and storage; output slots refuse by themselves)
            moved = false;
            for (int i = 0; i < machineSlots && !stack.isEmpty(); i++) {
                moved |= moveItemStackTo(stack, i, i + 1, false);
            }
        }
        if (!moved) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        return source == null || source.stillValid(player);
    }
}
