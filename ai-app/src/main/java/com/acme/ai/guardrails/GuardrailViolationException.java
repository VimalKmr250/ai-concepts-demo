package com.acme.ai.guardrails;

import com.acme.ai.observability.ExpectedOutcome;

/**
 * Thrown by an input guardrail when a request must be rejected outright (HTTP 400).
 */
public class GuardrailViolationException extends RuntimeException implements ExpectedOutcome {

	private final String guard;

	private final String rule;

	public GuardrailViolationException(String guard, String rule, String message) {
		super(message);
		this.guard = guard;
		this.rule = rule;
	}

	public String getGuard() {
		return this.guard;
	}

	@Override
	public String outcome() {
		return "blocked";
	}

	public String getRule() {
		return this.rule;
	}

}
