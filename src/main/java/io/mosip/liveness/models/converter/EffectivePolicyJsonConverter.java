package io.mosip.liveness.models.converter;

import io.mosip.liveness.config.EffectivePolicy;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

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

    private static final JsonMapper MAPPER = JsonMapper.builder()
            // The snapshot is written by this class and read back by this class;
            // unknown fields are tolerated so an older row still loads after a
            // policy field is added. Jackson 3 mappers are immutable, so the
            // setting belongs on the builder — ObjectMapper.configure() is gone.
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Override
    public String convertToDatabaseColumn(EffectivePolicy attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (JacksonException e) {
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
        } catch (JacksonException e) {
            throw new IllegalArgumentException(
                    "Cannot deserialise effective policy from JSON: " + dbData, e);
        }
    }
}
