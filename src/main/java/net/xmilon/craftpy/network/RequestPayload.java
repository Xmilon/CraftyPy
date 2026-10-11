package net.xmilon.craftpy.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import net.xmilon.craftpy.CraftPy;

/**
 * Client -&gt; server request. A single flexible envelope keeps the wire
 * protocol small; {@code data} carries action-specific JSON.
 */
public record RequestPayload(ProjectAction action, String projectId, String data) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<RequestPayload> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(CraftPy.MODID, "request"));

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

    public static final StreamCodec<FriendlyByteBuf, RequestPayload> STREAM_CODEC = StreamCodec.composite(
            ACTION_CODEC, RequestPayload::action,
            ByteBufCodecs.STRING_UTF8, RequestPayload::projectId,
            ByteBufCodecs.STRING_UTF8, RequestPayload::data,
            RequestPayload::new);

    public static RequestPayload of(ProjectAction action, String projectId, String data) {
        return new RequestPayload(action, projectId, data == null ? "" : data);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
