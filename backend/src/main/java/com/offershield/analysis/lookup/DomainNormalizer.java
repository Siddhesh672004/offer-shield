package com.offershield.analysis.lookup;

import java.net.IDN;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Look-alike handling for C4 (D-03; PRD F8 "IDN/punycode homoglyph handling"). {@link #skeleton} maps characters that
 * look alike to one letter, so a brand and its imitation get the same skeleton and plain string comparison finds it:
 * <ol>
 *   <li>punycode labels ({@code xn--…}) are decoded to their Unicode letters;</li>
 *   <li>accents and full-width forms are folded (NFKD, combining marks removed) and everything is lower-cased;</li>
 *   <li>Cyrillic and Greek look-alikes become the Latin letter they imitate (Cyrillic а е о р с у х і ѕ …);</li>
 *   <li>digits that pass for letters: 0→o, 1→l, 3→e, 4→a, 5→s, 7→t, 8→b; and i, l, 1 all become l;</li>
 *   <li>letter pairs: rn→m, vv→w.</li>
 * </ol>
 * Hyphens and dots are kept; other characters stay as they are (they cannot match a Latin brand). Pure functions only:
 * nothing here touches the network.
 */
public final class DomainNormalizer {

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Map<Character, Character> LOOK_ALIKES = Map.ofEntries(
            // digits and Latin letters that pass for another letter
            Map.entry('0', 'o'), Map.entry('1', 'l'), Map.entry('i', 'l'), Map.entry('3', 'e'), Map.entry('4', 'a'),
            Map.entry('5', 's'), Map.entry('7', 't'), Map.entry('8', 'b'),
            Map.entry('ı', 'l') /* ı */, Map.entry('ł', 'l') /* ł */, Map.entry('ø', 'o') /* ø */,
            Map.entry('đ', 'd') /* đ */, Map.entry('ɑ', 'a') /* ɑ */, Map.entry('ɡ', 'g') /* ɡ */,
            // Cyrillic
            Map.entry('а', 'a'), Map.entry('в', 'b'), Map.entry('е', 'e'), Map.entry('к', 'k'),
            Map.entry('м', 'm'), Map.entry('н', 'h'), Map.entry('о', 'o'), Map.entry('р', 'p'),
            Map.entry('с', 'c'), Map.entry('т', 't'), Map.entry('у', 'y'), Map.entry('х', 'x'),
            Map.entry('ь', 'b'), Map.entry('ѕ', 's'), Map.entry('і', 'l'), Map.entry('ј', 'j'),
            Map.entry('һ', 'h'), Map.entry('ӏ', 'l'), Map.entry('ԁ', 'd'), Map.entry('ԛ', 'q'),
            Map.entry('ԝ', 'w'),
            // Greek
            Map.entry('α', 'a'), Map.entry('β', 'b'), Map.entry('γ', 'y'), Map.entry('ε', 'e'),
            Map.entry('η', 'n'), Map.entry('ι', 'l'), Map.entry('κ', 'k'), Map.entry('ν', 'v'),
            Map.entry('ο', 'o'), Map.entry('ρ', 'p'), Map.entry('τ', 't'), Map.entry('υ', 'u'),
            Map.entry('χ', 'x'), Map.entry('ω', 'w'));

    private DomainNormalizer() {
    }

    /** The look-alike skeleton of a domain, a label or a name; {@code ""} for {@code null}. */
    public static String skeleton(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String folded = MARKS.matcher(Normalizer.normalize(decodePunycode(text.toLowerCase(Locale.ROOT)),
                Normalizer.Form.NFKD)).replaceAll("").toLowerCase(Locale.ROOT);
        StringBuilder mapped = new StringBuilder(folded.length());
        for (int i = 0; i < folded.length(); i++) {
            char ch = folded.charAt(i);
            mapped.append(LOOK_ALIKES.getOrDefault(ch, ch));
        }
        return mapped.toString().replace("rn", "m").replace("vv", "w");
    }

    /**
     * The labels of a sender domain in front of its public suffix: {@code hr.inf0sys-careers.co.in} →
     * [{@code hr}, {@code inf0sys-careers}]. A brand can hide in any of them, including a subdomain.
     */
    public static List<String> labelsBeforeSuffix(ValidatedDomain domain) {
        String registrable = domain.registrableDomain();
        String suffix = registrable.substring(registrable.indexOf('.') + 1);
        String host = domain.host();
        return List.of(host.substring(0, host.length() - suffix.length() - 1).split("\\."));
    }

    private static String decodePunycode(String text) {
        if (!text.contains("xn--")) {
            return text;
        }
        String[] labels = text.split("\\.", -1);
        for (int i = 0; i < labels.length; i++) {
            if (labels[i].startsWith("xn--")) {
                labels[i] = IDN.toUnicode(labels[i], IDN.ALLOW_UNASSIGNED);
            }
        }
        return String.join(".", labels);
    }
}
