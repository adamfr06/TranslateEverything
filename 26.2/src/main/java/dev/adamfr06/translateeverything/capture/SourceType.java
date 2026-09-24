package dev.adamfr06.translateeverything.capture;

/** Everything the mod knows how to translate. */
public enum SourceType {
    SIGN("Sign", "Signs you look at (incl. hanging signs)", 0xFF55FF55, true),
    SIGN_EDIT("Sign Edit", "The sign you are currently editing", 0xFF00AA55, true),
    BOOK("Book", "Pages of books you are reading", 0xFFFFAA00, true),
    BOOK_EDIT("Book Edit", "The book page you are currently writing", 0xFFCC7722, true),
    TITLE("Title", "Big title pop-ups in the middle of the screen", 0xFFFF5555, false),
    SUBTITLE("Subtitle", "The smaller line under titles", 0xFFFF8888, false),
    ACTION_BAR("Action Bar", "Messages above the hotbar", 0xFF55FFFF, false),
    BOSS_BAR("Boss Bar", "Boss bar names at the top of the screen", 0xFFCC55CC, false),
    ENTITY("Entity", "Name tags & holograms (text displays) you look at", 0xFF7788FF, true),
    ITEM("Item", "Names & lore of items you hover in inventories", 0xFFFFFF55, true),
    TRADE("Trade", "Villager/wandering trader offers you hover", 0xFF44DD88, true),
    ADVANCEMENT("Advancement", "Advancement toasts and the advancements screen", 0xFFDDAA33, false),
    SCREEN_TITLE("Menu", "Custom titles of chests/menus you open", 0xFF44CCCC, true),
    SCOREBOARD("Scoreboard", "The sidebar scoreboard (title + lines)", 0xFFFFCC44, true),
    OCR("OCR Scan", "Beta: on-demand OCR scan of the whole screen", 0xFFFF77CC, false),
    CHAT("Chat", "Chat messages (mode & position configurable in Settings > Chat)", 0xFF66BBFF, false);

    public final String label;
    public final String description;
    public final int color;
    public final boolean contextual;

    SourceType(String label, String description, int color, boolean contextual) {
        this.label = label;
        this.description = description;
        this.color = color;
        this.contextual = contextual;
    }
}
