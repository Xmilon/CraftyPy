package net.xmilon.craftpy.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import net.xmilon.craftpy.CraftPy;

/**
 * Server -&gt; client reply for a {@link RequestPayload}. {@code json} holds the
 * action-specific payload (for example the project list).
 */
public record ResponsePayload(ProjectAction action, boolean success, String message, String json)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ResponsePayload> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(CraftPy.MODID, "response"));

    public static final StreamCodec<FriendlyByteBuf, ResponsePayload> STREAM_CODEC = StreamCodec.composite(
            RequestPayload.ACTION_CODEC, ResponsePayload::action,
            ByteBufCodecs.BOOL, ResponsePayload::success,
            ByteBufCodecs.STRING_UTF8, ResponsePayload::message,
            // The default 32767 char limit would disconnect a client on a big file or the generated API stub.
            ByteBufCodecs.stringUtf8(400_000), ResponsePayload::json,
            ResponsePayload::new);

    /**
     * Guarantees non-null strings: {@code ByteBufCodecs.STRING_UTF8} throws an
     * NPE while encoding a null, which disconnects the client.
     */
    public ResponsePayload {
        action = action == null ? ProjectAction.LIST : action;
        message = message == null ? "" : message;
        json = json == null ? "" : json;
    }

    public static ResponsePayload success(ProjectAction action, String message, String json) {
        return new ResponsePayload(action, true, message, json);
    }

    public static ResponsePayload failure(ProjectAction action, String message) {
        return new ResponsePayload(action, false, message, "");
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
