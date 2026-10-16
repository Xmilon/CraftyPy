package net.xmilon.craftpy.client.mui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import icyllis.modernui.R;
import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.drawable.ColorDrawable;
import icyllis.modernui.text.Editable;
import icyllis.modernui.text.TextWatcher;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.Button;
import icyllis.modernui.widget.EditText;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.TextView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.xmilon.craftpy.client.RecipeCard;
import net.xmilon.craftpy.client.RecipeSources;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/**
 * The Recipes & drops tool window: every item and mob of the game and its mods; for an item, what makes it, what it
 * is used in and what drops it; for a mob, the mob itself (drawn live) and everything it drops. Recipes come from JEI's
 * own layouts when JEI is installed (every mod category, exactly as JEI shows it), otherwise from the server as
 * generic cards. Drops always come from the server's loot tables - the part JEI does not show.
 * Everything inside the body is drawn by Minecraft ({@link McCanvas}); this class only holds the Modern UI frame.
 */
final class RecipeBookPanel {
    interface Host {
        /** A server tool request ({@code recipe_book}, {@code loot_book}); the reply arrives on the UI thread. */
        void request(String op, JsonObject args, Consumer<JsonObject> reply);

        void hide();
    }

    private static final int MAX_CARDS = 80;
    private static final int TEXT = 0xFFDFE1E5;
    private static final int DIM = 0xFF8C8F94;
    private static final int ACCENT = 0xFF6FA8FF;
    private static final int ROW_HOVER = 0x30FFFFFF;

    private enum Tab { MADE_BY, USED_IN, DROPPED_BY }

    /** What the body shows; the back button walks this history. */
    private record Page(String item, String mob) {
    }

    private final Host host;
    private final LinearLayout view;
    private final EditText search;
    private final LinearLayout focusBar;
    private final TextView focusTitle;
    private final LinearLayout tabs;
    private final Button[] tabButtons = new Button[3];
    private final Button itemsChip;
    private final Button mobsChip;
    private final McCanvas body;
    private final Deque<Page> history = new ArrayDeque<>();
    private Page page;
    private boolean mobs;
    private Tab tab = Tab.MADE_BY;
    private volatile String query = "";
    private JsonObject recipeReply;
    private JsonObject dropReply;

