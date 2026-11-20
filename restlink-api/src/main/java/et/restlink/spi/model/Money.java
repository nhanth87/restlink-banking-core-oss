package et.restlink.spi.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Immutable money value. Minor-unit-safe {@link BigDecimal} amount + ISO-4217 currency.
 * Never use double for money.
 */
public record Money(BigDecimal amount, String currency) {
    public Money {
        Objects.requireNonNull(amount, "amount");
        currency = currency == null ? "" : currency.trim().toUpperCase(java.util.Locale.ROOT);
        if (currency.length() != 3) {
            throw new IllegalArgumentException("currency must be ISO-4217 (3 letters): " + currency);
        }
    }

    public static Money of(String amount, String currency) {
        return new Money(new BigDecimal(amount), currency);
    }
}
