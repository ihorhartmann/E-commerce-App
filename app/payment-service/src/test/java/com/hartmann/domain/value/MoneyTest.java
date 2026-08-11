package com.hartmann.domain.value;

import com.hartmann.domain.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void createsMoneyWithANonNegativeAmount() {
        var money = new Money(new BigDecimal("10.00"), Currency.USD);

        assertThat(money.amount()).isEqualByComparingTo("10.00");
        assertThat(money.currency()).isEqualTo(Currency.USD);
    }

    @Test
    void allowsAZeroAmount() {
        var money = new Money(BigDecimal.ZERO, Currency.USD);

        assertThat(money.amount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void rejectsANegativeAmount() {
        assertThatThrownBy(() -> new Money(new BigDecimal("-1"), Currency.USD))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void zeroBuildsAZeroAmountInTheGivenCurrency() {
        var money = Money.zero(Currency.EUR);

        assertThat(money.amount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(money.currency()).isEqualTo(Currency.EUR);
    }

    @Test
    void addSumsAmountsInTheSameCurrency() {
        var sum = new Money(new BigDecimal("10.00"), Currency.USD)
                .add(new Money(new BigDecimal("5.00"), Currency.USD));

        assertThat(sum.amount()).isEqualByComparingTo("15.00");
    }

    @Test
    void addThrowsOnCurrencyMismatch() {
        var usd = new Money(new BigDecimal("10.00"), Currency.USD);
        var eur = new Money(new BigDecimal("5.00"), Currency.EUR);

        assertThatThrownBy(() -> usd.add(eur)).isInstanceOf(DomainException.class);
    }

    @Test
    void subtractSubtractsAmountsInTheSameCurrency() {
        var difference = new Money(new BigDecimal("10.00"), Currency.USD)
                .subtract(new Money(new BigDecimal("4.00"), Currency.USD));

        assertThat(difference.amount()).isEqualByComparingTo("6.00");
    }

    @Test
    void subtractThrowsOnCurrencyMismatch() {
        var usd = new Money(new BigDecimal("10.00"), Currency.USD);
        var eur = new Money(new BigDecimal("5.00"), Currency.EUR);

        assertThatThrownBy(() -> usd.subtract(eur)).isInstanceOf(DomainException.class);
    }

    @Test
    void isGreaterThanComparesAmountsInTheSameCurrency() {
        var ten = new Money(new BigDecimal("10.00"), Currency.USD);
        var five = new Money(new BigDecimal("5.00"), Currency.USD);

        assertThat(ten.isGreaterThan(five)).isTrue();
        assertThat(five.isGreaterThan(ten)).isFalse();
    }

    @Test
    void isGreaterThanThrowsOnCurrencyMismatch() {
        var usd = new Money(new BigDecimal("10.00"), Currency.USD);
        var eur = new Money(new BigDecimal("5.00"), Currency.EUR);

        assertThatThrownBy(() -> usd.isGreaterThan(eur)).isInstanceOf(DomainException.class);
    }
}
