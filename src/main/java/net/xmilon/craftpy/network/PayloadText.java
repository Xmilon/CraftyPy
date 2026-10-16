package net.xmilon.craftpy.network;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * Text fields of CraftPy packets: UTF-8, deflated when longer than a few hundred bytes. Vanilla string codecs have
 * a hard length cap and throw while ENCODING a longer string, which disconnects the player ("Failed to encode packet
 * 'clientbound/minecraft.custom_payload'"); the payload records therefore check {@link #fits} before they are sent and
 * turn an oversized reply into a readable failure. NeoForge splits packets above the vanilla 1 MiB on its own.
 */
public final class PayloadText {
    private static final int RAW_BELOW = 512;

    private PayloadText() {
    }

    /** UTF-8 byte length of {@code text}, counted without allocating the bytes. */
    public static long utf8Length(String text) {
        long bytes = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x80) {
                bytes++;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }

    public static boolean fits(String text, int maxBytes) {
        return text.length() <= maxBytes && utf8Length(text) <= maxBytes;
    }

    /** A codec for strings of at most {@code maxBytes} UTF-8 bytes; a longer one fails to decode, never to encode. */
    public static StreamCodec<FriendlyByteBuf, String> codec(int maxBytes) {
        return StreamCodec.of((buf, text) -> write(buf, text, maxBytes), buf -> read(buf, maxBytes));
    }

    static void write(FriendlyByteBuf buf, String text, int maxBytes) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) {
            // the payload constructors keep this from happening; cut instead of disconnecting if one is missed
            bytes = text.substring(0, Math.min(text.length(), maxBytes / 3)).getBytes(StandardCharsets.UTF_8);
        }
        if (bytes.length < RAW_BELOW) {
            buf.writeBoolean(false);
            buf.writeByteArray(bytes);
            return;
        }
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            deflater.setInput(bytes);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, bytes.length / 4));
            byte[] chunk = new byte[8192];
            while (!deflater.finished()) {
                out.write(chunk, 0, deflater.deflate(chunk));
            }
            buf.writeBoolean(true);
            buf.writeVarInt(bytes.length);
            buf.writeByteArray(out.toByteArray());
        } finally {
            deflater.end();
        }
    }

    static String read(FriendlyByteBuf buf, int maxBytes) {
        if (!buf.readBoolean()) {
            return new String(buf.readByteArray(maxBytes), StandardCharsets.UTF_8);
        }
        int size = buf.readVarInt();
        if (size < 0 || size > maxBytes) {
            throw new DecoderException("CraftPy text of " + size + " bytes is over the limit of " + maxBytes);
        }
        byte[] packed = buf.readByteArray(maxBytes);
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(packed);
            byte[] bytes = new byte[size];
            int done = 0;
            while (done < size && !inflater.finished()) {
                int n = inflater.inflate(bytes, done, size - done);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                done += n;
            }
            if (done != size || !inflater.finished()) {
                throw new DecoderException("CraftPy text is damaged (" + done + " of " + size + " bytes)");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (DataFormatException e) {
            throw new DecoderException("CraftPy text is damaged: " + e.getMessage(), e);
        } finally {
            inflater.end();
        }
    }
}
