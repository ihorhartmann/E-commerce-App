package com.hartmann.domain.value;

import com.hartmann.domain.exception.DomainException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrencyTest {

    @Test
    void fromCodeResolvesAnUppercaseCode() {
        assertThat(Currency.fromCode("USD")).isEqualTo(Currency.USD);
    }

    @Test
    void fromCodeResolvesALowercaseCode() {
        assertThat(Currency.fromCode("eur")).isEqualTo(Currency.EUR);
    }

    @Test
    void fromCodeResolvesAMixedCaseCode() {
        assertThat(Currency.fromCode("GbP")).isEqualTo(Currency.GBP);
    }

    @Test
    void fromCodeThrowsOnANullCode() {
        assertThatThrownBy(() -> Currency.fromCode(null)).isInstanceOf(DomainException.class);
    }

    @Test
    void fromCodeThrowsOnAnUnsupportedCode() {
        assertThatThrownBy(() -> Currency.fromCode("JPY")).isInstanceOf(DomainException.class);
    }

    @Test
    void fromCodeThrowsOnABlankCode() {
        assertThatThrownBy(() -> Currency.fromCode("")).isInstanceOf(DomainException.class);
    }
}
