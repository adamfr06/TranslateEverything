package dev.adamfr06.translateeverything.capture;

import dev.adamfr06.translateeverything.config.TEConfig;
import dev.adamfr06.translateeverything.hud.BoxManager;
import dev.adamfr06.translateeverything.mixin.AbstractSignEditScreenAccessor;
import dev.adamfr06.translateeverything.mixin.BookEditScreenAccessor;
import dev.adamfr06.translateeverything.mixin.BookScreenAccessor;
import dev.adamfr06.translateeverything.mixin.BossBarHudAccessor;
import dev.adamfr06.translateeverything.mixin.HandledScreenAccessor;
import dev.adamfr06.translateeverything.mixin.MerchantScreenAccessor;
import dev.adamfr06.translateeverything.mixin.TextDisplayEntityInvoker;
import dev.adamfr06.translateeverything.translate.ImmersionService;
import dev.adamfr06.translateeverything.translate.LanguageUtil;
import dev.adamfr06.translateeverything.translate.TranslationService;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ClientBossBar;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen;
import net.minecraft.client.gui.screen.ingame.BookEditScreen;
import net.minecraft.client.gui.screen.ingame.BookScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.WrittenBookContentComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Watches the world and open screens for translatable text and feeds it into the {@link BoxManager}. */
public final class CaptureManager {
    /** Debounce slot for text the player is typing (sign edit, book edit). */
    private static final class PendingEdit {
        String key = "";
        String title = "";
        String text = "";
        long stableSince;
        boolean pushed;
    }

    private static final Map<SourceType, PendingEdit> pendingEdits = new EnumMap<>(SourceType.class);
    private static boolean suppressNextOverlay = false;
    private static int tickCounter = 0;

    private CaptureManager() {
    }

    // ------------------------------------------------------------------ mixin entry points

    /** Set while the mod re-delivers a held (translated) title/subtitle/action bar. */
    private static boolean internalHudMessage = false;

    public static void onTitle(Text text, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (internalHudMessage) {
            return;
        }
        if (ImmersionService.active(SourceType.TITLE) && holdForImmersion(text,
                t -> MinecraftClient.getInstance().inGameHud.setTitle(t))) {
            ci.cancel();
            return;
        }
        capture(SourceType.TITLE, "Title", text.getString());
    }

    public static void onSubtitle(Text text, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (internalHudMessage) {
            return;
        }
        if (ImmersionService.active(SourceType.SUBTITLE) && holdForImmersion(text,
                t -> MinecraftClient.getInstance().inGameHud.setSubtitle(t))) {
            ci.cancel();
            return;
        }
        capture(SourceType.SUBTITLE, "Subtitle", text.getString());
    }

    public static void onActionBar(Text text, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (internalHudMessage) {
            return;
        }
        if (suppressNextOverlay) {
            suppressNextOverlay = false;
            return;
        }
        if (ImmersionService.active(SourceType.ACTION_BAR) && holdForImmersion(text,
                t -> MinecraftClient.getInstance().inGameHud.setOverlayMessage(t, false))) {
            ci.cancel();
            return;
        }
        capture(SourceType.ACTION_BAR, "Action Bar", text.getString());
    }

    /** True when the message was taken hostage for an in-place swap. */
    private static boolean holdForImmersion(Text text, java.util.function.Consumer<Text> setter) {
        String plain = sanitize(text.getString());
        if (!worthTranslating(TEConfig.get(), plain)) {
            return false;
        }
        ImmersionService.holdAndSwap(plain, text, replacement -> {
            internalHudMessage = true;
            try {
                setter.accept(replacement);
            } finally {
                internalHudMessage = false;
            }
        });
        return true;
    }

    private static void capture(SourceType type, String title, String raw) {
        TEConfig cfg = TEConfig.get();
        if (!cfg.enabled || !cfg.isSourceEnabled(type) || cfg.immersionMode) {
            return;
        }
        String text = sanitize(raw);
        if (worthTranslating(cfg, text)) {
            BoxManager.pushEvent(type, title, text);
        }
    }

