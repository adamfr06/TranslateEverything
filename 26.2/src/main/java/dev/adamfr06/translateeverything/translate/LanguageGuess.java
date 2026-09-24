package dev.adamfr06.translateeverything.translate;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Offline "what language is this, roughly" guess. */
public final class LanguageGuess {
    /** {@code code} is an ISO 639-1 code, or "" when nothing is recognisable. */
    public record Guess(String code, double confidence) {
        public boolean confident() {
            return !code.isEmpty() && confidence >= 0.60;
        }
    }

    public static final Guess UNKNOWN = new Guess("", 0.0);

    private LanguageGuess() {
    }

    // ------------------------------------------------------------------ function words

    /** The words that carry no meaning but appear in nearly every sentence. */
    private static final Map<String, String> WORDS = new HashMap<>();

    static {
        WORDS.put("en", "the and or but if of in on at for with from by to is are was were be you your my we our they "
                + "this that not what when where who how all just very really still again too so out about because have has "
                + "press start stop click here there now then does did can will would should get got make take want need "
                + "know think see look give thanks please sorry hello yes okay session server world player message");
        WORDS.put("es", "el la los las un una de del que y o pero si en con por para es son era ser estar tengo tiene "
                + "muy mas como cuando donde quien todo nada bien hola gracias porque yo tu");
        WORDS.put("pt", "o a os as um uma de do da que e ou mas se em com por para eh sao era ser estar tenho tem "
                + "muito mais como quando onde quem tudo nada bem ola obrigado porque voce nao eu "
                + "fica sua meu minha aqui agora quero vamos entao ainda ja nunca sempre fazer feito vou vai");
        WORDS.put("fr", "le la les un une de des du que et ou mais si en avec pour par est sont etait etre avoir je "
                + "tu il elle nous vous ils tres plus comme quand qui tout rien bien bonjour merci pas ce cette "
                + "comment pourquoi ou oui non salut alors donc encore deja jamais toujours faire fait vais vas");
        WORDS.put("de", "der die das den dem des ein eine und oder aber wenn in mit fur von ist sind war sein haben "
                + "ich du er sie wir ihr nicht sehr mehr wie wann wo wer alles nichts gut hallo danke auch noch "
                + "geht dir mir mich dich hier jetzt mal schon kann muss will soll doch nur bei aber");
        WORDS.put("it", "il lo la i gli le un una di del che e ma se in con per da sono era essere avere io tu lui "
                + "lei noi voi molto piu quando dove chi tutto niente bene ciao grazie non anche "
                + "so sia casa questo quella perche quindi allora ancora gia mai sempre fare fatto vado vai");
        WORDS.put("nl", "de het een van dat die en of maar als in met voor door is zijn was worden ik jij hij zij "
                + "wij niet heel meer hoe wanneer waar wie alles niets goed hallo dank ook nog");
        WORDS.put("pl", "i w na z do nie to jest sa byl byc mam ma masz bardzo jak kiedy gdzie kto wszystko nic "
                + "dobrze czesc dziekuje ale ze sie juz tylko czy jestem tak");
        WORDS.put("cs", "a v na s do ne to je jsou byl byt mam ma mas velmi jak kdy kde kdo vsechno nic dobre ahoj "
                + "dekuji ale ze se uz jen jsem ten tady jak");
        WORDS.put("sv", "och i pa med av att det den ar var vara har jag du han hon vi de inte mycket mer hur nar "
                + "vem allt inget bra hej tack men som ska");
        WORDS.put("da", "og i pa med af at det den er var vaere har jeg du han hun vi de ikke meget mere hvordan "
                + "hvornar hvor hvem alt intet godt hej tak men som skal");
        WORDS.put("no", "og i pa med av at det den er var vaere har jeg du han hun vi de ikke mye mer hvordan nar "
                + "hvor hvem alt ingenting bra hei takk men som skal jeg");
        WORDS.put("fi", "ja on ei se tama etta kun mutta jos mina sina han me te ne hyva kiitos moi terve paljon "
                + "mita missa kuka kaikki nyt vain myos");
        WORDS.put("tr", "ve bir bu su o degil var yok icin ile ben sen biz siz cok daha nasil ne zaman nerede kim "
                + "her sey iyi merhaba tesekkur ama ki de mi");
        WORDS.put("id", "dan di ke dari yang untuk dengan ini itu tidak ada saya kamu kita mereka sangat lebih "
                + "bagaimana kapan dimana siapa semua baik halo terima kasih tapi sudah bisa");
        WORDS.put("vi", "va cua la khong co cho voi nay do toi ban chung ta ho rat hon the nao khi nao o dau ai "
                + "tat ca tot xin chao cam on nhung duoc minh");
        WORDS.put("ro", "si in la de pe cu ca nu este sunt era fi am are ai foarte mai cum cand unde cine tot "
                + "nimic bine salut multumesc dar se eu tu ce faci vrei poti acum doar chiar aici");
        WORDS.put("hu", "es a az egy nem van vannak volt lenni nekem neked nagyon tobb hogyan mikor hol ki minden "
                + "semmi jo szia koszonom de hogy most csak");
    }

