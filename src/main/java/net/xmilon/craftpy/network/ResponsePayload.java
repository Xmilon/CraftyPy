package net.xmilon.craftpy.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import net.xmilon.craftpy.CraftPyLog;

/**
 * Server -&gt; client reply for a {@link RequestPayload}. {@code json} holds the
 * action-specific payload (for example the project list).
 */
public record ResponsePayload(ProjectAction action, boolean success, String message, String json)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ResponsePayload> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(CraftPyLog.MODID, "response"));

    /** Largest json text of one reply (UTF-8 bytes, before compression). */
    public static final int MAX_JSON_BYTES = 16 * 1024 * 1024;
    private static final int MAX_MESSAGE_BYTES = 256 * 1024;

    public static final StreamCodec<FriendlyByteBuf, ResponsePayload> STREAM_CODEC = StreamCodec.composite(
            RequestPayload.ACTION_CODEC, ResponsePayload::action,
            ByteBufCodecs.BOOL, ResponsePayload::success,
            PayloadText.codec(MAX_MESSAGE_BYTES), ResponsePayload::message,
            // Vanilla string codecs throw while encoding a longer string, which disconnects the player.
            PayloadText.codec(MAX_JSON_BYTES), ResponsePayload::json,
            ResponsePayload::new);

    /**
     * Guarantees non-null strings: {@code ByteBufCodecs.STRING_UTF8} throws an
     * NPE while encoding a null, which disconnects the client.
     */
    public ResponsePayload {
        action = action == null ? ProjectAction.LIST : action;
        message = message == null ? "" : message;
        json = json == null ? "" : json;
        if (!PayloadText.fits(message, MAX_MESSAGE_BYTES)) {
            message = message.substring(0, MAX_MESSAGE_BYTES / 4) + " ... (cut)";
        }
        if (!PayloadText.fits(json, MAX_JSON_BYTES)) {
            // every reply of the project passes here: an oversized one becomes a readable failure, never a disconnect
            long size = PayloadText.utf8Length(json);
            message = "The reply was too big to send (" + size / 1024 / 1024 + " MiB, the limit is "
                    + MAX_JSON_BYTES / 1024 / 1024 + " MiB)" + (message.isEmpty() ? "" : ": " + message);
            json = "";
            success = false;
            net.xmilon.craftpy.CraftPyLog.LOGGER.warn("CraftPy reply {} dropped: {} bytes", action, size);
        }
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
