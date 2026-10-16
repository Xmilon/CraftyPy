package net.xmilon.craftpy.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Undo and redo of one file's text, grouped the way IntelliJ groups them: a word typed is one step, a run of
 * Backspace or Delete is one step, and what the editor adds by itself during the same keystroke (a closing bracket, the
 * indentation after Enter) belongs to that keystroke. A paste, a command (comment, reformat, rename...) or a jump of the
 * caret starts a new step. Pure: the editor feeds it every change and applies the steps it returns.
 */
public final class TextHistory {
    /** {@code removed} was replaced by {@code inserted} at {@code start}. */
    public record Edit(int start, String removed, String inserted) {
    }

    /** One undoable step: its edits in the order they happened, and the caret before and after it. */
    public record Step(List<Edit> edits, int caretBefore, int caretAfter) {
    }

    private static final int LIMIT = 1000;
    /** Changes this close together come from one keystroke (the key plus what the editor added). */
    private static final long SAME_KEYSTROKE_MS = 60;
    /** Typing pauses longer than this start a new step even inside a word. */
    private static final long TYPING_PAUSE_MS = 2000;

    private final Deque<MutableStep> undo = new ArrayDeque<>();
    private final Deque<Step> redo = new ArrayDeque<>();
    private boolean breakNext;

    private static final class MutableStep {
        final List<Edit> edits = new ArrayList<>();
        final int caretBefore;
        int caretAfter;
        long time;
        Kind kind;

        MutableStep(int caretBefore) {
            this.caretBefore = caretBefore;
        }

        Step freeze() {
            return new Step(Collections.unmodifiableList(new ArrayList<>(edits)), caretBefore, caretAfter);
        }
    }

    private enum Kind { TYPING, BACKSPACE, DELETE, OTHER }

    /** Records a change made by the user or by an editor command. {@code now} is in milliseconds. */
    public void record(Edit edit, int caretBefore, int caretAfter, long now) {
        if (edit.removed().isEmpty() && edit.inserted().isEmpty()) {
            return;
        }
        redo.clear();
        Kind kind = kindOf(edit, caretBefore);
        MutableStep last = undo.peekFirst();
        if (last != null && !breakNext && joins(last, edit, kind, now)) {
            last.edits.add(edit);
            last.caretAfter = caretAfter;
            last.time = now;
            if (last.kind != kind) {
                last.kind = Kind.OTHER; // a keystroke plus what the editor added: nothing more joins it
            }
            return;
        }
        breakNext = false;
        MutableStep step = new MutableStep(caretBefore);
        step.edits.add(edit);
        step.caretAfter = caretAfter;
        step.time = now;
        step.kind = kind;
        undo.addFirst(step);
        while (undo.size() > LIMIT) {
            undo.removeLast();
        }
    }

    /** The next change starts a new step (a command was run, the caret jumped, the file was saved). */
    public void breakGroup() {
        breakNext = true;
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    /** The step to take back (the caller applies {@link #undoText}), or null. */
    public Step undo() {
        MutableStep step = undo.pollFirst();
        if (step == null) {
            return null;
        }
        Step frozen = step.freeze();
        redo.addFirst(frozen);
        breakNext = true;
        return frozen;
    }

    /** The step to do again (the caller applies {@link #redoText}), or null. */
    public Step redo() {
        Step step = redo.pollFirst();
        if (step == null) {
            return null;
        }
        MutableStep again = new MutableStep(step.caretBefore());
        again.edits.addAll(step.edits());
        again.caretAfter = step.caretAfter();
        again.kind = Kind.OTHER;
        undo.addFirst(again);
        breakNext = true;
        return step;
    }

    public void clear() {
        undo.clear();
        redo.clear();
        breakNext = false;
    }

    /** {@code text} with the step taken back: its edits reversed, last first. */
    public static String undoText(String text, Step step) {
        StringBuilder out = new StringBuilder(text);
        List<Edit> edits = step.edits();
        for (int i = edits.size() - 1; i >= 0; i--) {
            Edit edit = edits.get(i);
            out.replace(edit.start(), edit.start() + edit.inserted().length(), edit.removed());
        }
        return out.toString();
    }

    /** {@code text} with the step done again. */
    public static String redoText(String text, Step step) {
        StringBuilder out = new StringBuilder(text);
        for (Edit edit : step.edits()) {
            out.replace(edit.start(), edit.start() + edit.removed().length(), edit.inserted());
        }
        return out.toString();
    }

    /** 0 word character, 1 space or tab, 2 anything else. */
    private static int charClass(char c) {
        return Character.isLetterOrDigit(c) || c == '_' ? 0 : c == ' ' || c == '\t' ? 1 : 2;
    }

    private static Kind kindOf(Edit edit, int caretBefore) {
        if (edit.removed().isEmpty() && edit.inserted().length() == 1) {
            return Kind.TYPING;
        }
        if (edit.inserted().isEmpty() && edit.removed().length() == 1) {
            return edit.start() < caretBefore ? Kind.BACKSPACE : Kind.DELETE;
        }
        return Kind.OTHER;
    }

    private static boolean joins(MutableStep last, Edit edit, Kind kind, long now) {
        if (now - last.time <= SAME_KEYSTROKE_MS) {
            return true; // the editor's own additions to the key just typed
        }
        if (now - last.time > TYPING_PAUSE_MS || kind != last.kind) {
            return false;
        }
        Edit previous = last.edits.get(last.edits.size() - 1);
        return switch (kind) {
            case TYPING -> {
                char c = edit.inserted().charAt(0);
                String before = previous.inserted();
                char p = before.isEmpty() ? '\n' : before.charAt(before.length() - 1);
                // a word (with the spaces before it) is one step; punctuation and Enter start their own
                boolean sameRun = charClass(c) == charClass(p) || charClass(p) == 1 && charClass(c) == 0;
                yield edit.start() == previous.start() + previous.inserted().length() && c != '\n' && p != '\n' && sameRun;
            }
            case BACKSPACE -> edit.start() + edit.removed().length() == previous.start();
            case DELETE -> edit.start() == previous.start();
            case OTHER -> false;
        };
    }
}
