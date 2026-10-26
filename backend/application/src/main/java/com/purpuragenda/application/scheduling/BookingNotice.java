package com.purpuragenda.application.scheduling;

import java.text.Normalizer;
import java.util.Objects;

/** Versioned plaintext presented before submission; synthetic fixtures never assert legal approval. */
public record BookingNotice(String version,String text,boolean synthetic) {
    public BookingNotice {
        Objects.requireNonNull(version);Objects.requireNonNull(text);
        if(!version.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}"))throw new IllegalArgumentException("INVALID_BOOKING_NOTICE");
        text=Normalizer.normalize(text,Normalizer.Form.NFC);
        if(text.isBlank() || text.codePointCount(0,text.length())>16000
                || text.codePoints().anyMatch(c -> Character.isISOControl(c) && c!='\n' && c!='\t'))
            throw new IllegalArgumentException("INVALID_BOOKING_NOTICE");
        text=text.strip();
    }
}
