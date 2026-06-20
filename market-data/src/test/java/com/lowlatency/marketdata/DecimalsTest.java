package com.lowlatency.marketdata;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DecimalsTest {

    @Test
    void parsesIntegerAndFraction() {
        assertThat(Decimals.parseScaled("42123.45", 2)).isEqualTo(4_212_345L);
        assertThat(Decimals.parseScaled("100", 2)).isEqualTo(10_000L);
        assertThat(Decimals.parseScaled("0.01", 2)).isEqualTo(1L);
    }

    @Test
    void padsShortFractionsAndTruncatesLongOnes() {
        assertThat(Decimals.parseScaled("0.1", 8)).isEqualTo(10_000_000L);   // padded
        assertThat(Decimals.parseScaled("42123.459", 2)).isEqualTo(4_212_345L); // truncated (floor)
        assertThat(Decimals.parseScaled("0.000123", 8)).isEqualTo(12_300L);
    }

    @Test
    void handlesNegativeAndLeadingZeroForms() {
        assertThat(Decimals.parseScaled("-1.5", 2)).isEqualTo(-150L);
        assertThat(Decimals.parseScaled("0.00", 2)).isEqualTo(0L);
    }

    @Test
    void rejectsNonDecimal() {
        org.junit.jupiter.api.Assertions.assertThrows(
                NumberFormatException.class, () -> Decimals.parseScaled("12.3x", 2));
    }
}
