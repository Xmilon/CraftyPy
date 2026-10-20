package net.xmilon.craftpy.client.mui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.google.gson.JsonObject;

import icyllis.modernui.R;
import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.drawable.ColorDrawable;
import icyllis.modernui.text.Editable;
import icyllis.modernui.text.TextWatcher;
import icyllis.modernui.view.Menu;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.BaseAdapter;
import icyllis.modernui.widget.EditText;
import icyllis.modernui.widget.HorizontalScrollView;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.ListView;
import icyllis.modernui.widget.TextView;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/**
 * The Mod catalog tool window: every id the installed mods bring (items, blocks, attributes, effects, sounds, their own
 * registries such as spells), their tags, recipe types with fields, Java events and classes, in one scrolling list with a
 * search box and a mod filter. It follows the cursor like completion does: inside {@code Attributes("spe} it shows the
 * attributes matching {@code spe}. Clicking an entry puts it into the code.
 */
final class CatalogPanel {
    interface Host {
        /** Runs the {@code catalog} tool; the reply arrives on the UI thread. */
        void request(JsonObject args, Consumer<JsonObject> reply);

        void insert(String id);

        void hide();
    }

    private record Row(String id, String mod, String detail) {
    }

    private static final long SEARCH_DELAY_MS = 180;

    private final Host host;
    private final LinearLayout view;
    private final EditText search;
    private final LinearLayout kinds;
    private final TextView modButton;
    private final TextView followButton;
    private final TextView status;
    private final List<Row> rows = new ArrayList<>();
    private final Adapter adapter = new Adapter();
    private List<String> mods = List.of();
    private String kindsSignature = "";
    private String kind = "";
    private String mod = "";
    private boolean follow = true;
    private boolean typingSearch;
    private int seq;
    private final Runnable searchTask = this::refresh;

