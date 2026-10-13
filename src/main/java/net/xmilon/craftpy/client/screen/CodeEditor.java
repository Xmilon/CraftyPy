package net.xmilon.craftpy.client.screen;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.xmilon.craftpy.assets.AssetKind;
import net.xmilon.craftpy.assets.AssetReference;
import net.xmilon.craftpy.assets.AssetReferences;

/**
 * A self-contained Python text editor for the CraftPy IDE. Pure rendering +
 * input; it has no game or network dependencies. All file IO is coordinated by
 * the owning screen on background threads; edits are applied to the local
 * buffer and flushed to disk on save.
 */
public class CodeEditor {
    private static final int COLOR_TEXT = 0xFFD4D4D4;
    private static final int COLOR_KEYWORD = 0xFFC586C0;
    private static final int COLOR_BUILTIN = 0xFFDCDCAA;
    private static final int COLOR_STRING = 0xFFCE9178;
    private static final int COLOR_COMMENT = 0xFF6A9955;
    private static final int COLOR_NUMBER = 0xFFB5CEA8;
    private static final int COLOR_DECORATOR = 0xFF4EC9B0;
    private static final int COLOR_DEF_NAME = 0xFF9CDCFE;
    private static final int COLOR_CURSOR = 0xFFFFFFFF;
    private static final int COLOR_SELECTION = 0x602C5B7A;
    private static final int COLOR_SEARCH = 0x40FFEB3B;
    private static final int COLOR_BRACKET = 0xFFFFE082;
    private static final int COLOR_GUTTER = 0xFF7D8B9A;
    private static final int COLOR_GUTTER_BG = 0xFF171D25;
    private static final int COLOR_BG = 0xFF10141A;
    private static final int COLOR_LINE_HIGHLIGHT = 0xFF161D26;
    private static final int COLOR_POPUP_BG = 0xFF1B222C;
    private static final int COLOR_POPUP_BORDER = 0xFF4FC3F7;
    private static final int COLOR_POPUP_SEL = 0xFF2C5B7A;
    private static final int COLOR_POPUP_TEXT = 0xFFD7E1EA;
    private static final int COLOR_POPUP_DOC = 0xFF9AB2C6;
    private static final int COLOR_GHOST = 0xFF6E8296;
    private static final int COLOR_JSON_KEY = 0xFF9CDCFE;
    private static final int COLOR_MD_HEADER = 0xFF569CD6;
    private static final int COLOR_MD_LIST = 0xFFC586C0;
    private static final int COLOR_MD_LINK = 0xFF9CDCFE;
    private static final int COLOR_MD_CODE = 0xFFCE9178;

    private static final Set<String> KEYWORDS = Set.of("False", "None", "True", "and", "as", "assert", "async",
            "await", "break", "class", "continue", "def", "del", "elif", "else", "except", "finally", "for", "from",
            "global", "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise", "return", "try",
            "while", "with", "yield");

    private static final Set<String> BUILTINS = Set.of("print", "len", "range", "str", "int", "float", "list", "dict",
            "set", "tuple", "sum", "min", "max", "enumerate", "zip", "map", "filter", "isinstance", "type", "id",
            "open", "sorted", "reversed", "abs", "all", "any", "bool", "bytes", "chr", "ord", "divmod", "format",
            "frozenset", "getattr", "hasattr", "setattr", "hash", "hex", "oct", "bin", "input", "iter", "next",
            "object", "property", "slice", "super", "vars");

    private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** The CraftPy v1 API surface, kept in sync with docs/pyforge_api.md. */
    public static final String[] CRAFTPY_API = { "block", "item", "entity", "effect", "enchantment", "potion",
            "recipe_shaped", "recipe_shapeless", "recipe_smelting", "recipe_blasting", "recipe_smoking",
            "recipe_campfire", "recipe_stonecutting", "recipe_smithing", "loot_table", "loot_entry", "loot_self",
            "tag", "lang", "advancement", "sound", "particle", "raw_json", "tex", "snd", "mdl", "on", "every",
            "on_load", "on_unload", "command", "has", "run_later", "run_every", "run_next_tick", "world", "players",
            "server", "storage", "scheduler", "window", "ui", "cp", "main" };

    private String language = "python";

    private final Font font;
    private int x;
    private int y;
    private int width;
    private int height;

    private final List<String> lines = new ArrayList<>();
    private boolean focused;
    private int clickButton = -1;

    private int cursorLine;
    private int cursorCol;
    private boolean selecting;
    private int anchorLine;
    private int anchorCol;
    private int scrollLine;
    private int scrollX;

    private final Deque<String> undo = new ArrayDeque<>();
    private final Deque<String> redo = new ArrayDeque<>();
    private boolean grouped;

    private String searchQuery = "";
    private int searchFromLine = 0;
    private int matchLine = -1;
    private int matchCol = -1;

    private List<Suggestion> suggestions = List.of();
    private int suggestionIndex;
    private int suggestionLine;
    private int suggestionCol;
    private int suggestionStartCol;

    private CompletionProvider completionProvider;

    public interface CompletionProvider {
        void provideCompletions(String code, int line, int col, String word);
    }

    public void setCompletionProvider(CompletionProvider provider) {
        this.completionProvider = provider;
    }

    public void setExternalSuggestions(List<Suggestion> list, String word) {
        if (list.isEmpty()) {
            closeSuggestions();
            return;
        }
        suggestionStartCol = cursorCol - word.length();
        suggestionLine = cursorLine;
        suggestionCol = cursorCol;
        suggestionIndex = 0;
        suggestions = list;
    }

    /**
     * One completion entry. {@code insert} is what accepting replaces the typed
     * word with; {@code cursorOffset} places the caret inside it (between quotes
     * or parentheses). Multi-line entries are snippet templates shown as a grey
     * "ghost" preview and applied with Tab.
     */
    public static final class Suggestion {
        public final String label;
        public final String signature;
        public final String description;
        public final String insert;
        public final int cursorOffset;
        public final String placeholder;

        public Suggestion(String label, String signature, String description, String insert, int cursorOffset,
                String placeholder) {
            this.label = label;
            this.signature = signature;
            this.description = description;
            this.insert = insert;
            this.cursorOffset = cursorOffset;
            this.placeholder = placeholder;
        }

        public boolean isSnippet() {
            return placeholder != null;
        }
    }

    /** Snippet templates: decorator + the fields a definition needs, ready to fill in. */
    private static final String BLOCK_SNIPPET =
            "@block(\"my_block\", name=\"My Block\", texture=\"minecraft:block/stone\",\n"
                    + "       hardness=2.0, resistance=6.0, light=0, tool=\"pickaxe\",\n"
                    + "       sound=\"stone\", needs_tool=False)\n"
                    + "class MyBlock:\n"
                    + "    pass";
    private static final String ITEM_SNIPPET =
            "@item(\"my_item\", name=\"My Item\", texture=\"minecraft:item/paper\",\n"
                    + "      max_stack=64, glow=False)\n"
                    + "class MyItem:\n"
                    + "    pass";
    private static final String ON_SNIPPET =
            "@on(\"block_break\")\ndef on_break(ctx):\n    pass";
    private static final String RECIPE_SNIPPET =
            "@recipe_shaped([\"AAA\", \" B \", \" B \"],\n"
                    + "               {\"A\": \"minecraft:iron_ingot\", \"B\": \"minecraft:stick\"},\n"
                    + "               result=\"minecraft:iron_pickaxe\")";

    private Runnable changeListener;

    /** Callback used to open the asset picker from a gutter marker or Ctrl+Space. */
    public interface AssetPickerRequest {
        void open(int line, int start, int end, AssetKind kind, boolean existing);
    }

    /** Callback used for shift-click "go to definition". */
    public interface DefinitionLookup {
        boolean goTo(int line, int col);
    }

    private AssetPickerRequest assetPickerRequest;

    public void setDefinitionLookup(DefinitionLookup lookup) {
        this.definitionLookup = lookup;
    }

    private DefinitionLookup definitionLookup;

    /** Scrolls so the given line is visible (roughly centered). */
    public void scrollToLine(int target) {
        int visible = visibleLineCount();
        int line = Math.max(0, Math.min(target, lines.size() - 1));
        scrollLine = Math.max(0, line - visible / 2);
        clampCursor();
    }

