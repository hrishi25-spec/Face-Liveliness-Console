package io.mosip.liveness.models.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosip.liveness.config.EffectivePolicy;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Persists a frozen {@link EffectivePolicy} as a JSON object in a text column.
 *
 * <p>The effective policy is resolved once at session creation and stored on the
 * session so the operating point cannot drift mid-session if an admin edits
 * {@code config_policies}. Stored as plain text (not {@code jsonb}) for the same
 * reason {@link StringListJsonConverter} is: identical behaviour on PostgreSQL
 * and on H2 (dev profile), and the column is never queried with JSON operators.</p>
 */
@Converter
public class EffectivePolicyJsonConverter implements AttributeConverter<EffectivePolicy, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            // The snapshot is written by this class and read back by this class;
            // unknown fields are tolerated so an older row still loads after a
            // policy field is added.
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public String convertToDatabaseColumn(EffectivePolicy attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialise effective policy to JSON", e);
        }
    }

    @Override
    public EffectivePolicy convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(dbData, EffectivePolicy.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Cannot deserialise effective policy from JSON: " + dbData, e);
        }
    }
}
