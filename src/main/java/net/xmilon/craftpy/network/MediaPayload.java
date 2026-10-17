package net.xmilon.craftpy.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.xmilon.craftpy.CraftPyLog;

/**
 * Shared media, both ways: {@code op} says what it is ({@code begin}, {@code chunk}, {@code end}, {@code control} from the
 * player who plays; {@code track}, {@code chunk}, {@code ready}, {@code control}, {@code stop} to the ones who hear it),
 * {@code json} its details and {@code data} a piece of the file.
 */
public record MediaPayload(String op, String json, byte[] data) implements CustomPacketPayload {
    public static final Type<MediaPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(CraftPyLog.MODID, "media"));
    /** One piece of a file; a file is sent in pieces of this size. */
    public static final int CHUNK = 256 * 1024;

    public static final StreamCodec<FriendlyByteBuf, MediaPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(32), MediaPayload::op,
            ByteBufCodecs.stringUtf8(4096), MediaPayload::json,
            ByteBufCodecs.byteArray(CHUNK), MediaPayload::data,
            MediaPayload::new);

    public MediaPayload {
        op = op == null ? "" : op;
        json = json == null ? "{}" : json;
        data = data == null ? new byte[0] : data;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
