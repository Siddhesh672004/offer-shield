package com.offershield.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks personal data before any text goes to a provider (PRD §8.1, §8.5; D-07). Each value becomes a placeholder,
 * the same value always the same one, and the map back stays in the JVM ({@link Redaction}):
 * <ol>
 *   <li>email addresses → {@code [EMAIL_n]}</li>
 *   <li>UPI IDs ({@code name@bank}) → {@code [UPI_n]}</li>
 *   <li>phone numbers (Indian mobiles in any grouping, with +91/0091/0; landlines with a 0 prefix; any number written
 *       with + or 00) → {@code [PHONE_n]}; any other run of 10 or more digits (Aadhaar, account numbers) →
 *       {@code [NUMBER_n]}. Amounts ("₹5,000", "6,00,000"), dates and short references stay.</li>
 *   <li>names after a greeting ("Dear", "Hi", "Hello", …), a title ("Mr.", "Ms.", "Mrs.", "Dr.", "Shri", "Smt.",
 *       "Kumari"), a label ("Candidate Name:", "Name:" at the start of a line, …) or alone on the line after "To," →
 *       {@code [NAME_n]}, and every other place the same name appears. Generic words ("Dear Candidate", "Sir/Madam",
 *       "Team") stay.</li>
 * </ol>
 * Text that already looks like a placeholder is changed to round brackets first, so a letter cannot plant one, and
 * zero-width characters are removed so they cannot hide a number. Residual risk (documented, D-07): a name that is not
 * after a greeting, title or label (for example a signature) is not masked.
 *
 * <p>Every pattern is bounded, so hostile input cannot make it backtrack for long.
 */
public final class Redactor {