    RecipeBookPanel(Context context, Host host) {
        this.host = host;
        view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setBackground(new ColorDrawable(IdeColors.PANEL));

        var header = CraftPyFragment.row(context);
        header.setPadding(header.dp(12), 0, header.dp(4), 0);
        var title = CraftPyFragment.label(context, "Recipes & drops", R.attr.textAppearanceLabelLarge);
        title.setTextColor(IdeColors.TEXT);
        header.addView(title, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        header.addView(EditorFragment.action(context, IdeIcon.Glyph.MINUS, "Hide", v -> host.hide()));
        view.addView(header, new LinearLayout.LayoutParams(MATCH_PARENT, header.dp(36)));

        search = CraftPyFragment.field(context);
        search.setSingleLine();
        search.setHint("Search items (@mod for one mod)");
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().strip().toLowerCase(Locale.ROOT);
                if (page != null) {
                    page = null;
                    history.clear();
                    show();
                }
            }
        });
        var searchLp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        searchLp.leftMargin = searchLp.rightMargin = view.dp(8);
        view.addView(search, searchLp);

        var modes = CraftPyFragment.row(context);
        modes.setPadding(view.dp(6), view.dp(2), view.dp(6), view.dp(2));
        itemsChip = CraftPyFragment.button(context, "Items", R.attr.borderlessButtonStyle, v -> browse(false));
        mobsChip = CraftPyFragment.button(context, "Mobs", R.attr.borderlessButtonStyle, v -> browse(true));
        modes.addView(itemsChip);
        modes.addView(mobsChip);
        view.addView(modes);

        focusBar = CraftPyFragment.row(context);
        focusBar.setPadding(view.dp(4), 0, view.dp(8), 0);
        focusBar.addView(CraftPyFragment.button(context, "← Back", R.attr.borderlessButtonStyle, v -> back()));
        focusTitle = CraftPyFragment.label(context, "", R.attr.textAppearanceTitleSmall);
        focusTitle.setTextColor(IdeColors.TEXT);
        focusBar.addView(focusTitle, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        view.addView(focusBar);

        tabs = CraftPyFragment.row(context);
        tabs.setPadding(view.dp(6), 0, view.dp(6), 0);
        String[] names = {"Made by", "Used in", "Dropped by"};
        for (int i = 0; i < 3; i++) {
            Tab which = Tab.values()[i];
            tabButtons[i] = CraftPyFragment.button(context, names[i], R.attr.borderlessButtonStyle, v -> {
                tab = which;
                show();
            });
            tabs.addView(tabButtons[i]);
        }
        view.addView(tabs);

        body = new McCanvas(context, null);
        body.setFallback("Recipes and drops come from the running game: open the IDE in a world.");
        var bodyLp = new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1);
        bodyLp.topMargin = view.dp(4);
        view.addView(body, bodyLp);
        show();
    }

    View view() {
        return view;
    }

    void focusSearch() {
        search.requestFocus();
    }

    /** Opens the page of one item (from Ctrl+Shift+R on an id in the code). */
    void showItem(String id) {
        open(new Page(normalize(id), null));
    }

    private static String normalize(String id) {
        String clean = id.strip();
        return clean.contains(":") ? clean : "minecraft:" + clean;
    }

    private void browse(boolean mobs) {
        this.mobs = mobs;
        page = null;
        history.clear();
        search.setHint(mobs ? "Search mobs (@mod for one mod)" : "Search items (@mod for one mod)");
        show();
    }

    private void open(Page next) {
        if (page != null) {
            history.push(page);
        }
        page = next;
        tab = Tab.MADE_BY;
        load();
        show();
    }

    private void back() {
        page = history.isEmpty() ? null : history.pop();
        if (page != null) {
            load();
        }
        show();
    }

    /** Asks the server for the page's data; a reply for a page that is no longer shown is dropped. */
    private void load() {
        Page asked = page;
        recipeReply = null;
        dropReply = null;
        JsonObject args = new JsonObject();
        if (asked.item() != null) {
            args.addProperty("item", asked.item());
            if (!RecipeSources.jei()) {
                host.request("recipe_book", args.deepCopy(), reply -> {
                    if (asked.equals(page)) {
                        recipeReply = reply;
                        show();
                    }
                });
            }
        } else {
            args.addProperty("mob", asked.mob());
        }
        host.request("loot_book", args, reply -> {
            if (asked.equals(page)) {
                dropReply = reply;
                show();
            }
        });
    }

    /** Rebuilds the frame and hands the body its painter. UI thread. */
    private void show() {
        boolean focused = page != null;
        focusBar.setVisibility(focused ? View.VISIBLE : View.GONE);
        tabs.setVisibility(focused && page.item() != null ? View.VISIBLE : View.GONE);
        itemsChip.setTextColor(!mobs ? IdeColors.ACCENT : IdeColors.TEXT);
        mobsChip.setTextColor(mobs ? IdeColors.ACCENT : IdeColors.TEXT);
        for (int i = 0; i < 3; i++) {
            tabButtons[i].setTextColor(tab.ordinal() == i ? IdeColors.ACCENT : IdeColors.TEXT);
        }
        if (!focused) {
            body.setPainter(mobs ? new MobList(this) : new ItemGrid(this));
            return;
        }
        if (page.mob() != null) {
            focusTitle.setText(page.mob());
            body.setPainter(new MobPage(this, page.mob(), dropReply));
            return;
        }
        focusTitle.setText(page.item());
        if (recipeReply != null) {
            tabButtons[0].setText("Made by " + count(recipeReply, "made_by_total"));
            tabButtons[1].setText("Used in " + count(recipeReply, "used_in_total"));
        } else {
            tabButtons[0].setText("Made by");
            tabButtons[1].setText("Used in");
        }
        tabButtons[2].setText(dropReply != null && dropReply.has("total") ? "Dropped by " + dropReply.get("total").getAsInt() : "Dropped by");
        body.setPainter(switch (tab) {
            case MADE_BY, USED_IN -> new Cards(this, page.item(), tab == Tab.USED_IN, recipeReply);
            case DROPPED_BY -> new Sources(this, dropReply);
        });
    }

    private static String count(JsonObject reply, String key) {
        return reply.has(key) ? "(" + reply.get(key).getAsInt() + ")" : "";
    }

    /** From the render thread: opens a page on the UI thread. */
    private void openLater(Page next) {
        view.post(() -> open(next));
    }

    // ------------------------------------------------------------------ shared drawing

    static ItemStack stackOf(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        String clean = id;
        int space = clean.indexOf(' ');
        int count = 1;
        if (space > 0) {
            String rest = clean.substring(space + 1).strip();
            clean = clean.substring(0, space);
            if (rest.startsWith("x")) {
                try {
                    count = Integer.parseInt(rest.substring(1));
                } catch (NumberFormatException ignored) {
                    // not a count: show one
                }
            }
        }
        if (clean.startsWith("#")) {
            ResourceLocation tag = ResourceLocation.tryParse(clean.substring(1));
            if (tag == null) {
                return null;
            }
            var set = BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, tag));
            if (set.isEmpty() || set.get().size() == 0) {
                return null;
            }
            return new ItemStack(set.get().get(0), count);
        }
        ItemStack stack = McPainters.stack(clean);
        if (stack != null) {
            stack.setCount(Math.max(1, count));
        }
        return stack;
    }

    static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && my >= y && mx < x + w && my < y + h;
    }

    static void slot(GuiGraphics g, int x, int y) {
        g.fill(x, y, x + 18, y + 18, 0xFF8B8B8B);
        g.fill(x + 1, y + 1, x + 18, y + 18, 0xFFFFFFFF);
        g.fill(x + 1, y + 1, x + 17, y + 17, 0xFF373737);
    }

    static void item(GuiGraphics g, Font font, ItemStack stack, int x, int y) {
        if (stack != null) {
            g.renderItem(stack, x, y);
            g.renderItemDecorations(font, stack, x, y);
        }
    }

    /** Scroll state shared by the list painters; the wheel moves 20 GUI units. */
    private abstract static class Scrolling implements McCanvas.Painter {
        protected volatile double scroll;
        protected int contentHeight;
        protected int viewHeight;

        @Override
        public void scroll(double amount) {
            scroll = Math.max(0, Math.min(Math.max(0, contentHeight - viewHeight), scroll - amount * 20));
        }
    }

    // ------------------------------------------------------------------ browse: items

    private static final class ItemGrid extends Scrolling {
        private final RecipeBookPanel panel;
        private List<ItemStack> all;
        private List<ItemStack> shown = List.of();
        private String filteredFor;
        private int columns = 1;

        ItemGrid(RecipeBookPanel panel) {
            this.panel = panel;
        }

        private void filter() {
            if (all == null) {
                all = new ArrayList<>();
                for (var item : BuiltInRegistries.ITEM) {
                    if (item != Items.AIR) {
                        all.add(new ItemStack(item));
                    }
                }
            }
            String q = panel.query;
            if (q.equals(filteredFor)) {
                return;
            }
            filteredFor = q;
            scroll = 0;
            List<ItemStack> out = new ArrayList<>();
            for (ItemStack stack : all) {
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
                boolean match;
                if (q.isEmpty()) {
                    match = true;
                } else if (q.startsWith("@")) {
                    match = id.getNamespace().startsWith(q.substring(1));
                } else {
                    match = id.getPath().contains(q) || stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(q);
                }
                if (match) {
                    out.add(stack);
                }
            }
            shown = out;
        }

        @Override
        public void paint(GuiGraphics g, int width, int height, int mouseX, int mouseY, float partialTick) {
            filter();
            Font font = Minecraft.getInstance().font;
            columns = Math.max(1, (width - 4) / 18);
            viewHeight = height;
            contentHeight = (shown.size() + columns - 1) / columns * 18 + 14;
            g.drawString(font, shown.size() + " items" + (RecipeSources.jei() ? " · recipes by JEI" : ""), 4, 2 - (int) scroll, DIM, false);
            ItemStack hovered = null;
            int top = 12 - (int) scroll;
            int first = Math.max(0, -top / 18 * columns);
            for (int i = first; i < shown.size(); i++) {
                int x = 2 + (i % columns) * 18;
                int y = top + (i / columns) * 18;
                if (y > height) {
                    break;
                }
                if (inside(mouseX, mouseY, x, y, 18, 18)) {
                    g.fill(x, y, x + 18, y + 18, ROW_HOVER);
                    hovered = shown.get(i);
                }
                g.renderItem(shown.get(i), x + 1, y + 1);
            }
            if (hovered != null) {
                g.renderTooltip(font, hovered, mouseX, mouseY);
            }
        }

        @Override
        public void click(int x, int y, int button) {
            int column = (x - 2) / 18;
            int row = (int) ((y - 12 + scroll) / 18);
            int index = row * columns + column;
            if (x >= 2 && column < columns && y >= 12 && index >= 0 && index < shown.size()) {
                panel.openLater(new Page(BuiltInRegistries.ITEM.getKey(shown.get(index).getItem()).toString(), null));
            }
        }
    }

    // ------------------------------------------------------------------ browse: mobs

    private static final class MobList extends Scrolling {
        private static final int ROW = 20;
        private final RecipeBookPanel panel;
        private List<String> shown = List.of();
        private String filteredFor;

        MobList(RecipeBookPanel panel) {
            this.panel = panel;
        }

        @Override
        public void paint(GuiGraphics g, int width, int height, int mouseX, int mouseY, float partialTick) {
            String q = panel.query;
            if (!q.equals(filteredFor)) {
                filteredFor = q;
                scroll = 0;
                List<String> out = new ArrayList<>();
                for (String id : McPainters.mobIds()) {
                    if (q.isEmpty() || (q.startsWith("@") ? id.startsWith(q.substring(1)) : id.contains(q))) {
                        out.add(id);
                    }
                }
                shown = out;
            }
            Font font = Minecraft.getInstance().font;
            viewHeight = height;
            contentHeight = shown.size() * ROW + 4;
            int top = 2 - (int) scroll;
            for (int i = Math.max(0, -top / ROW); i < shown.size(); i++) {
                int y = top + i * ROW;
                if (y > height) {
                    break;
                }
                String id = shown.get(i);
                if (inside(mouseX, mouseY, 0, y, width, ROW)) {
                    g.fill(0, y, width, y + ROW, ROW_HOVER);
                }
                var type = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(id));
                SpawnEggItem egg = SpawnEggItem.byId(type);
                item(g, font, egg == null ? null : new ItemStack(egg), 3, y + 2);
                g.drawString(font, font.plainSubstrByWidth(type.getDescription().getString(), width - 28), 24, y + 2, TEXT, false);
                g.drawString(font, font.plainSubstrByWidth(id, width - 28), 24, y + 11, DIM, false);
            }
        }

        @Override
        public void click(int x, int y, int button) {
            int index = (int) ((y - 2 + scroll) / ROW);
            if (index >= 0 && index < shown.size()) {
                panel.openLater(new Page(null, shown.get(index)));
            }
        }
    }

    // ------------------------------------------------------------------ an item: recipe cards

    private static final class Cards extends Scrolling {
        private static final int TITLE = 14;
        private static final int GAP = 6;
        private final RecipeBookPanel panel;
        private final String item;
        private final boolean usedIn;
        private final JsonObject reply;
        private List<RecipeCard> cards;
        private final List<int[]> boxes = new ArrayList<>(); // x, y, w, h, scaled? per card, last frame
        private float lastScale = 1;

        Cards(RecipeBookPanel panel, String item, boolean usedIn, JsonObject reply) {
            this.panel = panel;
            this.item = item;
            this.usedIn = usedIn;
            this.reply = reply;
        }

        private void build() {
            if (cards != null) {
                return;
            }
            if (RecipeSources.jei()) {
                ItemStack stack = stackOf(item);
                cards = stack == null ? List.of() : RecipeSources.jeiCards(stack, usedIn, MAX_CARDS);
            } else if (reply != null && reply.has(usedIn ? "used_in" : "made_by")) {
                List<RecipeCard> out = new ArrayList<>();
                for (JsonElement e : reply.getAsJsonArray(usedIn ? "used_in" : "made_by")) {
                    out.add(new GenericCard(e.getAsJsonObject()));
                }
                cards = out;
            }
        }

        @Override
        public void paint(GuiGraphics g, int width, int height, int mouseX, int mouseY, float partialTick) {
            build();
            Font font = Minecraft.getInstance().font;
            viewHeight = height;
            if (cards == null) {
                g.drawString(font, "Loading...", 4, 4, DIM, false);
                return;
            }
            if (reply != null && reply.has("error")) {
                g.drawString(font, font.plainSubstrByWidth(reply.get("error").getAsString(), width - 8), 4, 4, DIM, false);
                return;
            }
            if (cards.isEmpty()) {
                g.drawString(font, usedIn ? "Not used in any recipe." : "No recipe makes this.", 4, 4, DIM, false);
                return;
            }
            boxes.clear();
            int y = 4 - (int) scroll;
            RecipeCard hoveredCard = null;
            int[] hoveredBox = null;
            for (RecipeCard card : cards) {
                float s = Math.min(1f, (width - 8f) / Math.max(1, card.width()));
                int w = Math.round(card.width() * s);
                int h = Math.round(card.height() * s);
                int x = (width - w) / 2;
                if (y + TITLE + h >= 0 && y <= height) {
                    ItemStack station = card.station();
                    int tx = x;
                    if (station != null) {
                        g.pose().pushPose();
                        g.pose().translate(x, y, 0);
                        g.pose().scale(0.75f, 0.75f, 1);
                        g.renderItem(station, 0, 0);
                        g.pose().popPose();
                        tx += 14;
                    }
                    g.drawString(font, font.plainSubstrByWidth(card.title(), width - tx - 4), tx, y + 3, ACCENT, false);
                    int cy = y + TITLE;
                    int mx = mouseX < 0 ? -1 : Math.round((mouseX - x) / s);
                    int my = mouseY < 0 ? -1 : Math.round((mouseY - cy) / s);
                    g.pose().pushPose();
                    g.pose().translate(x, cy, 0);
                    g.pose().scale(s, s, 1);
                    card.draw(g, 0, 0, mx, my);
                    g.pose().popPose();
                    int[] box = {x, cy, w, h};
                    boxes.add(box);
                    if (inside(mouseX, mouseY, x, cy, w, h)) {
                        hoveredCard = card;
                        hoveredBox = box;
                        lastScale = s;
                    }
                } else {
                    boxes.add(new int[]{x, y + TITLE, w, h});
                }
                y += TITLE + h + GAP;
            }
            contentHeight = (int) (y + scroll);
            if (hoveredCard != null) {
                float s = lastScale;
                g.pose().pushPose();
                g.pose().translate(hoveredBox[0], hoveredBox[1], 200);
                g.pose().scale(s, s, 1);
                hoveredCard.drawOverlays(g, 0, 0, Math.round((mouseX - hoveredBox[0]) / s), Math.round((mouseY - hoveredBox[1]) / s));
                g.pose().popPose();
            }
        }

        @Override
        public void click(int x, int y, int button) {
            if (cards == null) {
                return;
            }
            for (int i = 0; i < boxes.size() && i < cards.size(); i++) {
                int[] b = boxes.get(i);
                if (inside(x, y, b[0], b[1], b[2], b[3])) {
                    float s = b[2] / (float) Math.max(1, cards.get(i).width());
                    ItemStack stack = cards.get(i).stackAt(0, 0, Math.round((x - b[0]) / s), Math.round((y - b[1]) / s));
                    if (stack != null && !stack.isEmpty()) {
                        panel.openLater(new Page(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), null));
                    }
                    return;
                }
            }
        }
    }

    /** A recipe from the server, drawn without JEI: inputs (a grid for shaped recipes), an arrow, the results. */
    static final class GenericCard implements RecipeCard {
        private final String title;
        private final ItemStack station;
        private final List<List<ItemStack>> inputs = new ArrayList<>();
        private final List<ItemStack> outputs = new ArrayList<>();
        private final List<String> outputNotes = new ArrayList<>();
        private final List<String> lines = new ArrayList<>();
        private final int gridW;
        private final int gridH;

        GenericCard(JsonObject card) {
            String type = card.has("type") ? card.get("type").getAsString() : "?";
            title = pretty(type);
            station = stationOf(type);
            for (JsonElement slot : card.getAsJsonArray("inputs")) {
                List<ItemStack> choices = new ArrayList<>();
                for (JsonElement choice : slot.getAsJsonArray()) {
                    ItemStack stack = stackOf(choice.getAsString());
                    if (stack != null) {
                        choices.add(stack);
                    }
                }
                inputs.add(choices);
            }
            JsonObject output = card.getAsJsonObject("output");
            ItemStack main = stackOf(output.get("item").getAsString() + (output.get("count").getAsInt() > 1 ? " x" + output.get("count").getAsInt() : ""));
            if (main != null) {
                outputs.add(main);
                outputNotes.add("");
            }
            if (card.has("extra_outputs")) {
                for (JsonElement e : card.getAsJsonArray("extra_outputs")) {
                    JsonObject extra = e.getAsJsonObject();
                    ItemStack stack = stackOf(extra.get("item").getAsString() + " x" + extra.get("count").getAsInt());
                    if (stack != null) {
                        outputs.add(stack);
                        outputNotes.add(extra.has("chance") ? Math.round(extra.get("chance").getAsDouble() * 100) + "%" : "");
                    }
                }
            }
            if (card.has("fluids")) {
                for (JsonElement fluid : card.getAsJsonArray("fluids")) {
                    lines.add(fluid.getAsString());
                }
            }
            if (card.has("time")) {
                lines.add(Math.round(card.get("time").getAsDouble() / 20.0 * 10) / 10.0 + " s");
            }
            int w = card.has("width") ? card.get("width").getAsInt() : 0;
            int h = card.has("height") ? card.get("height").getAsInt() : 0;
            if (w <= 0 || h <= 0) {
                w = Math.min(3, Math.max(1, inputs.size()));
                h = (inputs.size() + w - 1) / w;
            }
            gridW = w;
            gridH = Math.max(1, h);
        }

        static String pretty(String type) {
            String path = type.substring(type.indexOf(':') + 1).replace('_', ' ');
            return path.isEmpty() ? type : Character.toUpperCase(path.charAt(0)) + path.substring(1)
                    + (type.startsWith("minecraft:") ? "" : "  (" + type.substring(0, Math.max(0, type.indexOf(':'))) + ")");
        }

        private static ItemStack stationOf(String type) {
            return switch (type) {
                case "minecraft:crafting" -> new ItemStack(Items.CRAFTING_TABLE);
                case "minecraft:smelting" -> new ItemStack(Items.FURNACE);
                case "minecraft:blasting" -> new ItemStack(Items.BLAST_FURNACE);
                case "minecraft:smoking" -> new ItemStack(Items.SMOKER);
                case "minecraft:campfire_cooking" -> new ItemStack(Items.CAMPFIRE);
                case "minecraft:stonecutting" -> new ItemStack(Items.STONECUTTER);
                case "minecraft:smithing" -> new ItemStack(Items.SMITHING_TABLE);
                default -> null;
            };
        }

        @Override
        public String title() {
            return title;
        }

        @Override
        public ItemStack station() {
            return station;
        }

        @Override
        public int width() {
            return gridW * 18 + 26 + Math.max(1, Math.min(3, outputs.size())) * 20;
        }

        @Override
        public int height() {
            int rows = Math.max(gridH, (outputs.size() + 2) / 3);
            return rows * 18 + lines.size() * 10 + 2;
        }

        private ItemStack choice(List<ItemStack> choices) {
            return choices.isEmpty() ? null : choices.get((int) (System.currentTimeMillis() / 1000 % choices.size()));
        }

        @Override
        public void draw(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
            Font font = Minecraft.getInstance().font;
            for (int i = 0; i < gridW * gridH; i++) {
                int sx = x + (i % gridW) * 18;
                int sy = y + (i / gridW) * 18;
                slot(g, sx, sy);
                if (i < inputs.size()) {
                    item(g, font, choice(inputs.get(i)), sx + 1, sy + 1);
                }
            }
            int ax = x + gridW * 18 + 4;
            int ay = y + (gridH * 18) / 2 - 4;
            g.drawString(font, "→", ax + 3, ay, TEXT, false);
            int ox = x + gridW * 18 + 24;
            for (int i = 0; i < outputs.size(); i++) {
                int sx = ox + (i % 3) * 20;
                int sy = y + (i / 3) * 18;
                slot(g, sx, sy);
                item(g, font, outputs.get(i), sx + 1, sy + 1);
                if (!outputNotes.get(i).isEmpty()) {
                    g.pose().pushPose();
                    g.pose().translate(0, 0, 250);
                    g.drawString(font, outputNotes.get(i), sx + 1, sy + 1, 0xFFFFD34D, true);
                    g.pose().popPose();
                }
            }
            int ly = y + Math.max(gridH, (outputs.size() + 2) / 3) * 18 + 2;
            for (String line : lines) {
                g.drawString(font, line, x, ly, DIM, false);
                ly += 10;
            }
        }

        @Override
        public void drawOverlays(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
            ItemStack stack = stackAt(x, y, mouseX, mouseY);
            if (stack != null) {
                g.renderTooltip(Minecraft.getInstance().font, stack, mouseX, mouseY);
            }
        }

        @Override
        public ItemStack stackAt(int x, int y, int mouseX, int mouseY) {
            for (int i = 0; i < Math.min(inputs.size(), gridW * gridH); i++) {
                if (inside(mouseX, mouseY, x + (i % gridW) * 18, y + (i / gridW) * 18, 18, 18)) {
                    return choice(inputs.get(i));
                }
            }
            int ox = x + gridW * 18 + 24;
            for (int i = 0; i < outputs.size(); i++) {
                if (inside(mouseX, mouseY, ox + (i % 3) * 20, y + (i / 3) * 18, 18, 18)) {
                    return outputs.get(i);
                }
            }
            return null;
        }
    }

    // ------------------------------------------------------------------ an item: where it drops

    private static final class Sources extends Scrolling {
        private static final int ROW = 22;
        private final RecipeBookPanel panel;
        private final JsonObject reply;
        private final List<JsonObject> rows = new ArrayList<>();

        Sources(RecipeBookPanel panel, JsonObject reply) {
            this.panel = panel;
            this.reply = reply;
            if (reply != null && reply.has("sources")) {
                reply.getAsJsonArray("sources").forEach(e -> rows.add(e.getAsJsonObject()));
            }
        }

        @Override
        public void paint(GuiGraphics g, int width, int height, int mouseX, int mouseY, float partialTick) {
            Font font = Minecraft.getInstance().font;
            viewHeight = height;
            if (reply == null) {
                g.drawString(font, "Loading...", 4, 4, DIM, false);
                return;
            }
            if (reply.has("error")) {
                g.drawString(font, font.plainSubstrByWidth(reply.get("error").getAsString(), width - 8), 4, 4, DIM, false);
                return;
            }
            if (rows.isEmpty()) {
                g.drawString(font, "No mob, block or chest drops this.", 4, 4, DIM, false);
                return;
            }
            contentHeight = rows.size() * ROW + 4;
            int top = 2 - (int) scroll;
            JsonObject hovered = null;
            for (int i = Math.max(0, -top / ROW); i < rows.size(); i++) {
                int y = top + i * ROW;
                if (y > height) {
                    break;
                }
                JsonObject row = rows.get(i);
                String kind = row.get("kind").getAsString();
                String source = row.get("source").getAsString();
                if (inside(mouseX, mouseY, 0, y, width, ROW)) {
                    g.fill(0, y, width, y + ROW, ROW_HOVER);
                    hovered = row;
                }
                item(g, font, sourceIcon(kind, source), 3, y + 3);
                g.drawString(font, font.plainSubstrByWidth(sourceName(kind, source), width - 28), 24, y + 2, TEXT, false);
                g.drawString(font, font.plainSubstrByWidth(dropText(row.getAsJsonObject("drop")), width - 28), 24, y + 12, DIM, false);
            }
            if (hovered != null && hovered.getAsJsonObject("drop").has("notes")) {
                List<net.minecraft.network.chat.Component> tip = new ArrayList<>();
                tip.add(net.minecraft.network.chat.Component.literal(hovered.get("table").getAsString()));
                for (JsonElement note : hovered.getAsJsonObject("drop").getAsJsonArray("notes")) {
                    tip.add(net.minecraft.network.chat.Component.literal(note.getAsString()).withStyle(net.minecraft.ChatFormatting.GRAY));
                }
                g.renderComponentTooltip(font, tip, mouseX, mouseY);
            }
        }

        @Override
        public void click(int x, int y, int button) {
            int index = (int) ((y - 2 + scroll) / ROW);
            if (index < 0 || index >= rows.size()) {
                return;
            }
            JsonObject row = rows.get(index);
            String source = row.get("source").getAsString();
            switch (row.get("kind").getAsString()) {
                case "mob" -> panel.openLater(new Page(null, source));
                case "block" -> {
                    ItemStack block = stackOf(source);
                    if (block != null) {
                        panel.openLater(new Page(source, null));
                    }
                }
                default -> {
                }
            }
        }
    }

    static ItemStack sourceIcon(String kind, String source) {
        ResourceLocation id = ResourceLocation.tryParse(source);
        return switch (kind) {
            case "mob" -> {
                var type = id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
                SpawnEggItem egg = type == null ? null : SpawnEggItem.byId(type);
                yield egg == null ? new ItemStack(Items.ZOMBIE_HEAD) : new ItemStack(egg);
            }
            case "block" -> {
                ItemStack stack = stackOf(source);
                yield stack == null ? new ItemStack(Items.GRASS_BLOCK) : stack;
            }
            case "chest" -> new ItemStack(Items.CHEST);
            default -> source.contains("fishing") ? new ItemStack(Items.FISHING_ROD) : new ItemStack(Items.BOOK);
        };
    }

    static String sourceName(String kind, String source) {
        ResourceLocation id = ResourceLocation.tryParse(source);
        if ("mob".equals(kind) && id != null) {
            var type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
            if (type != null) {
                return type.getDescription().getString();
            }
        }
        if ("block".equals(kind)) {
            ItemStack stack = stackOf(source);
            if (stack != null) {
                return stack.getHoverName().getString();
            }
        }
        return source;
    }

    /** "0-2 (+0-1 Looting) · 100% · player kill". */
    static String dropText(JsonObject drop) {
        StringBuilder out = new StringBuilder();
        double min = drop.get("min").getAsDouble();
        double max = drop.get("max").getAsDouble();
        out.append(number(min)).append(min == max ? "" : "-" + number(max));
        if (drop.has("bonus_max")) {
            out.append(" (+").append(number(drop.get("bonus_min").getAsDouble())).append('-')
                    .append(number(drop.get("bonus_max").getAsDouble())).append(" Looting)");
        }
        double chance = drop.get("chance").getAsDouble() * 100;
        out.append(" · ").append(chance >= 10 ? String.valueOf(Math.round(chance)) : String.format(Locale.ROOT, "%.2f", chance)).append('%');
        if (drop.has("player_kill")) {
            out.append(" · player kill");
        }
        return out.toString();
    }

    private static String number(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.1f", value);
    }

    // ------------------------------------------------------------------ a mob: the mob and its drops

    private static final class MobPage extends Scrolling {
        private static final int ROW = 22;
        private final RecipeBookPanel panel;
        private final JsonObject reply;
        private final McPainters.Turntable mob;
        private final List<JsonObject> drops = new ArrayList<>();
        private volatile String problem;
        private int stageHeight;

        MobPage(RecipeBookPanel panel, String id, JsonObject reply) {
            this.panel = panel;
            this.reply = reply;
            this.mob = McPainters.turntable(McPainters.Kind.MOB, id, p -> problem = p);
            if (reply != null && reply.has("drops")) {
                reply.getAsJsonArray("drops").forEach(e -> drops.add(e.getAsJsonObject()));
            }
        }

        @Override
        public void drag(double dx, double dy) {
            mob.drag(dx, dy);
        }

        @Override
        public void paint(GuiGraphics g, int width, int height, int mouseX, int mouseY, float partialTick) {
            Font font = Minecraft.getInstance().font;
            stageHeight = Math.min(height / 2, Math.max(60, width));
            g.fill(0, 0, width, stageHeight, 0x40000000);
            g.pose().pushPose();
            mob.paint(g, width, stageHeight, -1, -1, partialTick);
            g.pose().popPose();
            if (problem != null) {
                g.drawString(font, font.plainSubstrByWidth(problem, width - 8), 4, 4, DIM, false);
            }
            int top = stageHeight + 4;
            viewHeight = height - top;
            if (reply == null) {
                g.drawString(font, "Loading drops...", 4, top, DIM, false);
                return;
            }
            if (reply.has("error")) {
                g.drawString(font, font.plainSubstrByWidth(reply.get("error").getAsString(), width - 8), 4, top, DIM, false);
                return;
            }
            g.drawString(font, drops.isEmpty() ? "Drops nothing." : "Drops (" + reply.get("table").getAsString() + ")", 4, top, ACCENT, false);
            contentHeight = drops.size() * ROW + 12;
            g.enableScissor(0, top + 10, width, height); // nested in the canvas scissor: keeps rows out of the mob
            int y0 = top + 12 - (int) scroll;
            JsonObject hovered = null;
            ItemStack hoveredStack = null;
            for (int i = 0; i < drops.size(); i++) {
                int y = y0 + i * ROW;
                if (y + ROW < top || y > height) {
                    continue;
                }
                JsonObject drop = drops.get(i);
                ItemStack stack = stackOf(drop.get("item").getAsString());
                if (inside(mouseX, mouseY, 0, y, width, ROW) && mouseY > top + 10) {
                    g.fill(0, y, width, y + ROW, ROW_HOVER);
                    hovered = drop;
                    hoveredStack = stack;
                }
                item(g, font, stack, 3, y + 3);
                String name = stack == null ? drop.get("item").getAsString() : stack.getHoverName().getString();
                g.drawString(font, font.plainSubstrByWidth(name, width - 28), 24, y + 2, TEXT, false);
                g.drawString(font, font.plainSubstrByWidth(dropText(drop), width - 28), 24, y + 12, DIM, false);
            }
            g.disableScissor();
            if (hovered != null) {
                List<net.minecraft.network.chat.Component> tip = new ArrayList<>();
                tip.add(hoveredStack == null ? net.minecraft.network.chat.Component.literal(hovered.get("item").getAsString())
                        : hoveredStack.getHoverName());
                if (hovered.has("notes")) {
                    for (JsonElement note : hovered.getAsJsonArray("notes")) {
                        tip.add(net.minecraft.network.chat.Component.literal(note.getAsString()).withStyle(net.minecraft.ChatFormatting.GRAY));
                    }
                }
                g.renderComponentTooltip(font, tip, mouseX, mouseY);
            }
        }

        @Override
        public void click(int x, int y, int button) {
            int index = (int) ((y - stageHeight - 16 + scroll) / ROW);
            if (y > stageHeight + 14 && index >= 0 && index < drops.size()) {
                String item = drops.get(index).get("item").getAsString();
                ItemStack stack = stackOf(item);
                if (stack != null) {
                    panel.openLater(new Page(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), null));
                }
            }
        }
    }
}