    /** word -> how many languages use it (rarity weighting) and which. */
    private static final Map<String, java.util.List<String>> INDEX = new HashMap<>();

    static {
        for (Map.Entry<String, String> e : WORDS.entrySet()) {
            for (String w : e.getValue().split(" ")) {
                INDEX.computeIfAbsent(w, k -> new java.util.ArrayList<>(2)).add(e.getKey());
            }
        }
    }

    /** Characters that only a few languages spell with. */
    private static final Map<Character, String> MARKS = new HashMap<>();

    static {
        put("ñ¿¡", "es");
        put("ãõ", "pt");
        put("œùû", "fr");
        put("ß", "de");
        put("ąćęłńśźż", "pl");
        put("ěščřžůťďň", "cs");
        put("å", "sv");
        put("æø", "da");
        put("ğış", "tr");
        put("ășț", "ro");
        put("őű", "hu");
        put("äö", "fi");
    }

    /** Vietnamese tone and vowel marks; none of the other languages here use these. */
    private static final java.util.regex.Pattern VIET = java.util.regex.Pattern.compile(
            "[\u0103\u01a1\u01b0\u0111\u1ea1-\u1ef9]");

    private static void put(String chars, String lang) {
        for (char c : chars.toCharArray()) {
            MARKS.put(c, lang);
        }
    }

    // ------------------------------------------------------------------ public API

    /** True when {@code text} is confidently already in {@code langCode}, so translating it would only reword it. */
    public static boolean isProbably(String text, String langCode) {
        if (langCode == null || langCode.isBlank()) {
            return false;
        }
        String want = base(langCode);
        Guess g = of(text);
        return g.confident() && sameEnough(g.code(), want);
    }

    /** Whether two guesses are close enough to count as "the player can read it". */
    private static boolean sameEnough(String a, String b) {
        return group(a).equals(group(b));
    }

    /** Canonical name for a set of languages this class does not try to tell apart. */
    private static String group(String code) {
        return code.equals("no") ? "da" : code;
    }

    /** Best guess at the language of {@code text}, with a 0..1 confidence. */
    public static Guess of(String text) {
        if (text == null) {
            return UNKNOWN;
        }
        String t = strip(text);
        if (t.isBlank()) {
            return UNKNOWN;
        }
        Guess byScript = fromScript(t);
        if (byScript != UNKNOWN) {
            return byScript;
        }
        return fromWords(t);
    }

    /**
     * Strips the parts of a chat line that say nothing about its language: URLs, player names in
     * brackets, coordinates, numbers and mod tags.
     */
    private static String strip(String text) {
        return text.replaceAll("https?://\\S+", " ")
                .replaceAll("[\\[<(][^\\]>)]{0,32}[\\]>)]", " ")
                .replaceAll("[0-9]+", " ")
                .trim();
    }

    private static String base(String code) {
        String c = code.toLowerCase(Locale.ROOT);
        int dash = c.indexOf('-');
        return dash > 0 ? c.substring(0, dash) : c;
    }

    // ------------------------------------------------------------------ script

