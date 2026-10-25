package com.purpuragenda.adapters.codec;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public final class Json {
    private static final ObjectMapper MAPPER=JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS).build();
    private Json() {}
    public static String write(Object value) { try{return MAPPER.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("SERIALIZATION_FAILED",e);} }
    public static <T>T read(String json,Class<T> type) {try{return MAPPER.readValue(json,type);}catch(Exception e){throw new IllegalStateException("PERSISTED_DATA_INVALID",e);} }
}
