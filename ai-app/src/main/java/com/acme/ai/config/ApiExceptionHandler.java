package com.acme.ai.config;

import java.net.ConnectException;
import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.acme.ai.guardrails.GuardrailViolationException;

/**
 * Maps failures to RFC 9457 problem details. LLM connectivity problems get an actionable hint,
 * because "LM Studio isn't running / model not loaded" is the most common local-dev failure.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	private final String llmBaseUrl;

	private final String chatModel;

	public ApiExceptionHandler(@Value("${spring.ai.openai.base-url:}") String llmBaseUrl,
			@Value("${spring.ai.openai.chat.model:}") String chatModel) {
		this.llmBaseUrl = llmBaseUrl;
		this.chatModel = chatModel;
	}

	@ExceptionHandler(GuardrailViolationException.class)
	ProblemDetail guardrail(GuardrailViolationException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
		problem.setType(URI.create("https://acme.example/problems/guardrail-violation"));
		problem.setTitle("Blocked by guardrail");
		problem.setProperty("guard", ex.getGuard());
		problem.setProperty("rule", ex.getRule());
		return problem;
	}

	@ExceptionHandler(NotFoundException.class)
	ProblemDetail notFound(NotFoundException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
	}

	@ExceptionHandler(ServiceUnavailableException.class)
	ProblemDetail unavailable(ServiceUnavailableException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
		problem.setProperty("hint", ex.getHint());
		return problem;
	}

	@ExceptionHandler(RuntimeException.class)
	ProblemDetail unexpected(RuntimeException ex) {
		Throwable guardrail = findCause(ex, GuardrailViolationException.class);
		if (guardrail != null) {
			return guardrail((GuardrailViolationException) guardrail);
		}
		if (isLlmConnectivityProblem(ex)) {
			log.warn("LLM call failed: {}", ex.toString());
			ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
					"The language model could not be reached or rejected the request: " + rootMessage(ex));
			problem.setTitle("LLM unavailable");
			problem.setProperty("hint", "Is the LLM server running at %s with model '%s' loaded?"
				.formatted(this.llmBaseUrl, this.chatModel));
			return problem;
		}
		log.error("Unhandled error", ex);
		return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, rootMessage(ex));
	}

	private static boolean isLlmConnectivityProblem(Throwable ex) {
		for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
			// provider-neutral check: no compile-time dependency on the OpenAI SDK
			if (t instanceof ConnectException || t.getClass().getName().startsWith("com.openai.errors.")) {
				return true;
			}
		}
		return false;
	}

	private static Throwable findCause(Throwable ex, Class<? extends Throwable> type) {
		for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
			if (type.isInstance(t)) {
				return t;
			}
		}
		return null;
	}

	private static String rootMessage(Throwable ex) {
		Throwable t = ex;
		while (t.getCause() != null && t.getCause() != t) {
			t = t.getCause();
		}
		return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
	}

	public static class NotFoundException extends RuntimeException {

		public NotFoundException(String message) {
			super(message);
		}

	}

	public static class ServiceUnavailableException extends RuntimeException {

		private final String hint;

		public ServiceUnavailableException(String message, String hint, Throwable cause) {
			super(message, cause);
			this.hint = hint;
		}

		public String getHint() {
			return this.hint;
		}

	}

}
