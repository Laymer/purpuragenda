package com.purpuragenda.adapters.in.rest;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.*;

/** Strict flat string-object bodies: JSON content type, 2 KiB bound, exact field set, no duplicates or trailing data. */
final class JsonBodies {
    private static final int LIMIT=2048;
    private static final ObjectMapper MAPPER=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private JsonBodies() { }
    static final class Invalid extends Exception { Invalid() { super("INVALID_BODY",null,false,false); } }

    /** Values are returned in the order of {@code fields}; any other shape throws {@link Invalid}. */
    static String[] read(HttpServletRequest request,String... fields) throws Invalid {
        String type=request.getContentType();
        if(type==null || !(type.equalsIgnoreCase("application/json") || type.toLowerCase(Locale.ROOT).startsWith("application/json;"))) throw new Invalid();
        try {
            byte[] bytes=request.getInputStream().readNBytes(LIMIT+1);
            if(bytes.length>LIMIT) throw new Invalid();
            JsonNode root=MAPPER.readTree(bytes);
            if(root==null || !root.isObject() || root.size()!=fields.length) throw new Invalid();
            String[] values=new String[fields.length];
            for(int i=0;i<fields.length;i++) {
                JsonNode value=root.get(fields[i]);
                if(value==null || !value.isTextual() || value.textValue().isEmpty() || value.textValue().length()>320) throw new Invalid();
                values[i]=value.textValue();
            }
            return values;
        } catch(IOException | RuntimeException malformed) { throw new Invalid(); }
    }
}
