package net.xmilon.craftpy.client.mui;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import icyllis.modernui.annotation.NonNull;
import icyllis.modernui.annotation.Nullable;
import icyllis.modernui.fragment.Fragment;
import icyllis.modernui.markflow.Markflow;
import icyllis.modernui.mc.ui.ClampingScrollView;
import icyllis.modernui.util.DataSet;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.LayoutInflater;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.ScrollView;
import icyllis.modernui.widget.TextView;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/** The bundled Python API reference ({@code assets/craftpy/docs/api.md}), rendered as Markdown. */
public class DocsFragment extends Fragment {

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable DataSet savedInstanceState) {
        var context = requireContext();
        var text = new TextView(context);
        text.setTextIsSelectable(true);
        int dp20 = text.dp(20);
        text.setPadding(dp20, dp20, dp20, dp20);
        text.setText("Loading...");

        var scroll = new ClampingScrollView(context);
        scroll.setChildMaxWidth(text.dp(900));
        scroll.addView(text, new ScrollView.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));

        Markflow markflow = Markflow.create(context);
        // Resources belong to the client thread; render back on the UI thread.
        Minecraft.getInstance().execute(() -> {
            String markdown = loadApiDoc();
            text.post(() -> markflow.setMarkdown(text, markdown));
        });
        return scroll;
    }

    private static String loadApiDoc() {
        var location = ResourceLocation.fromNamespaceAndPath("craftpy", "docs/api.md");
        try {
            var resource = Minecraft.getInstance().getResourceManager().getResource(location);
            if (resource.isEmpty()) {
                return "# API reference\n\nThe bundled documentation (`craftpy:docs/api.md`) is missing.";
            }
            try (InputStream in = resource.get().open()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return "# API reference\n\nCould not read the documentation: " + e.getMessage();
        }
    }
}
