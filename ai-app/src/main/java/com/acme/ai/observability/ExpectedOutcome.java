package com.acme.ai.observability;

/**
 * Implemented by exceptions that end an AI request on purpose (a guardrail block, a pause for
 * human approval). Metrics record them as an outcome instead of as an error.
 */
public interface ExpectedOutcome {

	/** Low-cardinality outcome tag, e.g. {@code blocked} or {@code approval_pending}. */
	String outcome();

}
