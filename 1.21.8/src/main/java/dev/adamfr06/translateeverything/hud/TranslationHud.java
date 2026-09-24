package dev.adamfr06.translateeverything.hud;

import dev.adamfr06.translateeverything.config.TEConfig;
import dev.adamfr06.translateeverything.gui.OverlayScreen;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.render.RenderTickCounter;

/** Renders the translation cards. */
public class TranslationHud implements HudElement {
    @Override
    public void render(DrawContext context, RenderTickCounter tickCounter) {
        if (MinecraftClient.getInstance().currentScreen == null) {
            renderCards(context);
        }
    }

    /** Draws the cards after an open screen has rendered, so they are not buried by it. */
    public static void renderOverScreen(Screen screen, DrawContext context, int mouseX, int mouseY, float delta) {
        renderCards(context);
    }

    private static void renderCards(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        TEConfig cfg = TEConfig.get();
        if (!cfg.enabled || BoxManager.isEmpty() || client.player == null) {
            return;
        }
        if (client.options.hudHidden || client.currentScreen instanceof OverlayScreen) {
            return;
        }
        if (client.currentScreen != null && client.currentScreen.getClass().getPackageName().equals("dev.adamfr06.translateeverything.gui")) return;
        if (cfg.hideInDebugHud && client.getDebugHud().shouldShowDebugHud()) {
            return;
        }

        float scale = (float) cfg.textScale;
        int width = (int) (context.getScaledWindowWidth() / scale);
        int height = (int) (context.getScaledWindowHeight() / scale);

        HudLayout.Layout layout = HudLayout.compute(client, width, height);
        long now = System.currentTimeMillis();

        context.getMatrices().pushMatrix();
        context.getMatrices().scale(scale, scale);
        for (HudLayout.Placed placed : layout.placed()) {
            HudLayout.drawBox(context, client.textRenderer, placed, placed.box().alpha(now), false, 0, true);
        }
        HudLayout.drawOverflow(context, client.textRenderer, layout, 1f);
        context.getMatrices().popMatrix();
    }
}
