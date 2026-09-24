package dev.adamfr06.translateeverything.translate;

import dev.adamfr06.translateeverything.config.TEConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A running chat thread with the local AI, used by the follow-up box (ask about a message) and the
 * confirmation box (verify/refine an outgoing translation).
 */
public final class AiConversation {
    public enum Mode { CONFIRM, FOLLOWUP }

    /** One visible turn (role is "user" or "assistant"). */
    public record Turn(String role, String text) {
    }

    private final List<String[]> wire = new ArrayList<>();   // full [role,content] incl. system
    public final List<Turn> transcript = new java.util.concurrent.CopyOnWriteArrayList<>();  // display only (no system)
    public final Mode mode;
    /** The proposed translation this box is about (target language). */
    public volatile String currentTranslation;
    /** The original message (source language). */
    public volatile String currentOriginal = "";
    public volatile boolean busy = false;
    public String commandPrefix = "";
    public String targetCode = "";
    public boolean useContext = true;
    /** Languages of this conversation, restated on every turn. */
    public volatile String converseLang = LanguageUtil.name(TranslationService.homeLanguage());
    public volatile String tgtName = "";

    private static final Pattern LABELED = Pattern.compile("(?im)^[\\s*_>#-]*TRANSLATION[:：*_\\s]+(.+?)\\s*$");

    private AiConversation(Mode mode, String system, String currentTranslation) {
        this.mode = mode;
        this.currentTranslation = currentTranslation;
        wire.add(new String[]{"system", system});
    }

    private List<String> confirmContext = List.of();
    private String confirmGlossary = "";
    private String rivalEngine = "";
    private String rivalText = "";
    private String rivalBack = "";
    private String chosenEngine = "";
    private String chosenBack = "";

    /** Tells the coach about both candidates the player is looking at. */
    public void setCandidates(String chosenEngine, String chosenBack,
                              String rivalEngine, String rivalText, String rivalBack) {
        this.chosenEngine = nz(chosenEngine);
        this.chosenBack = nz(chosenBack);
        this.rivalEngine = nz(rivalEngine);
        this.rivalText = nz(rivalText);
        this.rivalBack = nz(rivalBack);
        refreshSystem();
    }

    private static String nz(String s) {
        return s == null ? "" : s.strip();
    }

    private void refreshSystem() {
        if (!wire.isEmpty() && "system".equals(wire.get(0)[0])) {
            wire.set(0, new String[]{"system", buildConfirmSystem(currentOriginal, currentTranslation,
                    converseLang, tgtName, confirmContext, confirmGlossary, this)});
        }
    }

    /** Builds a review conversation for an outgoing message. */
    public static AiConversation confirm(String original, String proposed, String srcName, String tgtName,
                                         String converseLang, List<String> context, String glossary) {
        AiConversation c = new AiConversation(Mode.CONFIRM, "", proposed);
        c.currentOriginal = original;
        c.converseLang = converseLang;
        c.tgtName = tgtName;
        c.confirmContext = context == null ? List.of() : context;
        c.confirmGlossary = glossary == null ? "" : glossary;
        c.refreshSystem();
        return c;
    }

    /**
     * Rebuild the confirm system prompt after the real translation is fetched (box opens instantly)
     * or the user edits.
     */
    public void reseedConfirm(String newOriginal, String newProposed) {
        this.currentOriginal = newOriginal;
        this.currentTranslation = newProposed;
        refreshSystem();
    }

    /** Update only the original (refreshing the system prompt) while keeping the existing thread intact. */
    public void reseedOriginalOnly(String newOriginal) {
        this.currentOriginal = newOriginal;
        refreshSystem();
    }

    /** Opening of the review-coach prompt. */
    private static final String CONFIRM_INTRO_ZH =
            "你是一名为 Minecraft 玩家服务的双语翻译顾问。玩家用{converseLang}写了 MESSAGE，"
            + "它被翻译成了{tgtName}。玩家会询问这段{tgtName} TRANSLATION 的语气、礼貌程度、自然度，"
            + "或者某个词、某种细微含义是否被保留。\n";

