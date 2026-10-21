package com.acme.mcp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Static reference data for the demo (no external API keys needed). FX rates are fixed sample
 * values and the holiday calendar covers 2026 for a few countries - not for real travel planning.
 */
final class TravelData {

	private TravelData() {
	}

	/** Units of currency per 1 EUR (sample rates). */
	static final Map<String, BigDecimal> EUR_RATES = new TreeMap<>(Map.ofEntries(Map.entry("EUR", bd("1")),
			Map.entry("USD", bd("1.08")), Map.entry("GBP", bd("0.85")), Map.entry("JPY", bd("162.50")),
			Map.entry("INR", bd("90.20")), Map.entry("CHF", bd("0.95")), Map.entry("SGD", bd("1.45")),
			Map.entry("NGN", bd("1650.00")), Map.entry("PLN", bd("4.30"))));

	static final Map<String, ZoneId> CITY_ZONES = new TreeMap<>(Map.ofEntries(
			Map.entry("berlin", ZoneId.of("Europe/Berlin")), Map.entry("london", ZoneId.of("Europe/London")),
			Map.entry("madrid", ZoneId.of("Europe/Madrid")), Map.entry("warsaw", ZoneId.of("Europe/Warsaw")),
			Map.entry("zurich", ZoneId.of("Europe/Zurich")), Map.entry("new york", ZoneId.of("America/New_York")),
			Map.entry("san francisco", ZoneId.of("America/Los_Angeles")),
			Map.entry("tokyo", ZoneId.of("Asia/Tokyo")), Map.entry("singapore", ZoneId.of("Asia/Singapore")),
			Map.entry("bangalore", ZoneId.of("Asia/Kolkata")), Map.entry("lagos", ZoneId.of("Africa/Lagos"))));

	record Holiday(LocalDate date, String name) {
	}

	static final Map<String, List<Holiday>> HOLIDAYS_2026 = Map.of(
			"JP", List.of(h("2026-01-01", "New Year's Day"), h("2026-01-12", "Coming of Age Day"),
					h("2026-02-11", "National Foundation Day"), h("2026-02-23", "Emperor's Birthday"),
					h("2026-03-20", "Vernal Equinox Day"), h("2026-04-29", "Showa Day"),
					h("2026-05-03", "Constitution Memorial Day"), h("2026-05-04", "Greenery Day"),
					h("2026-05-05", "Children's Day"), h("2026-05-06", "Substitute Holiday"),
					h("2026-07-20", "Marine Day"), h("2026-08-11", "Mountain Day"),
					h("2026-09-21", "Respect for the Aged Day"), h("2026-09-22", "Citizens' Holiday"),
					h("2026-09-23", "Autumnal Equinox Day"), h("2026-10-12", "Sports Day"),
					h("2026-11-03", "Culture Day"), h("2026-11-23", "Labour Thanksgiving Day")),
			"DE", List.of(h("2026-01-01", "Neujahr"), h("2026-04-03", "Karfreitag"), h("2026-04-06", "Ostermontag"),
					h("2026-05-01", "Tag der Arbeit"), h("2026-05-14", "Christi Himmelfahrt"),
					h("2026-05-25", "Pfingstmontag"), h("2026-10-03", "Tag der Deutschen Einheit"),
					h("2026-12-25", "1. Weihnachtstag"), h("2026-12-26", "2. Weihnachtstag")),
			"GB", List.of(h("2026-01-01", "New Year's Day"), h("2026-04-03", "Good Friday"),
					h("2026-04-06", "Easter Monday"), h("2026-05-04", "Early May bank holiday"),
					h("2026-05-25", "Spring bank holiday"), h("2026-08-31", "Summer bank holiday"),
					h("2026-12-25", "Christmas Day"), h("2026-12-28", "Boxing Day (substitute)")),
			"US", List.of(h("2026-01-01", "New Year's Day"), h("2026-01-19", "Martin Luther King Jr. Day"),
					h("2026-02-16", "Presidents' Day"), h("2026-05-25", "Memorial Day"),
					h("2026-06-19", "Juneteenth"), h("2026-07-03", "Independence Day (observed)"),
					h("2026-09-07", "Labor Day"), h("2026-11-26", "Thanksgiving Day"),
					h("2026-12-25", "Christmas Day")),
			"IN", List.of(h("2026-01-26", "Republic Day"), h("2026-03-04", "Holi"),
					h("2026-08-15", "Independence Day"), h("2026-10-02", "Gandhi Jayanti"),
					h("2026-11-08", "Diwali"), h("2026-12-25", "Christmas Day")));

	private static Holiday h(String date, String name) {
		return new Holiday(LocalDate.parse(date), name);
	}

	private static BigDecimal bd(String value) {
		return new BigDecimal(value);
	}

}
