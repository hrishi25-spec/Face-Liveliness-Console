package io.mosip.liveness.models.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists a {@code List<String>} as a JSON array in a text column, e.g.
 * {@code ["blink","smile","turn_left"]}.
 *
 * <p>An explicit converter is used rather than Hibernate's
 * {@code @JdbcTypeCode(SqlTypes.JSON)} because H2's {@code jsonb} type stores any
 * JDBC string write as a JSON <em>string scalar</em>, so the value comes back
 * double-encoded ({@code "[\"blink\"]"}). Writing plain text keeps the mapping
 * identical on PostgreSQL and on H2 (dev profile). These columns are never
 * queried with JSON operators, so text storage loses nothing.</p>
 */
@Converter
public class StringListJsonConverter implements AttributeConverter<List<String>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> LIST_OF_STRING = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialise challenge types to JSON", e);
        }
    }

    @Override
    public List<String> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(MAPPER.readValue(dbData, LIST_OF_STRING));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Cannot deserialise challenge types from JSON: " + dbData, e);
        }
    }
}