    /** "How to answer" rules of the review-coach prompt. */
    private static final String CONFIRM_RULES_ZH =
            "回答方式：\n"
            + "- 用{converseLang}直接回答玩家关于 TRANSLATION 的问题，1 到 2 句话。绝对不要把{tgtName}译文"
            + "再翻译回{converseLang}作为回答——原文是玩家自己写的，这样做毫无用处。\n"
            + "- 语气与礼貌：指出{tgtName}译文的语体（随意 / 中性 / 礼貌 / 正式），并说明它是否与 MESSAGE 相符。"
            + "如果 MESSAGE 里有俚语或称呼（比如 'my g'、'bro'）而{tgtName}译文把它删掉或弱化了，要如实说明。\n"
            + "- 如果某个词或细微含义并不真正存在于{tgtName}译文中，绝不能声称它存在。先核对。回译中看起来不对的词，"
            + "可能恰恰是正确的 Minecraft 术语——这类词要保留。\n"
            + "- 如果玩家要求修改、改写或调整译文（更随意、更正式、更短、增加或保留某些内容），你必须在最后单独输出一行 "
            + "'TRANSLATION: <新的{tgtName}译文>' 来体现修改。否则完全不要输出 TRANSLATION 行。\n"
            + "- 你的回答必须使用{converseLang}（即使这些说明是用中文写的）。";

    /** Opening of the follow-up ("ask about this message") prompt. */
    private static final String FOLLOWUP_INTRO_ZH =
            "你是 Minecraft 聊天模组中的翻译助手。你给用户的每一条回复都必须使用{converseLang}"
            + "（即使这些说明是用中文写的）。除非用户要求，否则不要用{srcName}回复。\n"
            + "用户刚刚读到了下面这条消息，以及为他们提供的译文：\n";

    /** Rules of the follow-up prompt. */
    private static final String FOLLOWUP_RULES_ZH =
            "用户发给你的每一条消息，都是关于这段译文的问题或评论——即使措辞随意、有错别字，或者看起来像闲聊。"
            + "绝不要把它当作闲聊，不要提供一般的 Minecraft 帮助，也不要说你是来提供帮助的——只回答关于译文的问题。\n"
            + "如果用户指着某个词或短语问它为什么出现，就把它与原文（Original）对照，直接说明：原文是否真的有这个意思，"
            + "它是否是为了让{converseLang}读起来自然而添加的，还是属于误译。译者增删了内容时要如实说明。\n"
            + "回复控制在 1 到 2 个简短的句子，使用{converseLang}；不要加音译或注音。如果你给出更好的译文，"
            + "必须在最后单独一行，严格按照 'TRANSLATION: <译文>' 的格式输出。";

    private static String buildConfirmSystem(String original, String proposed, String converseLang, String tgtName,
                                             List<String> context, String glossary, AiConversation c) {
        String intro = chinese()
                ? CONFIRM_INTRO_ZH.replace("{converseLang}", converseLang).replace("{tgtName}", tgtName)
                : "You are a bilingual translation coach for a Minecraft player. They wrote MESSAGE in " + converseLang
                + " and it was translated into " + tgtName + ". They will ask about the " + tgtName + " TRANSLATION - its "
                + "tone, politeness, naturalness, or whether some word or nuance was kept.\n";
        String rules = chinese()
                ? CONFIRM_RULES_ZH.replace("{converseLang}", converseLang).replace("{tgtName}", tgtName)
                : "HOW TO ANSWER:\n"
                + "- Directly answer their question about the TRANSLATION, in " + converseLang + ", in 1-2 sentences. NEVER "
                + "reply by translating the " + tgtName + " back into " + converseLang + " - they wrote it, that is useless.\n"
                + "- Tone/politeness: name the register of the " + tgtName + " (casual / neutral / polite / formal) and say "
                + "whether it matches the MESSAGE. If the MESSAGE has slang or an address (like 'my g', 'bro') that the "
                + tgtName + " dropped or flattened, say so honestly.\n"
                + "- Never claim a word or nuance is present if it is not actually in the " + tgtName + ". Check first. A "
                + "word that looks wrong in a back-translation may still be the correct Minecraft term - keep those.\n"
                + "- If they ask you to change, rewrite, or adjust it (more casual, more formal, shorter, add or keep "
                + "something), you MUST end with one line 'TRANSLATION: <new " + tgtName + " text>' reflecting the change. "
                + "Otherwise do NOT output a TRANSLATION line at all.";
        return intro
                + "MESSAGE (" + converseLang + "): \"" + original + "\"\n"
                + "TRANSLATION (" + (c == null || c.chosenEngine.isEmpty() ? "chosen" : c.chosenEngine)
                + ", " + tgtName + "): \"" + proposed + "\"\n"
                + (c == null || c.chosenBack.isEmpty() ? ""
                        : "That TRANSLATION reads back as: \"" + c.chosenBack + "\"\n")
                + (c == null || c.rivalText.isEmpty() ? ""
                        : "The player is comparing it against a second candidate, which is ALSO on their screen:\n"
                          + "OTHER CANDIDATE (" + c.rivalEngine + ", " + tgtName + "): \"" + c.rivalText + "\"\n"
                          + (c.rivalBack.isEmpty() ? ""
                                : "which reads back as: \"" + c.rivalBack + "\"\n")
                          + "You CAN see both. If they ask which is better, or why one is longer, more formal or more "
                          + "literal, compare them directly and pick one.\n")
                + (glossary == null || glossary.isBlank() ? ""
                        : "Official Minecraft " + tgtName + " terms here: " + glossary + "\n")
                + contextBlock(context)
                + rules;
    }

