package com.acme.ai.guardrails;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic PII detection and masking. Runs before any text leaves the application, so
 * the model provider (and the chat memory table) never sees raw card numbers, emails, etc.
 * <p>
 * Regex + checksum detectors are cheap and predictable; production systems often add an NER
 * model or a DLP service on top.
 */
public final class PiiRedactor {

	private record Detector(String label, Pattern pattern, Predicate<String> validator) {
	}

	private static final List<Detector> DETECTORS = List.of(
			new Detector("EMAIL", Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), s -> true),
			new Detector("IBAN", Pattern.compile("\\b[A-Z]{2}\\d{2}(?: ?[A-Z0-9]{4}){2,7}(?: ?[A-Z0-9]{1,4})?\\b"),
					s -> true),
			new Detector("CARD", Pattern.compile("(?<![\\w-])(?:\\d[ -]?){12,18}\\d(?![\\w-])"),
					PiiRedactor::passesLuhn),
			new Detector("SSN", Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b"), s -> true),
			new Detector("PHONE", Pattern.compile("(?<![\\w+])\\+?\\(?\\d[\\d ()-]{6,18}\\d(?!\\w)"),
					s -> digits(s).length() >= 8 && digits(s).length() <= 15));

	private PiiRedactor() {
	}

	public static Result redact(String text) {
		if (text == null || text.isBlank()) {
			return new Result(text, Map.of());
		}
		String current = text;
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (Detector detector : DETECTORS) {
			Matcher matcher = detector.pattern().matcher(current);
			StringBuilder out = new StringBuilder();
			while (matcher.find()) {
				if (detector.validator().test(matcher.group())) {
					counts.merge(detector.label(), 1, Integer::sum);
					matcher.appendReplacement(out, "[" + detector.label() + "]");
				}
				else {
					matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
				}
			}
			matcher.appendTail(out);
			current = out.toString();
		}
		return new Result(current, counts);
	}

	static boolean passesLuhn(String candidate) {
		String digits = digits(candidate);
		if (digits.length() < 13 || digits.length() > 19) {
			return false;
		}
		int sum = 0;
		boolean doubleIt = false;
		for (int i = digits.length() - 1; i >= 0; i--) {
			int d = digits.charAt(i) - '0';
			if (doubleIt) {
				d *= 2;
				if (d > 9) {
					d -= 9;
				}
			}
			sum += d;
			doubleIt = !doubleIt;
		}
		return sum % 10 == 0;
	}

	private static String digits(String s) {
		return s.replaceAll("\\D", "");
	}

	/**
	 * @param text redacted text
	 * @param findings PII type to number of occurrences masked
	 */
	public record Result(String text, Map<String, Integer> findings) {

		public boolean found() {
			return !this.findings.isEmpty();
		}

		public List<String> labels() {
			return new ArrayList<>(this.findings.keySet());
		}

	}

}