    /** Lets the mod's own action-bar feedback bypass translation. */
    public static void suppressNextOverlay() {
        suppressNextOverlay = true;
    }

    // ------------------------------------------------------------------ tick polling

    public static void tick(MinecraftClient client) {
        TEConfig cfg = TEConfig.get();
        if (!cfg.enabled || client.player == null || client.world == null) {
            return;
        }
        tickCounter++;
        watchSign(client, cfg);
        watchEntity(client, cfg);
        watchScreens(client, cfg);
        if (tickCounter % 10 == 0) {
            watchBossBars(client, cfg);
        }
        if (tickCounter % 20 == 0) {
            watchScoreboard(client, cfg);
        }
        ImmersionService.tick(client);
        TranslationService.maybePersist();
    }

    private static void watchSign(MinecraftClient client, TEConfig cfg) {
        if (!cfg.isSourceEnabled(SourceType.SIGN) || cfg.immersionMode) {
            BoxManager.clearContext(SourceType.SIGN);
            return;
        }
        SignBlockEntity sign = targetedSign(client, cfg.scanRange);
        if (sign != null) {
            boolean front = sign.isPlayerFacingFront(client.player);
            String text = signText(sign, front);
            if (worthTranslating(cfg, text)) {
                BlockPos pos = sign.getPos();
                String key = "sign:" + pos.asLong() + ":" + (front ? "F" : "B") + ":" + text.hashCode();
                String title = "Sign · " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
                BoxManager.pushContext(SourceType.SIGN, title, text, key, false);
                return;
            }
        }
        BoxManager.clearContext(SourceType.SIGN);
    }

    private static void watchEntity(MinecraftClient client, TEConfig cfg) {
        if (!cfg.isSourceEnabled(SourceType.ENTITY) || cfg.immersionMode) {
            BoxManager.clearContext(SourceType.ENTITY);
            return;
        }
        Entity entity = findLookedAtNamedEntity(client, cfg.scanRange);
        if (entity != null) {
            String text;
            String title;
            if (entity instanceof DisplayEntity.TextDisplayEntity) {
                // Holograms are usually a stack of text displays, merge nearby lines.
                text = collectHologramText(client, entity);
                title = "Hologram";
            } else {
                text = sanitize(nameOf(entity));
                title = "Entity · " + entity.getType().getName().getString();
            }
            if (worthTranslating(cfg, text)) {
                String key = "entity:" + entity.getId() + ":" + text.hashCode();
                BoxManager.pushContext(SourceType.ENTITY, title, text, key, false);
                return;
            }
        }
        BoxManager.clearContext(SourceType.ENTITY);
    }

    /** Finds the named entity / text display closest to the player's line of sight within {@code range}. */
    private static Entity findLookedAtNamedEntity(MinecraftClient client, double range) {
        Vec3d eye = client.player.getCameraPosVec(1.0f);
        Vec3d look = client.player.getRotationVec(1.0f);
        Box search = client.player.getBoundingBox().expand(range);
        Entity best = null;
        double bestScore = Double.MAX_VALUE;
        for (Entity entity : client.world.getOtherEntities(client.player, search, CaptureManager::hasTranslatableName)) {
            Vec3d to = entity.getBoundingBox().getCenter().subtract(eye);
            double along = to.dotProduct(look);
            if (along <= 0 || along > range) {
                continue; // behind the player or too far
            }
            double offAxis = to.subtract(look.multiply(along)).length();
            double tolerance = Math.max(0.6, entity.getBoundingBox().getAverageSideLength() * 0.5) + along * 0.05;
            if (offAxis <= tolerance && along + offAxis * 4 < bestScore) {
                bestScore = along + offAxis * 4;
                best = entity;
            }
        }
        return best;
    }

    private static boolean hasTranslatableName(Entity entity) {
        if (entity instanceof DisplayEntity.TextDisplayEntity textDisplay) {
            Text text = ((TextDisplayEntityInvoker) textDisplay).translateeverything$getText();
            return text != null && !text.getString().isBlank();
        }
        return entity.getCustomName() != null;
    }