    /** Builds a follow-up conversation about a received message. */
    public static AiConversation followup(String original, String translation, String srcName, String tgtName,
                                          String converseLang, List<String> context) {
        String intro = chinese()
                ? FOLLOWUP_INTRO_ZH.replace("{converseLang}", converseLang).replace("{srcName}", srcName)
                : "You are a translation assistant inside a Minecraft chat mod. WRITE EVERY REPLY TO THE USER IN "
                + converseLang + ". Do not reply in " + srcName + " unless asked.\n"
                + "The user just read this message, translated for them:\n";
        String rules = chinese()
                ? FOLLOWUP_RULES_ZH.replace("{converseLang}", converseLang)
                : "EVERY message the user sends you is a question or comment ABOUT THIS TRANSLATION, even when it is "
                + "phrased loosely, has typos, or reads like small talk. Never treat it as chit-chat, never offer "
                + "general Minecraft help, and never say you are here to assist - just answer about the translation.\n"
                + "If they point at a word or phrase and ask why it is there, compare it against the ORIGINAL and say "
                + "plainly whether the original really says it, whether it was added to sound natural in " + converseLang
                + ", or whether it is a mistranslation. Be honest when the translator added or dropped something.\n"
                + "Keep replies to 1-2 short sentences in " + converseLang + "; no transliteration or pronunciation guides. "
                + "If you offer a better translation, END with a final line EXACTLY as 'TRANSLATION: <text>'.";
        String sys = intro
                + "Original (" + srcName + "): \"" + original + "\"\n"
                + "Translation shown to them (" + tgtName + "): \"" + translation + "\"\n"
                + contextBlock(context)
                + rules;
        AiConversation c = new AiConversation(Mode.FOLLOWUP, sys, translation);
        c.currentOriginal = original;
        c.converseLang = converseLang;
        c.tgtName = tgtName;
        return c;
    }

    /** Transcript-friendly text: drop the machine 'TRANSLATION:' line and markdown so the chat reads cleanly. */
    public static String displayText(String s) {
        if (s == null) {
            return "";
        }
        s = s.replaceAll("(?im)^[\\s*_>#-]*TRANSLATION[:：].*$", "").trim();
        s = s.replaceAll("[*_`]+", "").trim();
        s = s.replaceAll("\\n{3,}", "\n\n");
        return s;
    }

    /** Pops the last user↔AI exchange and returns the user's text so it can be edited and re-asked. */
    public String editLast() {
        String userText = "";
        for (int i = transcript.size() - 1; i >= 0; i--) {
            if (transcript.get(i).role().equals("user")) {
                userText = transcript.get(i).text();
                break;
            }
        }
        while (!transcript.isEmpty() && transcript.get(transcript.size() - 1).role().equals("assistant")) {
            transcript.remove(transcript.size() - 1);
        }
        if (!transcript.isEmpty() && transcript.get(transcript.size() - 1).role().equals("user")) {
            transcript.remove(transcript.size() - 1);
        }
        while (wire.size() > 1 && wire.get(wire.size() - 1)[0].equals("assistant")) {
            wire.remove(wire.size() - 1);
        }
        if (wire.size() > 1 && wire.get(wire.size() - 1)[0].equals("user")) {
            wire.remove(wire.size() - 1);
        }
        return userText;
    }

