package net.xmilon.craftpy.analysis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * Where a string literal of Python code sits: which call it is an argument of, at which position, under which keyword.
 * That is what decides what to offer or check (an item id for {@code give("...")}, a mob effect for {@code effect("...")}).
 * Pure text analysis on top of {@link PyLex}; the code may be incomplete (the line being typed).
 */
public final class StringContext {
    /**
     * @param typed    the text of the string (for {@link #at}: the part before the cursor)
     * @param callee   the last name of the call ({@code effect} for {@code player.effect(}), empty outside a call
     * @param chain    the whole dotted name ({@code player.effect})
     * @param argument index of the positional argument, counting from 0
     * @param keyword  the keyword the string is the value of ({@code block} in {@code on.block_break(block="x")}), or null
     */
    public record Call(String typed, String callee, String chain, int argument, String keyword) {
    }

    /** A plain string of the code and the call around it. {@code start} is the offset of its first content character. */
    public record Located(int start, int end, Call call) {
    }

    private static final class Frame {
        final char opener;
        final String chain;
        int argument;
        String keyword;
        int segment;

        Frame(char opener, String chain, int segment) {
            this.opener = opener;
            this.chain = chain;
            this.segment = segment;
        }
    }

    /** Reads code (with strings and comments blanked) from left to right and knows the open brackets at the read position. */
    private static final class Walker {
        private final String masked;
        private final Deque<Frame> stack = new ArrayDeque<>();
        private int position;

        Walker(String masked) {
            this.masked = masked;
        }

        void advanceTo(int end) {
            for (; position < end; position++) {
                char c = masked.charAt(position);
                switch (c) {
                    case '(' -> stack.push(new Frame(c, chainBefore(masked, position), position + 1));
                    case '[', '{' -> stack.push(new Frame(c, "", position + 1));
                    case ')', ']', '}' -> {
                        if (!stack.isEmpty()) {
                            stack.pop();
                        }
                    }
                    case ',' -> {
                        if (!stack.isEmpty()) {
                            Frame top = stack.peek();
                            top.argument++;
                            top.keyword = null;
                            top.segment = position + 1;
                        }
                    }
                    case '=' -> {
                        boolean comparison = position + 1 < masked.length() && masked.charAt(position + 1) == '='
                                || position > 0 && "=!<>".indexOf(masked.charAt(position - 1)) >= 0;
                        if (!comparison && !stack.isEmpty() && stack.peek().opener == '(') {
                            String name = masked.substring(stack.peek().segment, position).trim();
                            if (name.matches("[A-Za-z_]\\w*")) {
                                stack.peek().keyword = name;
                            }
                        }
                    }
                    default -> {
                    }
                }
            }
        }

        Call call(String typed) {
            for (Frame frame : stack) { // innermost first; a list or dict inside a call belongs to that call's argument
                if (frame.opener == '(') {
                    String chain = frame.chain;
                    return new Call(typed, chain.substring(chain.lastIndexOf('.') + 1), chain, frame.argument, frame.keyword);
                }
            }
            return new Call(typed, "", "", 0, null);
        }
    }

    private StringContext() {
    }

    /** The context of the cursor, or empty when it is not inside a plain (non f-) string. */
    public static Optional<Call> at(String code, int offset) {
        if (offset < 0 || offset > code.length()) {
            return Optional.empty();
        }
        for (PyLex.Span span : PyLex.spans(code)) {
            if (span.kind() == PyLex.Kind.STRING && !span.fstring() && offset >= span.contentStart() && offset <= span.contentEnd()) {
                Walker walker = new Walker(PyLex.maskNonCode(code.substring(0, span.start())));
                walker.advanceTo(span.start());
                return Optional.of(walker.call(code.substring(span.contentStart(), offset)));
            }
        }
        return Optional.empty();
    }

    /**
     * The call a cursor outside any string is typing an argument of: right after {@code (} or {@code ,} with a part of a name
     * typed (a keyword being written), or empty. {@code typed} is that part of the name.
     */
    public static Optional<Call> callAt(String code, int offset) {
        if (offset < 0 || offset > code.length()) {
            return Optional.empty();
        }
        for (PyLex.Span span : PyLex.spans(code)) {
            if (span.kind() != PyLex.Kind.CODE && offset > span.start() && offset <= span.end()) {
                return Optional.empty();
            }
        }
        String masked = PyLex.maskNonCode(code.substring(0, offset));
        int start = masked.length();
        while (start > 0 && (Character.isLetterOrDigit(masked.charAt(start - 1)) || masked.charAt(start - 1) == '_')) {
            start--;
        }
        int before = start;
        while (before > 0 && masked.charAt(before - 1) == ' ') {
            before--;
        }
        if (before == 0 || "(,".indexOf(masked.charAt(before - 1)) < 0) {
            return Optional.empty(); // after a dot, an operator or at the start of a statement
        }
        Walker walker = new Walker(masked);
        walker.advanceTo(start);
        if (walker.stack.isEmpty() || walker.stack.peek().opener != '(') {
            return Optional.empty();
        }
        Call call = walker.call(masked.substring(start));
        return call.chain().isEmpty() ? Optional.empty() : Optional.of(call);
    }

    /** Every plain string of the code with its call, in one pass. */
    public static List<Located> all(String code) {
        List<Located> out = new ArrayList<>();
        Walker walker = new Walker(PyLex.maskNonCode(code));
        for (PyLex.Span span : PyLex.spans(code)) {
            if (span.kind() == PyLex.Kind.STRING && !span.fstring()) {
                walker.advanceTo(span.start());
                out.add(new Located(span.contentStart(), span.contentEnd(), walker.call(code.substring(span.contentStart(), span.contentEnd()))));
            }
        }
        return out;
    }

    private static String chainBefore(String text, int paren) {
        int end = paren;
        while (end > 0 && text.charAt(end - 1) == ' ') {
            end--;
        }
        int start = end;
        while (start > 0 && (Character.isLetterOrDigit(text.charAt(start - 1)) || text.charAt(start - 1) == '_'
                || text.charAt(start - 1) == '.')) {
            start--;
        }
        return text.substring(start, end);
    }
}
