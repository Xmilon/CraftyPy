package net.xmilon.craftpy.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import net.xmilon.craftpy.CraftPyLog;

/**
 * Client -&gt; server request. A single flexible envelope keeps the wire
 * protocol small; {@code data} carries action-specific JSON.
 */
public record RequestPayload(ProjectAction action, String projectId, String data) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<RequestPayload> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(CraftPyLog.MODID, "request"));

    public static final StreamCodec<FriendlyByteBuf, ProjectAction> ACTION_CODEC = StreamCodec.of(
            (buf, value) -> buf.writeVarInt(value.ordinal()),
            buf -> ProjectAction.values()[buf.readVarInt()]);

    /**
     * Guarantees non-null strings: {@code ByteBufCodecs.STRING_UTF8} throws an
     * NPE while encoding a null, which disconnects the client.
     */
    public RequestPayload {
        projectId = projectId == null ? "" : projectId;
        data = data == null ? "" : data;
        action = action == null ? ProjectAction.LIST : action;
    }

    /** Largest data text of one request (UTF-8 bytes); big uploads are sent in parts below it. */
    public static final int MAX_DATA_BYTES = 4 * 1024 * 1024;

    public static final StreamCodec<FriendlyByteBuf, RequestPayload> STREAM_CODEC = StreamCodec.composite(
            ACTION_CODEC, RequestPayload::action,
            PayloadText.codec(1024), RequestPayload::projectId,
            PayloadText.codec(MAX_DATA_BYTES), RequestPayload::data,
            RequestPayload::new);

    public static RequestPayload of(ProjectAction action, String projectId, String data) {
        return new RequestPayload(action, projectId, data == null ? "" : data);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
