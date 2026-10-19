package net.xmilon.craftpy.entity;

import com.google.gson.JsonObject;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.xmilon.craftpy.content.NativeContent;
import net.xmilon.craftpy.lifecycle.EventBridge;
import net.xmilon.craftpy.registry.SlotRegistry;

/** Owned entities only, at most once a second each and 64 queued snapshots in a server tick. */
public final class NativeEntityEvents {
    private static int tick = -1, used;
    private NativeEntityEvents() { }
    @SubscribeEvent public static void tick(EntityTickEvent.Post event) {
        var entity = event.getEntity();
        if (!(entity.level() instanceof net.minecraft.server.level.ServerLevel level)
                || Math.floorMod(entity.getUUID().hashCode(), 20) != Math.floorMod(level.getServer().getTickCount(), 20)
                || !SlotRegistry.handlesEvent("entity_tick")) return;
        if (!(entity instanceof SlottedEntity) && !entity.getPersistentData().contains("craftpy:variant")) return;
        String id = NativeContent.logicalId(entity);
        if (!SlotRegistry.handlesEntityTick(id)) return;
        int current = level.getServer().getTickCount();
        if (tick != current) { tick = current; used = 0; }
        if (used++ >= 64) return;
        JsonObject context = new JsonObject(); context.add("entity", EventBridge.wrapEntity(entity));
        context.addProperty("tick", level.getGameTime());
        EventBridge.fire("entity_tick", level, context);
    }
}