    private static String nameOf(Entity entity) {
        if (entity instanceof DisplayEntity.TextDisplayEntity textDisplay) {
            Text text = ((TextDisplayEntityInvoker) textDisplay).translateeverything$getText();
            return text == null ? "" : text.getString();
        }
        return entity.getCustomName() == null ? "" : entity.getCustomName().getString();
    }

    /** Merges the text-display lines stacked within ~2 blocks of {@code anchor}, top line first. */
    private static String collectHologramText(MinecraftClient client, Entity anchor) {
        List<Entity> parts = client.world.getOtherEntities(client.player,
                anchor.getBoundingBox().expand(1.0, 2.5, 1.0),
                e -> e instanceof DisplayEntity.TextDisplayEntity td
                        && !((TextDisplayEntityInvoker) td).translateeverything$getText().getString().isBlank());
        parts.sort(Comparator.comparingDouble(Entity::getY).reversed());
        StringBuilder sb = new StringBuilder();
        for (Entity part : parts) {
            String line = sanitize(nameOf(part));
            if (!line.isBlank()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private static void watchBossBars(MinecraftClient client, TEConfig cfg) {
        if (!cfg.isSourceEnabled(SourceType.BOSS_BAR)) {
            return;
        }
        Map<java.util.UUID, ClientBossBar> bars =
                ((BossBarHudAccessor) client.inGameHud.getBossBarHud()).translateeverything$getBossBars();
        for (ClientBossBar bar : bars.values()) {
            String text = sanitize(bar.getName().getString());
            if (!worthTranslating(cfg, text)) {
                continue;
            }
            if (cfg.immersionMode) {
                String ready = ImmersionService.ready(text);
                if (ready != null && !ready.equals(bar.getName().getString())) {
                    bar.setName(ImmersionService.restyled(bar.getName(), ready));
                }
            } else {
                BoxManager.pushEvent(SourceType.BOSS_BAR, "Boss Bar", text);
            }
        }
    }

    private static void watchScreens(MinecraftClient client, TEConfig cfg) {
        Screen screen = client.currentScreen;

        // --- reading a book
        if (screen instanceof BookScreen book && cfg.isSourceEnabled(SourceType.BOOK)) {
            BookScreenAccessor acc = (BookScreenAccessor) (Object) book;
            BookScreen.Contents contents = acc.translateeverything$getContents();
            int page = acc.translateeverything$getPageIndex();
            if (contents != null && page >= 0 && page < contents.getPageCount()) {
                String text = sanitize(contents.getPage(page).getString());
                if (cfg.immersionMode) {
                    BoxManager.clearContext(SourceType.BOOK);
                    java.util.List<net.minecraft.text.OrderedText> lines =
                            ImmersionService.bookPage(text, contents.getPage(page));
                    if (lines != null) {
                        acc.translateeverything$setCachedPage(lines);
                        acc.translateeverything$setCachedPageIndex(page);
                    }
                } else if (worthTranslating(cfg, text)) {
                    String key = "book:" + page + ":" + text.hashCode();
                    String title = "Book · Page " + (page + 1) + "/" + contents.getPageCount();
                    BoxManager.pushContext(SourceType.BOOK, title, text, key, false);
                } else {
                    BoxManager.clearContext(SourceType.BOOK);
                }
            }
        } else {
            BoxManager.clearContext(SourceType.BOOK);
        }

        // --- writing a book
        if (screen instanceof BookEditScreen edit && cfg.isSourceEnabled(SourceType.BOOK_EDIT)) {
            BookEditScreenAccessor acc = (BookEditScreenAccessor) (Object) edit;
            int page = acc.translateeverything$getCurrentPage();
            java.util.List<String> pages = acc.translateeverything$getPages();
            String text = page >= 0 && page < pages.size() ? sanitize(pages.get(page)) : "";
            debounceEdit(cfg, SourceType.BOOK_EDIT, "Writing · Page " + (page + 1), text, "bookedit:" + page);
        } else {
            resetEdit(SourceType.BOOK_EDIT);
        }

        // --- editing a sign
        if (screen instanceof AbstractSignEditScreen signEdit && cfg.isSourceEnabled(SourceType.SIGN_EDIT) && !cfg.writeTranslateButtons) {
            AbstractSignEditScreenAccessor acc = (AbstractSignEditScreenAccessor) (Object) signEdit;
            String text = sanitize(String.join("\n", acc.translateeverything$getMessages()).trim());
            SignBlockEntity be = acc.translateeverything$getBlockEntity();
            String pos = be != null ? be.getPos().asLong() + "" : "?";
            debounceEdit(cfg, SourceType.SIGN_EDIT, "Sign (editing)", text, "signedit:" + pos);
        } else {
            resetEdit(SourceType.SIGN_EDIT);
        }

        // --- hovering an item in any container screen
        if (screen instanceof HandledScreen<?> handled && cfg.isSourceEnabled(SourceType.ITEM) && !cfg.immersionMode) {
            watchHoveredItem(cfg, handled);
        } else {
            BoxManager.clearContext(SourceType.ITEM);
        }

        // --- hovering a villager / wandering trader offer
        if (screen instanceof MerchantScreen merchant && cfg.isSourceEnabled(SourceType.TRADE) && !cfg.immersionMode) {
            watchHoveredTrade(client, cfg, merchant);
        } else {
            BoxManager.clearContext(SourceType.TRADE);
        }

        // --- custom titles of chests, shops, menus…
        if (screen instanceof HandledScreen<?> titled && cfg.isSourceEnabled(SourceType.SCREEN_TITLE)) {
            watchScreenTitle(cfg, titled);
        } else {
            BoxManager.clearContext(SourceType.SCREEN_TITLE);
        }
    }

    /** The trade list buttons aren't slots, so the item hover capture never sees them. */
    private static void watchHoveredTrade(MinecraftClient client, TEConfig cfg, MerchantScreen screen) {
        double mouseX = client.mouse.getX() * client.getWindow().getScaledWidth() / (double) client.getWindow().getWidth();
        double mouseY = client.mouse.getY() * client.getWindow().getScaledHeight() / (double) client.getWindow().getHeight();
        int left = ((HandledScreenAccessor) (Object) screen).translateeverything$getX();
        int top = ((HandledScreenAccessor) (Object) screen).translateeverything$getY();
        int row = (int) ((mouseY - (top + 18)) / 20);
        TradeOfferList offers = screen.getScreenHandler().getRecipes();
        if (mouseX < left + 5 || mouseX >= left + 94 || row < 0 || row > 6) {
            BoxManager.clearContext(SourceType.TRADE);
            return;
        }
        int index = ((MerchantScreenAccessor) (Object) screen).translateeverything$getIndexStartOffset() + row;
        if (index < 0 || index >= offers.size()) {
            BoxManager.clearContext(SourceType.TRADE);
            return;
        }
        TradeOffer offer = offers.get(index);
        String text = itemText(offer.getSellItem(), cfg.itemsRequireCustomText);
        if (text.isBlank() || !worthTranslating(cfg, text)) {
            BoxManager.clearContext(SourceType.TRADE);
            return;
        }
        String key = "trade:" + index + ":" + text.hashCode();
        BoxManager.pushContext(SourceType.TRADE, "Trade · offer " + (index + 1), text, key, false);
    }

    private static void watchScreenTitle(TEConfig cfg, HandledScreen<?> screen) {
        Text title = screen.getTitle();
        // Vanilla titles ("Chest", "Furnace"…) are translatable keys, already localized.
        if (title == null || title.getContent() instanceof TranslatableTextContent) {
            BoxManager.clearContext(SourceType.SCREEN_TITLE);
            return;
        }
        String text = sanitize(title.getString());
        if (!worthTranslating(cfg, text)) {
            BoxManager.clearContext(SourceType.SCREEN_TITLE);
            return;
        }
        if (cfg.immersionMode) {
            BoxManager.clearContext(SourceType.SCREEN_TITLE);
            String ready = ImmersionService.ready(text);
            if (ready != null) {
                ((dev.adamfr06.translateeverything.mixin.ScreenAccessor) screen)
                        .translateeverything$setTitle(Text.literal(ready));
            }
            return;
        }
        String key = "menutitle:" + text.hashCode();
        BoxManager.pushContext(SourceType.SCREEN_TITLE, "Menu Title", text, key, false);
    }

    /** Sidebar scoreboard, debounced because plugins frequently animate it. */
    private static void watchScoreboard(MinecraftClient client, TEConfig cfg) {
        if (!cfg.isSourceEnabled(SourceType.SCOREBOARD)) {
            resetEdit(SourceType.SCOREBOARD);
            return;
        }
        Scoreboard scoreboard = client.world.getScoreboard();
        ScoreboardObjective objective = scoreboard.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
        if (objective == null) {
            resetEdit(SourceType.SCOREBOARD);
            return;
        }
        StringBuilder sb = new StringBuilder(sanitize(objective.getDisplayName().getString()));
        List<ScoreboardEntry> entries = new ArrayList<>(scoreboard.getScoreboardEntries(objective));
        entries.removeIf(ScoreboardEntry::hidden);
        entries.sort(Comparator.comparingInt(ScoreboardEntry::value).reversed());
        int lines = 0;
        for (ScoreboardEntry entry : entries) {
            if (lines++ >= 15) {
                break;
            }
            Team team = scoreboard.getScoreHolderTeam(entry.owner());
            String line = sanitize(Team.decorateName(team, entry.name()).getString());
            if (!line.isBlank()) {
                sb.append('\n').append(line);
            }
        }
        String text = sb.toString().trim();
        if (text.isBlank()) {
            resetEdit(SourceType.SCOREBOARD);
            return;
        }
        // Reuse the typing debounce: only translate once the sidebar holds still.
        debounceEdit(cfg, SourceType.SCOREBOARD, "Scoreboard", text, "scoreboard");
    }

    /** Called from the advancement toast + advancements screen mixins. */
    public static void onAdvancement(Text title, Text description) {
        TEConfig cfg = TEConfig.get();
        if (!cfg.enabled || !cfg.isSourceEnabled(SourceType.ADVANCEMENT)) {
            return;
        }
        // Vanilla advancements are translatable keys, already in the client language.
        boolean titleCustom = !(title.getContent() instanceof TranslatableTextContent);
        boolean descCustom = description != null && !(description.getContent() instanceof TranslatableTextContent);
        StringBuilder sb = new StringBuilder();
        if (titleCustom) {
            sb.append(sanitize(title.getString()));
        }
        if (descCustom) {
            String desc = sanitize(description.getString());
            if (!desc.isBlank()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(desc);
            }
        }
        String text = sb.toString().trim();
        if (worthTranslating(cfg, text)) {
            BoxManager.pushEvent(SourceType.ADVANCEMENT, "Advancement", text);
        }
    }

    private static void watchHoveredItem(TEConfig cfg, HandledScreen<?> screen) {
        Slot slot = ((HandledScreenAccessor) (Object) screen).translateeverything$getFocusedSlot();
        if (slot == null || !slot.hasStack()) {
            BoxManager.clearContext(SourceType.ITEM);
            return;
        }
        String text = itemText(slot.getStack(), cfg.itemsRequireCustomText);
        if (text.isBlank() || !worthTranslating(cfg, text)) {
            BoxManager.clearContext(SourceType.ITEM);
            return;
        }
        String key = "item:" + text.hashCode();
        BoxManager.pushContext(SourceType.ITEM, "Item", text, key, false);
    }

    /** Name + book title + lore of a stack; "" when requireCustomText filters it out. */
    private static String itemText(ItemStack stack, boolean requireCustomText) {
        if (stack.isEmpty()) {
            return "";
        }
        Text customName = stack.get(DataComponentTypes.CUSTOM_NAME);
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        WrittenBookContentComponent book = stack.get(DataComponentTypes.WRITTEN_BOOK_CONTENT);
        boolean hasCustomText = customName != null
                || (lore != null && !lore.lines().isEmpty()) || book != null;
        if (requireCustomText && !hasCustomText) {
            return "";
        }
        StringBuilder sb = new StringBuilder(sanitize(stack.getName().getString()));
        if (book != null) {
            String bookTitle = sanitize(book.title().raw());
            if (!bookTitle.isBlank() && !sb.toString().equals(bookTitle)) {
                sb.append('\n').append(bookTitle);
            }
        }
        if (lore != null) {
            for (Text line : lore.lines()) {
                String s = sanitize(line.getString());
                if (!s.isBlank()) {
                    sb.append('\n').append(s);
                }
            }
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------ typing debounce

    private static void debounceEdit(TEConfig cfg, SourceType type, String title, String text, String keyBase) {
        PendingEdit pending = pendingEdits.computeIfAbsent(type, t -> new PendingEdit());
        long now = System.currentTimeMillis();
        String key = keyBase + ":" + text.hashCode();
        if (!key.equals(pending.key)) {
            pending.key = key;
            pending.title = title;
            pending.text = text;
            pending.stableSince = now;
            pending.pushed = false;
            // While the text is changing, let the previous box linger away.
            BoxManager.clearContext(type);
            return;
        }
        if (!pending.pushed && now - pending.stableSince >= cfg.editDebounceMs) {
            pending.pushed = true;
            if (worthTranslating(cfg, pending.text)) {
                BoxManager.pushContext(type, pending.title, pending.text, pending.key, false);
            }
        } else if (pending.pushed && worthTranslating(cfg, pending.text)) {
            // Keep the context alive while the screen stays open and unchanged.
            BoxManager.pushContext(type, pending.title, pending.text, pending.key, false);
        }
    }

    private static void resetEdit(SourceType type) {
        PendingEdit pending = pendingEdits.get(type);
        if (pending != null) {
            pending.key = "";
            pending.pushed = false;
        }
        BoxManager.clearContext(type);
    }

    // ------------------------------------------------------------------ manual translate key

    /** Force-translates whatever the player is pointing at: sign, then named entity, then held item. */
    public static void manualTranslate(MinecraftClient client) {
        manualTranslate(client, null);
    }

    /** {@code engineOverride} non-null forces that engine (retry-with-AI). */
    public static void manualTranslate(MinecraftClient client, TEConfig.Engine engineOverride) {
        if (client.player == null || client.world == null) {
            return;
        }
        // Reading a book: retry the current page.
        if (client.currentScreen instanceof BookScreen book) {
            BookScreenAccessor acc = (BookScreenAccessor) (Object) book;
            BookScreen.Contents contents = acc.translateeverything$getContents();
            int page = acc.translateeverything$getPageIndex();
            if (contents != null && page >= 0 && page < contents.getPageCount()) {
                String bookText = sanitize(contents.getPage(page).getString());
                if (!bookText.isBlank()) {
                    String bookKey = "book:" + page + ":" + bookText.hashCode();
                    BoxManager.forget(bookKey);
                    BoxManager.pushContext(SourceType.BOOK, "Book \u00b7 Page " + (page + 1),
                            bookText, bookKey, true, engineOverride);
                    return;
                }
            }
        }
        if (client.player == null || client.world == null) {
            return;
        }
        TEConfig cfg = TEConfig.get();

        SignBlockEntity sign = targetedSign(client, Math.max(cfg.scanRange, 16));
        if (sign != null) {
            boolean front = sign.isPlayerFacingFront(client.player);
            String text = signText(sign, front);
            if (!text.isBlank()) {
                BlockPos pos = sign.getPos();
                String key = "sign:" + pos.asLong() + ":" + (front ? "F" : "B") + ":" + text.hashCode();
                BoxManager.forget(key);
                BoxManager.pushContext(SourceType.SIGN,
                        "Sign · " + pos.getX() + " " + pos.getY() + " " + pos.getZ(), text, key, true, engineOverride);
                return;
            }
        }

        Entity entity = findLookedAtNamedEntity(client, Math.max(cfg.scanRange, 16));
        if (entity == null) {
            entity = client.targetedEntity;
        }
        if (entity != null) {
            String text;
            String title;
            if (entity instanceof DisplayEntity.TextDisplayEntity) {
                text = collectHologramText(client, entity);
                title = "Hologram";
            } else {
                Text name = entity.getCustomName() != null ? entity.getCustomName() : entity.getDisplayName();
                text = name == null ? "" : sanitize(name.getString());
                title = "Entity · " + entity.getType().getName().getString();
            }
            if (!text.isBlank()) {
                String key = "entity:" + entity.getId() + ":" + text.hashCode();
                BoxManager.forget(key);
                BoxManager.pushContext(SourceType.ENTITY, title, text, key, true, engineOverride);
                return;
            }
        }

        ItemStack held = client.player.getMainHandStack();
        String heldText = itemText(held, false);
        if (!heldText.isBlank()) {
            String key = "item:" + heldText.hashCode();
            BoxManager.forget(key);
            BoxManager.pushContext(SourceType.ITEM, "Item (held)", heldText, key, true, engineOverride);
            return;
        }

        feedback(client, "Nothing to translate here");
    }

    /** Shows a short action-bar note without it being captured for translation. */
    public static void feedback(MinecraftClient client, String message) {
        suppressNextOverlay();
        client.inGameHud.setOverlayMessage(Text.literal(message), false);
    }

    // ------------------------------------------------------------------ helpers

    private static SignBlockEntity targetedSign(MinecraftClient client, double range) {
        HitResult hit = client.player.raycast(range, 1.0f, false);
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            BlockEntity be = client.world.getBlockEntity(blockHit.getBlockPos());
            if (be instanceof SignBlockEntity sign) {
                return sign;
            }
        }
        return null;
    }

    private static String signText(SignBlockEntity sign, boolean front) {
        SignText signText = sign.getText(front);
        StringBuilder sb = new StringBuilder();
        for (Text message : signText.getMessages(false)) {
            String line = sanitize(message.getString());
            if (!line.isBlank()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            }
        }
        return sb.toString();
    }

    /** Strips legacy §-format codes and normalizes whitespace (newlines survive). */
    public static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replaceAll("§.", "");
        s = s.replace('\u00A0', ' '); // non-breaking space
        s = s.replaceAll("[ \\t]+", " ");
        s = s.replaceAll(" ?\\n ?", "\n");
        return s.strip();
    }

    /** Immersive item tooltips: replaces foreign lines in place (fabric ItemTooltipCallback). */
    public static void onItemTooltip(ItemStack stack, java.util.List<Text> lines) {
        TEConfig cfg = TEConfig.get();
        if (!ImmersionService.active(SourceType.ITEM)) {
            return;
        }
        if (cfg.itemsRequireCustomText && !hasCustomText(stack)) {
            return;
        }
        for (int i = 0; i < lines.size(); i++) {
            Text line = lines.get(i);
            String text = sanitize(line.getString());
            if (!worthTranslating(cfg, text)) {
                continue;
            }
            String ready = ImmersionService.ready(text);
            if (ready != null && !ready.equals(text)) {
                lines.set(i, Text.literal(ready).setStyle(line.getStyle()));
            }
        }
    }

    private static boolean hasCustomText(ItemStack stack) {
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        return stack.get(DataComponentTypes.CUSTOM_NAME) != null
                || (lore != null && !lore.lines().isEmpty())
                || stack.get(DataComponentTypes.WRITTEN_BOOK_CONTENT) != null;
    }

    public static boolean worthTranslating(TEConfig cfg, String text) {
        if (text.isBlank() || text.length() < cfg.minTextLength) {
            return false;
        }
        if (LanguageUtil.hasNoLetters(text)) {
            return false;
        }
        if (cfg.onlyTranslateNonAscii && LanguageUtil.isAsciiOnly(text)) {
            return false;
        }
        for (String pattern : cfg.ignorePatterns) {
            try {
                if (Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(text).find()) {
                    return false;
                }
            } catch (PatternSyntaxException ignored) {
            }
        }
        return true;
    }
}
