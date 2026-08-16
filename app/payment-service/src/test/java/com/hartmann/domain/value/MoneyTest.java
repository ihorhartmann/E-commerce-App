package com.hartmann.domain.value;

import com.hartmann.domain.exception.DomainException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void constructor_createsMoneyWithANonNegativeAmount() {
        var amount = "10.00";
        var currency = Currency.USD;
        var money = new Money(new BigDecimal(amount), currency);

        assertThat(money.amount()).isEqualByComparingTo(amount);
        assertThat(money.currency()).isEqualTo(currency);
    }

    @Test
    void constructor_allowsAZeroAmount() {
        var amount = BigDecimal.ZERO;
        var money = new Money(amount, Currency.USD);

        assertThat(money.amount()).isEqualByComparingTo(amount);
    }

    @Test
    void constructor_rejectsANegativeAmount() {
        assertThatThrownBy(() -> new Money(new BigDecimal("-1"), Currency.USD))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void zero_buildsAZeroAmountInTheGivenCurrency() {
        var currency = Currency.EUR;
        var money = Money.zero(currency);

        assertThat(money.amount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(money.currency()).isEqualTo(currency);
    }

    @Test
    void add_sumsAmountsInTheSameCurrency() {
        var sum = new Money(new BigDecimal("10.00"), Currency.USD)
                .add(new Money(new BigDecimal("5.00"), Currency.USD));

        assertThat(sum.amount()).isEqualByComparingTo("15.00");
    }

    @Test
    void add_throwsOnCurrencyMismatch() {
        var usd = new Money(new BigDecimal("10.00"), Currency.USD);
        var eur = new Money(new BigDecimal("5.00"), Currency.EUR);

        assertThatThrownBy(() -> usd.add(eur)).isInstanceOf(DomainException.class);
    }

    @Test
    void subtract_subtractsAmountsInTheSameCurrency() {
        var difference = new Money(new BigDecimal("10.00"), Currency.USD)
                .subtract(new Money(new BigDecimal("4.00"), Currency.USD));

        assertThat(difference.amount()).isEqualByComparingTo("6.00");
    }

    @Test
    void subtract_throwsOnCurrencyMismatch() {
        var usd = new Money(new BigDecimal("10.00"), Currency.USD);
        var eur = new Money(new BigDecimal("5.00"), Currency.EUR);

        assertThatThrownBy(() -> usd.subtract(eur)).isInstanceOf(DomainException.class);
    }

    @Test
    void isGreaterThan_comparesAmountsInTheSameCurrency() {
        var currency = Currency.USD;
        var ten = new Money(new BigDecimal("10.00"), currency);
        var five = new Money(new BigDecimal("5.00"), currency);

        assertThat(ten.isGreaterThan(five)).isTrue();
        assertThat(five.isGreaterThan(ten)).isFalse();
    }

    @Test
    void isGreaterThan_throwsOnCurrencyMismatch() {
        var usd = new Money(new BigDecimal("10.00"), Currency.USD);
        var eur = new Money(new BigDecimal("5.00"), Currency.EUR);

        assertThatThrownBy(() -> usd.isGreaterThan(eur)).isInstanceOf(DomainException.class);
    }
}