    CatalogPanel(Context context, Host host) {
        this.host = host;
        view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setBackground(new ColorDrawable(IdeColors.PANEL));

        var header = CraftPyFragment.row(context);
        header.setPadding(header.dp(12), 0, header.dp(4), 0);
        var title = CraftPyFragment.label(context, "Mod catalog", R.attr.textAppearanceLabelLarge);
        title.setTextColor(IdeColors.TEXT);
        header.addView(title, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        followButton = link(context, "", "Follow the cursor: inside a string that takes an id, show that kind and filter by what is typed",
                v -> {
                    follow = !follow;
                    updateFollow();
                });
        header.addView(followButton);
        header.addView(EditorFragment.action(context, IdeIcon.Glyph.MINUS, "Hide", v -> host.hide()));
        view.addView(header, new LinearLayout.LayoutParams(MATCH_PARENT, header.dp(36)));

        var filters = CraftPyFragment.row(context);
        search = CraftPyFragment.field(context);
        search.setSingleLine();
        search.setHint("Search ids (words in any order)");
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (typingSearch) {
                    return;
                }
                search.removeCallbacks(searchTask);
                search.postDelayed(searchTask, SEARCH_DELAY_MS);
            }
        });
        filters.addView(search, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        modButton = link(context, "All mods", "Show one mod only", this::chooseMod);
        filters.addView(modButton);
        var filtersLp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        filtersLp.setMargins(view.dp(10), 0, view.dp(6), view.dp(4));
        view.addView(filters, filtersLp);

        var kindScroll = new HorizontalScrollView(context);
        kinds = CraftPyFragment.row(context);
        kinds.setPadding(view.dp(8), 0, view.dp(8), 0);
        kindScroll.addView(kinds);
        view.addView(kindScroll, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        status = CraftPyFragment.label(context, "", R.attr.textAppearanceLabelSmall);
        status.setTextColor(IdeColors.TEXT_DISABLED);
        status.setPadding(view.dp(12), view.dp(4), view.dp(12), view.dp(4));
        view.addView(status);

        var list = new ListView(context);
        list.setAdapter(adapter);
        list.setDivider(null);
        list.setOnItemClickListener((parent, item, position, id) -> {
            if (position >= 0 && position < rows.size()) {
                host.insert(rows.get(position).id());
            }
        });
        view.addView(list, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1));
        updateFollow();
    }

    View view() {
        return view;
    }

    void focusSearch() {
        search.requestFocus();
    }

    /** Loads the list for the chosen kind, search and mod. */
    void refresh() {
        JsonObject args = new JsonObject();
        args.addProperty("kind", kind);
        args.addProperty("q", search.getText().toString());
        args.addProperty("mod", mod);
        send(args, false);
    }

    /** The cursor moved: when it is inside a string that takes an id, show that kind filtered by the typed part. */
    void follow(String code, int line, int column) {
        if (!follow || view.getVisibility() != View.VISIBLE) {
            return;
        }
        JsonObject args = new JsonObject();
        args.addProperty("code", code);
        args.addProperty("line", line);
        args.addProperty("column", column);
        args.addProperty("mod", mod);
        args.addProperty("kind", kind);
        send(args, true);
    }

    private void send(JsonObject args, boolean following) {
        int mine = ++seq;
        args.addProperty("seq", mine);
        host.request(args, reply -> {
            if (mine != seq) {
                return; // an older request: a newer one is on its way
            }
            if (reply.has("error")) {
                status.setText(reply.get("error").getAsString());
                return;
            }
            if (following && !reply.has("detected")) {
                return; // the cursor is not in an id string: keep what is shown
            }
            show(reply);
        });
    }

    private void show(JsonObject reply) {
        kind = reply.has("kind") ? reply.get("kind").getAsString() : kind;
        if (reply.has("detected")) {
            typingSearch = true;
            try {
                search.setText(reply.has("typed") ? reply.get("typed").getAsString() : "");
            } finally {
                typingSearch = false;
            }
        }
        List<String> modList = new ArrayList<>();
        reply.getAsJsonArray("mods").forEach(m -> modList.add(m.getAsString()));
        mods = modList;
        renderKinds(reply);
        rows.clear();
        for (var element : reply.getAsJsonArray("rows")) {
            JsonObject row = element.getAsJsonObject();
            rows.add(new Row(row.get("id").getAsString(), row.has("mod") ? row.get("mod").getAsString() : "",
                    row.has("detail") ? row.get("detail").getAsString() : ""));
        }
        adapter.notifyDataSetChanged();
        int matched = reply.has("matched") ? reply.get("matched").getAsInt() : rows.size();
        String shown = matched > rows.size() ? rows.size() + " of " + matched : String.valueOf(matched);
        status.setText(shown + " " + kind + (mod.isEmpty() ? "" : " from " + mod)
                + (reply.has("detected") ? "  -  following the cursor" : "") + "  -  click one to put it in the code");
        if (rows.isEmpty() && reply.getAsJsonArray("kinds").isEmpty()) {
            status.setText("The catalog is written when the game starts with the mods installed; start it once.");
        }
    }

    private void renderKinds(JsonObject reply) {
        String signature = reply.getAsJsonArray("kinds").toString() + "|" + kind;
        if (signature.equals(kindsSignature)) {
            return;
        }
        kindsSignature = signature;
        kinds.removeAllViews();
        Context context = kinds.getContext();
        for (var element : reply.getAsJsonArray("kinds")) {
            JsonObject row = element.getAsJsonObject();
            String name = row.get("kind").getAsString();
            int count = row.get("count").getAsInt();
            if (count == 0) {
                continue;
            }
            var chip = CraftPyFragment.label(context, name.replace('_', ' ') + "  " + count, R.attr.textAppearanceLabelMedium);
            chip.setPadding(chip.dp(10), chip.dp(4), chip.dp(10), chip.dp(4));
            chip.setClickable(true);
            boolean selected = name.equals(kind);
            CraftPyFragment.styleChip(chip, selected);
            chip.setTextColor(selected ? IdeColors.TEXT : IdeColors.TEXT_SECONDARY);
            chip.setOnClickListener(v -> {
                kind = name;
                follow = false; // picking a kind by hand stops following the cursor
                updateFollow();
                refresh();
            });
            var lp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
            lp.rightMargin = chip.dp(4);
            kinds.addView(chip, lp);
        }
    }

    private void chooseMod(View anchor) {
        var menu = Overlays.menu(anchor);
        Menu m = menu.getMenu();
        m.add("All mods").setOnMenuItemClickListener(item -> {
            setMod("");
            return true;
        });
        for (String name : mods) {
            m.add(name).setOnMenuItemClickListener(item -> {
                setMod(name);
                return true;
            });
        }
        menu.show();
    }

    private void setMod(String name) {
        mod = name;
        modButton.setText(name.isEmpty() ? "All mods" : name);
        refresh();
    }

    private void updateFollow() {
        followButton.setText(follow ? "Following cursor" : "Follow cursor");
        followButton.setTextColor(follow ? IdeColors.ACCENT : IdeColors.TEXT_SECONDARY);
    }

    private static TextView link(Context context, String text, String tooltip, View.OnClickListener onClick) {
        var link = CraftPyFragment.label(context, text, R.attr.textAppearanceLabelMedium);
        link.setTextColor(IdeColors.ACCENT);
        link.setPadding(link.dp(8), link.dp(4), link.dp(8), link.dp(4));
        link.setClickable(true);
        link.setTooltipText(tooltip);
        IdeColors.hoverBackground(link, 4);
        link.setOnClickListener(EditorFragment.hidingTooltip(tooltip, onClick));
        return link;
    }

    /** Rows are views made once and reused while scrolling (thousands of ids stay smooth). */
    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            TextView id;
            TextView detail;
            if (convertView instanceof LinearLayout reused && reused.getChildCount() == 2) {
                row = reused;
                id = (TextView) row.getChildAt(0);
                detail = (TextView) row.getChildAt(1);
            } else {
                Context context = parent.getContext();
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(row.dp(12), row.dp(4), row.dp(12), row.dp(4));
                IdeColors.hoverBackground(row, 4);
                id = CraftPyFragment.label(context, "", R.attr.textAppearanceBodyMedium);
                id.setTypeface(EditorFont.get());
                id.setTextColor(IdeColors.TEXT);
                id.setSingleLine();
                detail = CraftPyFragment.label(context, "", R.attr.textAppearanceLabelSmall);
                detail.setTextColor(IdeColors.TEXT_SECONDARY);
                detail.setSingleLine();
                row.addView(id);
                row.addView(detail);
            }
            Row entry = rows.get(position);
            id.setText(entry.id());
            String text = entry.mod().isEmpty() ? entry.detail()
                    : entry.detail().isEmpty() ? entry.mod() : entry.mod() + "  -  " + entry.detail();
            detail.setText(text);
            detail.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
            return row;
        }
    }
}
