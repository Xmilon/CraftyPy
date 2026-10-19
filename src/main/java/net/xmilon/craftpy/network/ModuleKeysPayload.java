package net.xmilon.craftpy.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Separate codecs bound client descriptions and the much smaller server action request. */
public record ModuleKeysPayload(String json) implements CustomPacketPayload {
    public static final Type<ModuleKeysPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("craftpy", "module_keys"));
    public static final StreamCodec<FriendlyByteBuf, ModuleKeysPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(262144), ModuleKeysPayload::json, ModuleKeysPayload::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public record Press(String owner, String action, long revision) implements CustomPacketPayload {
        public static final Type<Press> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("craftpy", "module_key_press"));
        public static final StreamCodec<FriendlyByteBuf, Press> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(160), Press::owner, ByteBufCodecs.stringUtf8(128), Press::action,
                ByteBufCodecs.VAR_LONG, Press::revision, Press::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
