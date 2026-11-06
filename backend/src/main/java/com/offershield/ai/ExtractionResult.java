package com.offershield.ai;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The fixed shape of an extraction answer (PRD §8.1, §8.6), still masked as the model wrote it. {@link #parse} is
 * strict: one JSON object with only these keys (missing keys are fine); text fields are a string of at most
 * {@value #MAX_TEXT} characters or null; lists are at most {@value #MAX_ITEMS} strings of at most
 * {@value #MAX_MENTION} characters. Unknown or duplicate keys, wrong types, longer values, trailing text or anything
 * that is not an object reject the <b>whole</b> answer, so a model that was talked into adding a "score" or "safe"
 * field gives no result at all.
 */
public record ExtractionResult(String companyName, String recruiterName, String ctc, String joiningDate,
                               List<String> phoneNumbers, List<String> upiIds, List<String> paymentMentions) {

    static final int MAX_TEXT = 200;
    static final int MAX_MENTION = 300;
    static final int MAX_ITEMS = 10;
    static final int MAX_JSON_CHARS = 16_384;
    private static final Set<String> TEXT_FIELDS = Set.of("companyName", "recruiterName", "ctc", "joiningDate");
    private static final Set<String> LIST_FIELDS = Set.of("phoneNumbers", "upiIds", "paymentMentions");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public ExtractionResult {
        phoneNumbers = phoneNumbers == null ? List.of() : List.copyOf(phoneNumbers);
        upiIds = upiIds == null ? List.of() : List.copyOf(upiIds);
        paymentMentions = paymentMentions == null ? List.of() : List.copyOf(paymentMentions);
    }

    public static Optional<ExtractionResult> parse(String json) {
        if (json == null || json.isBlank() || json.length() > MAX_JSON_CHARS) {
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JacksonException ex) {
            return Optional.empty();
        }
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }
        Iterator<Map.Entry<String, JsonNode>> fields = root.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            boolean valid = TEXT_FIELDS.contains(field.getKey()) ? isText(field.getValue())
                    : LIST_FIELDS.contains(field.getKey()) && isList(field.getValue());
            if (!valid) {
                return Optional.empty();
            }
        }
        return Optional.of(new ExtractionResult(text(root, "companyName"), text(root, "recruiterName"),
                text(root, "ctc"), text(root, "joiningDate"), list(root, "phoneNumbers"), list(root, "upiIds"),
                list(root, "paymentMentions")));
    }

    /** The answer as stored in {@code llm_cache} (still masked). */
    public String toJson() {
        ObjectNode root = JSON.createObjectNode();
        root.put("companyName", companyName);
        root.put("recruiterName", recruiterName);
        root.put("ctc", ctc);
        root.put("joiningDate", joiningDate);
        phoneNumbers.forEach(root.putArray("phoneNumbers")::add);
        upiIds.forEach(root.putArray("upiIds")::add);
        paymentMentions.forEach(root.putArray("paymentMentions")::add);
        return JSON.writeValueAsString(root);
    }

    private static boolean isText(JsonNode node) {
        return node.isNull() || node.isString() && node.stringValue().length() <= MAX_TEXT;
    }

    private static boolean isList(JsonNode node) {
        if (node.isNull()) {
            return true;
        }
        if (!node.isArray() || node.size() > MAX_ITEMS) {
            return false;
        }
        for (JsonNode item : node) {
            if (!item.isString() || item.stringValue().length() > MAX_MENTION) {
                return false;
            }
        }
        return true;
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isString() || node.stringValue().isBlank()) {
            return null;
        }
        return node.stringValue().strip();
    }

    private static List<String> list(JsonNode root, String field) {
        JsonNode node = root.get(field);
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                if (!item.stringValue().isBlank()) {
                    values.add(item.stringValue().strip());
                }
            }
        }
        return values;
    }
}
