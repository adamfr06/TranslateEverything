package dev.adamfr06.translateeverything.hud;

import dev.adamfr06.translateeverything.config.TEConfig;
import dev.adamfr06.translateeverything.gui.OverlayScreen;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.DeltaTracker;

/** Renders the translation cards. */
public class TranslationHud implements HudElement {
    @Override
    public void extractRenderState(GuiGraphicsExtractor context, DeltaTracker tickCounter) {
        if (Minecraft.getInstance().gui.screen() == null) {
            renderCards(context);
        }
    }

    /** Draws the cards after an open screen has rendered, so they are not buried by it. */
    public static void renderOverScreen(Screen screen, GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        renderCards(context);
    }

    private static void renderCards(GuiGraphicsExtractor context) {
        Minecraft client = Minecraft.getInstance();
        TEConfig cfg = TEConfig.get();
        if (!cfg.enabled || BoxManager.isEmpty() || client.player == null) {
            return;
        }
        if (client.gui.hud.isHidden() || client.gui.screen() instanceof OverlayScreen) {
            return;
        }
        if (client.gui.screen() != null && client.gui.screen().getClass().getPackageName().equals("dev.adamfr06.translateeverything.gui")) return;
        if (cfg.hideInDebugHud && client.getDebugOverlay().showDebugScreen()) {
            return;
        }

        float scale = (float) cfg.textScale;
        int width = (int) (context.guiWidth() / scale);
        int height = (int) (context.guiHeight() / scale);

        HudLayout.Layout layout = HudLayout.compute(client, width, height);
        long now = System.currentTimeMillis();

        context.pose().pushMatrix();
        context.pose().scale(scale, scale);
        for (HudLayout.Placed placed : layout.placed()) {
            HudLayout.drawBox(context, client.font, placed, placed.box().alpha(now), false, 0, true);
        }
        HudLayout.drawOverflow(context, client.font, layout, 1f);
        context.pose().popMatrix();
    }
}
