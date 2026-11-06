package com.offershield.ai;

import com.offershield.common.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * AI extraction for one letter (PRD §8.1, §8.5, §8.6): clean the text, mask it (D-07), cap it at about 6,000
 * characters, reuse a cached answer or ask the {@link LlmChain} with the fixed {@link ExtractionPrompt}, store a new
 * valid answer (masked) in {@link LlmCache}, then keep only what the model could have read in the letter:
 * <ul>
 *   <li>text fields and payment mentions must appear in the masked letter (case and spacing ignored), so the AI
 *       never creates evidence (PRD §7); a text field holding a placeholder is dropped (no personal data is stored
 *       as an AI fact);</li>
 *   <li>a phone number or UPI ID must be a placeholder of that kind made for this letter (mapped back locally) or a
 *       value written in the letter.</li>
 * </ul>
 * Runs outside any database transaction (the analysis calls it before saving). Never throws: AI off, every model
 * failing, an invalid answer or any error gives an empty result and the report says "AI unavailable". Logs never
 * contain the letter, the answer or the values (D-27).
 */
@Service
public class ExtractionService {

    static final int MIN_MENTION_LENGTH = 8;
    static final int MIN_TEXT_LENGTH = 2;
    static final int MAX_VALUES = 5;
    private static final Pattern PHONE_SHAPE = Pattern.compile("\\+?(?:[ ()-]?\\d){8,15}");
    private static final Pattern UPI_SHAPE = Pattern.compile("[A-Za-z0-9._-]{2,64}@[A-Za-z][A-Za-z0-9]{1,34}");
    private static final Logger log = LoggerFactory.getLogger(ExtractionService.class);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\[(EMAIL|UPI|PHONE|NUMBER|NAME)_\\d{1,6}]");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private final LlmChain chain;
    private final LlmCache cache;
    private final int maxLetterChars;
    private final int maxTokens;

    public ExtractionService(LlmChain chain, LlmCache cache, AiProperties properties) {
        this.chain = chain;
        this.cache = cache;
        this.maxLetterChars = properties.maxLetterChars();
        this.maxTokens = properties.extractionMaxTokens();
    }

    public Optional<Extraction> extract(String letterText) {
        if (!chain.enabled()) {
            return Optional.empty();
        }
        try {
            Redaction redaction = Redactor.mask(Redactor.clean(letterText));
            String masked = cap(redaction.text(), maxLetterChars);
            String key = cacheKey(masked);
            Optional<String> cachedJson = cache.find(key);
            if (cachedJson.isPresent()) {
                Optional<ExtractionResult> cached = ExtractionResult.parse(cachedJson.get());
                if (cached.isPresent()) {
                    log.info("ai extraction from cache");
                    return Optional.of(ground(cached.get(), redaction, masked, null, true));
                }
                // P13: e.g. stored before a parser change. Removed, so the next valid answer takes its place.
                log.warn("ai extraction cached answer unusable: removed, asking the AI again");
                cache.evict(key);
            }
            Optional<LlmAnswer<ExtractionResult>> answer =
                    chain.complete(ExtractionPrompt.request(masked, maxTokens), ExtractionResult::parse);
            if (answer.isEmpty()) {
                return Optional.empty();
            }
            LlmAnswer<ExtractionResult> found = answer.get();
            cache.put(key, ExtractionPrompt.TASK, found.value().toJson(), found.provider(), found.model());
            return Optional.of(ground(found.value(), redaction, masked, found.model(), false));
        } catch (RuntimeException ex) {
            log.warn("ai extraction failed error={}", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** sha256("extract:" + prompt version + ":" + masked letter): the same letter is never sent twice (PRD §8.5). */
    static String cacheKey(String maskedLetter) {
        return Hashing.sha256Hex(ExtractionPrompt.TASK + ":" + ExtractionPrompt.VERSION + ":" + maskedLetter);
    }

    private static Extraction ground(ExtractionResult result, Redaction redaction, String masked, String model,
                                     boolean fromCache) {
        String letter = normalize(masked);
        return new Extraction(groundedText(result.companyName(), letter), groundedText(result.recruiterName(), letter),
                groundedText(result.ctc(), letter), groundedText(result.joiningDate(), letter),
                contacts(result.phoneNumbers(), "PHONE", redaction, letter),
                contacts(result.upiIds(), "UPI", redaction, letter),
                result.paymentMentions().stream()
                        .filter(mention -> mention.strip().length() >= MIN_MENTION_LENGTH)
                        .filter(mention -> letter.contains(normalize(mention)))
                        .distinct().limit(MAX_VALUES).toList(),
                model, fromCache);
    }

    private static String groundedText(String value, String letter) {
        if (value == null || value.strip().length() < MIN_TEXT_LENGTH || PLACEHOLDER.matcher(value).find()
                || !letter.contains(normalize(value))) {
            return null;
        }
        return value;
    }

    /**
     * A placeholder of this kind made for this letter → its original value; otherwise a value written in the letter
     * that has the shape of a phone number (8–15 digits) or a UPI ID ({@code name@bank}).
     */
    private static List<String> contacts(List<String> values, String kind, Redaction redaction, String letter) {
        Pattern shape = "PHONE".equals(kind) ? PHONE_SHAPE : UPI_SHAPE;
        return values.stream()
                .map(value -> {
                    if (PLACEHOLDER.matcher(value).matches()) {
                        return value.startsWith("[" + kind + "_") ? redaction.original(value) : null;
                    }
                    boolean usable = !PLACEHOLDER.matcher(value).find() && shape.matcher(value.strip()).matches()
                            && letter.contains(normalize(value));
                    return usable ? value.strip() : null;
                })
                .filter(Objects::nonNull)
                .distinct().limit(MAX_VALUES).toList();
    }

    /** Lower case, straight quotes, single spaces: how the model may re-type a quote without changing it. */
    static String normalize(String text) {
        String straight = text.replace('‘', '\'').replace('’', '\'').replace('“', '"').replace('”', '"');
        return SPACES.matcher(straight.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }

    private static String cap(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int end = Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max;
        return text.substring(0, end);
    }
}