    /** Identifies non-Latin writing systems. */
    private static Guess fromScript(String t) {
        int kana = 0, han = 0, hangul = 0, cyr = 0, greek = 0, arab = 0, hebrew = 0;
        int thai = 0, deva = 0, latin = 0, ukrainian = 0, letters = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (!Character.isLetter(c)) {
                continue;
            }
            letters++;
            if (c >= 0x3040 && c <= 0x30FF) kana++;
            else if (c >= 0x4E00 && c <= 0x9FFF) han++;
            else if (c >= 0xAC00 && c <= 0xD7AF || c >= 0x1100 && c <= 0x11FF) hangul++;
            else if (c >= 0x0400 && c <= 0x04FF) {
                cyr++;
                if (c == 0x0456 || c == 0x0457 || c == 0x0454 || c == 0x0491) ukrainian++;
            } else if (c >= 0x0370 && c <= 0x03FF) greek++;
            else if (c >= 0x0600 && c <= 0x06FF) arab++;
            else if (c >= 0x0590 && c <= 0x05FF) hebrew++;
            else if (c >= 0x0E00 && c <= 0x0E7F) thai++;
            else if (c >= 0x0900 && c <= 0x097F) deva++;
            else if (c < 0x0250) latin++;
        }
        if (letters == 0) {
            return UNKNOWN;
        }
        // A single kana character is proof of Japanese; Han alone is Chinese.
        if (kana > 0) return new Guess("ja", 0.98);
        if (hangul > 0) return new Guess("ko", 0.98);
        if (han > 0 && han * 2 >= letters) return new Guess("zh", 0.92);
        if (cyr * 2 >= letters) return new Guess(ukrainian > 0 ? "uk" : "ru", ukrainian > 0 ? 0.90 : 0.88);
        if (greek * 2 >= letters) return new Guess("el", 0.95);
        if (arab * 2 >= letters) return new Guess("ar", 0.95);
        if (hebrew * 2 >= letters) return new Guess("he", 0.95);
        if (thai * 2 >= letters) return new Guess("th", 0.95);
        if (deva * 2 >= letters) return new Guess("hi", 0.90);
        return UNKNOWN; // Latin script: the function words decide
    }

    // ------------------------------------------------------------------ words

    /**
     * Scores every language by the function words and distinctive letters present, then reports the
     * winner and how far clear of the runner-up it is.
     */
    private static Guess fromWords(String t) {
        String lower = t.toLowerCase(Locale.ROOT);
        Map<String, Double> score = new HashMap<>();

        for (int i = 0; i < lower.length(); i++) {
            String lang = MARKS.get(lower.charAt(i));
            if (lang != null) {
                score.merge(lang, 1.5, Double::sum);
            }
        }

        boolean viMarks = VIET.matcher(lower).find();

        String folded = fold(lower);
        String[] words = folded.split("[^a-z']+");
        int real = 0;
        for (String w : words) {
            if (w.isEmpty()) {
                continue;
            }
            real++;
            List<String> langs = INDEX.get(w);
            if (langs == null) {
                continue;
            }
            double weight = 1.0 / langs.size(); // a word shared by five languages proves little
            for (String lang : langs) {
                if ("vi".equals(lang) && !viMarks) {
                    continue;
                }
                score.merge(lang, weight, Double::sum);
            }
        }
        if (score.isEmpty() || real == 0) {
            return UNKNOWN;
        }

        Map<String, Double> pooled = new HashMap<>();
        for (Map.Entry<String, Double> e : score.entrySet()) {
            pooled.merge(group(e.getKey()), e.getValue(), Double::sum);
        }
        String bestGroup = "";
        double bestScore = 0, second = 0;
        for (Map.Entry<String, Double> e : pooled.entrySet()) {
            if (e.getValue() > bestScore) {
                second = bestScore;
                bestScore = e.getValue();
                bestGroup = e.getKey();
            } else if (e.getValue() > second) {
                second = e.getValue();
            }
        }
        // Name the group by its strongest member, so the answer is still a real code.
        String best = "";
        double bestMember = -1;
        for (Map.Entry<String, Double> e : score.entrySet()) {
            if (group(e.getKey()).equals(bestGroup) && e.getValue() > bestMember) {
                bestMember = e.getValue();
                best = e.getKey();
            }
        }

        double coverage = Math.min(1.0, bestScore / Math.max(1.0, real * 0.34));
        double margin = bestScore <= 0 ? 0 : (bestScore - second) / bestScore;
        double confidence = coverage * (0.45 + 0.55 * margin);
        return new Guess(best, Math.min(0.97, confidence));
    }

    /**
     * Folds accents to their base letters so the function-word table can stay plain ASCII; the
     * accents themselves were already counted as evidence above.
     */
    private static String fold(String s) {
        String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}+", "");
    }
}
