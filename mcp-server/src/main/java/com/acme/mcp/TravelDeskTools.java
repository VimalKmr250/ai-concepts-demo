package com.acme.mcp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import com.acme.mcp.TravelData.Holiday;

/**
 * The Travel Desk capabilities exposed over MCP: three tools, one resource and one prompt.
 */
@Component
public class TravelDeskTools {

	// ----------------------------------------------------------------------------- tools

	@McpTool(name = "convert_currency", description = "Convert an amount between currencies using Acme's travel-policy exchange rates.",
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
	public ConversionResult convertCurrency(@McpToolParam(description = "Amount to convert") BigDecimal amount,
			@McpToolParam(description = "ISO currency code to convert from, e.g. USD") String from,
			@McpToolParam(description = "ISO currency code to convert to, e.g. JPY") String to) {
		BigDecimal fromRate = rate(from);
		BigDecimal toRate = rate(to);
		BigDecimal converted = amount.divide(fromRate, 10, RoundingMode.HALF_UP)
			.multiply(toRate)
			.setScale(2, RoundingMode.HALF_UP);
		BigDecimal unitRate = toRate.divide(fromRate, 6, RoundingMode.HALF_UP);
		return new ConversionResult(amount, from.toUpperCase(Locale.ROOT), converted, to.toUpperCase(Locale.ROOT),
				unitRate, "Acme travel-policy rates (sample data)");
	}

	@McpTool(name = "world_time", description = "Current local date and time in a city, with its UTC offset.",
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
	public CityTime worldTime(@McpToolParam(description = "City name, e.g. Tokyo") String city) {
		ZoneId zone = TravelData.CITY_ZONES.get(city.trim().toLowerCase(Locale.ROOT));
		if (zone == null) {
			throw new IllegalArgumentException(
					"Unknown city '%s'. Known cities: %s".formatted(city, TravelData.CITY_ZONES.keySet()));
		}
		ZonedDateTime now = ZonedDateTime.now(zone);
		return new CityTime(city, zone.getId(), now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
				now.getOffset().getId(), now.getDayOfWeek().toString());
	}

	@McpTool(name = "public_holidays", description = "Check whether a date is a public holiday in a country and list holidays within 14 days of it (2026 calendar for JP, DE, GB, US, IN).",
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
	public HolidayCheck publicHolidays(
			@McpToolParam(description = "ISO 3166 two-letter country code, e.g. JP") String countryCode,
			@McpToolParam(description = "Date to check, ISO-8601 yyyy-MM-dd") String date) {
		String country = countryCode.trim().toUpperCase(Locale.ROOT);
		List<Holiday> holidays = TravelData.HOLIDAYS_2026.get(country);
		if (holidays == null) {
			throw new IllegalArgumentException(
					"No holiday calendar for '%s'. Available: %s".formatted(countryCode, TravelData.HOLIDAYS_2026.keySet()));
		}
		LocalDate day = LocalDate.parse(date.trim());
		String holidayName = holidays.stream().filter(h -> h.date().equals(day)).map(Holiday::name).findFirst().orElse(null);
		List<String> nearby = holidays.stream()
			.filter(h -> !h.date().equals(day) && Math.abs(h.date().toEpochDay() - day.toEpochDay()) <= 14)
			.map(h -> h.date() + " " + h.name())
			.toList();
		return new HolidayCheck(country, day.toString(), holidayName != null, holidayName, nearby);
	}

	// ------------------------------------------------------------------------- resources

	@McpResource(uri = "travel://fx-rates", name = "fx-rates", title = "Travel policy exchange rates",
			description = "Exchange rates (units per 1 EUR) used for Acme travel expense conversions", mimeType = "text/plain")
	public String fxRates() {
		return TravelData.EUR_RATES.entrySet()
			.stream()
			.map(e -> "1 EUR = %s %s".formatted(e.getValue().toPlainString(), e.getKey()))
			.collect(Collectors.joining("\n"));
	}

	// --------------------------------------------------------------------------- prompts

	@McpPrompt(name = "trip-briefing", title = "Trip briefing",
			description = "Prepare a short pre-trip briefing for an Acme employee travelling on business")
	public String tripBriefing(@McpArg(name = "city", description = "Destination city", required = true) String city,
			@McpArg(name = "date", description = "Travel date (yyyy-MM-dd)", required = true) String date,
			@McpArg(name = "budget", description = "Daily budget with currency, e.g. 200 USD") String budget) {
		return """
				Prepare a concise pre-trip briefing for a business trip to %s on %s.
				Use the travel desk tools to: (1) check whether the date is a public holiday at the
				destination, (2) give the current local time there, and (3) convert the daily budget of %s
				into the local currency. Finish with three practical tips."""
			.formatted(city, date, budget == null || budget.isBlank() ? "100 EUR" : budget);
	}

	private static BigDecimal rate(String currency) {
		BigDecimal rate = TravelData.EUR_RATES.get(currency.trim().toUpperCase(Locale.ROOT));
		if (rate == null) {
			throw new IllegalArgumentException(
					"Unsupported currency '%s'. Supported: %s".formatted(currency, TravelData.EUR_RATES.keySet()));
		}
		return rate;
	}

	public record ConversionResult(BigDecimal amount, String from, BigDecimal converted, String to,
			BigDecimal rate, String source) {
	}

	public record CityTime(String city, String timeZone, String localDateTime, String utcOffset, String dayOfWeek) {
	}

	public record HolidayCheck(String country, String date, boolean isPublicHoliday, String holidayName,
			List<String> holidaysWithin14Days) {
	}

}
