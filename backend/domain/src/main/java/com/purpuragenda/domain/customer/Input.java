package com.purpuragenda.domain.customer;

import com.purpuragenda.domain.DomainFailure;
import static com.purpuragenda.domain.DomainFailure.require;
import java.net.IDN;
import java.text.Normalizer;
import java.time.*;
import java.util.Locale;

public final class Input {
    private Input() {}
    public static String text(String value, int min, int max) {
        require(value != null, "VALIDATION_FAILED");
        String normalized = Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
        int size = normalized.codePointCount(0, normalized.length());
        require(size >= min && size <= max && normalized.codePoints().noneMatch(Character::isISOControl), "VALIDATION_FAILED");
        return normalized;
    }
    public static String multiline(String value, int max) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
        require(normalized.codePointCount(0, normalized.length()) <= max && normalized.codePoints().noneMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'), "VALIDATION_FAILED");
        return normalized;
    }
    public static String email(String value) {
        String source = text(value, 3, 254);
        int at = source.lastIndexOf('@');
        require(at > 0 && at == source.indexOf('@') && at < source.length() - 1, "INVALID_EMAIL");
        String local = source.substring(0, at), domain;
        require(local.length() <= 64 && local.matches("[A-Za-z0-9!#$%&'*+/=?^_`{|}~.-]+") && !local.startsWith(".") && !local.endsWith(".") && !local.contains(".."), "INVALID_EMAIL");
        try { domain = IDN.toASCII(source.substring(at + 1), IDN.USE_STD3_ASCII_RULES); }
        catch (IllegalArgumentException e) { throw new DomainFailure("INVALID_EMAIL"); }
        require(domain.contains(".") && domain.length() <= 253 && !domain.endsWith(".") && source.length() - source.substring(at + 1).length() + domain.length() <= 254, "INVALID_EMAIL");
        return (local + "@" + domain).toLowerCase(Locale.ROOT);
    }
    public static String phone(String value) {
        String source = text(value, 8, 40);
        require(source.matches("\\+[0-9 () .-]+"), "INVALID_PHONE");
        String normalized = source.replaceAll("[ () .-]", "");
        require(normalized.matches("\\+[1-9][0-9]{6,14}"), "INVALID_PHONE");
        return normalized;
    }
    public static ZoneId zone(String value) {
        try { require(value != null && ZoneId.getAvailableZoneIds().contains(value), "INVALID_ZONE"); return ZoneId.of(value); }
        catch (DateTimeException e) { throw new DomainFailure("INVALID_ZONE"); }
    }
    public static String reason(String value) { return text(value, 10, 500); }
}