    private static final Pattern PLACEHOLDER_LIKE = Pattern.compile("\\[((?:EMAIL|UPI|PHONE|NUMBER|NAME)_\\d{1,6})]");
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]{1,63}(?:\\.[A-Za-z0-9-]{1,63}){1,8}");
    private static final Pattern UPI = Pattern.compile(
            "(?<![A-Za-z0-9._-])[A-Za-z0-9._-]{2,64}@[A-Za-z][A-Za-z0-9]{1,34}(?![A-Za-z0-9])");
    /** A run of digits with single spaces or dashes between them, optionally after + or 00. */
    private static final Pattern NUMBER_RUN = Pattern.compile("(?<![\\d+])(?:(?:\\+|00)[ \\t]?)?\\d(?:[ \\t-]?\\d){7,40}(?!\\d)");
    private static final Pattern ZERO_WIDTH = Pattern.compile("[\\u200B-\\u200D\\u2060\\uFEFF]");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}&&[^\\n\\t]]");
    private static final Pattern HORIZONTAL_SPACE = Pattern.compile("[ \\t\\x0B\\f\\u00A0\\u2000-\\u200A\\u202F\\u205F\\u3000]+");
    private static final Pattern LINE_EDGE_SPACE = Pattern.compile("(?m)^ +| +$");
    private static final Pattern MANY_BLANK_LINES = Pattern.compile("\\n{3,}");

    private static final String NAME = "(\\p{Lu}[\\p{L}'’.-]{0,30}(?:[ \\t]+\\p{Lu}[\\p{L}'’.-]{0,30}){0,3})";
    private static final String TITLE = "(?:Mr|Mrs|Ms|Mx|Dr|Shri|Smt|Sri|Kumari)\\.?";
    private static final List<Pattern> NAME_TRIGGERS = List.of(
            Pattern.compile("(?<!\\p{L})(?i:dear|hi|hello|hey|respected|congratulations|congrats)[ \\t]+(?:" + TITLE
                    + "[ \\t]+)?" + NAME),
            Pattern.compile("(?<!\\p{L})" + TITLE + "[ \\t]+" + NAME),
            Pattern.compile("(?<!\\p{L})(?i:candidate(?:['’]s)?[ \\t]+name|name[ \\t]+of[ \\t]+(?:the[ \\t]+)?"
                    + "(?:candidate|employee|applicant|student)|(?:employee|applicant|student|full)[ \\t]+name)"
                    + "[ \\t]*[:–-][ \\t]*" + NAME),
            Pattern.compile("(?m)^[ \\t]*(?i:name)[ \\t]*[:–-][ \\t]*" + NAME),
            Pattern.compile("(?m)^[ \\t]*(?i:to),?[ \\t]*\\n[ \\t]*(?:" + TITLE + "[ \\t]+)?" + NAME + "[ \\t]*,?[ \\t]*$"));
    /** First words that make a greeting generic ("Dear Candidate"), not a name. */
    private static final Set<String> GENERIC = Set.of("candidate", "candidates", "sir", "sirs", "madam", "madame",
            "applicant", "applicants", "student", "students", "team", "all", "hiring", "recruiter", "colleague",
            "colleagues", "friend", "friends", "user", "users", "customer", "member", "members", "concern", "whom",
            "employee", "employees", "intern", "interns", "trainee", "trainees", "graduate", "graduates", "there",
            "everyone", "everybody", "valued", "selected", "shortlisted", "future", "new", "prospective", "dear", "the",
            "a", "an", "our", "your", "hr", "manager", "management", "aspirant", "aspirants", "jobseeker", "participant",
            "gentlemen", "ladies", "folks", "guys", "again", "and", "on", "you", "email", "phone", "upi", "number",
            "name");
    /** Words that end a name ("Dear Rahul We are…"). */
    private static final Set<String> NAME_STOP = Set.of("we", "you", "i", "it", "this", "the", "our", "your", "thank",
            "thanks", "greetings", "welcome", "congratulations", "please", "kindly", "as", "with", "on", "in", "from",
            "for", "at", "is", "are", "has", "have", "and", "to", "of");

    private Redactor() {
    }

    /** Line breaks unified, control and zero-width characters removed, spaces collapsed, at most one blank line. */
    public static String clean(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = text.replace("\r\n", "\n").replace('\r', '\n');
        cleaned = ZERO_WIDTH.matcher(cleaned).replaceAll("");
        cleaned = CONTROL.matcher(cleaned).replaceAll("");
        cleaned = HORIZONTAL_SPACE.matcher(cleaned).replaceAll(" ");
        cleaned = LINE_EDGE_SPACE.matcher(cleaned).replaceAll("");
        cleaned = MANY_BLANK_LINES.matcher(cleaned).replaceAll("\n\n");
        return cleaned.strip();
    }

    public static Redaction mask(String text) {
        Masker masker = new Masker();
        String masked = text == null ? "" : ZERO_WIDTH.matcher(text).replaceAll("");
        masked = PLACEHOLDER_LIKE.matcher(masked).replaceAll("($1)");
        masked = EMAIL.matcher(masked).replaceAll(match -> masker.placeholder("EMAIL", match.group()));
        masked = UPI.matcher(masked).replaceAll(match -> masker.placeholder("UPI", match.group()));
        masked = NUMBER_RUN.matcher(masked).replaceAll(match -> masker.number(match));
        masked = masker.names(masked);
        return new Redaction(masked, masker.originals);
    }

    /** Keeps the numbering and the map of one {@link #mask} call. */
    private static final class Masker {

        private static final int MAX_NAMES_EVERYWHERE = 20;
        private final Map<String, String> originals = new LinkedHashMap<>();
        private final Map<String, String> byValue = new HashMap<>();
        private final Map<String, Integer> counters = new HashMap<>();

        String placeholder(String kind, String value) {
            String key = kind + "\u0000" + value;
            String existing = byValue.get(key);
            if (existing == null) {
                existing = "[" + kind + "_" + counters.merge(kind, 1, Integer::sum) + "]";
                byValue.put(key, existing);
                originals.put(existing, value);
            }
            return Matcher.quoteReplacement(existing);
        }

        String number(MatchResult match) {
            String value = match.group();
            String digits = value.replaceAll("\\D", "");
            // "+44 20 …" or "0091 98765 …"; an unbroken "00123456789012" is more likely an account number.
            boolean international = value.startsWith("+")
                    || value.startsWith("00") && (value.indexOf(' ') > 0 || value.indexOf('-') > 0);
            if (international) {
                return placeholder(digits.length() <= 15 ? "PHONE" : "NUMBER", value);
            }
            if (digits.length() < 10) {
                return Matcher.quoteReplacement(value);
            }
            return placeholder(isIndianPhone(digits) ? "PHONE" : "NUMBER", value);
        }

        private static boolean isIndianPhone(String digits) {
            return switch (digits.length()) {
                case 10 -> digits.charAt(0) >= '6';
                case 11 -> digits.charAt(0) == '0';
                case 12 -> digits.startsWith("91") && digits.charAt(2) >= '6';
                default -> false;
            };
        }

        /**
         * Finds names after greetings, titles and labels and numbers them in text order. Each is masked where it was
         * found, and the first {@value #MAX_NAMES_EVERYWHERE} different names also everywhere else they appear (a
         * real letter has a handful; the cap keeps hostile text with thousands of names fast).
         */
        String names(String text) {
            TreeMap<Integer, String> found = new TreeMap<>();
            for (Pattern trigger : NAME_TRIGGERS) {
                Matcher matcher = trigger.matcher(text);
                while (matcher.find()) {
                    String name = trimName(matcher.group(1));
                    if (name != null && text.startsWith(name, matcher.start(1))) {
                        found.putIfAbsent(matcher.start(1), name);
                    }
                }
            }
            found.values().forEach(name -> placeholder("NAME", name));
            StringBuilder masked = new StringBuilder(text);
            int limit = Integer.MAX_VALUE;
            for (Map.Entry<Integer, String> entry : found.descendingMap().entrySet()) {
                int start = entry.getKey();
                int end = start + entry.getValue().length();
                if (end <= limit) {
                    masked.replace(start, end, placeholder("NAME", entry.getValue()));
                    limit = start;
                }
            }
            List<String> names = new ArrayList<>(found.values().stream().distinct().limit(MAX_NAMES_EVERYWHERE).toList());
            // Longest first, so "Rahul Sharma" is masked before a shorter name inside it.
            names.sort((left, right) -> right.length() - left.length());
            String result = masked.toString();
            for (String name : names) {
                // A full name in any case ("RAHUL SHARMA"); a single word only as written, so the name "Will" does
                // not swallow the word "will".
                int flags = name.indexOf(' ') > 0 ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
                Pattern everywhere = Pattern.compile("(?<![\\p{L}\\[_])" + Pattern.quote(name) + "(?![\\p{L}_\\]])",
                        flags);
                result = everywhere.matcher(result).replaceAll(placeholder("NAME", name));
            }
            return result;
        }

        /** Drops generic greetings and cuts the name before ordinary words; {@code null} when nothing is left. */
        private static String trimName(String candidate) {
            String[] words = candidate.strip().split("[ \\t]+");
            if (words.length == 0 || GENERIC.contains(bare(words[0]))) {
                return null;
            }
            List<String> kept = new ArrayList<>();
            for (String word : words) {
                if (NAME_STOP.contains(bare(word))) {
                    break;
                }
                kept.add(word);
            }
            String name = String.join(" ", kept).replaceAll("[.’'-]+$", "");
            return name.length() < 2 ? null : name;
        }

        private static String bare(String word) {
            return word.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}]", "");
        }
    }
}
