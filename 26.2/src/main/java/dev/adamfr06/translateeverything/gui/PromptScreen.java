package dev.adamfr06.translateeverything.gui;

import dev.adamfr06.translateeverything.config.TEConfig;
import dev.adamfr06.translateeverything.translate.TranslationService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The AI's instructions, on a screen of their own. */
public class PromptScreen extends Screen {
    private final Screen parent;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 48, 36);
    private MultiLineEditBox editor;

    public PromptScreen(Screen parent) {
        super(Component.literal("AI instructions"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        TEConfig cfg = TEConfig.get();
        layout.addTitleHeader(title, font);

        editor = layout.addToContents(MultiLineEditBox.builder()
                .build(font, Math.min(400, width - 40), height - 100, Component.literal("System prompt")));
        editor.setCharacterLimit(4096);
        editor.setValue(cfg.aiSystemPrompt.isBlank() ? TranslationService.DEFAULT_AI_PROMPT : cfg.aiSystemPrompt);
        editor.setValueListener(v -> cfg.aiSystemPrompt = v.equals(TranslationService.DEFAULT_AI_PROMPT) ? "" : v);

        LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
        footer.addChild(Button.builder(Component.literal("Restore default"), b -> {
            cfg.aiSystemPrompt = "";
            editor.setValue(TranslationService.DEFAULT_AI_PROMPT);
        }).width(150).build());
        footer.addChild(Button.builder(Component.literal("Done"), b -> onClose()).width(150).build());

        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
    }

    @Override
    public void onClose() {
        TEConfig.save();
        Minecraft.getInstance().setScreenAndShow(parent);
    }
}
