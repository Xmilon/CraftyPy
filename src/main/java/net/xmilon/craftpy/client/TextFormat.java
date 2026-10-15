package net.xmilon.craftpy.client;

/**
 * Line endings (11) and encoding facts (12) of a file's text. Files are always read and written as UTF-8, so
 * "encoding" here means: is there a BOM, and did the file contain bytes that were not valid UTF-8 (they show up
 * as U+FFFD and would be saved as that character).
 */
public final class TextFormat {
    public enum LineEnding {
        LF("LF"), CRLF("CRLF"), MIXED("LF+CRLF"), NONE("no line breaks");

        public final String label;

        LineEnding(String label) {
            this.label = label;
        }
    }

    private TextFormat() {
    }

    public static LineEnding detect(String text) {
        int lf = 0;
        int crlf = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                if (i > 0 && text.charAt(i - 1) == '\r') {
                    crlf++;
                } else {
                    lf++;
                }
            }
        }
        if (lf == 0 && crlf == 0) {
            return LineEnding.NONE;
        }
        return crlf == 0 ? LineEnding.LF : lf == 0 ? LineEnding.CRLF : LineEnding.MIXED;
    }

    /** Every line break becomes CRLF ({@code crlf}) or LF. Lone CRs (old Mac) become the chosen ending too. */
    public static String convert(String text, boolean crlf) {
        String unified = text.replace("\r\n", "\n").replace('\r', '\n');
        return crlf ? unified.replace("\n", "\r\n") : unified;
    }

    public static boolean hasBom(String text) {
        return !text.isEmpty() && text.charAt(0) == '﻿';
    }

    public static String stripBom(String text) {
        return hasBom(text) ? text.substring(1) : text;
    }

    /** Number of U+FFFD characters: bytes that were not valid UTF-8 when the file was read. */
    public static int invalidChars(String text) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '�') {
                n++;
            }
        }
        return n;
    }

    public static String encodingLabel(String text) {
        String label = hasBom(text) ? "UTF-8 BOM" : "UTF-8";
        int bad = invalidChars(text);
        return bad == 0 ? label : label + " (" + bad + " invalid)";
    }
}
