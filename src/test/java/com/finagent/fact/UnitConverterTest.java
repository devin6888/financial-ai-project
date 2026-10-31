package com.finagent.fact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class UnitConverterTest {

    @Test
    @DisplayName("≥ 1 亿：换算成亿元、2 位小数、HALF_UP")
    void convertsToYi() {
        assertThat(UnitConverter.convert(new BigDecimal("16067741039.51")).text()).isEqualTo("160.68 亿元");
        assertThat(UnitConverter.convert(new BigDecimal("240327781.69")).text()).isEqualTo("2.40 亿元");
        assertThat(UnitConverter.convert(new BigDecimal("100000000")).text()).isEqualTo("1.00 亿元");
    }

    @Test
    @DisplayName("1000 万 ~ 1 亿：万元；其余原样元")
    void convertsToWanOrKeepsYuan() {
        assertThat(UnitConverter.convert(new BigDecimal("50000000")).text()).isEqualTo("5000.00 万元");
        assertThat(UnitConverter.convert(new BigDecimal("9999999")).text()).isEqualTo("9999999 元");
        assertThat(UnitConverter.convert(new BigDecimal("-12000000")).text()).isEqualTo("-1200.00 万元");
        assertThat(UnitConverter.convert(null).text()).isEmpty();
    }

    @Test
    @DisplayName("百分数与百分点：固定 2 位，绝不出现科学计数法")
    void formatsPercentAndPoints() {
        assertThat(UnitConverter.percent(new BigDecimal("38.46"))).isEqualTo("38.46%");
        assertThat(UnitConverter.percent(new BigDecimal("0.609213"))).isEqualTo("0.61%");
        assertThat(UnitConverter.percent(new BigDecimal("100"))).isEqualTo("100.00%");
        assertThat(UnitConverter.points(new BigDecimal("4.825"))).isEqualTo("4.83 个百分点");
        assertThat(UnitConverter.percent(null)).isEmpty();
    }

    @Test
    @DisplayName("plain：去千分位、去尾随 0，但不换单位")
    void plainKeepsMagnitude() {
        assertThat(UnitConverter.plain(new BigDecimal("16067741039.51"))).isEqualTo("16067741039.51");
        assertThat(UnitConverter.plain(new BigDecimal("0.7650"))).isEqualTo("0.765");
        assertThat(UnitConverter.plain(new BigDecimal("100.00"))).isEqualTo("100");
    }
}
