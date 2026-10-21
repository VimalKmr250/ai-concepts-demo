package com.acme.ai.guardrails;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PiiRedactorTest {

	@Test
	void masksEmailCardAndPhone() {
		PiiRedactor.Result result = PiiRedactor.redact(
				"Card 4111 1111 1111 1111 was charged. Mail priya@example.com or call +44 20 7946 0958.");

		assertThat(result.text()).isEqualTo("Card [CARD] was charged. Mail [EMAIL] or call [PHONE].");
		assertThat(result.findings()).containsEntry("CARD", 1).containsEntry("EMAIL", 1).containsEntry("PHONE", 1);
	}

	@Test
	void masksIbanAndSsn() {
		PiiRedactor.Result result = PiiRedactor.redact("Pay to DE89 3704 0044 0532 0130 00, SSN 123-45-6789");

		assertThat(result.text()).isEqualTo("Pay to [IBAN], SSN [SSN]");
	}

	@Test
	void leavesNumbersThatFailTheLuhnCheckAlone() {
		// 16 digits but not a valid card number, and too long for a phone number
		PiiRedactor.Result result = PiiRedactor.redact("Order 1234 5678 9012 3456 shipped");

		assertThat(result.findings()).doesNotContainKey("CARD");
		assertThat(result.text()).contains("1234 5678 9012 3456");
	}

	@Test
	void leavesOrdinaryTextUntouched() {
		PiiRedactor.Result result = PiiRedactor.redact("How do I raise my overdraft limit to 500 EUR?");

		assertThat(result.found()).isFalse();
		assertThat(result.text()).isEqualTo("How do I raise my overdraft limit to 500 EUR?");
	}

	@Test
	void luhnCheck() {
		assertThat(PiiRedactor.passesLuhn("4111111111111111")).isTrue();
		assertThat(PiiRedactor.passesLuhn("4111111111111112")).isFalse();
	}

}
