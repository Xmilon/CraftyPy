package net.xmilon.craftpy.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Regression tests for the client disconnect caused by encoding a
 * {@code craftpy:request} payload with a null project id:
 * {@code java.lang.NullPointerException: Cannot invoke "CharSequence.length()"}
 * thrown from {@code ByteBufCodecs.STRING_UTF8}.
 *
 * <p>Minecraft-free by design: the test asserts the null-sanitizing compact
 * constructors, which is the actual fix. The codec itself cannot be exercised on
 * the shared test classpath (Gradle 9 rejects main configurations as test deps).
 */
class PayloadCodecTest {

    @Test
    void requestNullProjectIdFallsBackToEmptyString() {
        RequestPayload payload = RequestPayload.of(ProjectAction.LIST, null, null);
        assertEquals(ProjectAction.LIST, payload.action());
        assertEquals("", payload.projectId());
        assertEquals("", payload.data());
    }

    @Test
    void requestNullActionFallsBackToList() {
        RequestPayload payload = new RequestPayload(null, null, null);
        assertNotNull(payload.action());
        assertEquals(ProjectAction.LIST, payload.action());
        assertEquals("", payload.projectId());
        assertEquals("", payload.data());
    }

    @Test
    void responseNullFieldsFallBackToEmptyStrings() {
        ResponsePayload payload = new ResponsePayload(ProjectAction.LIST, true, null, null);
        assertEquals("", payload.message());
        assertEquals("", payload.json());
    }

    @Test
    void responseNullActionFallsBackToList() {
        ResponsePayload payload = new ResponsePayload(null, true, "msg", "{}");
        assertEquals(ProjectAction.LIST, payload.action());
        assertEquals("msg", payload.message());
        assertEquals("{}", payload.json());
    }

    @Test
    void originalRequestPayloadRoundTripsFieldValues() {
        RequestPayload payload = RequestPayload.of(ProjectAction.READ_FILE, "demo", "code/main.py");
        assertEquals(ProjectAction.READ_FILE, payload.action());
        assertEquals("demo", payload.projectId());
        assertEquals("code/main.py", payload.data());
    }

    @Test
    void requestEncodesAndDecodesWithoutNullFields() {
        RequestPayload payload = RequestPayload.of(ProjectAction.LIST, null, null);
        RequestPayload decoded = roundTrip(payload);
        assertEquals(ProjectAction.LIST, decoded.action());
        assertEquals("", decoded.projectId());
        assertEquals("", decoded.data());
    }

    @Test
    void responseEncodesAndDecodesWithoutNullFields() {
        ResponsePayload payload = new ResponsePayload(null, true, null, null);
        ResponsePayload decoded = roundTrip(payload);
        assertEquals(ProjectAction.LIST, decoded.action());
        assertEquals("", decoded.message());
        assertEquals("", decoded.json());
    }

    private static <T> T roundTrip(T value) {
        if (value instanceof RequestPayload r) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            RequestPayload.STREAM_CODEC.encode(buf, r);
            return (T) RequestPayload.STREAM_CODEC.decode(buf);
        }
        if (value instanceof ResponsePayload r) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            ResponsePayload.STREAM_CODEC.encode(buf, r);
            return (T) ResponsePayload.STREAM_CODEC.decode(buf);
        }
        throw new IllegalArgumentException("unsupported " + value);
    }
}