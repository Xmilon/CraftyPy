package net.xmilon.craftpy.util;

import com.google.gson.*;
import net.minecraft.nbt.*;

import java.util.Map;

public final class NbtJson {
    private NbtJson() {}

    public static JsonElement nbtToJson(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            JsonObject obj = new JsonObject();
            for (String key : compound.getAllKeys()) {
                obj.add(key, nbtToJson(compound.get(key)));
            }
            return obj;
        } else if (tag instanceof ListTag list) {
            JsonArray arr = new JsonArray();
            for (Tag item : list) {
                arr.add(nbtToJson(item));
            }
            return arr;
        } else if (tag instanceof StringTag s) {
            return new JsonPrimitive(s.getAsString());
        } else if (tag instanceof NumericTag n) {
            return new JsonPrimitive(n.getAsNumber());
        } else if (tag instanceof ByteTag b) {
            return new JsonPrimitive(b.getAsByte() != 0);
        }
        return JsonNull.INSTANCE;
    }

    public static Tag jsonToNbt(JsonElement element) {
        if (element.isJsonObject()) {
            CompoundTag compound = new CompoundTag();
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                compound.put(entry.getKey(), jsonToNbt(entry.getValue()));
            }
            return compound;
        } else if (element.isJsonArray()) {
            ListTag list = new ListTag();
            for (JsonElement item : element.getAsJsonArray()) {
                list.add(jsonToNbt(item));
            }
            return list;
        } else if (element.isJsonPrimitive()) {
            JsonPrimitive p = element.getAsJsonPrimitive();
            if (p.isString()) return StringTag.valueOf(p.getAsString());
            if (p.isBoolean()) return ByteTag.valueOf(p.getAsBoolean());
            if (p.isNumber()) {
                double d = p.getAsDouble();
                if (d == (long)d) return LongTag.valueOf((long)d);
                return DoubleTag.valueOf(d);
            }
        }
        return EndTag.INSTANCE;
    }
}
