package com.offershield.analysis.checks;

import com.offershield.company.KnownCompanyDirectory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Finding known companies by name (C3) and turning a name into a brand (C4). A company's "core" name is its list name
 * without trailing legal words ("Northwind Analytics Private Limited" → "Northwind Analytics"). A letter names the
 * company when the core name appears as whole words, ignoring case and spacing (line breaks included): "Zenvora" is
 * named in "Welcome to Zenvora." but not in "Zenvorabank".
 */
final class CompanyNames {

    private static final Set<String> LEGAL_WORDS = Set.of("ltd", "limited", "pvt", "private", "inc", "llp", "llc",
            "plc", "corp", "corporation", "co", "company");
    private static final Pattern WORDS = Pattern.compile("\\s+");
    private static final Pattern NOT_LETTER_OR_DIGIT = Pattern.compile("[^\\p{L}\\p{N}]");

    private CompanyNames() {
    }

    /** The name without trailing legal words; the whole name when nothing else would be left. */
    static String core(String name) {
        String[] words = WORDS.split(name.strip());
        int end = words.length;
        while (end > 1 && LEGAL_WORDS.contains(NOT_LETTER_OR_DIGIT.matcher(words[end - 1].toLowerCase(Locale.ROOT))
                .replaceAll(""))) {
            end--;
        }
        return String.join(" ", Arrays.copyOf(words, end));
    }

    /** The known companies whose core name appears in {@code text} as whole words, in list order. */
    static List<KnownCompanyDirectory.Company> namedIn(String text, List<KnownCompanyDirectory.Company> companies) {
        List<KnownCompanyDirectory.Company> named = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return named;
        }
        for (KnownCompanyDirectory.Company company : companies) {
            if (pattern(company.name()).matcher(text).find()) {
                named.add(company);
            }
        }
        return named;
    }

    /** Display list: at most {@code max} names, comma-separated. */
    static String names(List<KnownCompanyDirectory.Company> companies, int max) {
        return companies.stream().limit(max).map(KnownCompanyDirectory.Company::name).collect(Collectors.joining(", "));
    }

    private static Pattern pattern(String name) {
        String words = Arrays.stream(WORDS.split(core(name))).map(Pattern::quote).collect(Collectors.joining("\\s+"));
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + words + "(?![\\p{L}\\p{N}])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
    }
}
