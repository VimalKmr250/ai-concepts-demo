package com.acme.ai.extraction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import com.acme.ai.config.AcmeProperties;
import com.acme.ai.config.AiClients;

/**
 * Demo 6 - structured extraction: unstructured text in, validated Java records out.
 * <p>
 * Two layers of safety:
 * <ol>
 * <li><b>Shape</b> - {@code entity(Invoice.class)} with provider-native structured output: the
 * JSON schema derived from the record is sent as {@code response_format}, so the model (or LM
 * Studio's grammar-constrained decoding) can only produce matching JSON;</li>
 * <li><b>Business rules</b> - Jakarta Bean Validation (e.g. {@link TotalsMatch}). Violations are
 * fed back to the model for another attempt; if the document itself is inconsistent the result
 * is flagged {@code needsReview} for a human instead of being "fixed" by the model.</li>
 * </ol>
 */
@Service
public class ExtractionService {

	private static final String INVOICE_SYSTEM = """
			You extract data from invoices received by Acme Corp's accounts-payable team.
			Copy values exactly as they appear in the document; never calculate or correct amounts.
			Convert numbers to plain decimals (1.234,56 -> 1234.56) and dates to ISO-8601.
			The vendor is the company that issued the invoice, not Acme.""";

	private static final String MEETING_SYSTEM = """
			You turn meeting transcripts into structured notes.
			Go through the transcript line by line: every first-person commitment ("I'll ...", "I will ...",
			"I'll take ...") is one action item owned by the speaker. Do not invent items nobody agreed to.
			Resolve relative dates against the meeting date: "by Friday" / "end of this week" means the
			Friday of the meeting's week, "next Wednesday" means the Wednesday of the following week,
			"end of the month" means the last day of that month.
			Priority: HIGH if it is urgent or blocking, LOW if the speaker says so, otherwise MEDIUM.""";

	private final ChatClient chatClient;

	private final Validator validator;

	private final int maxRetries;

	public ExtractionService(AiClients aiClients, Validator validator, AcmeProperties properties) {
		this.chatClient = aiClients.forFeature("extraction").build();
		this.validator = validator;
		this.maxRetries = properties.extraction().maxRetries();
	}

	public ExtractionResult<Invoice> extractInvoice(String text) {
		return extract(text, INVOICE_SYSTEM, Invoice.class);
	}

	public ExtractionResult<MeetingNotes> extractMeetingNotes(String text) {
		return extract(text, MEETING_SYSTEM, MeetingNotes.class);
	}

	private <T> ExtractionResult<T> extract(String text, String system, Class<T> type) {
		List<Attempt> attempts = new ArrayList<>();
		T result = null;
		List<String> issues = List.of();
		for (int attempt = 1; attempt <= this.maxRetries + 1; attempt++) {
			String user = attempts.isEmpty() ? text : """
					%s

					Your previous extraction violated these rules: %s
					Re-read the document carefully and correct any value you extracted wrongly.
					If the document itself contains these inconsistencies, keep the values exactly as written."""
				.formatted(text, issues);
			long start = System.nanoTime();
			result = this.chatClient.prompt()
				.system(system)
				.user(user)
				.call()
				.entity(type, spec -> spec.useProviderStructuredOutput());
			List<String> previous = issues;
			issues = violations(result);
			attempts.add(new Attempt(attempt, issues, (System.nanoTime() - start) / 1_000_000));
			if (issues.isEmpty()) {
				return new ExtractionResult<>(result, false, issues, attempts);
			}
			if (issues.equals(previous)) {
				break; // the model re-read the document and got the same values: the source is inconsistent
			}
		}
		// still invalid after retries: the source document is most likely wrong - escalate to a human
		return new ExtractionResult<>(result, true, issues, attempts);
	}

	private <T> List<String> violations(T value) {
		if (value == null) {
			return List.of("model returned no data");
		}
		Set<ConstraintViolation<T>> violations = this.validator.validate(value);
		return violations.stream()
			.sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
			.map(v -> v.getPropertyPath() + ": " + v.getMessage())
			.toList();
	}

	public record Attempt(int attempt, List<String> violations, long millis) {
	}

	/**
	 * @param needsReview true when validation still fails after all retries - route to a human
	 */
	public record ExtractionResult<T>(T data, boolean needsReview, List<String> issues, List<Attempt> attempts) {
	}

}
