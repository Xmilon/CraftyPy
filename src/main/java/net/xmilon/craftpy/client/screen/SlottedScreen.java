package net.xmilon.craftpy.client.screen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.xmilon.craftpy.client.ui.*;

import java.util.ArrayList;
import java.util.List;

/**
 * A screen whose layout is dynamically built from a JSON structure
 * provided by the Python logic.
 */
public class SlottedScreen extends Screen {
    private final JsonObject uiDef;
    private final List<Widget> widgets = new ArrayList<>();
    private final UIContext context = new UIContext();

    public SlottedScreen(JsonObject uiDef) {
        super(Component.literal("Custom GUI"));
        this.uiDef = uiDef;
    }

    @Override
    protected void init() {
        widgets.clear();
        buildUI(uiDef, 0, 0);
    }

    private void buildUI(JsonObject def, int parentX, int parentY) {
        String type = def.has("type") ? def.get("type").getAsString() : "panel";
        int x = parentX + (def.has("x") ? def.get("x").getAsInt() : 0);
        int y = parentY + (def.has("y") ? def.get("y").getAsInt() : 0);
        int w = def.has("w") ? def.get("w").getAsInt() : 100;
        int h = def.has("h") ? def.get("h").getAsInt() : 100;

        Widget widget = null;
        switch (type) {
            case "label" -> {
                String text = def.has("text") ? def.get("text").getAsString() : "";
                widget = new Label(text);
            }
            case "button" -> {
                String text = def.has("text") ? def.get("text").getAsString() : "";
                widget = new Button(text, () -> {
                    // TODO: handle on_click
                });
            }
            case "panel" -> {
                widget = new Panel(Theme.PANEL).withBorder();
            }
        }

        if (widget != null) {
            widget.setBounds(x, y, w, h);
            widgets.add(widget);
            
            if (def.has("children") && def.get("children").isJsonArray()) {
                JsonArray children = def.getAsJsonArray("children");
                for (JsonElement child : children) {
                    if (child.isJsonObject()) {
                        buildUI(child.getAsJsonObject(), x, y);
                    }
                }
            }
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        for (Widget w : widgets) {
            w.render(graphics, mouseX, mouseY, partialTick);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        for (int i = widgets.size() - 1; i >= 0; i--) {
            if (widgets.get(i).mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
