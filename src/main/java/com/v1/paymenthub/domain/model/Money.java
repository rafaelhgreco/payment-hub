package com.v1.paymenthub.domain.model;

import com.v1.paymenthub.domain.exception.InvalidMoneyException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

public record Money(BigDecimal amount, Currency currency) {
    public Money {
        Objects.requireNonNull(amount, "amount is required");
        Objects.requireNonNull(currency, "currency is required");

        int fractionDigits = currency.getDefaultFractionDigits();
        if (fractionDigits < 0) {
            throw new InvalidMoneyException("Currency not supported: " + currency.getCurrencyCode());
        }
        if (amount.stripTrailingZeros().scale() > fractionDigits) {
            throw new InvalidMoneyException("Amount %s has more decimal places than %s allow (%d)".formatted(amount.toPlainString(), currency.getCurrencyCode(), fractionDigits));
        }
        amount = amount.setScale(fractionDigits, RoundingMode.UNNECESSARY);
    }

    public static Money of(BigDecimal amount, String currencyCode) {
        Objects.requireNonNull(currencyCode, "currencyCode is required");
        Currency currency;
        try {
            currency = Currency.getInstance(currencyCode);
        } catch (IllegalArgumentException e) {
            throw new InvalidMoneyException("Unknow currency code:" + currencyCode);
        }
        return new Money(amount, currency);
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) > 0;
    }

    private void requireSameCurrency(Money other) {
        if(!currency.equals(other.currency)) {
            throw new InvalidMoneyException("Currency mismatch: %s vs %s".formatted(currency.getCurrencyCode(), other.currency.getCurrencyCode()));
        }
    }
}
