package net.xmilon.craftpy.client.mui;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import icyllis.modernui.R;
import icyllis.modernui.annotation.Nullable;
import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.drawable.ColorDrawable;
import icyllis.modernui.markflow.Markflow;
import icyllis.modernui.markflow.MarkflowPlugin;
import icyllis.modernui.markflow.MarkflowTheme;
import icyllis.modernui.text.Editable;
import icyllis.modernui.text.Spannable;
import icyllis.modernui.text.SpannableStringBuilder;
import icyllis.modernui.text.Spanned;
import icyllis.modernui.text.TextWatcher;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.KeyEvent;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.EditText;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.ScrollView;
import icyllis.modernui.widget.TextView;
import net.xmilon.craftpy.client.IdeHost;
import net.xmilon.craftpy.client.docs.DocIndex;
import net.xmilon.craftpy.util.CraftPyExecutors;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static net.xmilon.craftpy.client.mui.CraftPyFragment.*;

/**
 * The help: Start, the Cookbook (complete examples), the IDE guide, the Python API, the changelog and the Java bridge, from the
 * Markdown files in {@code assets/craftpy/docs}. Used by the Docs page and the Help button (F1) of the editor.
 * <p>
 * Documents are cut into pages ({@link DocIndex}) once, off the UI thread, and kept; a page is rendered (Markdown and the
 * syntax colors of its code) off the UI thread too and kept, so opening the help or turning a page never waits on a whole
 * document. The search box looks through every document at once. On the left: the pages of the open document, and the
 * headings of the open page with the one you are reading marked.
 */
final class HelpPanel {
    /** A tab: its name, the document, and for the changelog the page of that document it shows. */
    record Topic(String name, String file, @Nullable String page) {
    }

    static final List<Topic> TOPICS = List.of(new Topic("Start", "cookbook.md", null), new Topic("Cookbook", "cookbook.md", null),
            new Topic("IDE guide", "guide.md", null), new Topic("Python API", "api.md", null),
            new Topic("Changelog", "api.md", "API versions"), new Topic("Java bridge", "java.md", null));
    static final int START = 0, PYTHON_API = 3;
    private static final List<String> FILES = List.of("cookbook.md", "guide.md", "api.md", "java.md");

