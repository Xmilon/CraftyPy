package net.xmilon.craftpy.client;

import net.xmilon.craftpy.client.Syntax.Kind;

/** Color schemes for the code area. The rest of the UI follows Modern UI's own theme. */
public enum EditorTheme {
    //            label            bg          gutterBg    gutterText  text        currentLine  ruler       wordMatch   findMatch   keyword     builtin     string      comment     number      decorator   function    clazz       self        constant
    DARK("Dark", 0xFF1E1F22, 0xFF1A1B1E, 0xFF6F737A, 0xFFD4D4D4, 0xFF26282E, 0xFF34373D, 0x40FFFFFF, 0x66F2C53D, 0xFFC586C0, 0xFFDCDCAA, 0xFFCE9178, 0xFF6A9955, 0xFFB5CEA8, 0xFF4EC9B0, 0xFF9CDCFE, 0xFF4EC9B0, 0xFF569CD6, 0xFF4FC1FF),
    LIGHT("Light", 0xFFFFFFFF, 0xFFF3F3F3, 0xFF9A9A9A, 0xFF1F1F1F, 0xFFF1F5FB, 0xFFE2E2E2, 0x30000000, 0x66FFD54F, 0xFF0033B3, 0xFF795E26, 0xFF067D17, 0xFF8C8C8C, 0xFF1750EB, 0xFF9E880D, 0xFF00627A, 0xFF267F99, 0xFF94558D, 0xFF871094),
    MONOKAI("Monokai", 0xFF272822, 0xFF22231E, 0xFF75715E, 0xFFF8F8F2, 0xFF3E3D32, 0xFF3B3A32, 0x40FFFFFF, 0x66E6DB74, 0xFFF92672, 0xFF66D9EF, 0xFFE6DB74, 0xFF75715E, 0xFFAE81FF, 0xFFA6E22E, 0xFFA6E22E, 0xFF66D9EF, 0xFFFD971F, 0xFFAE81FF),
    DRACULA("Dracula", 0xFF282A36, 0xFF21222C, 0xFF6272A4, 0xFFF8F8F2, 0xFF343746, 0xFF3A3D4E, 0x40FFFFFF, 0x66F1FA8C, 0xFFFF79C6, 0xFF8BE9FD, 0xFFF1FA8C, 0xFF6272A4, 0xFFBD93F9, 0xFF50FA7B, 0xFF50FA7B, 0xFF8BE9FD, 0xFFFFB86C, 0xFFBD93F9),
    SOLARIZED_DARK("Solarized Dark", 0xFF002B36, 0xFF00252F, 0xFF586E75, 0xFF93A1A1, 0xFF073642, 0xFF0A3F4C, 0x40FFFFFF, 0x66B58900, 0xFF859900, 0xFF268BD2, 0xFF2AA198, 0xFF586E75, 0xFFD33682, 0xFFCB4B16, 0xFF268BD2, 0xFFB58900, 0xFF6C71C4, 0xFFD33682),
    HIGH_CONTRAST("High Contrast", 0xFF000000, 0xFF000000, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF1A1A1A, 0xFF808080, 0x60FFFFFF, 0x99FFFF00, 0xFFFFFF00, 0xFF00FFFF, 0xFF00FF00, 0xFFC0C0C0, 0xFFFF80FF, 0xFFFF8000, 0xFF80C0FF, 0xFF00FFFF, 0xFFFF8080, 0xFFFF80FF);

    public final String label;
    public final int bg, gutterBg, gutterText, text, currentLine, ruler, wordMatch, findMatch;
    private final int keyword, builtin, string, comment, number, decorator, function, clazz, self, constant;

    EditorTheme(String label, int bg, int gutterBg, int gutterText, int text, int currentLine, int ruler,
                int wordMatch, int findMatch, int keyword, int builtin, int string, int comment, int number,
                int decorator, int function, int clazz, int self, int constant) {
        this.label = label;
        this.bg = bg;
        this.gutterBg = gutterBg;
        this.gutterText = gutterText;
        this.text = text;
        this.currentLine = currentLine;
        this.ruler = ruler;
        this.wordMatch = wordMatch;
        this.findMatch = findMatch;
        this.keyword = keyword;
        this.builtin = builtin;
        this.string = string;
        this.comment = comment;
        this.number = number;
        this.decorator = decorator;
        this.function = function;
        this.clazz = clazz;
        this.self = self;
        this.constant = constant;
    }

    public int color(Kind kind) {
        return switch (kind) {
            case TEXT -> text;
            case KEYWORD -> keyword;
            case BUILTIN -> builtin;
            case STRING, MD_CODE -> string;
            case COMMENT -> comment;
            case NUMBER -> number;
            case DECORATOR -> decorator;
            case FUNCTION, JSON_KEY, MD_LINK -> function;
            case CLASS, MD_HEADER -> clazz;
            case SELF, MD_LIST -> self;
            case CONSTANT -> constant;
        };
    }

    public static EditorTheme byName(String name) {
        for (EditorTheme theme : values()) {
            if (theme.name().equalsIgnoreCase(name)) {
                return theme;
            }
        }
        return DARK;
    }
}
