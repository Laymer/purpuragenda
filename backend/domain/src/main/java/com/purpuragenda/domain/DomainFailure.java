package com.purpuragenda.domain;

/** A safe business code; messages must never contain customer data or credentials. */
public final class DomainFailure extends RuntimeException {
    private final String code;
    public DomainFailure(String code) { super(code); this.code = code; }
    public String code() { return code; }
    public static void require(boolean condition, String code) { if (!condition) throw new DomainFailure(code); }
}
