package net.xmilon.craftpy.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.xmilon.craftpy.CraftPyLog;

/**
 * What a server runs, for the players, while they join (configuration phase) and later (play). {@code op} says what it
 * is: to the player {@code info} (the modules that ask for permissions, with their READMEs, and the asset pack's hash)
 * and {@code chunk} (a piece of the asset pack); to the server {@code reply} ({@code {"choice": "leave" | "have" |
 * "need"}}) and {@code installed} (the pack is in use).
 */
public record ServerModulesPayload(String op, String json, byte[] data) implements CustomPacketPayload {
    public static final Type<ServerModulesPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CraftPyLog.MODID, "server_modules"));
    /** The asset pack is sent in pieces of this size. */
    public static final int CHUNK = 512 * 1024;
    /** READMEs are cut to this many characters each, so the info stays small. */
    public static final int README_CHARS = 64 * 1024;
    private static final int JSON_BYTES = 4 * 1024 * 1024;

    public static final StreamCodec<FriendlyByteBuf, ServerModulesPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(32), ServerModulesPayload::op,
            PayloadText.codec(JSON_BYTES), ServerModulesPayload::json,
            ByteBufCodecs.byteArray(CHUNK), ServerModulesPayload::data,
            ServerModulesPayload::new);

    public ServerModulesPayload {
        op = op == null ? "" : op;
        json = json == null ? "{}" : json;
        data = data == null ? new byte[0] : data;
    }

    public ServerModulesPayload(String op, String json) {
        this(op, json, null);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
