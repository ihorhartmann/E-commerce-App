package com.hartmann.domain.value;

import com.hartmann.domain.value.exception.UnsupportedCurrencyException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrencyTest {

    @Test
    void fromCode_resolvesAnUppercaseCode() {
        assertThat(Currency.fromCode("USD")).isEqualTo(Currency.USD);
    }

    @Test
    void fromCode_resolvesALowercaseCode() {
        assertThat(Currency.fromCode("eur")).isEqualTo(Currency.EUR);
    }

    @Test
    void fromCode_resolvesAMixedCaseCode() {
        assertThat(Currency.fromCode("GbP")).isEqualTo(Currency.GBP);
    }

    @Test
    void fromCode_throwsOnANullCode() {
        assertThatThrownBy(() -> Currency.fromCode(null)).isInstanceOf(UnsupportedCurrencyException.class);
    }

    @Test
    void fromCode_throwsOnAnUnsupportedCode() {
        assertThatThrownBy(() -> Currency.fromCode("JPY"))
                .isInstanceOf(UnsupportedCurrencyException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromCode_throwsOnABlankCode() {
        assertThatThrownBy(() -> Currency.fromCode("")).isInstanceOf(UnsupportedCurrencyException.class);
    }
}
