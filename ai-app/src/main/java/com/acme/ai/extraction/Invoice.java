package com.acme.ai.extraction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Target type for invoice extraction. The JSON schema sent to the model is generated from this
 * record (including the descriptions); Bean Validation then enforces the business rules the
 * schema cannot express, such as {@link TotalsMatch}.
 */
@TotalsMatch
public record Invoice(
		@NotBlank @JsonPropertyDescription("Legal name of the company that issued the invoice") String vendor,
		@NotBlank @JsonPropertyDescription("Invoice number exactly as printed") String invoiceNumber,
		@NotNull @JsonPropertyDescription("Invoice date, ISO-8601 (yyyy-MM-dd)") LocalDate invoiceDate,
		@JsonPropertyDescription("Due date ISO-8601 only if a date is printed; null if only payment terms are given") LocalDate dueDate,
		@NotBlank @Pattern(regexp = "[A-Z]{3}") @JsonPropertyDescription("ISO-4217 currency code, e.g. EUR") String currency,
		@NotEmpty List<@Valid LineItem> lineItems,
		@NotNull @PositiveOrZero @JsonPropertyDescription("Sum of line items before tax") BigDecimal subtotal,
		@NotNull @PositiveOrZero @JsonPropertyDescription("Total tax amount (0 if none)") BigDecimal tax,
		@NotNull @Positive @JsonPropertyDescription("Amount payable as stated on the invoice") BigDecimal total) {

	public record LineItem(@NotBlank String description, @NotNull @Positive BigDecimal quantity,
			@NotNull BigDecimal unitPrice, @NotNull @JsonPropertyDescription("quantity x unit price") BigDecimal amount) {
	}

}
