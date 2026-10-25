package com.purpuragenda.adapters.out.postgres;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.purpuragenda.application.port.out.SchedulingUnitOfWork.Failure;
import java.util.Objects;

/** Typed records only; no polymorphic typing, logging or retained parser causes. */
final class PersistedJson {
    private static final ObjectMapper MAPPER=JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private PersistedJson() {}
    static String write(Object value) {
        try { return MAPPER.writeValueAsString(Objects.requireNonNull(value)); }
        catch(Exception failure) { throw new Failure(Failure.Kind.STORAGE_FAILURE); }
    }
    static <T> T read(String json,Class<T> type) {
        try { return Objects.requireNonNull(MAPPER.readValue(json,type)); }
        catch(Exception failure) { throw new Failure(Failure.Kind.STORAGE_FAILURE); }
    }
}