    public int visibleLineCount() {
        int lineHeight = Math.max(1, lineHeight());
        return Math.max(1, (int) (height / lineHeight));
    }
    private final List<int[]> assetMarkers = new ArrayList<>();

    /** Bumped whenever the document changes; used to invalidate per-frame caches. */
    private long docStamp;
    private long cacheStamp = -1;
    private List<Token>[] tokenCache;
    private List<AssetReference>[] refCache;

    public CodeEditor(Font font) {
        this.font = font;
        setContent("");
    }

    public void setAssetPickerRequest(AssetPickerRequest request) {
        this.assetPickerRequest = request;
    }

    public int cursorLineIndex() {
        return cursorLine;
    }

    public int cursorColumn() {
        return cursorCol;
    }

    /** Replaces the text in [start,end) on the given line, recording one undo step. */
    public void replaceRange(int line, int start, int end, String text) {
        if (line < 0 || line >= lines.size()) {
            return;
        }
        beginGroup();
        String current = lines.get(line);
        int s = Math.max(0, Math.min(start, current.length()));
        int e = Math.max(s, Math.min(end, current.length()));
        lines.set(line, current.substring(0, s) + text + current.substring(e));
        cursorLine = line;
        cursorCol = s + text.length();
        clampCursor();
        endGroup();
        notifyChanged();
    }

