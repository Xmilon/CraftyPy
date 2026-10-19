package net.xmilon.craftpy.content;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.xmilon.craftpy.block.NativeMachine;

/** Loaded nodes only. An invalidated generation cannot power a processor until rebuilt. */
public final class RotationNetworks {
    private static final Map<Level, Network> LEVELS = new WeakHashMap<>();
    private RotationNetworks() { }
    public static long version(Level level) { return LEVELS.computeIfAbsent(level, k -> new Network()).version; }
    public static void changed(Level level) { var n = LEVELS.computeIfAbsent(level, k -> new Network()); n.version++; n.pending = null; }
    public static void add(NativeMachine machine) { var n = LEVELS.computeIfAbsent(machine.getLevel(), k -> new Network()); n.nodes.put(machine.getBlockPos(), machine); changed(machine.getLevel()); }
    public static void remove(NativeMachine machine) { var n = LEVELS.get(machine.getLevel()); if (n != null && n.nodes.remove(machine.getBlockPos(), machine)) changed(machine.getLevel()); }
    @SubscribeEvent public static void unload(LevelEvent.Unload event) { LEVELS.remove(event.getLevel()); }
    @SubscribeEvent public static void tick(LevelTickEvent.Pre event) {
        if (event.getLevel().isClientSide) return;
        var n = LEVELS.get(event.getLevel()); if (n == null) return;
        if (n.pending == null) { n.pending = n.nodes.keySet().iterator(); n.queue.clear(); n.component = new ArrayList<>(); n.seen = new HashSet<>(); n.finalizing = null; n.load = new Load(); }
        int budget = net.xmilon.craftpy.Config.get(net.xmilon.craftpy.Config.ROTATION_BUDGET);
        while (budget-- > 0) {
            if (n.finalizing != null) {
                n.finishOne();
                continue;
            }
            if (n.queue.isEmpty()) {
                if (!n.component.isEmpty()) {
                    n.finalizing = n.load.result(n.component.size(), net.xmilon.craftpy.Config.get(net.xmilon.craftpy.Config.ROTATION_NETWORK_LIMIT));
                    n.finishIndex = 0;
                    continue;
                }
                if (!n.pending.hasNext()) return;
                BlockPos start = n.pending.next();
                if (!n.seen.add(start)) continue;
                n.queue.add(start);
            }
            BlockPos pos = n.queue.removeFirst(); var node = n.nodes.get(pos);
            if (node == null || node.isRemoved() || !event.getLevel().hasChunkAt(pos)) continue;
            n.component.add(node);
            var def = node.machineState().rotation(); n.load.add(def);
            if (def == null) continue;
            for (Direction dir : Direction.values()) {
                if (dir.getAxis() != node.getBlockState().getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS)) continue;
                BlockPos next = pos.relative(dir); var neighbor = n.nodes.get(next);
                if (neighbor == null || neighbor.isRemoved() || !event.getLevel().hasChunkAt(next) || neighbor.machineState().rotation() == null
                        || neighbor.getBlockState().getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS) != dir.getAxis() || !n.seen.add(next)) continue;
                n.queue.addLast(next);
            }
        }
    }
    public record Result(float speed, String state) { }
    public static Result solve(Collection<KineticDef> definitions, int limit) {
        var load = new Load(); definitions.forEach(load::add); return load.result(definitions.size(), limit);
    }
    private static final class Load {
        float speed; double capacity, impact; boolean conflict;
        void add(KineticDef def) {
            if (def == null) return;
            impact += def.impact();
            if (def.role().equals("generator") && def.speed() != 0) {
                conflict |= speed != 0 && speed != def.speed(); speed = def.speed(); capacity += def.capacity();
            }
        }
        Result result(int size, int limit) {
            return size > limit ? new Result(0,"network_limit") : conflict ? new Result(0,"conflict")
                    : new Result(speed, speed == 0 ? "unpowered" : impact > capacity ? "overstressed" : "ready");
        }
    }
    private static final class Network {
        final Map<BlockPos, NativeMachine> nodes = new LinkedHashMap<>();
        long version;
        Iterator<BlockPos> pending;
        Set<BlockPos> seen = new HashSet<>();
        final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        List<NativeMachine> component = new ArrayList<>();
        Load load = new Load();
        Result finalizing;
        int finishIndex;
        void finishOne() {
            var node = component.get(finishIndex++); var result = finalizing;
            boolean changed = node.speed != result.speed || !node.rotation.equals(result.state);
            node.speed = result.speed; node.rotation = result.state; node.solved = version;
            if (changed) node.syncMachine();
            if (finishIndex == component.size()) { component = new ArrayList<>(); finalizing = null; load = new Load(); }
        }
    }
}
