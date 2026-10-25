package com.purpuragenda.domain.booking;

import static com.purpuragenda.domain.DomainFailure.require;
import java.util.Currency;

public record Money(long minor, String currency) {
    public Money {
        require(minor >= 0 && minor <= 100_000_000L, "INVALID_AMOUNT");
        require(currency != null && currency.matches("[A-Z]{3}"), "INVALID_CURRENCY");
        try { Currency.getInstance(currency); } catch (IllegalArgumentException e) { throw new com.purpuragenda.domain.DomainFailure("INVALID_CURRENCY"); }
    }
    public static Money eur(long minor) { return new Money(minor, "EUR"); }
}
