package io.mosip.liveness.models.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.HashMap;
import java.util.Map;

/**
 * Persists a {@code Map<String,Object>} as a JSON object in a text column.
 *
 * <p>See {@link StringListJsonConverter} for why an explicit converter is used
 * instead of Hibernate's JSON JDBC type.</p>
 */
@Converter
public class StringObjectMapJsonConverter implements AttributeConverter<Map<String, Object>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(Map<String, Object> attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialise audit details to JSON", e);
        }
    }

    @Override
    public Map<String, Object> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return new HashMap<>();
        }
        try {
            return new HashMap<>(MAPPER.readValue(dbData, MAP_TYPE));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Cannot deserialise audit details from JSON: " + dbData, e);
        }
    }
}
