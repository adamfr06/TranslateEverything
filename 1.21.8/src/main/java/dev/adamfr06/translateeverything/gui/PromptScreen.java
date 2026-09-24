package dev.adamfr06.translateeverything.gui;

import dev.adamfr06.translateeverything.config.TEConfig;
import dev.adamfr06.translateeverything.translate.TranslationService;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.EditBoxWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.client.gui.widget.ThreePartsLayoutWidget;
import net.minecraft.client.gui.widget.DirectionalLayoutWidget;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/** The AI's instructions, on a screen of their own. */
public class PromptScreen extends Screen {
    private final Screen parent;
    private final ThreePartsLayoutWidget layout = new ThreePartsLayoutWidget(this, 48, 36);
    private EditBoxWidget editor;

    public PromptScreen(Screen parent) {
        super(Text.literal("AI instructions"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        TEConfig cfg = TEConfig.get();
        layout.addHeader(title, textRenderer);

        editor = layout.addBody(EditBoxWidget.builder()
                .build(textRenderer, Math.min(400, width - 40), height - 100, Text.literal("System prompt")));
        editor.setMaxLength(4096);
        editor.setText(cfg.aiSystemPrompt.isBlank() ? TranslationService.DEFAULT_AI_PROMPT : cfg.aiSystemPrompt);
        editor.setChangeListener(v -> cfg.aiSystemPrompt = v.equals(TranslationService.DEFAULT_AI_PROMPT) ? "" : v);

        DirectionalLayoutWidget footer = layout.addFooter(DirectionalLayoutWidget.horizontal().spacing(8));
        footer.add(ButtonWidget.builder(Text.literal("Restore default"), b -> {
            cfg.aiSystemPrompt = "";
            editor.setText(TranslationService.DEFAULT_AI_PROMPT);
        }).width(150).build());
        footer.add(ButtonWidget.builder(Text.literal("Done"), b -> close()).width(150).build());

        layout.forEachElement(w -> w.forEachChild(this::addDrawableChild));
        refreshWidgetPositions();
    }

    @Override
    protected void refreshWidgetPositions() {
        layout.refreshPositions();
    }

    @Override
    public void close() {
        TEConfig.save();
        MinecraftClient.getInstance().setScreen(parent);
    }
}
