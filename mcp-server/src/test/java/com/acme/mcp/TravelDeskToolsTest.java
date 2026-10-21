package com.acme.mcp;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TravelDeskToolsTest {

	private final TravelDeskTools tools = new TravelDeskTools();

	@Test
	void convertsViaEuroRates() {
		var result = this.tools.convertCurrency(new BigDecimal("500"), "usd", "JPY");

		// 500 USD / 1.08 * 162.50
		assertThat(result.converted()).isEqualByComparingTo("75231.48");
		assertThat(result.from()).isEqualTo("USD");
	}

	@Test
	void rejectsUnknownCurrency() {
		assertThatThrownBy(() -> this.tools.convertCurrency(BigDecimal.ONE, "XYZ", "EUR"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Supported");
	}

	@Test
	void checksPublicHolidays() {
		var germanUnity = this.tools.publicHolidays("de", "2026-10-03");
		var christmasEveTokyo = this.tools.publicHolidays("JP", "2026-12-24");

		assertThat(germanUnity.isPublicHoliday()).isTrue();
		assertThat(germanUnity.holidayName()).isEqualTo("Tag der Deutschen Einheit");
		assertThat(christmasEveTokyo.isPublicHoliday()).isFalse();
	}

	@Test
	void reportsLocalTime() {
		var tokyo = this.tools.worldTime("Tokyo");

		assertThat(tokyo.timeZone()).isEqualTo("Asia/Tokyo");
		assertThat(tokyo.utcOffset()).isEqualTo("+09:00");
	}

	@Test
	void fxResourceListsRates() {
		assertThat(this.tools.fxRates()).contains("1 EUR = 162.50 JPY");
	}

}
