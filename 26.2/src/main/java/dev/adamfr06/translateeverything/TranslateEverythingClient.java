package dev.adamfr06.translateeverything;

import dev.adamfr06.translateeverything.capture.CaptureManager;
import dev.adamfr06.translateeverything.capture.ChatTranslator;
import dev.adamfr06.translateeverything.capture.InputTranslator;
import dev.adamfr06.translateeverything.config.TEConfig;
import dev.adamfr06.translateeverything.gui.ConfigScreen;
import dev.adamfr06.translateeverything.gui.HistoryScreen;
import dev.adamfr06.translateeverything.gui.OverlayScreen;
import dev.adamfr06.translateeverything.hud.BoxManager;
import dev.adamfr06.translateeverything.hud.TranslationHud;
import dev.adamfr06.translateeverything.translate.OcrService;
import dev.adamfr06.translateeverything.translate.TranslationService;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.client.gui.components.EditBox;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TranslateEverythingClient implements ClientModInitializer {
    public static final String MOD_ID = "translateeverything";
    public static final Logger LOGGER = LoggerFactory.getLogger("TranslateEverything");

    private static final KeyMapping.Category KEY_CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));

    private KeyMapping toggleKey;
    private KeyMapping configKey;
    private KeyMapping overlayKey;
    private KeyMapping manualKey;
    private KeyMapping dismissKey;
    private KeyMapping pinKey;
    private KeyMapping clearKey;
    private KeyMapping historyKey;
    private KeyMapping ocrKey;
    private KeyMapping immersionKey;
    private KeyMapping inputTranslateKey;
    private KeyMapping inputToggleKey;
    private KeyMapping retryAiKey;
    private KeyMapping followupKey;
    private KeyMapping contextKey;

    /** Other chat-translator mods that hook the same chat pipeline; map id -> display name. */
    private static final java.util.Map<String, String> CONFLICTING_CHAT_MODS = java.util.Map.of(
            "google-chat", "GoogleChat",
            "googlechat", "GoogleChat");

    @Override
    public void onInitializeClient() {
        TEConfig.load();
        TranslationService.init();
        dev.adamfr06.translateeverything.translate.GameLanguages.install();

        for (var entry : CONFLICTING_CHAT_MODS.entrySet()) {
            if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded(entry.getKey())) {
                ChatTranslator.setConflictMod(entry.getValue());
                LOGGER.warn("Detected {}: it also translates chat. Running both is not recommended.", entry.getValue());
                break;
            }
        }

        toggleKey = register("toggle", GLFW.GLFW_KEY_K);
        overlayKey = register("overlay", GLFW.GLFW_KEY_B);
        configKey = register("config", GLFW.GLFW_KEY_O);
        manualKey = register("manual", GLFW.GLFW_KEY_I);
        dismissKey = register("dismiss", GLFW.GLFW_KEY_N);
        pinKey = register("pin", GLFW.GLFW_KEY_J);
        clearKey = register("clear", GLFW.GLFW_KEY_UNKNOWN);
        historyKey = register("history", GLFW.GLFW_KEY_UNKNOWN);
        ocrKey = register("ocr", GLFW.GLFW_KEY_UNKNOWN);
        immersionKey = register("immersion", GLFW.GLFW_KEY_UNKNOWN);
        inputTranslateKey = register("input_translate", GLFW.GLFW_KEY_UNKNOWN);
        inputToggleKey = register("input_toggle", GLFW.GLFW_KEY_UNKNOWN);
        retryAiKey = register("retry_ai", GLFW.GLFW_KEY_UNKNOWN);
        followupKey = register("followup", GLFW.GLFW_KEY_UNKNOWN);
        contextKey = register("context_panel", GLFW.GLFW_KEY_UNKNOWN);

        // Client-side command backing the in-chat "⟳ ask AI" click on any message.
        net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
                dispatcher.register(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("teai")
                        .then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument(
                                        "id", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                .executes(ctx -> {
                                    InputTranslator.openFollowupFor(Minecraft.getInstance(),
                                            com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "id"));
                                    return 1;
                                }))));
        net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
                dispatcher.register(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("teforce")
                        .then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument(
                                        "id", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                .executes(ctx -> {
                                    ChatTranslator.forceTranslate(Minecraft.getInstance(),
                                            com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "id"));
                                    return 1;
                                }))));

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "translation_cards"), new TranslationHud());
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register(
                (client, screen, scaledWidth, scaledHeight) ->
                        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.afterExtract(screen)
                                .register(TranslationHud::renderOverScreen));
        ItemTooltipCallback.EVENT.register((stack, tooltipContext, tooltipType, lines) ->
                CaptureManager.onItemTooltip(stack, lines));
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> ChatTranslator.reset());
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) -> ChatTranslator.reset());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            TranslationService.shutdown();
            TEConfig.save();
        });

        LOGGER.info("TranslateEverything ready: [K] toggle, [B] card manager, [O] settings, [I] translate what you're looking at");
    }

    private static KeyMapping register(String name, int code) {
        return KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.translateeverything." + name, InputConstants.Type.KEYSYM, code, KEY_CATEGORY));
    }

    private void onTick(Minecraft client) {
        while (toggleKey.consumeClick()) {
            TEConfig cfg = TEConfig.get();
            cfg.enabled = !cfg.enabled;
            TEConfig.save();
            if (!cfg.enabled) {
                ChatTranslator.flushAll(client); // never strand held chat lines
            }
            if (client.player != null) {
                CaptureManager.feedback(client, cfg.enabled ? "Translation ON" : "Translation OFF");
            }
        }
        while (overlayKey.consumeClick()) {
            client.setScreenAndShow(new OverlayScreen());
        }
        while (configKey.consumeClick()) {
            client.setScreenAndShow(new ConfigScreen(null));
        }
        while (manualKey.consumeClick()) {
            CaptureManager.manualTranslate(client);
        }
        while (dismissKey.consumeClick()) {
            BoxManager.dismissNewest();
        }
        while (pinKey.consumeClick()) {
            BoxManager.togglePinNewest();
        }
        while (clearKey.consumeClick()) {
            BoxManager.clearAll();
        }
        while (historyKey.consumeClick()) {
            client.setScreenAndShow(new HistoryScreen(null));
        }
        while (ocrKey.consumeClick()) {
            OcrService.scanScreen(client);
        }
        while (immersionKey.consumeClick()) {
            TEConfig cfg = TEConfig.get();
            cfg.immersionMode = !cfg.immersionMode;
            TEConfig.save();
            BoxManager.clearAll();
            CaptureManager.feedback(client, cfg.immersionMode
                    ? "Full immersion ON. Text is translated in place"
                    : "Full immersion OFF. Back to cards");
        }

        while (inputToggleKey.consumeClick()) {
            TEConfig cfg = TEConfig.get();
            cfg.inputTranslatorEnabled = !cfg.inputTranslatorEnabled;
            TEConfig.save();
            CaptureManager.feedback(client, cfg.inputTranslatorEnabled
                    ? "Input translation ON (" + cfg.inputMode.label + ")"
                    : "Input translation OFF");
        }

        while (retryAiKey.consumeClick()) {
            CaptureManager.manualTranslate(client, TEConfig.Engine.AI_LOCAL);
        }
        while (followupKey.consumeClick()) {
            dev.adamfr06.translateeverything.capture.InputTranslator.openFollowup(client);
        }
        while (contextKey.consumeClick()) {
            client.setScreenAndShow(new dev.adamfr06.translateeverything.gui.ContextScreen(null));
        }

        handleInScreenHotkeys(client);
        ChatTranslator.tick(client);
        CaptureManager.tick(client);
        BoxManager.tick();
    }

    private boolean ocrKeyWasDown = false;

    /**
     * OCR is most useful while a menu/custom GUI is open, but vanilla key bindings never fire
     * inside screens, so poll the raw key there instead.
     */
    private boolean inputKeyWasDown = false;
    private boolean retryAiKeyWasDown = false;

    private void handleInScreenHotkeys(Minecraft client) {
        Screen screen = client.gui.screen();
        if (screen == null) {
            ocrKeyWasDown = false;
            inputKeyWasDown = false;
            retryAiKeyWasDown = false;
            return;
        }

        InputConstants.Key retry = KeyMappingHelper.getBoundKeyOf(retryAiKey);
        if (retry.getType() == InputConstants.Type.KEYSYM && retry.getValue() != GLFW.GLFW_KEY_UNKNOWN) {
            boolean down = InputConstants.isKeyDown(client.getWindow(), retry.getValue());
            boolean pressed = down && !retryAiKeyWasDown;
            retryAiKeyWasDown = down;
            if (pressed && screen instanceof net.minecraft.client.gui.screens.inventory.BookViewScreen) {
                CaptureManager.manualTranslate(client, TEConfig.Engine.AI_LOCAL);
            }
        }

        InputConstants.Key ocr = KeyMappingHelper.getBoundKeyOf(ocrKey);
        if (ocr.getType() == InputConstants.Type.KEYSYM && ocr.getValue() != GLFW.GLFW_KEY_UNKNOWN) {
            boolean down = InputConstants.isKeyDown(client.getWindow(), ocr.getValue());
            boolean pressed = down && !ocrKeyWasDown;
            ocrKeyWasDown = down;
            if (pressed
                    && !(screen.getFocused() instanceof EditBox)
                    && !(screen instanceof AbstractSignEditScreen)
                    && !(screen instanceof BookEditScreen)) {
                OcrService.scanScreen(client);
            }
        }

        InputConstants.Key inp = KeyMappingHelper.getBoundKeyOf(inputTranslateKey);
        if (TEConfig.get().inputTranslatorEnabled
                && inp.getType() == InputConstants.Type.KEYSYM && inp.getValue() != GLFW.GLFW_KEY_UNKNOWN) {
            boolean down = InputConstants.isKeyDown(client.getWindow(), inp.getValue());
            boolean pressed = down && !inputKeyWasDown;
            inputKeyWasDown = down;
            if (pressed && screen.getFocused() instanceof EditBox field) {
                InputTranslator.translateFocusedField(client, field);
            }
        }
    }
}