    /** Every document cut into pages, kept for the whole game. */
    private static final Map<String, CompletableFuture<List<DocIndex.Page>>> PAGES = new ConcurrentHashMap<>();
    /** Rendered pages (UI thread only); the oldest is dropped. */
    private static final Map<String, Spanned> RENDERED = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Spanned> eldest) {
            return size() > 48;
        }
    };

    private final Context context;
    private final Markflow markflow;
    private final LinearLayout column;
    private final EditText search;
    private final LinearLayout pageList;
    private final ScrollView pageListScroll;
    private final ScrollView scroll;
    private final LinearLayout content;
    private final TextView text;
    private final LinearLayout pager;
    private final List<View> tabs = new ArrayList<>();
    private int topic = -1;
    private List<DocIndex.Page> pages = List.of();
    @Nullable
    private DocIndex.Page page;
    private final List<TextView> sectionRows = new ArrayList<>();
    private final List<Integer> sectionOffsets = new ArrayList<>();
    private int marked = -1;
    private int opening;

    private HelpPanel(Context context) {
        this.context = context;
        this.markflow = markflow(context);
        column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        var top = row(context);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(column.dp(8), column.dp(6), column.dp(8), column.dp(6));
        top.setBackground(new ColorDrawable(IdeColors.PANEL));
        search = CraftPyFragment.field(context);
        search.setSingleLine();
        search.setHint("Search all docs (Enter opens the first result)");
        search.setContentDescription("Search the docs");
        top.addView(search, new LinearLayout.LayoutParams(column.dp(300), WRAP_CONTENT));
        var tabRow = row(context);
        for (int i = 0; i < TOPICS.size(); i++) {
            final int index = i;
            var tab = button(context, TOPICS.get(i).name(), R.attr.borderlessButtonStyle, v -> openTopic(index, null, -1));
            tabs.add(tab);
            tabRow.addView(tab);
        }
        var tabScroll = new icyllis.modernui.widget.HorizontalScrollView(context);
        tabScroll.addView(tabRow);
        top.addView(tabScroll, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        column.addView(top, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        var line = new View(context);
        line.setBackground(IdeColors.divider(line, false));
        column.addView(line, new LinearLayout.LayoutParams(MATCH_PARENT, line.dp(1)));

        pageList = new LinearLayout(context);
        pageList.setOrientation(LinearLayout.VERTICAL);
        pageList.setPadding(column.dp(6), column.dp(8), column.dp(6), column.dp(8));
        pageListScroll = new ScrollView(context);
        pageListScroll.setBackground(new ColorDrawable(IdeColors.PANEL));
        pageListScroll.addView(pageList, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        text = docText(context);
        int pad = text.dp(28);
        text.setPadding(pad, text.dp(16), pad, text.dp(8));
        pager = row(context);
        pager.setPadding(pad, 0, pad, pad);
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(text, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        content.addView(pager, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        scroll = new ScrollView(context);
        scroll.setBackground(new ColorDrawable(IdeColors.EDITOR));
        scroll.addView(content, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> markReading());

        var body = row(context);
        body.addView(pageListScroll, new LinearLayout.LayoutParams(body.dp(240), MATCH_PARENT));
        var split = new View(context);
        split.setBackground(IdeColors.divider(split, true));
        body.addView(split, new LinearLayout.LayoutParams(split.dp(1), MATCH_PARENT));
        body.addView(scroll, new LinearLayout.LayoutParams(0, MATCH_PARENT, 1));
        column.addView(body, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));

        Runnable runSearch = this::runSearch;
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                search.removeCallbacks(runSearch);
                search.postDelayed(runSearch, 180);
            }
        });
        search.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEY_ENTER || keyCode == KeyEvent.KEY_KP_ENTER) {
                search.removeCallbacks(runSearch);
                searchAll(search.getText().toString(), true);
                return true;
            }
            return false;
        });
    }

    /** A column with the search and the tabs on top, the pages on the left and the page on the right. */
    static LinearLayout create(Context context, int firstTopic) {
        var panel = new HelpPanel(context);
        // the documents are cut into pages in the background while the first one shows
        for (String file : FILES) document(file);
        panel.openTopic(Math.max(0, Math.min(firstTopic, TOPICS.size() - 1)), null, -1);
        return panel.column;
    }

    /** The help in a dialog over the editor. */
    static void open(View anchor) {
        var dialog = Overlays.dialog(anchor, "Help - Esc closes", 1040, 24);
        int windowHeight = anchor.getRootView().getHeight();
        int height = windowHeight > 0 ? Math.max(anchor.dp(200), windowHeight - anchor.dp(120)) : anchor.dp(420);
        dialog.body.addView(create(anchor.getContext(), START), new LinearLayout.LayoutParams(MATCH_PARENT, height));
        dialog.show();
    }

    /** One section of a bundled document (from its heading to the next one of the same level) in a dialog. */
    static void openSection(View anchor, String title, String file, String heading) {
        var dialog = Overlays.dialog(anchor, title + " - Esc closes", 680, 24);
        var text = docText(anchor.getContext());
        int pad = text.dp(16);
        text.setPadding(pad, pad / 2, pad, pad);
        text.setText("Loading...");
        var scroll = new ScrollView(anchor.getContext());
        scroll.setBackground(new ColorDrawable(IdeColors.EDITOR));
        scroll.addView(text, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        int windowHeight = anchor.getRootView().getHeight();
        int height = windowHeight > 0 ? Math.max(anchor.dp(200), Math.min(anchor.dp(520), windowHeight - anchor.dp(160)))
                : anchor.dp(420);
        dialog.body.addView(scroll, new LinearLayout.LayoutParams(MATCH_PARENT, height));
        var markflow = markflow(anchor.getContext());
        IdeHost.get().execute(() -> {
            String markdown = net.xmilon.craftpy.project.BuildingBlocks.section(load(file), heading);
            text.post(() -> {
                markflow.setMarkdown(text, markdown);
                if (text.getText() instanceof Spannable spannable) highlightCode(spannable, markdown);
            });
        });
        dialog.show();
    }

    // ------------------------------------------------------------------
    // Documents and pages
    // ------------------------------------------------------------------

    /** The pages of a bundled document: read on the client thread (resources live there), cut on a worker, kept. */
    static CompletableFuture<List<DocIndex.Page>> document(String file) {
        return PAGES.computeIfAbsent(file, name -> {
            CompletableFuture<String> read = new CompletableFuture<>();
            IdeHost.get().execute(() -> read.complete(load(name)));
            return read.thenApplyAsync(markdown -> DocIndex.pages(name, markdown), CraftPyExecutors.pool());
        });
    }

    private void openTopic(int index, @Nullable String pageTitle, int line) {
        topic = index;
        for (int i = 0; i < tabs.size(); i++) styleChip(tabs.get(i), i == index);
        Topic chosen = TOPICS.get(index);
        int run = ++opening;
        document(chosen.file()).thenAccept(found -> column.post(() -> {
            if (run != opening) return;
            List<DocIndex.Page> shown = found;
            if (chosen.page() != null) {
                shown = found.stream().filter(p -> p.title().equals(chosen.page())).toList();
            }
            pages = shown;
            if (index == START) {
                showStart(found);
                return;
            }
            DocIndex.Page target = shown.isEmpty() ? null : shown.get(0);
            if (pageTitle != null) {
                for (DocIndex.Page p : shown) if (p.title().equals(pageTitle)) target = p;
            }
            if (target == null) {
                text.setText("This document is empty.");
                return;
            }
            showPage(target, line);
        }));
    }

    /** The page list: every page of the document, and under the open one its headings. */
    private void fillPageList() {
        pageList.removeAllViews();
        sectionRows.clear();
        if (topic == START) return;
        for (DocIndex.Page p : pages) {
            boolean open = page != null && p.key().equals(page.key());
            var entry = label(context, p.title(), R.attr.textAppearanceBodyMedium);
            entry.setSingleLine();
            entry.setTextColor(open ? IdeColors.ACCENT : IdeColors.TEXT);
            entry.setPadding(entry.dp(8), entry.dp(4), entry.dp(8), entry.dp(4));
            hoverBackground(entry);
            entry.setOnClickListener(v -> showPage(p, -1));
            if (open) entry.setBackground(new ColorDrawable(IdeColors.SELECTED));
            pageList.addView(entry, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            if (!open) continue;
            for (DocIndex.Section section : p.sections()) {
                if (section.line() == 0) continue; // the page's own title
                var row = label(context, section.title(), R.attr.textAppearanceBodySmall);
                row.setSingleLine();
                row.setTextColor(IdeColors.TEXT_SECONDARY);
                row.setPadding(row.dp(8 + 12 * Math.max(1, section.level() - 1)), row.dp(3), row.dp(8), row.dp(3));
                hoverBackground(row);
                row.setOnClickListener(v -> scrollToHeading(section.title()));
                pageList.addView(row, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
                sectionRows.add(row);
            }
        }
    }

    /** Shows a page (rendered off the UI thread the first time) and scrolls to the heading at {@code line} of it. */
    private void showPage(DocIndex.Page target, int line) {
        page = target;
        marked = -1;
        restoreContent();
        fillPageList();
        fillPager();
        String headingTitle = null;
        if (line > 0) {
            for (DocIndex.Section section : target.sections()) if (section.line() == line) headingTitle = section.title();
        }
        String scrollTo = headingTitle;
        Spanned ready = RENDERED.get(target.key());
        if (ready != null) {
            show(ready, scrollTo);
            return;
        }
        text.setText("Loading ...");
        int run = ++opening;
        CraftPyExecutors.supplyAsync(() -> render(markflow, target.markdown())).whenComplete((rendered, error) -> text.post(() -> {
            Spanned result = rendered;
            if (result == null) {
                // the worker could not render it: do it here instead
                result = render(markflow, target.markdown());
            }
            RENDERED.put(target.key(), result);
            if (run == opening && page == target) show(result, scrollTo);
        }));
        prefetch(target);
    }

    /** The next page is rendered ahead, so turning the page is instant. */
    private void prefetch(DocIndex.Page from) {
        int at = pages.indexOf(from);
        if (at < 0 || at + 1 >= pages.size()) return;
        DocIndex.Page next = pages.get(at + 1);
        if (RENDERED.containsKey(next.key())) return;
        CraftPyExecutors.supplyAsync(() -> render(markflow, next.markdown()))
                .thenAccept(rendered -> text.post(() -> RENDERED.putIfAbsent(next.key(), rendered)));
    }

    /** Markdown to styled text with the syntax colors of the code blocks; safe off the UI thread. */
    static Spanned render(Markflow markflow, String markdown) {
        Spanned converted = markflow.convert(markdown);
        Spannable spannable = converted instanceof Spannable own ? own : new SpannableStringBuilder(converted);
        highlightCode(spannable, markdown);
        return spannable;
    }

    private void show(Spanned rendered, @Nullable String heading) {
        markflow.setRenderedMarkdown(text, rendered);
        sectionOffsets.clear();
        String plain = rendered.toString();
        int from = 0;
        for (DocIndex.Section section : page.sections()) {
            if (section.line() == 0) continue;
            int at = plain.indexOf(section.title(), from);
            sectionOffsets.add(Math.max(0, at));
            if (at >= 0) from = at + section.title().length();
        }
        if (heading != null) {
            text.post(() -> scrollToHeading(heading));
        } else {
            scroll.scrollTo(0, 0);
        }
        text.post(this::markReading);
    }

    private void scrollToHeading(String title) {
        var layout = text.getLayout();
        if (layout == null) return;
        int at = text.getText().toString().indexOf(title);
        if (at >= 0) scroll.scrollTo(0, text.getTop() + text.getPaddingTop() + layout.getLineTop(layout.getLineForOffset(at)));
    }

    /** Marks, in the page list, the heading you are reading (a search through the headings' places, not every line). */
    private void markReading() {
        var layout = text.getLayout();
        if (layout == null || sectionRows.isEmpty() || sectionOffsets.size() != sectionRows.size()) return;
        int reading = scroll.getScrollY() + text.dp(48) - text.getTop() - text.getPaddingTop();
        int low = 0, high = sectionOffsets.size() - 1, current = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (layout.getLineTop(layout.getLineForOffset(sectionOffsets.get(mid))) <= reading) {
                current = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        if (current == marked) return;
        marked = current;
        for (int i = 0; i < sectionRows.size(); i++) sectionRows.get(i).setTextColor(i == current ? IdeColors.ACCENT : IdeColors.TEXT_SECONDARY);
        if (current >= 0) {
            View row = sectionRows.get(current);
            if (row.getTop() < pageListScroll.getScrollY()) {
                pageListScroll.scrollTo(0, Math.max(0, row.getTop() - row.dp(8)));
            } else if (row.getBottom() > pageListScroll.getScrollY() + pageListScroll.getHeight()) {
                pageListScroll.scrollTo(0, row.getBottom() - pageListScroll.getHeight() + row.dp(8));
            }
        }
    }

    private void fillPager() {
        pager.removeAllViews();
        int at = page == null ? -1 : pages.indexOf(page);
        if (at > 0) {
            DocIndex.Page previous = pages.get(at - 1);
            pager.addView(button(context, "← " + previous.title(), R.attr.borderlessButtonStyle, v -> showPage(previous, -1)));
        }
        pager.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1));
        if (at >= 0 && at + 1 < pages.size()) {
            DocIndex.Page next = pages.get(at + 1);
            pager.addView(button(context, next.title() + " →", R.attr.buttonTonalStyle, v -> showPage(next, -1)));
        }
    }

    /** Back to the page text after the start cards or search results took the space. */
    private void restoreContent() {
        if (content.getChildAt(0) == text && content.getChildCount() == 2) return;
        content.removeAllViews();
        content.addView(text, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        content.addView(pager, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
    }

    // ------------------------------------------------------------------
    // Start and search
    // ------------------------------------------------------------------

    /** Cards for the cookbook's recipes, what changed last, and where the rest is. */
    private void showStart(List<DocIndex.Page> cookbook) {
        page = null;
        fillPageList();
        content.removeAllViews();
        int pad = content.dp(28);
        var intro = docText(context);
        intro.setPadding(pad, content.dp(16), pad, content.dp(4));
        markflow.setMarkdown(intro, "# CraftPy docs\n\nType in the search box above to find anything in every page. The **Cookbook** "
                + "has complete examples to copy; the **IDE guide** explains the editor and its makers; the **Python API** lists "
                + "every function.");
        content.addView(intro, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        document("api.md").thenAccept(api -> content.post(() -> {
            if (topic != START) return;
            api.stream().filter(p -> p.title().equals("API versions")).findFirst().ifPresent(versions -> {
                DocIndex.Section latest = versions.sections().stream().filter(s -> s.level() == 3).findFirst().orElse(null);
                if (latest == null) return;
                var card = startCard("What's new: API " + latest.title(), "What the newest version added and changed.",
                        () -> openTopic(4, null, -1));
                content.addView(card, 1, cardParams());
            });
        }));
        var heading = label(context, "Cookbook", R.attr.textAppearanceTitleMedium);
        heading.setTextColor(IdeColors.TEXT);
        heading.setPadding(pad, content.dp(12), pad, content.dp(6));
        content.addView(heading);
        var cards = new FlowLayout(context);
        cards.setPadding(pad - content.dp(6), 0, pad, pad);
        for (DocIndex.Page recipe : cookbook) {
            if (recipe.number() == 0) continue; // the cookbook's own introduction
            cards.addView(startCard(recipe.title(), recipe.summary(), () -> openTopic(1, recipe.title(), -1)),
                    new LinearLayout.LayoutParams(content.dp(260), WRAP_CONTENT));
        }
        content.addView(cards, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        scroll.scrollTo(0, 0);
    }

    private LinearLayout.LayoutParams cardParams() {
        var lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        lp.leftMargin = lp.rightMargin = content.dp(28);
        lp.bottomMargin = content.dp(6);
        return lp;
    }

    private View startCard(String title, String summary, Runnable open) {
        var card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(card.dp(12), card.dp(10), card.dp(12), card.dp(10));
        card.setBackground(IdeColors.fading(card, 8, IdeColors.BORDER, IdeColors.SELECTED, IdeColors.HOVER, IdeColors.RAISED,
                IdeColors.RAISED, IdeColors.RAISED));
        var name = label(context, title, R.attr.textAppearanceTitleSmall);
        name.setTextColor(IdeColors.TEXT);
        card.addView(name);
        if (!summary.isEmpty()) {
            var about = label(context, summary, R.attr.textAppearanceBodySmall);
            about.setTextColor(IdeColors.TEXT_SECONDARY);
            card.addView(about);
        }
        card.setClickable(true);
        card.setOnClickListener(v -> open.run());
        var holder = new LinearLayout(context);
        holder.setPadding(card.dp(6), card.dp(6), 0, 0);
        holder.addView(card, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        return holder;
    }

    private void runSearch() {
        searchAll(search.getText().toString(), false);
    }

    /** Searches every document; {@code openFirst} goes straight to the best result. */
    private void searchAll(String query, boolean openFirst) {
        if (query.isBlank()) {
            openTopic(topic < 0 ? START : topic, page == null ? null : page.title(), -1);
            return;
        }
        int run = ++opening;
        List<CompletableFuture<List<DocIndex.Page>>> all = FILES.stream().map(HelpPanel::document).toList();
        CompletableFuture.allOf(all.toArray(CompletableFuture[]::new)).thenApplyAsync(done -> {
            List<DocIndex.Page> every = new ArrayList<>();
            for (var future : all) every.addAll(future.join());
            return DocIndex.search(every, query, 40);
        }, CraftPyExecutors.pool()).thenAccept(hits -> column.post(() -> {
            if (run != opening) return;
            if (openFirst && !hits.isEmpty()) {
                openHit(hits.get(0));
                return;
            }
            showHits(query, hits);
        }));
    }

    private void showHits(String query, List<DocIndex.Hit> hits) {
        page = null;
        content.removeAllViews();
        int pad = content.dp(28);
        var title = label(context, hits.isEmpty() ? "Nothing found for \"" + query + "\"" : hits.size() + " results for \"" + query + "\"",
                R.attr.textAppearanceTitleMedium);
        title.setTextColor(IdeColors.TEXT);
        title.setPadding(pad, content.dp(16), pad, content.dp(8));
        content.addView(title);
        for (DocIndex.Hit hit : hits) {
            String where = TOPICS.stream().filter(t -> t.file().equals(hit.page().doc()) && t.page() == null).map(Topic::name)
                    .findFirst().orElse(hit.page().doc());
            var card = startCard(hit.heading(), where + "  ›  " + hit.page().title() + "\n" + hit.snippet(), () -> openHit(hit));
            content.addView(card, cardParams());
        }
        scroll.scrollTo(0, 0);
    }

    private void openHit(DocIndex.Hit hit) {
        int index = 1;
        for (int i = 1; i < TOPICS.size(); i++) {
            if (TOPICS.get(i).file().equals(hit.page().doc()) && TOPICS.get(i).page() == null) index = i;
        }
        if (hit.page().title().equals("API versions")) index = 4;
        openTopic(index, hit.page().title(), hit.line());
    }

    // ------------------------------------------------------------------
    // Text
    // ------------------------------------------------------------------

    /** The text view the documents are drawn in: IDE colors, a larger size and more space between lines. */
    private static TextView docText(Context context) {
        var text = new PlainText(context);
        text.setTextIsSelectable(true);
        text.setTextSize(15);
        text.setLineSpacing(0, 1.42f);
        text.setTextColor(IdeColors.TEXT);
        text.setLinkTextColor(IdeColors.ACCENT);
        text.setHighlightColor(IdeColors.FOCUS);
        return text;
    }

    /** Markdown in the IDE's look: green headings rules, raised code blocks, monospaced code. */
    static Markflow markflow(Context context) {
        return Markflow.builder(context).usePlugin(new MarkflowPlugin() {
            @Override
            public void configureTheme(MarkflowTheme.Builder theme) {
                theme.headingTextSizeMultipliers(1.9f, 1.5f, 1.25f, 1.1f, 1f, 0.9f)
                        .headingBreakColor(IdeColors.BORDER)
                        .headingBreakHeightDp(1)
                        .thematicBreakColor(IdeColors.BORDER)
                        .codeTypeface(EditorFont.get())
                        .codeBlockTypeface(EditorFont.get())
                        .codeTextColor(IdeColors.ACCENT)
                        .codeBackgroundColor(IdeColors.RAISED)
                        .codeBlockTextColor(IdeColors.TEXT)
                        .codeBlockBackgroundColor(IdeColors.PANEL)
                        .codeBlockMarginDp(12)
                        .blockQuoteColor(IdeColors.ACCENT)
                        .blockQuoteTextColor(IdeColors.TEXT_SECONDARY)
                        .listItemColor(IdeColors.ACCENT);
            }
        }).build();
    }

    /** A fenced code block of a document: its language tag ("python", "json", "") and its text. */
    record CodeBlock(String language, String code) {
    }

    /** The fenced code blocks of a Markdown document, in order. */
    static List<CodeBlock> codeBlocks(String markdown) {
        List<CodeBlock> out = new ArrayList<>();
        StringBuilder code = null;
        String language = "";
        for (String line : markdown.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("```")) {
                if (code == null) {
                    language = trimmed.substring(3).strip().toLowerCase(java.util.Locale.ROOT);
                    code = new StringBuilder();
                } else {
                    out.add(new CodeBlock(language, code.toString().stripTrailing()));
                    code = null;
                }
            } else if (code != null) {
                code.append(line).append('\n');
            }
        }
        return out;
    }

    /**
     * Colors the code blocks of rendered Markdown with the editor's syntax colors (Python, JSON, Markdown, ...): each block
     * is found in the rendered text in order, then its lines are tokenized like the editor does.
     */
    static void highlightCode(Spannable spannable, String markdown) {
        var theme = net.xmilon.craftpy.client.EditorSettings.get().theme();
        String rendered = spannable.toString();
        int from = 0;
        for (CodeBlock block : codeBlocks(markdown)) {
            if (block.code().isEmpty()) {
                continue;
            }
            int at = rendered.indexOf(block.code(), from);
            if (at < 0) {
                continue;
            }
            from = at + block.code().length();
            String path = switch (block.language()) {
                case "python", "py", "" -> "x.py";
                case "json" -> "x.json";
                case "markdown", "md" -> "x.md";
                default -> null;
            };
            if (path == null) {
                continue;
            }
            String language = net.xmilon.craftpy.client.Syntax.language(path);
            int pos = at;
            for (String line : block.code().split("\n", -1)) {
                int cursor = pos;
                for (var token : net.xmilon.craftpy.client.Syntax.tokenize(line, language)) {
                    int end = cursor + token.text().length();
                    if (token.kind() != net.xmilon.craftpy.client.Syntax.Kind.TEXT && end > cursor) {
                        spannable.setSpan(new icyllis.modernui.text.style.ForegroundColorSpan(theme.color(token.kind())),
                                cursor, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    cursor = end;
                }
                pos += line.length() + 1;
            }
        }
    }

    private static String load(String file) {
        try (InputStream in = IdeHost.get().doc(file)) {
            if (in == null) {
                return "# Missing\n\nThe bundled document `craftpy:docs/" + file + "` is not in this build.";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "# Could not read the document\n\n" + e.getMessage();
        }
    }
}
