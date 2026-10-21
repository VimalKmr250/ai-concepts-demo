package com.acme.ai.extraction;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.math.BigDecimal;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * Cross-field rule: line items must add up to the subtotal, and subtotal + tax to the total
 * (within one cent). LLMs are bad at arithmetic and documents are sometimes wrong, so this is
 * checked in code, never trusted to the model.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = TotalsMatch.Validator.class)
@Documented
public @interface TotalsMatch {

	String message() default "amounts do not add up";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

	class Validator implements ConstraintValidator<TotalsMatch, Invoice> {

		private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

		@Override
		public boolean isValid(Invoice invoice, ConstraintValidatorContext context) {
			if (invoice == null || invoice.lineItems() == null || invoice.subtotal() == null || invoice.tax() == null
					|| invoice.total() == null) {
				return true; // @NotNull reports missing values
			}
			boolean valid = true;
			context.disableDefaultConstraintViolation();

			BigDecimal lineSum = invoice.lineItems()
				.stream()
				.map(Invoice.LineItem::amount)
				.filter(java.util.Objects::nonNull)
				.reduce(BigDecimal.ZERO, BigDecimal::add);
			if (lineSum.subtract(invoice.subtotal()).abs().compareTo(TOLERANCE) > 0) {
				context
					.buildConstraintViolationWithTemplate(
							"line items add up to %s but subtotal is %s".formatted(lineSum, invoice.subtotal()))
					.addPropertyNode("subtotal")
					.addConstraintViolation();
				valid = false;
			}
			BigDecimal expectedTotal = invoice.subtotal().add(invoice.tax());
			if (expectedTotal.subtract(invoice.total()).abs().compareTo(TOLERANCE) > 0) {
				context
					.buildConstraintViolationWithTemplate("subtotal %s + tax %s = %s but total is %s"
						.formatted(invoice.subtotal(), invoice.tax(), expectedTotal, invoice.total()))
					.addPropertyNode("total")
					.addConstraintViolation();
				valid = false;
			}
			return valid;
		}

	}

}
