package com.offershield.analysis;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * Stores a list of registry keys (HMAC-SHA256 hex values) in a MySQL {@code JSON} column as a JSON array; an empty or
 * missing list reads as empty.
 */
@Converter
public class HmacListConverter implements AttributeConverter<List<String>, String> {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(List<String> keys) {
        return JSON.writeValueAsString(keys == null ? List.of() : keys);
    }

    @Override
    public List<String> convertToEntityAttribute(String json) {
        return json == null || json.isBlank() ? List.of() : List.copyOf(JSON.readValue(json, STRINGS));
    }
}
