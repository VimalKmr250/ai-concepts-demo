package com.acme.ai.extraction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import com.acme.ai.extraction.Invoice.LineItem;

import static org.assertj.core.api.Assertions.assertThat;

class TotalsMatchTest {

	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	private static Invoice invoice(String subtotal, String tax, String total) {
		return new Invoice("Northwind", "NW-1", LocalDate.of(2026, 10, 14), null, "GBP",
				List.of(new LineItem("Chair", new BigDecimal("10"), new BigDecimal("180.00"), new BigDecimal("1800.00")),
						new LineItem("Paper", new BigDecimal("25"), new BigDecimal("22.50"), new BigDecimal("562.50"))),
				new BigDecimal(subtotal), new BigDecimal(tax), new BigDecimal(total));
	}

	@Test
	void consistentInvoiceIsValid() {
		assertThat(this.validator.validate(invoice("2362.50", "472.50", "2835.00"))).isEmpty();
	}

	@Test
	void reportsTotalThatDoesNotAddUp() {
		Set<ConstraintViolation<Invoice>> violations = this.validator.validate(invoice("2362.50", "472.50", "2935.00"));

		assertThat(violations).extracting(v -> v.getPropertyPath().toString()).containsExactly("total");
		assertThat(violations.iterator().next().getMessage()).contains("but total is 2935.00");
	}

	@Test
	void reportsLineItemsThatDoNotMatchSubtotal() {
		Set<ConstraintViolation<Invoice>> violations = this.validator.validate(invoice("2400.00", "0", "2400.00"));

		assertThat(violations).extracting(v -> v.getPropertyPath().toString()).containsExactly("subtotal");
	}

	@Test
	void toleratesRoundingWithinOneCent() {
		assertThat(this.validator.validate(invoice("2362.50", "472.51", "2835.00"))).isEmpty();
	}

}
