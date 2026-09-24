package dev.adamfr06.translateeverything.gui;

import dev.adamfr06.translateeverything.config.TEConfig;
import dev.adamfr06.translateeverything.gui.widget.SettingsList;
import dev.adamfr06.translateeverything.translate.ChatContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ThreePartsLayoutWidget;
import net.minecraft.client.gui.widget.DirectionalLayoutWidget;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.List;

/** Which recent chat lines the translator is allowed to read. */
public class ContextScreen extends Screen {
    private final Screen parent;
    private final ThreePartsLayoutWidget layout = new ThreePartsLayoutWidget(this);
    private SettingsList list;
    private List<ChatContext.Entry> shown = List.of();

    public ContextScreen(Screen parent) {
        super(Text.literal("Translation context"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        layout.addHeader(title, textRenderer);

        shown = ChatContext.recent(300);

        list = layout.addBody(new SettingsList(client, width, height, 0, 0));
        if (shown.isEmpty()) list.note("No recent messages");
        for (ChatContext.Entry e : shown) {
            ButtonWidget toggle = ButtonWidget.builder(label(e), b -> {
                e.manual = !e.included();
                b.setMessage(label(e));
            }).dimensions(0, 0, 310, 20).build();
            String who = e.origin == ChatContext.Origin.OUTGOING ? "You: " : "";
            list.entry(toggle, who + e.text, e::included);
        }

        DirectionalLayoutWidget footer = layout.addFooter(DirectionalLayoutWidget.horizontal().spacing(8));
        footer.add(ButtonWidget.builder(Text.literal("Use all"), b -> setAll(true)).width(100).build());
        footer.add(ButtonWidget.builder(Text.literal("Ignore all"), b -> setAll(false)).width(100).build());
        footer.add(ButtonWidget.builder(Text.literal("Done"), b -> close()).width(100).build());

        layout.forEachElement(w -> w.forEachChild(this::addDrawableChild));
        refreshWidgetPositions();
    }

    private static Text label(ChatContext.Entry e) {
        boolean in = e.included();
        return Text.literal(in ? "Use" : "Ignore")
                .styled(s -> s.withColor(in ? 0x7FE08A : 0xE0827F));
    }

    private void setAll(boolean use) {
        for (ChatContext.Entry e : shown) {
            e.manual = use;
        }
        clearAndInit();
    }

    @Override
    protected void refreshWidgetPositions() {
        if (list != null) {
            list.position(width, layout);
        }
        layout.refreshPositions();
    }

    @Override
    public void close() {
        MinecraftClient.getInstance().setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
