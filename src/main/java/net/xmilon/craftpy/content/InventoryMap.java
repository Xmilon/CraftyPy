package net.xmilon.craftpy.content;

import java.util.Set;

import net.minecraft.core.Direction;

/**
 * The slot arithmetic of a machine's inventory with no item in sight: which slot is {@code group.n}, what role a slot has and
 * from which sides automation may insert or extract. A null spec is the two-slot machine (input, output).
 */
public final class InventoryMap {
    private static final Set<Direction> HORIZONTAL = Set.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST);

    private InventoryMap() {
    }

    public static int slotOf(Defs.InventorySpec spec, String group, int n) {
        if (spec == null) return n == 0 && group.equals("input") ? 0 : n == 0 && group.equals("output") ? 1 : -1;
        int start = 0;
        for (var g : spec.groups) {
            if (g.name().equals(group)) return n >= 0 && n < g.count() ? start + n : -1;
            start += g.count();
        }
        return -1;
    }

    /** The group a slot belongs to, or null beyond the declaration (slots kept only so items are not lost). */
    public static Defs.InventorySpec.Group groupAt(Defs.InventorySpec spec, int slot) {
        if (spec == null) return null;
        int start = 0;
        for (var g : spec.groups) {
            if (slot < start + g.count()) return slot >= start ? g : null;
            start += g.count();
        }
        return null;
    }

    public static String groupName(Defs.InventorySpec spec, int slot) {
        if (spec == null) return slot == 0 ? "input" : slot == 1 ? "output" : "";
        var g = groupAt(spec, slot);
        return g == null ? "" : g.name();
    }

    /** "input", "fuel", "output", "storage", or "overflow" for a slot kept only so its items are not lost. */
    public static String roleOf(Defs.InventorySpec spec, int slot) {
        if (spec == null) return slot == 0 ? "input" : slot == 1 ? "output" : "overflow";
        var g = groupAt(spec, slot);
        return g == null ? "overflow" : g.role();
    }

    public static boolean canInsert(Defs.InventorySpec spec, int slot, Direction side) {
        String role = roleOf(spec, slot);
        return (role.equals("input") || role.equals("fuel") || role.equals("storage")) && (side == null || sides(spec, slot).contains(side));
    }

    public static boolean canExtract(Defs.InventorySpec spec, int slot, Direction side) {
        String role = roleOf(spec, slot);
        return (role.equals("output") || role.equals("storage") || role.equals("overflow")) && (side == null || sides(spec, slot).contains(side));
    }

    /** A group's own {@code sides=}, else the furnace-like defaults of its role. */
    public static Set<Direction> sides(Defs.InventorySpec spec, int slot) {
        var g = groupAt(spec, slot);
        if (g != null && !g.sides().isEmpty()) return g.sides();
        return switch (roleOf(spec, slot)) {
            case "input" -> Set.of(Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST);
            case "fuel" -> HORIZONTAL;
            case "output", "overflow" -> Set.of(Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST);
            default -> Set.of(Direction.values());
        };
    }
}