    private boolean openAssetAtCursor() {
        if (assetPickerRequest == null) {
            return false;
        }
        String line = currentLine();
        List<AssetReference> refs = AssetReferences.parse(line);
        for (AssetReference ref : refs) {
            if (cursorCol >= ref.start() && cursorCol <= ref.end() + 1) {
                assetPickerRequest.open(cursorLine, ref.start(), ref.end(), ref.kind(), true);
                return true;
            }
        }
        // No reference under the cursor: offer to insert a tex(...) call.
        AssetKind guess = AssetKind.TEXTURE;
        String lower = line.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("sound")) {
            guess = AssetKind.SOUND;
        } else if (lower.contains("model")) {
            guess = AssetKind.MODEL;
        } else if (lower.contains("icon")) {
            guess = AssetKind.ITEM;
        }
        assetPickerRequest.open(cursorLine, cursorCol, cursorCol, guess, false);
        return true;
    }

    public void setChangeListener(Runnable listener) {
        this.changeListener = listener;
    }

    private void notifyChanged() {
        docStamp++;
        if (changeListener != null) {
            changeListener.run();
        }
    }

    // ------------------------------------------------------------------
    // Content
    // ------------------------------------------------------------------

    public void setContent(String content) {
        lines.clear();
        if (content == null || content.isEmpty()) {
            lines.add("");
        } else {
            String[] parts = content.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);
            java.util.Collections.addAll(lines, parts);
            if (lines.get(lines.size() - 1).isEmpty() && lines.size() > 1) {
                lines.remove(lines.size() - 1);
            }
            if (lines.isEmpty()) {
                lines.add("");
            }
        }
        cursorLine = 0;
        cursorCol = 0;
        anchorLine = 0;
        anchorCol = 0;
        scrollLine = 0;
        scrollX = 0;
        undo.clear();
        redo.clear();
        closeSuggestions();
        docStamp++;
    }

    public String getContent() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(lines.get(i));
        }
        return builder.toString();
    }

    public String currentLine() {
        return lineAt(cursorLine);
    }

    public String lineAt(int index) {
        if (index < 0 || index >= lines.size()) {
            return "";
        }
        return lines.get(index);
    }

    /** Returns the identifier under (line, col), or "" if the position is not inside a word. */
    public String wordAt(int line, int col) {
        String text = lineAt(line);
        Matcher matcher = WORD.matcher(text);
        while (matcher.find()) {
            if (col >= matcher.start() && col <= matcher.end()) {
                return matcher.group();
            }
        }
        return "";
    }

    public int lineCount() {
        return lines.size();
    }

    public void setLanguage(String lang) {
        if (lang == null) lang = "python";
        lang = lang.toLowerCase(java.util.Locale.ROOT);
        if (!lang.equals(this.language)) {
            this.language = lang;
            docStamp++; // Force re-tokenize
        }
    }

    public String getLanguage() {
        return language;
    }

    public void setBounds(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public void setFocused(boolean focused) {
        this.focused = focused;
        if (!focused) {
            closeSuggestions();
            clickButton = -1;
        }
    }

    public boolean isFocused() {
        return focused;
    }

    public String searchQuery() {
        return searchQuery;
    }

    public void setSearchQuery(String query) {
        this.searchQuery = query == null ? "" : query;
        this.matches = null;
        this.searchFromLine = cursorLine;
        findNextMatch();
    }

    // ------------------------------------------------------------------
    // Undo
    // ------------------------------------------------------------------

    private void beginGroup() {
        if (grouped) {
            return;
        }
        undo.push(getContent());
        while (undo.size() > 300) {
            undo.removeLast();
        }
        redo.clear();
        grouped = true;
    }

    private void endGroup() {
        grouped = false;
    }

    public void undo() {
        if (undo.isEmpty()) {
            return;
        }
        redo.push(getContent());
        restore(undo.pop());
        notifyChanged();
    }

    public void redo() {
        if (redo.isEmpty()) {
            return;
        }
        undo.push(getContent());
        restore(redo.pop());
        notifyChanged();
    }

    private void restore(String content) {
        lines.clear();
        if (content.isEmpty()) {
            lines.add("");
        } else {
            String[] parts = content.split("\n", -1);
            java.util.Collections.addAll(lines, parts);
        }
        clampCursor();
        closeSuggestions();
    }

    public boolean hasSelection() {
        return selecting && !(anchorLine == cursorLine && anchorCol == cursorCol);
    }

    public String getSelectedText() {
        if (!hasSelection()) {
            return "";
        }
        int[] start = selectionStart();
        int[] end = selectionEnd();
        if (start[0] == end[0]) {
            return lines.get(start[0]).substring(start[1], end[1]);
        }
        StringBuilder builder = new StringBuilder();
        builder.append(lines.get(start[0]).substring(start[1]));
        for (int i = start[0] + 1; i < end[0]; i++) {
            builder.append('\n').append(lines.get(i));
        }
        builder.append('\n').append(lines.get(end[0]), 0, end[1]);
        return builder.toString();
    }

    public void selectAll() {
        selecting = true;
        anchorLine = 0;
        anchorCol = 0;
        cursorLine = lines.size() - 1;
        cursorCol = lines.get(cursorLine).length();
        clampCursor();
    }

    // ------------------------------------------------------------------
    // Editing primitives
    // ------------------------------------------------------------------

    private void insertWithoutSnapshot(String text) {
        String[] parts = text.split("\n", -1);
        String line = lines.get(cursorLine);
        if (parts.length == 1) {
            lines.set(cursorLine, line.substring(0, cursorCol) + parts[0] + line.substring(cursorCol));
            cursorCol += parts[0].length();
            return;
        }
        lines.set(cursorLine, line.substring(0, cursorCol) + parts[0]);
        for (int i = 1; i < parts.length; i++) {
            lines.add(cursorLine + i, parts[i] + (i == parts.length - 1 ? line.substring(cursorCol) : ""));
        }
        cursorLine = Math.min(lines.size() - 1, cursorLine + parts.length - 1);
        cursorCol = parts[parts.length - 1].length();
    }

    private void newline() {
        String line = lines.get(cursorLine);
        String before = line.substring(0, cursorCol);
        String after = line.substring(cursorCol);
        String trimmed = before.trim();
        String indent = currentIndent(cursorLine);
        if (!trimmed.isEmpty() && (trimmed.endsWith(":") || trimmed.endsWith("(") || trimmed.endsWith("[")
                || trimmed.endsWith("{"))) {
            indent = indent + "    ";
        }
        lines.set(cursorLine, before);
        lines.add(cursorLine + 1, indent + after);
        cursorLine++;
        cursorCol = indent.length();
    }

    private String currentIndent(int lineIndex) {
        String line = lineAt(lineIndex);
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return line.substring(0, i);
    }

    private void deleteBack() {
        if (hasSelection()) {
            deleteSelection();
            return;
        }
        if (cursorCol > 0) {
            String line = lines.get(cursorLine);
            int step = 1;
            if (cursorCol >= 4 && line.startsWith("    ") && cursorCol % 4 == 0
                    && indentationOnly(line, cursorCol)) {
                step = 4;
            }
            lines.set(cursorLine, line.substring(0, cursorCol - step) + line.substring(cursorCol));
            cursorCol -= step;
        } else if (cursorLine > 0) {
            String prev = lines.get(cursorLine - 1);
            cursorCol = prev.length();
            lines.set(cursorLine - 1, prev + lines.get(cursorLine));
            lines.remove(cursorLine);
            cursorLine--;
        }
        clampCursor();
    }

    private boolean indentationOnly(String line, int col) {
        for (int i = 0; i < col; i++) {
            if (line.charAt(i) != ' ') {
                return false;
            }
        }
        return true;
    }

    private void deleteForward() {
        if (hasSelection()) {
            deleteSelection();
            return;
        }
        String line = lines.get(cursorLine);
        if (cursorCol < line.length()) {
            lines.set(cursorLine, line.substring(0, cursorCol) + line.substring(cursorCol + 1));
        } else if (cursorLine < lines.size() - 1) {
            lines.set(cursorLine, line + lines.get(cursorLine + 1));
            lines.remove(cursorLine + 1);
        }
        clampCursor();
    }

    private void deleteSelection() {
        int[] start = selectionStart();
        int[] end = selectionEnd();
        if (start[0] == end[0]) {
            String line = lines.get(start[0]);
            lines.set(start[0], line.substring(0, start[1]) + line.substring(end[1]));
        } else {
            String head = lines.get(start[0]).substring(0, start[1]);
            String tail = lines.get(end[0]).substring(end[1]);
            for (int i = end[0]; i > start[0]; i--) {
                lines.remove(i);
            }
            lines.set(start[0], head + tail);
        }
        cursorLine = start[0];
        cursorCol = start[1];
        setAnchorToCursor();
        clampCursor();
    }

    public void copy() {
        if (hasSelection()) {
            Minecraft.getInstance().keyboardHandler.setClipboard(getSelectedText());
        }
    }

    public void cut() {
        if (hasSelection()) {
            String text = getSelectedText();
            Minecraft.getInstance().keyboardHandler.setClipboard(text);
            beginGroup();
            deleteSelection();
            endGroup();
            notifyChanged();
        }
    }

    public void paste() {
        String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
        if (clip == null || clip.isEmpty()) {
            return;
        }
        beginGroup();
        if (hasSelection()) {
            deleteSelection();
        }
        insertWithoutSnapshot(clip.replace("\r\n", "\n").replace("\r", "\n"));
        clampCursor();
        endGroup();
        notifyChanged();
    }

    /** Returns the matching closer for an opening bracket/quote, or 0 for normal chars. */
    private char autoclose(char codePoint) {
        return switch (codePoint) {
            case '(' -> ')';
            case '[' -> ']';
            case '{' -> '}';
            case '"', '\'' -> codePoint;
            default -> 0;
        };
    }

    /** Ctrl+D: duplicates the current line (or the text selected on one line) below. */
    private void duplicateLine() {
        beginGroup();
        if (hasSelection() && selectionStart()[0] == selectionEnd()[0]) {
            int[] start = selectionStart();
            int[] end = selectionEnd();
            String line = lines.get(start[0]);
            String selected = line.substring(start[1], end[1]);
            lines.set(start[0], line.substring(0, end[1]) + selected + line.substring(end[1]));
            cursorLine = start[0];
            cursorCol = end[1] + selected.length();
        } else {
            String line = lines.get(cursorLine);
            lines.add(cursorLine + 1, line);
            cursorLine++;
        }
        setAnchorToCursor();
        clampCursor();
        endGroup();
        notifyChanged();
    }

    /** Ctrl+/: toggles "# " comments on the cursor line or every selected line. */
    private void toggleComment() {
        int start = Math.min(anchorLine, cursorLine);
        int end = Math.max(anchorLine, cursorLine);
        boolean uncomment = true;
        for (int i = start; i <= end; i++) {
            if (!lineAt(i).isBlank() && !isCommented(lineAt(i))) {
                uncomment = false;
                break;
            }
        }
        boolean anyChanged = false;
        beginGroup();
        for (int i = start; i <= end; i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            String updated = uncomment ? stripComment(line) : insertComment(line);
            if (!updated.equals(line)) {
                lines.set(i, updated);
                anyChanged = true;
            }
        }
        setAnchorToCursor();
        clampCursor();
        endGroup();
        if (anyChanged) {
            notifyChanged();
        }
    }

    private static boolean isCommented(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i < line.length() && line.charAt(i) == '#';
    }

    private static String stripComment(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        if (i < line.length() && line.charAt(i) == '#') {
            int end = i + 1;
            if (end < line.length() && line.charAt(end) == ' ') {
                end++;
            }
            return line.substring(0, i) + line.substring(end);
        }
        return line;
    }

    private static String insertComment(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return line.substring(0, i) + "# " + line.substring(i);
    }

    private void indentSelection(boolean unindent) {
        int start = Math.min(anchorLine, cursorLine);
        int end = Math.max(anchorLine, cursorLine);
        if (unindent) {
            for (int i = start; i <= end; i++) {
                String line = lines.get(i);
                if (line.startsWith("    ")) {
                    lines.set(i, line.substring(4));
                } else if (line.startsWith(" ")) {
                    lines.set(i, line.substring(1));
                }
            }
            cursorCol = Math.max(0, cursorCol - 4);
            anchorCol = Math.max(0, anchorCol - 4);
        } else {
            for (int i = start; i <= end; i++) {
                lines.set(i, "    " + lines.get(i));
            }
            cursorCol += 4;
            anchorCol += 4;
        }
        clampCursor();
    }

    // ------------------------------------------------------------------
    // Cursor
    // ------------------------------------------------------------------

    public void clampCursor() {
        if (lines.isEmpty()) {
            lines.add("");
        }
        cursorLine = Math.max(0, Math.min(cursorLine, lines.size() - 1));
        anchorLine = Math.max(0, Math.min(anchorLine, lines.size() - 1));
        cursorCol = Math.max(0, Math.min(cursorCol, lines.get(cursorLine).length()));
        anchorCol = Math.max(0, Math.min(anchorCol, lines.get(anchorLine).length()));
        int visible = Math.max(1, (int) (height / lineHeight()));
        if (cursorLine < scrollLine) {
            scrollLine = cursorLine;
        }
        if (cursorLine >= scrollLine + visible) {
            scrollLine = cursorLine - visible + 1;
        }
        int xPx = colToX(currentLine(), cursorCol);
        int avail = textWidth();
        if (xPx < scrollX) {
            scrollX = Math.max(0, xPx - 16);
        }
        if (xPx > scrollX + avail) {
            scrollX = Math.max(0, xPx - avail + 8);
        }
    }

    private void moveCursor(int line, int col, boolean extend) {
        int clampedLine = Math.max(0, Math.min(line, lines.size() - 1));
        int clampedCol = Math.max(0, Math.min(col, lines.get(clampedLine).length()));
        cursorLine = clampedLine;
        cursorCol = clampedCol;
        if (!extend) {
            setAnchorToCursor();
        }
        // Moving the caret away from the typed word dismisses the popup.
        closeSuggestions();
        clampCursor();
    }

    private void setAnchorToCursor() {
        anchorLine = cursorLine;
        anchorCol = cursorCol;
        selecting = false;
    }

    private int[] selectionStart() {
        if (!selecting) {
            return new int[] { cursorLine, cursorCol };
        }
        if (cursorLine < anchorLine || (cursorLine == anchorLine && cursorCol < anchorCol)) {
            return new int[] { cursorLine, cursorCol };
        }
        return new int[] { anchorLine, anchorCol };
    }

    private int[] selectionEnd() {
        if (!selecting) {
            return new int[] { cursorLine, cursorCol };
        }
        int[] start = selectionStart();
        if (start[0] == anchorLine && start[1] == anchorCol) {
            return new int[] { cursorLine, cursorCol };
        }
        return new int[] { anchorLine, anchorCol };
    }

    // ------------------------------------------------------------------
    // Mouse
    // ------------------------------------------------------------------

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Gutter asset markers take priority over text placement.
        for (int[] marker : assetMarkers) {
            if (mouseX >= marker[1] && mouseX <= marker[1] + marker[3]
                    && mouseY >= marker[2] && mouseY <= marker[2] + marker[4]
                    && assetPickerRequest != null) {
                int line = marker[0];
                List<AssetReference> refs = AssetReferences.parse(lineAt(line));
                if (!refs.isEmpty()) {
                    AssetReference ref = refs.get(0);
                    assetPickerRequest.open(line, ref.start(), ref.end(), ref.kind(), true);
                    return true;
                }
            }
        }
        if (!contains(mouseX, mouseY)) {
            return false;
        }
        int[] pos = posAt(mouseX, mouseY);
        if (button == 0 && Screen.hasShiftDown() && definitionLookup != null
                && definitionLookup.goTo(pos[0], pos[1])) {
            return true;
        }
        cursorLine = Math.max(0, Math.min(pos[0], lines.size() - 1));
        cursorCol = Math.max(0, Math.min(pos[1], lines.get(cursorLine).length()));
        setAnchorToCursor();
        closeSuggestions();
        clickButton = button;
        return true;
    }

    public void mouseDragged(double mouseX, double mouseY, int button) {
        if (clickButton != 0) {
            return;
        }
        int[] pos = posAt(mouseX, mouseY);
        int targetLine = Math.max(0, Math.min(pos[0], lines.size() - 1));
        int targetCol = Math.max(0, Math.min(pos[1], lines.get(targetLine).length()));
        if (!selecting) {
            selecting = true;
            anchorLine = cursorLine;
            anchorCol = cursorCol;
        }
        cursorLine = targetLine;
        cursorCol = targetCol;
        clampCursor();
    }

    public boolean mouseScrolled(double horizontalAmount, double verticalAmount) {
        double result = horizontalAmount;
        if (result == 0) {
            result = verticalAmount;
        }
        if (!focused || result == 0) {
            return false;
        }
        int maxScroll = Math.max(0, lines.size() - Math.max(1, height / lineHeight()));
        scrollLine = Math.min(maxScroll, Math.max(0, scrollLine - (int) Math.signum(result) * 3));
        return true;
    }

    /** True when the point falls inside the visible editor region (source of truth for hit-testing). */
    public boolean isInside(double mouseX, double mouseY) {
        return contains(mouseX, mouseY);
    }

    private boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    private int[] posAt(double mouseX, double mouseY) {
        double localX = (mouseX - x - gutterWidth()) + scrollX;
        double localY = (mouseY - y);
        // The visible row is relative to the scroll position; without this the
        // caret jumped to the top of the document whenever it was scrolled.
        int line = Math.max(0, Math.min(scrollLine + (int) (localY / lineHeight()), lines.size() - 1));
        String text = lineAt(line);
        int col = 0;
        for (int i = 0; i <= text.length(); i++) {
            int w = font.width(text.substring(0, i));
            if (localX < w + font.width(" ") / 2.0) {
                col = i;
                break;
            }
            col = i + 1;
        }
        return new int[] { line, col };
    }

    // ------------------------------------------------------------------
    // Keyboard
    // ------------------------------------------------------------------

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean ctrl = Screen.hasControlDown();
        boolean shift = Screen.hasShiftDown();

        if (!suggestions.isEmpty()) {
            switch (keyCode) {
                case GLFW.GLFW_KEY_UP -> {
                    suggestionIndex = Math.max(0, suggestionIndex - 1);
                    return true;
                }
                case GLFW.GLFW_KEY_DOWN -> {
                    suggestionIndex = Math.min(suggestions.size() - 1, suggestionIndex + 1);
                    return true;
                }
                case GLFW.GLFW_KEY_TAB, GLFW.GLFW_KEY_ENTER -> {
                    acceptSuggestion();
                    return true;
                }
                case GLFW.GLFW_KEY_ESCAPE -> {
                    closeSuggestions();
                    return true;
                }
                default -> {
                }
            }
        }

        if (ctrl) {
            switch (keyCode) {
                case GLFW.GLFW_KEY_A -> {
                    selectAll();
                    return true;
                }
                case GLFW.GLFW_KEY_C -> {
                    copy();
                    return true;
                }
                case GLFW.GLFW_KEY_X -> {
                    cut();
                    return true;
                }
                case GLFW.GLFW_KEY_V -> {
                    paste();
                    return true;
                }
                case GLFW.GLFW_KEY_Z -> {
                    undo();
                    return true;
                }
                case GLFW.GLFW_KEY_Y -> {
                    redo();
                    return true;
                }
                case GLFW.GLFW_KEY_SPACE -> {
                    if (!openAssetAtCursor()) {
                        openSuggestions();
                    }
                    return true;
                }
                case GLFW.GLFW_KEY_D -> {
                    duplicateLine();
                    return true;
                }
                case GLFW.GLFW_KEY_SLASH -> {
                    toggleComment();
                    return true;
                }
                default -> {
                }
            }
        }

        switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT -> {
                int target = cursorCol - 1;
                int line = cursorLine;
                if (target < 0 && line > 0) {
                    line--;
                    target = lines.get(line).length();
                }
                moveCursor(line, Math.max(0, target), shift);
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                int target = cursorCol + 1;
                int line = cursorLine;
                if (cursorCol >= lines.get(line).length() && line < lines.size() - 1) {
                    line++;
                    target = 0;
                }
                moveCursor(line, target, shift);
            }
            case GLFW.GLFW_KEY_UP -> moveCursor(cursorLine - 1, cursorCol, shift);
            case GLFW.GLFW_KEY_DOWN -> moveCursor(cursorLine + 1, cursorCol, shift);
            case GLFW.GLFW_KEY_HOME -> moveCursor(ctrl ? 0 : cursorLine, 0, shift);
            case GLFW.GLFW_KEY_END -> moveCursor(ctrl ? lines.size() - 1 : cursorLine,
                    lines.get(cursorLine).length(), shift);
            case GLFW.GLFW_KEY_PAGE_UP -> moveCursor(Math.max(0, cursorLine - (int) (height / lineHeight())), cursorCol,
                    shift);
            case GLFW.GLFW_KEY_PAGE_DOWN -> moveCursor(Math.min(lines.size() - 1, cursorLine + (int) (height / lineHeight())),
                    cursorCol, shift);
            case GLFW.GLFW_KEY_BACKSPACE -> {
                beginGroup();
                deleteBack();
                endGroup();
                maybeSuggest();
                notifyChanged();
                return true;
            }
            case GLFW.GLFW_KEY_DELETE -> {
                beginGroup();
                deleteForward();
                endGroup();
                notifyChanged();
                return true;
            }
            case GLFW.GLFW_KEY_ENTER -> {
                beginGroup();
                newline();
                clampCursor();
                endGroup();
                notifyChanged();
                return true;
            }
            case GLFW.GLFW_KEY_TAB -> {
                beginGroup();
                if (hasSelection()) {
                    indentSelection(shift);
                } else {
                    insertWithoutSnapshot("    ");
                    clampCursor();
                }
                endGroup();
                notifyChanged();
                return true;
            }
            case GLFW.GLFW_KEY_ESCAPE -> {
                closeSuggestions();
                if (!searchQuery.isEmpty()) {
                    setSearchQuery("");
                    return true;
                }
                return false;
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    public boolean charTyped(char codePoint, int modifiers) {
        if (codePoint == 0 || Character.isISOControl(codePoint)) {
            return false;
        }
        beginGroup();
        if (hasSelection()) {
            deleteSelection();
            insertWithoutSnapshot(String.valueOf(codePoint));
            closeSuggestions();
        } else {
            char close = autoclose(codePoint);
            String line = lines.get(cursorLine);
            if (close != 0) {
                // Overtype instead of duplicating a closing bracket/quote (PyCharm behavior).
                if (cursorCol < line.length() && line.charAt(cursorCol) == close) {
                    cursorCol++;
                } else {
                    lines.set(cursorLine,
                            line.substring(0, cursorCol) + codePoint + close + line.substring(cursorCol));
                    cursorCol++;
                }
                closeSuggestions();
            } else {
                lines.set(cursorLine, line.substring(0, cursorCol) + codePoint + line.substring(cursorCol));
                cursorCol++;
                if (Character.isLetterOrDigit(codePoint) || codePoint == '_') {
                    maybeSuggest();
                } else {
                    // Typing punctuation ends the word: drop the popup instead of leaving it stale.
                    closeSuggestions();
                }
            }
        }
        clampCursor();
        endGroup();
        notifyChanged();
        return true;
    }

    // ------------------------------------------------------------------
    // Suggestions
    // ------------------------------------------------------------------

    /** The visible suggestion labels (backing list is internal). */
    public List<String> suggestionsBacking() {
        List<String> labels = new ArrayList<>();
        for (Suggestion suggestion : suggestions) {
            labels.add(suggestion.label);
        }
        return labels;
    }

    /**
     * Recomputes the popup for the word being typed. Anything that no longer
     * matches - a word without candidates, or a non-word character - dismisses
     * the popup instead of leaving stale entries on screen.
     */
    private void maybeSuggest() {
        String word = currentWord();
        if (word.length() < 2 || !focused) {
            closeSuggestions();
            return;
        }
        
        if (completionProvider != null) {
            completionProvider.provideCompletions(getContent(), cursorLine, cursorCol, word);
        }

        List<Suggestion> list = suggestFor(word);
        if (list.isEmpty() && completionProvider == null) {
            closeSuggestions();
            return;
        }
        if (!list.isEmpty()) {
            suggestionStartCol = cursorCol - word.length();
            suggestionLine = cursorLine;
            suggestionCol = cursorCol;
            suggestionIndex = 0;
            suggestions = list;
        }
    }

    private void openSuggestions() {
        String word = currentWord();
        List<Suggestion> list = suggestFor(word);
        if (list.isEmpty()) {
            closeSuggestions();
            return;
        }
        suggestionStartCol = cursorCol - word.length();
        suggestionLine = cursorLine;
        suggestionCol = cursorCol;
        suggestionIndex = 0;
        suggestions = list;
    }

    private String currentWord() {
        String line = currentLine();
        int start = cursorCol;
        while (start > 0 && isWordChar(line.charAt(start - 1))) {
            start--;
        }
        return line.substring(start, cursorCol);
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private void closeSuggestions() {
        suggestions = List.of();
        suggestionIndex = 0;
    }    private boolean isFunctionName(String word) {
        return word.startsWith("recipe_") || word.equals("loot_self") || word.equals("has")
                || word.equals("run_later") || word.equals("run_every") || word.equals("run_next_tick")
                || word.equals("every");
    }

    /** Functions taking one id string: accepting inserts {@code name("")} with the caret inside. */
    private boolean isIdCall(String word) {
        return word.equals("tex") || word.equals("snd") || word.equals("mdl") || word.equals("on")
                || word.equals("advancement") || word.equals("sound") || word.equals("particle")
                || word.equals("loot_table") || word.equals("loot_entry") || word.equals("tag")
                || word.equals("raw_json") || word.equals("potion") || word.equals("enchantment")
                || word.equals("command") || word.equals("window") || word.equals("lang");
    }

    /**
     * Accepts the selected entry: snippets insert their full template (with the
     * placeholder selected so typing replaces it), fields insert {@code name=""},
     * function calls insert {@code name()} with the caret inside the parens.
     */
    private void acceptSuggestion() {
        if (suggestions.isEmpty()) {
            return;
        }
        Suggestion chosen = suggestions.get(suggestionIndex);
        int start = Math.max(0, Math.min(suggestionStartCol, currentLine().length()));
        int end = Math.min(Math.max(suggestionCol, cursorCol), currentLine().length());
        beginGroup();
        String line = currentLine();
        String before = line.substring(0, start);
        String after = line.substring(end);
        if (chosen.isSnippet() && before.isBlank()) {
            // Full template at a statement start; the placeholder stays selected.
            String[] parts = chosen.insert.split("\n", -1);
            lines.set(suggestionLine, before + parts[0] + after);
            for (int i = 1; i < parts.length; i++) {
                lines.add(suggestionLine + i, parts[i]);
            }
            int phLine = -1;
            int phStart = -1;
            for (int i = 0; i < parts.length; i++) {
                int at = parts[i].indexOf(chosen.placeholder);
                if (at >= 0) {
                    phLine = i;
                    phStart = at + (i == 0 ? before.length() : 0);
                    break;
                }
            }
            if (phLine >= 0) {
                cursorLine = suggestionLine + phLine;
                cursorCol = phStart + chosen.placeholder.length();
                anchorLine = cursorLine;
                anchorCol = phStart;
                selecting = true;
            } else {
                cursorLine = suggestionLine + parts.length - 1;
                cursorCol = parts[parts.length - 1].length();
                setAnchorToCursor();
            }
        } else {
            lines.set(cursorLine, before + chosen.insert + after);
            cursorCol = before.length() + chosen.cursorOffset;
            setAnchorToCursor();
        }
        clampCursor();
        endGroup();
        closeSuggestions();
        notifyChanged();
    }

    private List<Suggestion> suggestFor(String prefix) {
        List<Suggestion> result = new ArrayList<>();

        // Inside @block( ... ) / @item( ... ) etc: offer the fields of that kind,
        // each with a short explanation of what it controls.
        String decorator = activeDecorator();
        if (decorator != null) {
            for (ApiDocs.Field field : ApiDocs.fieldsFor(decorator)) {
                if (field.name().startsWith(prefix)) {
                    String insert = field.name() + "=\"\"";
                    result.add(new Suggestion(field.name(), field.name() + " = ...", field.description(),
                            insert, field.name().length() + 2, null));
                }
            }
            return result;
        }

        // Snippet templates for declarations (block / item).
        if ("block".startsWith(prefix)) {
            ApiDocs.Doc doc = ApiDocs.get("block");
            result.add(new Suggestion("block", doc.signature(), doc.description(), BLOCK_SNIPPET, 0, "my_block"));
        }
        if ("item".startsWith(prefix)) {
            ApiDocs.Doc doc = ApiDocs.get("item");
            result.add(new Suggestion("item", doc.signature(), doc.description(), ITEM_SNIPPET, 0, "my_item"));
        }
        if ("on".startsWith(prefix)) {
            ApiDocs.Doc doc = ApiDocs.get("on");
            result.add(new Suggestion("on", doc != null ? doc.signature() : "@on(event)", "", ON_SNIPPET, 0, "block_break"));
        }
        if ("recipe_shaped".startsWith(prefix)) {
            ApiDocs.Doc doc = ApiDocs.get("recipe_shaped");
            result.add(new Suggestion("recipe_shaped", doc != null ? doc.signature() : "@recipe_shaped(...)", "", RECIPE_SNIPPET, 0, "minecraft:iron_ingot"));
        }

        // Documented API names with signatures and descriptions.
        Set<String> known = new HashSet<>();
        for (String name : CRAFTPY_API) {
            known.add(name);
            // block/item are covered by the snippet templates above.
            if (!name.startsWith(prefix) || name.equals("block") || name.equals("item")) {
                continue;
            }
            ApiDocs.Doc doc = ApiDocs.get(name);
            String signature = doc != null ? doc.signature() : name;
            String description = doc != null ? doc.description() : "";
            if (isIdCall(name)) {
                result.add(new Suggestion(name, signature, description, name + "(\"\")", name.length() + 2, null));
            } else if (isFunctionName(name)) {
                result.add(new Suggestion(name, signature, description, name + "()", name.length() + 1, null));
            } else {
                result.add(new Suggestion(name, signature, description, name, name.length(), null));
            }
        }

        // Keywords, builtins and identifiers already present in the document.
        Set<String> words = new HashSet<>();
        words.addAll(KEYWORDS);
        words.addAll(BUILTINS);
        for (String line : lines) {
            Matcher matcher = WORD.matcher(line);
            while (matcher.find()) {
                words.add(matcher.group());
            }
        }
        for (String word : words) {
            if (!word.startsWith(prefix) || known.contains(word)) {
                continue;
            }
            String signature = KEYWORDS.contains(word) ? "keyword" : BUILTINS.contains(word) ? "builtin" : "";
            result.add(new Suggestion(word, signature, "", word, word.length(), null));
        }

        result.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.label, b.label));
        return result.size() > 24 ? new ArrayList<>(result.subList(0, 24)) : result;
    }

    private static final java.util.regex.Pattern DECORATOR =
            java.util.regex.Pattern.compile("@(block|item|entity|effect)\\s*\\(");

    /**
     * The declaration decorator whose argument list contains the cursor:
     * "block", "item", "entity" or "effect", or null when outside one.
     */
    private String activeDecorator() {
        for (int i = cursorLine; i >= 0 && cursorLine - i < 12; i--) {
            String scan = lineAt(i);
            int limit = scan.length();
            if (i == cursorLine) {
                limit = Math.min(cursorCol, scan.length());
            }
            Matcher matcher = DECORATOR.matcher(scan.substring(0, limit));
            int start = -1;
            String kind = null;
            while (matcher.find()) {
                kind = matcher.group(1);
                start = matcher.start();
            }
            if (kind == null) {
                continue;
            }
            StringBuilder region = new StringBuilder();
            if (i == cursorLine) {
                region.append(scan, start, limit);
            } else {
                region.append(scan.substring(start));
                for (int j = i + 1; j <= cursorLine; j++) {
                    region.append('\n');
                    String l = lineAt(j);
                    region.append(j == cursorLine ? l.substring(0, Math.min(cursorCol, l.length())) : l);
                }
            }
            if (parenBalance(region.toString()) > 0) {
                return kind;
            }
        }
        return null;
    }

    /** Counts unclosed '(' in the region, ignoring parentheses inside strings. */
    private static int parenBalance(String region) {
        int balance = 0;
        boolean inString = false;
        char quote = 0;
        for (int i = 0; i < region.length(); i++) {
            char c = region.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    inString = false;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                inString = true;
                quote = c;
            } else if (c == '(') {
                balance++;
            } else if (c == ')') {
                balance--;
            }
        }
        return balance;
    }

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------

    private int[][] matches;

    public void findNextMatch() {
        if (searchQuery.isEmpty()) {
            matches = null;
            matchLine = -1;
            matchCol = -1;
            return;
        }
        List<int[]> found = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String text = lines.get(i);
            int idx = text.indexOf(searchQuery);
            while (idx >= 0) {
                found.add(new int[] { i, idx });
                idx = text.indexOf(searchQuery, idx + 1);
            }
        }
        matches = found.isEmpty() ? null : found.toArray(new int[0][0]);
        if (matches == null) {
            matchLine = -1;
            matchCol = -1;
            return;
        }
        int best = 0;
        for (int i = 0; i < matches.length; i++) {
            if (matches[i][0] > matchLine || (matches[i][0] == matchLine && matches[i][1] >= matchCol)) {
                best = i;
                break;
            }
        }
        jumpToMatch(best);
    }

    public void findNext() {
        if (matches == null || matches.length == 0) {
            findNextMatch();
            return;
        }
        int current = currentMatchIndex();
        jumpToMatch((current + 1) % matches.length);
    }

    public void findPrevious() {
        if (matches == null || matches.length == 0) {
            findNextMatch();
            return;
        }
        int current = currentMatchIndex();
        jumpToMatch((current - 1 + matches.length) % matches.length);
    }

    private int currentMatchIndex() {
        for (int i = 0; i < matches.length; i++) {
            if (matches[i][0] == matchLine && matches[i][1] == matchCol) {
                return i;
            }
        }
        return -1;
    }

    private void jumpToMatch(int index) {
        matchLine = matches[index][0];
        matchCol = matches[index][1];
        cursorLine = matchLine;
        cursorCol = matchCol;
        setAnchorToCursor();
        clampCursor();
    }

    public int matchCount() {
        return matches == null ? 0 : matches.length;
    }

    /**
     * Replaces the current search match. The caret moves past the replacement and
     * the scan jumps to the next remaining match. Returns 0 when nothing matched.
     */
    public int replaceCurrent(String replacement) {
        if (searchQuery.isEmpty() || matches == null || matches.length == 0) {
            return 0;
        }
        String query = searchQuery;
        String line = lines.get(matchLine);
        int end = Math.min(line.length(), matchCol + query.length());
        if (!line.substring(matchCol, end).equals(query)) {
            setSearchQuery(query);
            return matchCount();
        }
        String fill = replacement == null ? "" : replacement;
        beginGroup();
        lines.set(matchLine, line.substring(0, matchCol) + fill + line.substring(end));
        endGroup();
        cursorLine = matchLine;
        cursorCol = matchCol + fill.length();
        clampCursor();
        notifyChanged();
        setSearchQuery(query);
        return matchCount();
    }

    /** Replaces every occurrence of the search query. Returns the number of replacements made. */
    public int replaceAll(String replacement) {
        if (searchQuery.isEmpty()) {
            return 0;
        }
        String query = searchQuery;
        String content = getContent();
        String fill = replacement == null ? "" : replacement;
        String updated = content.replace(query, fill);
        if (updated.equals(content)) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = content.indexOf(query, idx)) >= 0) {
            count++;
            idx += query.length();
        }
        beginGroup();
        restore(updated);
        endGroup();
        notifyChanged();
        setSearchQuery(query);
        return count;
    }

    // ------------------------------------------------------------------
    // Layout helpers
    // ------------------------------------------------------------------

    private int lineHeight() {
        return font.lineHeight;
    }

    private int gutterWidth() {
        int digits = Math.max(1, Integer.toString(lines.size()).length());
        return digits * 6 + 14;
    }

    private int textWidth() {
        return width - gutterWidth();
    }

    private int colToX(String text, int col) {
        if (col <= 0) {
            return 0;
        }
        int end = Math.min(col, text.length());
        return font.width(text.substring(0, end));
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    public void render(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.fill(x, y, x + width, y + height, COLOR_BG);
        int textLeft = x + gutterWidth();
        graphics.fill(x, y, textLeft, y + height, COLOR_GUTTER_BG);
        assetMarkers.clear();

        int offsetY = 0;
        int visibleLines = (int) (height / lineHeight()) + 1;
        for (int lineIndex = scrollLine; lineIndex < Math.min(scrollLine + visibleLines, lines.size()); lineIndex++) {
            if (offsetY > height) {
                break;
            }
            drawLine(graphics, lineIndex, offsetY);
            offsetY += lineHeight();
        }

        // scrollbar
        int visible = Math.max(1, (int) (height / lineHeight()));
        if (lines.size() > visible) {
            int barHeight = Math.max(12, height * visible / lines.size());
            int barY = y + (int) ((height - barHeight) * ((double) scrollLine / (lines.size() - visible)));
            graphics.fill(x + width - 3, barY, x + width, barY + barHeight, 0xFF2E3A48);
        }

        if (focused && (System.currentTimeMillis() / 500) % 2 == 0) {
            int cx = textLeft + (int) (colToX(currentLine(), cursorCol) - scrollX);
            int cy = y + (int) ((cursorLine - scrollLine) * lineHeight());
            graphics.fill(cx, cy, cx + Math.max(1, font.width(" ") / 2), cy + lineHeight(), COLOR_CURSOR);
        }

        if (isSuggesting()) {
            drawSuggestions(graphics);
            drawSnippetGhost(graphics);
        }
    }

    /**
     * Grey inline preview of the snippet Tab would insert (the fields a
     * definition needs). Dismissed automatically as soon as typing stops
     * matching the snippet.
     */
    private void drawSnippetGhost(GuiGraphics graphics) {
        Suggestion selected = suggestions.get(suggestionIndex);
        if (selected == null || !selected.isSnippet() || !atStatementStart()) {
            return;
        }
        int textLeft = x + gutterWidth();
        String[] parts = selected.insert.split("\n", -1);
        int lineY = y + (int) ((suggestionLine - scrollLine) * lineHeight());
        int startX = textLeft + (int) (colToX(lineAt(suggestionLine), suggestionStartCol) - scrollX);
        graphics.enableScissor(textLeft, y, x + width, y + height);
        for (int i = 0; i < parts.length; i++) {
            int gy = lineY + i * lineHeight();
            int gx = i == 0 ? startX : textLeft + 4;
            graphics.fill(gx, gy, x + width, gy + lineHeight(), COLOR_BG);
            graphics.drawString(font, parts[i], gx, gy, COLOR_GHOST, false);
        }
        graphics.disableScissor();
    }

    /** True when only whitespace precedes the typed word on its line. */
    private boolean atStatementStart() {
        String line = lineAt(suggestionLine);
        int start = Math.min(suggestionStartCol, line.length());
        return line.substring(0, start).isBlank();
    }

    private void drawLine(GuiGraphics graphics, int lineIndex, int offsetY) {
        int textLeft = x + gutterWidth();
        String text = lineAt(lineIndex);
        int yTop = y + offsetY;
        int yBot = y + offsetY + lineHeight() - 1;

        if (lineIndex == cursorLine && focused) {
            graphics.fill(textLeft, yTop, x + width, yBot + 1, COLOR_LINE_HIGHLIGHT);
        }
        String number = Integer.toString(lineIndex + 1);
        graphics.drawString(font, number, textLeft - font.width(number) - 8, yTop, COLOR_GUTTER, false);

        // Asset reference gutter marker ("A"): clickable to open the picker.
        if (!cachedRefs(lineIndex, text).isEmpty() && assetPickerRequest != null) {
            int mx = textLeft - 12;
            graphics.fill(mx, yTop, mx + 9, yTop + 9, 0xFF2E7D32);
            graphics.drawString(font, "A", mx + 2, yTop + 1, 0xFFB9F6CA, false);
            assetMarkers.add(new int[] { lineIndex, mx, yTop, 9, 9 });
        }

        if (hasSelection()) {
            int[] start = selectionStart();
            int[] end = selectionEnd();
            if (lineIndex >= start[0] && lineIndex <= end[0]) {
                int from = lineIndex == start[0] ? start[1] : 0;
                int to = lineIndex == end[0] ? end[1] : text.length();
                int sx = textLeft + (int) (colToX(text, from) - scrollX);
                int ex = textLeft + (int) (colToX(text, to) - scrollX);
                graphics.fill(Math.max(sx, textLeft), yTop, Math.max(sx + 1, ex), yBot + 1, COLOR_SELECTION);
            }
        }

        if (searchQuery != null && !searchQuery.isEmpty()) {
            int idx = text.indexOf(searchQuery);
            while (idx >= 0) {
                boolean isCurrent = lineIndex == matchLine && idx == matchCol;
                int shx = textLeft + (int) (colToX(text, idx) - scrollX);
                int ehx = textLeft + (int) (colToX(text, idx + searchQuery.length()) - scrollX);
                graphics.fill(Math.max(shx, textLeft), yTop, Math.max(shx + 1, ehx), yBot + 1,
                        isCurrent ? 0x80FF9800 : COLOR_SEARCH);
                idx = text.indexOf(searchQuery, idx + 1);
            }
        }

        // bracket match
        int[] bm = bracketMatch();
        if (bm != null) {
            highlightBracket(graphics, textLeft, lineIndex, text, yTop, yBot, bm[0], bm[1]);
            highlightBracket(graphics, textLeft, lineIndex, text, yTop, yBot, bm[2], bm[3]);
        }

        List<Token> tokens = cachedTokens(lineIndex, text);
        int drawX = textLeft + (int) (0 - scrollX);
        for (Token token : tokens) {
            int tokenWidth = font.width(token.text);
            if (drawX + tokenWidth < textLeft || drawX > x + width) {
                drawX += tokenWidth;
                continue;
            }
            graphics.drawString(font, token.text, drawX, yTop, token.color, false);
            drawX += tokenWidth;
        }
    }

    private void highlightBracket(GuiGraphics graphics, int textLeft, int lineIndex, String text, int yTop, int yBot,
            int bLine, int bCol) {
        if (lineIndex != bLine) {
            return;
        }
        if (bCol < 0 || bCol >= text.length()) {
            return;
        }
        int bx = textLeft + (int) (colToX(text, bCol) - scrollX);
        int bw = font.width(String.valueOf(text.charAt(bCol)));
        graphics.fill(Math.max(bx, textLeft), yTop, Math.max(bx + 1, bx + bw), yBot, COLOR_BRACKET);
    }

    /** Returns {line,col,targetLine,targetCol} if cursor sits on a matching bracket pair. */
    private int[] bracketMatch() {
        String line = currentLine();
        int col = cursorCol;
        char at = col < line.length() ? line.charAt(col) : 0;
        char before = col > 0 ? line.charAt(col - 1) : 0;
        int startLine = cursorLine;
        int startCol;
        char open;
        char close;
        boolean forward;
        if (at == '(' || at == '[' || at == '{') {
            open = at;
            close = open == '(' ? ')' : open == '[' ? ']' : '}';
            startCol = col;
            forward = true;
        } else if (before == ')' || before == ']' || before == '}') {
            close = before;
            open = close == ')' ? '(' : close == ']' ? '[' : '{';
            startCol = col - 1;
            forward = false;
        } else {
            return null;
        }
        int depth = 0;
        if (forward) {
            for (int i = startLine; i < lines.size(); i++) {
                String text = lines.get(i);
                int from = i == startLine ? startCol + 1 : 0;
                for (int j = from; j < text.length(); j++) {
                    if (text.charAt(j) == open) {
                        depth++;
                    } else if (text.charAt(j) == close) {
                        depth--;
                        if (depth < 0) {
                            return new int[] { startLine, startCol, i, j };
                        }
                    }
                }
            }
        } else {
            for (int i = startLine; i >= 0; i--) {
                String text = lines.get(i);
                int from = i == startLine ? startCol - 1 : text.length() - 1;
                for (int j = from; j >= 0; j--) {
                    if (text.charAt(j) == close) {
                        depth++;
                    } else if (text.charAt(j) == open) {
                        depth--;
                        if (depth < 0) {
                            return new int[] { startLine, startCol, i, j };
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Completion popup: candidate list on the left, and a description panel for
     * the selected entry (signature + what it does) on the right.
     */
    private void drawSuggestions(GuiGraphics graphics) {
        Suggestion selected = suggestions.get(suggestionIndex);
        int availW = Math.min(width - 8, 320);
        boolean showDetail = availW >= 220 && selected != null;
        int listW = showDetail ? 132 : availW;
        int detailW = showDetail ? availW - listW - 2 : 0;
        int popupWidth = listW + (showDetail ? detailW + 2 : 0);

        int maxRows = Math.max(2, (height - 24) / 10);
        int visible = Math.min(suggestions.size(), maxRows);
        int first = Math.max(0, Math.min(suggestionIndex - visible + 1, suggestions.size() - visible));
        int listHeight = visible * 10 + 6;

        List<String> signatureLines = List.of();
        List<String> descriptionLines = List.of();
        int detailHeight = 0;
        if (showDetail) {
            signatureLines = wrap(selected.signature, detailW - 8, 2);
            descriptionLines = wrap(selected.description, detailW - 8, 5);
            detailHeight = 6 + signatureLines.size() * 10 + 2 + descriptionLines.size() * 10;
        }
        int popupHeight = Math.max(listHeight, detailHeight);

        int cx = x + gutterWidth() + (int) (colToX(lineAt(suggestionLine), suggestionCol) - scrollX) - 6;
        int cy = y + (int) ((suggestionLine - scrollLine) * lineHeight()) + lineHeight();
        if (cy + popupHeight > y + height) {
            cy = Math.max(y + 2, y + (int) ((suggestionLine - scrollLine) * lineHeight()) - popupHeight - 4);
        }
        int cpx = Math.max(x + 2, Math.min(cx, x + width - popupWidth - 2));
        graphics.fill(cpx - 1, cy - 1, cpx + popupWidth + 1, cy + popupHeight + 1, COLOR_POPUP_BORDER);
        graphics.fill(cpx, cy, cpx + popupWidth, cy + popupHeight, COLOR_POPUP_BG);
        for (int i = 0; i < visible; i++) {
            int index = first + i;
            int sy = cy + 3 + i * 10;
            if (index == suggestionIndex) {
                graphics.fill(cpx + 1, sy - 2, cpx + listW - 1, sy + 8, COLOR_POPUP_SEL);
            }
            graphics.drawString(font, font.plainSubstrByWidth(suggestions.get(index).label, listW - 8), cpx + 4, sy,
                    COLOR_POPUP_TEXT, false);
        }
        if (showDetail) {
            int dx = cpx + listW + 2;
            graphics.fill(dx - 1, cy + 1, dx, cy + popupHeight - 1, COLOR_POPUP_BORDER);
            int dy = cy + 3;
            for (String line : signatureLines) {
                graphics.drawString(font, line, dx + 4, dy, COLOR_POPUP_BORDER, false);
                dy += 10;
            }
            dy += 2;
            for (String line : descriptionLines) {
                graphics.drawString(font, line, dx + 4, dy, COLOR_POPUP_DOC, false);
                dy += 10;
            }
        }
    }

    /** Word-wraps {@code text} to {@code maxWidth} pixels, at most {@code maxLines} lines. */
    private List<String> wrap(String text, int maxWidth, int maxLines) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        String line = "";
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && font.width(candidate) > maxWidth) {
                out.add(line);
                if (out.size() >= maxLines) {
                    return out;
                }
                line = word;
            } else {
                line = candidate;
            }
        }
        if (!line.isEmpty()) {
            out.add(line);
        }
        return out;
    }

    public boolean isSuggesting() {
        return !suggestions.isEmpty();
    }

    // ------------------------------------------------------------------
    // Python syntax highlighting
    // ------------------------------------------------------------------

    private record Token(String text, int color) {
    }

    /** Tokenizes one visible line, reusing the result while the document is unchanged. */
    private List<Token> cachedTokens(int index, String text) {
        ensureCaches();
        List<Token> tokens = tokenCache[index];
        if (tokens == null) {
            tokens = tokenizeDocLine(text);
            tokenCache[index] = tokens;
        }
        return tokens;
    }

    /** Parses the asset references on one visible line, cached like the tokens. */
    private List<AssetReference> cachedRefs(int index, String text) {
        ensureCaches();
        List<AssetReference> refs = refCache[index];
        if (refs == null) {
            refs = AssetReferences.parse(text);
            refCache[index] = refs;
        }
        return refs;
    }

    @SuppressWarnings("unchecked")
    private void ensureCaches() {
        if (tokenCache != null && refCache != null && cacheStamp == docStamp
                && tokenCache.length == lines.size()) {
            return;
        }
        int size = lines.size();
        tokenCache = new List[size];
        refCache = new List[size];
        cacheStamp = docStamp;
    }

    public static List<Token> tokenize(String line) {
        return tokenizePython(line);
    }

    private List<Token> tokenizeDocLine(String line) {
        if (line.isEmpty()) {
            return new ArrayList<>();
        }
        return switch (language) {
            case "json" -> tokenizeJson(line);
            case "md", "markdown" -> tokenizeMarkdown(line);
            default -> tokenizePython(line);
        };
    }

    private static List<Token> tokenizePython(String line) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        String prevWord = "";
        while (i < line.length()) {
            char c = line.charAt(i);
            int start = i;
            if (c == '#') {
                tokens.add(new Token(line.substring(i), COLOR_COMMENT));
                return tokens;
            }
            if (c == '"' || c == '\'') {
                char quote = c;
                i++;
                boolean closed = false;
                while (i < line.length()) {
                    if (line.charAt(i) == '\\') {
                        i += 2;
                        continue;
                    }
                    if (line.charAt(i) == quote) {
                        i++;
                        closed = true;
                        break;
                    }
                    i++;
                }
                if (!closed) {
                    i = Math.min(i, line.length());
                }
                tokens.add(new Token(line.substring(start, i), COLOR_STRING));
                continue;
            }
            if (Character.isDigit(c)) {
                while (i < line.length() && (Character.isLetterOrDigit(line.charAt(i)) || line.charAt(i) == '.'
                        || line.charAt(i) == '_')) {
                    i++;
                }
                tokens.add(new Token(line.substring(start, i), COLOR_NUMBER));
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                while (i < line.length() && (Character.isLetterOrDigit(line.charAt(i)) || line.charAt(i) == '_')) {
                    i++;
                }
                String word = line.substring(start, i);
                int color = COLOR_TEXT;
                if (KEYWORDS.contains(word)) {
                    color = COLOR_KEYWORD;
                } else if (BUILTINS.contains(word)) {
                    color = COLOR_BUILTIN;
                } else if (prevWord.equals("def") || prevWord.equals("class")) {
                    color = COLOR_DEF_NAME;
                } else {
                    int after = i;
                    while (after < line.length() && line.charAt(after) == ' ') {
                        after++;
                    }
                    if (after < line.length() && line.charAt(after) == '(') {
                        color = COLOR_DEF_NAME;
                    }
                }
                prevWord = word;
                tokens.add(new Token(line.substring(start, i), color));
                continue;
            }
            if (c == '@') {
                i++;
                while (i < line.length() && (Character.isLetterOrDigit(line.charAt(i)) || line.charAt(i) == '_'
                        || line.charAt(i) == '.')) {
                    i++;
                }
                tokens.add(new Token(line.substring(start, i), COLOR_DECORATOR));
                continue;
            }
            while (i < line.length() && !isWordChar(line.charAt(i)) && line.charAt(i) != '#'
                    && line.charAt(i) != '"' && line.charAt(i) != '\'' && line.charAt(i) != '@'
                    && !Character.isDigit(line.charAt(i))) {
                i++;
            }
            tokens.add(new Token(line.substring(start, i), COLOR_TEXT));
        }
        return tokens;
    }

    private static List<Token> tokenizeJson(String line) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            int start = i;
            if (c == '"') {
                i++;
                boolean closed = false;
                while (i < line.length()) {
                    if (line.charAt(i) == '\\') {
                        i += 2;
                        continue;
                    }
                    if (line.charAt(i) == '"') {
                        i++;
                        closed = true;
                        break;
                    }
                    i++;
                }
                String str = line.substring(start, i);
                int color = COLOR_STRING;
                // Heuristic for keys: if followed by a colon
                int after = i;
                while (after < line.length() && Character.isWhitespace(line.charAt(after))) {
                    after++;
                }
                if (after < line.length() && line.charAt(after) == ':') {
                    color = COLOR_JSON_KEY;
                }
                tokens.add(new Token(str, color));
                continue;
            }
            if (Character.isDigit(c) || c == '-') {
                i++;
                while (i < line.length() && (Character.isDigit(line.charAt(i)) || line.charAt(i) == '.')) {
                    i++;
                }
                tokens.add(new Token(line.substring(start, i), COLOR_NUMBER));
                continue;
            }
            if (Character.isLetter(c)) {
                while (i < line.length() && Character.isLetter(line.charAt(i))) {
                    i++;
                }
                String word = line.substring(start, i);
                int color = ("true".equals(word) || "false".equals(word) || "null".equals(word))
                        ? COLOR_KEYWORD : COLOR_TEXT;
                tokens.add(new Token(word, color));
                continue;
            }
            i++;
            tokens.add(new Token(line.substring(start, i), COLOR_TEXT));
        }
        return tokens;
    }

    private static List<Token> tokenizeMarkdown(String line) {
        List<Token> tokens = new ArrayList<>();
        if (line.startsWith("#")) {
            tokens.add(new Token(line, COLOR_MD_HEADER));
            return tokens;
        }
        if (line.trim().startsWith("* ") || line.trim().startsWith("- ") || line.trim().startsWith("+ ")
                || line.matches("^\\s*\\d+\\.\\s.*")) {
            tokens.add(new Token(line, COLOR_MD_LIST));
            return tokens;
        }
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            int start = i;
            if (c == '`') {
                i++;
                while (i < line.length() && line.charAt(i) != '`') i++;
                if (i < line.length()) i++;
                tokens.add(new Token(line.substring(start, i), COLOR_MD_CODE));
                continue;
            }
            if (c == '[' || c == '(') {
                char endChar = (c == '[') ? ']' : ')';
                i++;
                while (i < line.length() && line.charAt(i) != endChar) i++;
                if (i < line.length()) i++;
                tokens.add(new Token(line.substring(start, i), COLOR_MD_LINK));
                continue;
            }
            i++;
            while (i < line.length() && line.charAt(i) != '`' && line.charAt(i) != '[' && line.charAt(i) != '(') {
                i++;
            }
            tokens.add(new Token(line.substring(start, i), COLOR_TEXT));
        }
        return tokens;
    }
}