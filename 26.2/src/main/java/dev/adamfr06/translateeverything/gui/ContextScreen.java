package dev.adamfr06.translateeverything.gui;

import dev.adamfr06.translateeverything.config.TEConfig;
import dev.adamfr06.translateeverything.gui.widget.SettingsList;
import dev.adamfr06.translateeverything.translate.ChatContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Which recent chat lines the translator is allowed to read. */
public class ContextScreen extends Screen {
    private final Screen parent;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
    private SettingsList list;
    private List<ChatContext.Entry> shown = List.of();

    public ContextScreen(Screen parent) {
        super(Component.literal("Translation context"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        layout.addTitleHeader(title, font);

        shown = ChatContext.recent(300);

        list = layout.addToContents(new SettingsList(minecraft, width, height, 0, 0));
        if (shown.isEmpty()) list.note("No recent messages");
        for (ChatContext.Entry e : shown) {
            Button toggle = Button.builder(label(e), b -> {
                e.manual = !e.included();
                b.setMessage(label(e));
            }).bounds(0, 0, 310, 20).build();
            String who = e.origin == ChatContext.Origin.OUTGOING ? "You: " : "";
            list.entry(toggle, who + e.text, e::included);
        }

        LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
        footer.addChild(Button.builder(Component.literal("Use all"), b -> setAll(true)).width(100).build());
        footer.addChild(Button.builder(Component.literal("Ignore all"), b -> setAll(false)).width(100).build());
        footer.addChild(Button.builder(Component.literal("Done"), b -> onClose()).width(100).build());

        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    private static Component label(ChatContext.Entry e) {
        boolean in = e.included();
        return Component.literal(in ? "Use" : "Ignore")
                .withStyle(s -> s.withColor(in ? 0x7FE08A : 0xE0827F));
    }

    private void setAll(boolean use) {
        for (ChatContext.Entry e : shown) {
            e.manual = use;
        }
        rebuildWidgets();
    }

    @Override
    protected void repositionElements() {
        if (list != null) {
            list.updateSize(width, layout);
        }
        layout.arrangeElements();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
