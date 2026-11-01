package com.offershield.analysis;

import com.offershield.common.events.AnalysisCompletedEvent;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * Stores a report's phone / UPI marker hints (P13, N2: type, HMAC key, source and masked hint; never a raw value) in a
 * MySQL {@code JSON} column as a JSON array; a missing value (a report made before P13) reads as empty.
 */
@Converter
public class MarkerHintListConverter implements AttributeConverter<List<AnalysisCompletedEvent.MarkerHint>, String> {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<AnalysisCompletedEvent.MarkerHint>> HINTS = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(List<AnalysisCompletedEvent.MarkerHint> hints) {
        return JSON.writeValueAsString(hints == null ? List.of() : hints);
    }

    @Override
    public List<AnalysisCompletedEvent.MarkerHint> convertToEntityAttribute(String json) {
        return json == null || json.isBlank() ? List.of() : List.copyOf(JSON.readValue(json, HINTS));
    }
}