    private static boolean chinese() {
        return TEConfig.get().aiPromptChinese;
    }

    private static String contextBlock(List<String> context) {
        if (context == null || context.isEmpty()) {
            return "";
        }
        return "Recent conversation for context:\n" + String.join("\n", context) + "\n";
    }

    /** Sends a user turn; the future resolves with the assistant's reply (already appended to the transcript). */
    public CompletableFuture<String> ask(String userText) {
        return ask(userText, true);
    }

    /** Ask without showing the user's turn in the transcript (used for the opening auto-question). */
    public CompletableFuture<String> askHidden(String userText) {
        return ask(userText, false);
    }

    private CompletableFuture<String> ask(String userText, boolean show) {
        if (busy) return CompletableFuture.completedFuture("");
        busy = true;
        String wireContent = userText;
        if (mode == Mode.CONFIRM) {
            wireContent = userText + "\n\n(Answer me in " + converseLang + ". Do NOT re-translate the " + tgtName
                    + " back to " + converseLang + ". Add a final 'TRANSLATION: <text>' line, in " + tgtName
                    + ", ONLY if you are changing the translation.)";
        } else {
            wireContent = userText + "\n\n(This is about the translation above. Answer it directly in "
                    + converseLang + " in 1-2 sentences. Do not offer general help.)";
        }
        wire.add(new String[]{"user", wireContent});
        if (show) {
            transcript.add(new Turn("user", userText));
        }
        return TranslationService.chatRaw(new ArrayList<>(wire)).exceptionally(error -> TranslationService.Result.fail("Request failed")).thenApply(r -> {
            String reply = r.error() ? "(AI error: " + r.errorMessage() + ")" : r.translatedText();
            wire.add(new String[]{"assistant", reply});
            transcript.add(new Turn("assistant", reply));
            busy = false;
            return reply;
        });
    }

    /** Apply the AI's latest suggested translation as the active "will send" text (user-initiated only). */
    public boolean applySuggestion() {
        String s = latestSuggestion();
        if (s != null && !s.isBlank()) {
            currentTranslation = s;
            return true;
        }
        return false;
    }

    /** True when the AI has proposed a translation different from the current "will send". */
    public boolean hasNewSuggestion() {
        String s = latestSuggestion();
        return s != null && !s.isBlank() && !s.equals(currentTranslation);
    }

    /** The most recent AI-proposed translation (from a TRANSLATION: line), or null. */
    public String latestSuggestion() {
        for (int i = transcript.size() - 1; i >= 0; i--) {
            Turn t = transcript.get(i);
            if (t.role().equals("assistant")) {
                String s = extractSuggestion(t.text());
                return s == null || s.isBlank() ? null : s;
            }
        }
        return null;
    }

    /** Public parse of one reply's suggested translation, used by the input translator's sticky path. */
    public static String suggestionIn(String reply) {
        return extractSuggestion(reply);
    }

    private static String extractSuggestion(String reply) {
        if (reply == null) {
            return null;
        }
        Matcher m = LABELED.matcher(reply);
        String last = null;
        while (m.find()) {
            last = m.group(1).trim();
        }
        if (last != null && !last.isBlank()) {
            return clean(last);
        }
        return null;
    }

    /** The text of the last {@code TRANSLATION:} line in a reply, or null. */
    private static String clean(String s) {
        s = s.replaceAll("[*_`]+", "").trim();
        if (s.length() >= 2 && (s.charAt(0) == '"' || s.charAt(0) == '“') && (s.endsWith("\"") || s.endsWith("”"))) {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    /** Reset the baseline after the user edits the original and re-translates, keeping the AI in the loop. */
    public void updateProposed(String newOriginal, String newTranslation) {
        currentOriginal = newOriginal;
        currentTranslation = newTranslation;
        wire.add(new String[]{"user", "I changed my message to: \"" + newOriginal
                + "\". Its translation is now: " + newTranslation + ". Keep this as the new baseline."});
        wire.add(new String[]{"assistant", "Understood. TRANSLATION: " + newTranslation});
        transcript.add(new Turn("assistant", "Updated: " + newTranslation));
    }

    public static String langName(String code) {
        if (code == null || code.isBlank() || code.equalsIgnoreCase("auto")) {
            return "the detected language";
        }
        return LanguageUtil.name(code.toLowerCase(Locale.ROOT));
    }
}
