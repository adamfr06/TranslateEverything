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
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen;
import net.minecraft.client.gui.screen.ingame.BookEditScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TranslateEverythingClient implements ClientModInitializer {
    public static final String MOD_ID = "translateeverything";
    public static final Logger LOGGER = LoggerFactory.getLogger("TranslateEverything");

    private static final String KEY_CATEGORY = "key.categories.translateeverything";

    private KeyBinding toggleKey;
    private KeyBinding configKey;
    private KeyBinding overlayKey;
    private KeyBinding manualKey;
    private KeyBinding dismissKey;
    private KeyBinding pinKey;
    private KeyBinding clearKey;
    private KeyBinding historyKey;
    private KeyBinding ocrKey;
    private KeyBinding immersionKey;
    private KeyBinding inputTranslateKey;
    private KeyBinding inputToggleKey;
    private KeyBinding retryAiKey;
    private KeyBinding followupKey;
    private KeyBinding contextKey;

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

        net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
                dispatcher.register(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("teai")
                        .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument(
                                        "id", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                .executes(ctx -> {
                                    dev.adamfr06.translateeverything.capture.InputTranslator.openFollowupFor(
                                            MinecraftClient.getInstance(),
                                            com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "id"));
                                    return 1;
                                }))));
        net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
                dispatcher.register(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("teforce")
                        .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument(
                                        "id", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                .executes(ctx -> {
                                    dev.adamfr06.translateeverything.capture.ChatTranslator.forceTranslate(
                                            MinecraftClient.getInstance(),
                                            com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "id"));
                                    return 1;
                                }))));

        HudElementRegistry.addLast(Identifier.of(MOD_ID, "translation_cards"), new TranslationHud());
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register(
                (client, screen, scaledWidth, scaledHeight) ->
                        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.afterRender(screen)
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

    private static KeyBinding register(String name, int code) {
        return KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.translateeverything." + name, InputUtil.Type.KEYSYM, code, KEY_CATEGORY));
    }

    private void onTick(MinecraftClient client) {
        while (toggleKey.wasPressed()) {
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
        while (overlayKey.wasPressed()) {
            client.setScreen(new OverlayScreen());
        }
        while (configKey.wasPressed()) {
            client.setScreen(new ConfigScreen(null));
        }
        while (manualKey.wasPressed()) {
            CaptureManager.manualTranslate(client);
        }
        while (dismissKey.wasPressed()) {
            BoxManager.dismissNewest();
        }
        while (pinKey.wasPressed()) {
            BoxManager.togglePinNewest();
        }
        while (clearKey.wasPressed()) {
            BoxManager.clearAll();
        }
        while (historyKey.wasPressed()) {
            client.setScreen(new HistoryScreen(null));
        }
        while (ocrKey.wasPressed()) {
            OcrService.scanScreen(client);
        }
        while (immersionKey.wasPressed()) {
            TEConfig cfg = TEConfig.get();
            cfg.immersionMode = !cfg.immersionMode;
            TEConfig.save();
            BoxManager.clearAll();
            CaptureManager.feedback(client, cfg.immersionMode
                    ? "Full immersion ON. Text is translated in place"
                    : "Full immersion OFF. Back to cards");
        }

        while (inputToggleKey.wasPressed()) {
            TEConfig cfg = TEConfig.get();
            cfg.inputTranslatorEnabled = !cfg.inputTranslatorEnabled;
            TEConfig.save();
            CaptureManager.feedback(client, cfg.inputTranslatorEnabled
                    ? "Input translation ON (" + cfg.inputMode.label + ")"
                    : "Input translation OFF");
        }

        while (retryAiKey.wasPressed()) {
            CaptureManager.manualTranslate(client, TEConfig.Engine.AI_LOCAL);
        }
        while (followupKey.wasPressed()) {
            dev.adamfr06.translateeverything.capture.InputTranslator.openFollowup(client);
        }
        while (contextKey.wasPressed()) {
            client.setScreen(new dev.adamfr06.translateeverything.gui.ContextScreen(null));
        }

        handleInScreenHotkeys(client);
        ChatTranslator.tick(client);
        CaptureManager.tick(client);
        BoxManager.tick();
    }

    private boolean ocrKeyWasDown = false;
    private boolean inputKeyWasDown = false;
    private boolean retryAiKeyWasDown = false;

    /**
     * Vanilla key bindings don't fire while a screen is open, so poll raw keys here for the OCR
     * scan and the input-translate action.
     */
    private void handleInScreenHotkeys(MinecraftClient client) {
        Screen screen = client.currentScreen;
        if (screen == null) {
            ocrKeyWasDown = false;
            inputKeyWasDown = false;
            retryAiKeyWasDown = false;
            return;
        }

        // Retry-with-AI while reading a book (keybinds don't fire inside screens).
        InputUtil.Key retry = KeyBindingHelper.getBoundKeyOf(retryAiKey);
        if (retry.getCategory() == InputUtil.Type.KEYSYM && retry.getCode() != GLFW.GLFW_KEY_UNKNOWN) {
            boolean down = InputUtil.isKeyPressed(client.getWindow().getHandle(), retry.getCode());
            boolean pressed = down && !retryAiKeyWasDown;
            retryAiKeyWasDown = down;
            if (pressed && screen instanceof net.minecraft.client.gui.screen.ingame.BookScreen) {
                CaptureManager.manualTranslate(client, TEConfig.Engine.AI_LOCAL);
            }
        }

        // OCR: only when not typing.
        InputUtil.Key ocr = KeyBindingHelper.getBoundKeyOf(ocrKey);
        if (ocr.getCategory() == InputUtil.Type.KEYSYM && ocr.getCode() != GLFW.GLFW_KEY_UNKNOWN) {
            boolean down = InputUtil.isKeyPressed(client.getWindow().getHandle(), ocr.getCode());
            boolean pressed = down && !ocrKeyWasDown;
            ocrKeyWasDown = down;
            if (pressed
                    && !(screen.getFocused() instanceof TextFieldWidget)
                    && !(screen instanceof AbstractSignEditScreen)
                    && !(screen instanceof BookEditScreen)) {
                OcrService.scanScreen(client);
            }
        }

        InputUtil.Key inp = KeyBindingHelper.getBoundKeyOf(inputTranslateKey);
        if (TEConfig.get().inputTranslatorEnabled
                && inp.getCategory() == InputUtil.Type.KEYSYM && inp.getCode() != GLFW.GLFW_KEY_UNKNOWN) {
            boolean down = InputUtil.isKeyPressed(client.getWindow().getHandle(), inp.getCode());
            boolean pressed = down && !inputKeyWasDown;
            inputKeyWasDown = down;
            if (pressed && screen.getFocused() instanceof TextFieldWidget field) {
                InputTranslator.translateFocusedField(client, field);
            }
        }
    }
}
