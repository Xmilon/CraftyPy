package net.xmilon.craftpy.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** The free values (JSON object, at most a few KB) a machine window shows, for the open window {@code containerId}. */
public record ScreenValuesPayload(int containerId, String json) implements CustomPacketPayload {
    public static final Type<ScreenValuesPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("craftpy", "screen_values"));
    public static final StreamCodec<FriendlyByteBuf, ScreenValuesPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ScreenValuesPayload::containerId, ByteBufCodecs.stringUtf8(16384), ScreenValuesPayload::json,
            ScreenValuesPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
