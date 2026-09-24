package dev.adamfr06.translateeverything.translate;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.adamfr06.translateeverything.TranslateEverythingClient;
import dev.adamfr06.translateeverything.capture.CaptureManager;
import dev.adamfr06.translateeverything.capture.SourceType;
import dev.adamfr06.translateeverything.config.TEConfig;
import dev.adamfr06.translateeverything.hud.BoxManager;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Screenshot;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** BETA: on-demand OCR of the whole screen via the OCR.space API. */
public final class OcrService {
    private static final Logger LOGGER = TranslateEverythingClient.LOGGER;
    /** Free-tier OCR.space rejects uploads over ~1 MB; stay safely below. */
    private static final int MAX_PNG_BYTES = 950_000;
    private static final long STUCK_SCAN_MS = 20_000;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private static volatile boolean running = false;
    private static volatile long runningSince = 0;

    private OcrService() {
    }

    public static void scanScreen(Minecraft client) {
        TEConfig cfg = TEConfig.get();
        LOGGER.info("[OCR] Scan triggered (enabled={}, apiKey={})", cfg.ocrEnabled, maskKey(cfg.ocrApiKey));
        if (!cfg.ocrEnabled) {
            CaptureManager.feedback(client, "OCR scan is a beta feature. Enable it in Settings > Advanced");
            return;
        }
        long now = System.currentTimeMillis();
        if (running) {
            if (now - runningSince < STUCK_SCAN_MS) {
                LOGGER.info("[OCR] Ignored: a scan is already in flight ({}s old)", (now - runningSince) / 1000);
                CaptureManager.feedback(client, "OCR scan already running…");
                return;
            }
            // Self-heal: something died without resetting the latch.
            LOGGER.warn("[OCR] Previous scan never finished: resetting and starting a new one");
        }
        running = true;
        runningSince = now;
        CaptureManager.feedback(client, "Scanning screen…");
        try {
            Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),
                    image -> handleCapture(client, cfg, image));
            LOGGER.info("[OCR] Screenshot requested from main render target");
        } catch (Throwable t) {
            running = false;
            LOGGER.error("[OCR] Failed to request screenshot", t);
            CaptureManager.feedback(client, "OCR failed: couldn't capture screen");
        }
    }

    /** Runs wherever the screenshot callback fires; guarded against everything. */
    private static void handleCapture(Minecraft client, TEConfig cfg, NativeImage image) {
        byte[] png;
        try {
            LOGGER.info("[OCR] Captured {}x{} screenshot", image.getWidth(), image.getHeight());
            png = encodePng(image);
            LOGGER.info("[OCR] Encoded PNG: {} KB", png.length / 1024);
        } catch (Throwable t) {
            running = false;
            LOGGER.error("[OCR] Screenshot encode failed", t);
            client.execute(() -> CaptureManager.feedback(client, "OCR failed: couldn't encode screenshot"));
            return;
        } finally {
            image.close();
        }
        byte[] finalPng = png;
        CompletableFuture.runAsync(() -> {
            try {
                LOGGER.info("[OCR] Uploading {} KB to OCR.space…", finalPng.length / 1024);
                String text = requestOcr(cfg, finalPng);
                LOGGER.info("[OCR] Got {} chars of usable text", text.length());
                client.execute(() -> {
                    running = false;
                    if (text.isBlank()) {
                        CaptureManager.feedback(client, "OCR found no readable text");
                    } else {
                        LOGGER.info("[OCR] Showing result card");
                        BoxManager.pushEvent(SourceType.OCR, "OCR Scan", text, true);
                    }
                });
            } catch (Throwable t) {
                LOGGER.error("[OCR] Request failed", t);
                client.execute(() -> {
                    running = false;
                    CaptureManager.feedback(client, "OCR failed: " + shortMessage(t));
                });
            }
        });
    }

    /** Encodes to PNG, halving the image until it fits the upload limit. */
    private static byte[] encodePng(NativeImage original) throws IOException {
        NativeImage current = original;
        boolean owned = false;
        try {
            // Rough pre-shrink so Retina-sized frames don't need many encode passes.
            while ((long) current.getWidth() * current.getHeight() > 1_800_000L) {
                NativeImage half = halve(current);
                if (owned) {
                    current.close();
                }
                current = half;
                owned = true;
            }
            byte[] png = toPngBytes(current);
            while (png.length > MAX_PNG_BYTES && current.getWidth() > 480) {
                LOGGER.info("[OCR] PNG still {} KB: downscaling further", png.length / 1024);
                NativeImage half = halve(current);
                if (owned) {
                    current.close();
                }
                current = half;
                owned = true;
                png = toPngBytes(current);
            }
            return png;
        } finally {
            if (owned) {
                current.close();
            }
        }
    }

    /** NativeImage has no in-memory PNG export, so bounce through a temp file. */
    private static byte[] toPngBytes(NativeImage image) throws IOException {
        Path tmp = Files.createTempFile("translateeverything-ocr", ".png");
        try {
            image.writeToFile(tmp);
            return Files.readAllBytes(tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static NativeImage halve(NativeImage src) {
        NativeImage out = new NativeImage(src.getWidth() / 2, src.getHeight() / 2, false);
        for (int y = 0; y < out.getHeight(); y++) {
            for (int x = 0; x < out.getWidth(); x++) {
                out.setPixel(x, y, src.getPixel(x * 2, y * 2));
            }
        }
        return out;
    }

    private static String requestOcr(TEConfig cfg, byte[] png) throws IOException, InterruptedException {
        String body = "apikey=" + URLEncoder.encode(cfg.ocrApiKey, StandardCharsets.UTF_8)
                + "&OCREngine=2"
                + "&scale=true"
                + "&base64Image=" + URLEncoder.encode(
                "data:image/png;base64," + Base64.getEncoder().encodeToString(png), StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.ocr.space/parse/image"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        LOGGER.info("[OCR] OCR.space answered HTTP {} ({} bytes)", response.statusCode(), response.body().length());
        if (response.statusCode() == 403) {
            throw new IllegalStateException("HTTP 403: API key rejected");
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        if (root.has("IsErroredOnProcessing") && root.get("IsErroredOnProcessing").getAsBoolean()) {
            String message = root.has("ErrorMessage") ? root.get("ErrorMessage").toString() : "OCR error";
            LOGGER.warn("[OCR] Service reported an error: {}", message);
            throw new IllegalStateException(message);
        }
        JsonArray results = root.getAsJsonArray("ParsedResults");
        if (results == null || results.isEmpty()) {
            LOGGER.warn("[OCR] Response had no ParsedResults: {}", trim(response.body()));
            return "";
        }
        String parsed = results.get(0).getAsJsonObject().get("ParsedText").getAsString();
        LOGGER.info("[OCR] Raw parsed text: {} chars", parsed.length());
        return cleanOcrText(parsed);
    }

    /** Dedupes lines and drops obvious HUD noise (coordinates, pure numbers…). */
    private static String cleanOcrText(String parsed) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> lines = new ArrayList<>();
        for (String raw : parsed.split("\\r?\\n")) {
            String line = CaptureManager.sanitize(raw);
            if (line.length() < 3 || LanguageUtil.hasNoLetters(line)) {
                continue;
            }
            if (seen.add(line.toLowerCase())) {
                lines.add(line);
            }
        }
        return String.join("\n", lines);
    }

    private static String maskKey(String key) {
        if (key == null || key.isBlank()) {
            return "(none)";
        }
        if ("helloworld".equals(key)) {
            return "demo key";
        }
        return key.length() <= 4 ? "***" : key.substring(0, 2) + "***" + key.substring(key.length() - 2);
    }

    private static String trim(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    private static String shortMessage(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }
}
