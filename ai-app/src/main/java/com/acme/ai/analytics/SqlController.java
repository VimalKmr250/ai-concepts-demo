package com.acme.ai.analytics;

import java.net.URI;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.acme.ai.analytics.SqlAssistantService.SqlAnswer;
import com.acme.ai.analytics.SqlAssistantService.SqlBlockedException;
import com.acme.ai.analytics.SqlAssistantService.SqlFailedException;

@RestController
@RequestMapping("/api/sql")
public class SqlController {

	private final SqlAssistantService assistant;

	private final SchemaCatalog schema;

	public SqlController(SqlAssistantService assistant, SchemaCatalog schema) {
		this.assistant = assistant;
		this.schema = schema;
	}

	@PostMapping("/ask")
	public SqlAnswer ask(@Valid @RequestBody SqlQuestion question) {
		return this.assistant.ask(question.question());
	}

	/** The schema description the model receives (built from Postgres comments). */
	@GetMapping(path = "/schema", produces = "text/plain")
	public String schema() {
		return this.schema.describe();
	}

	/** Same 400 problem as every guardrail, plus the SQL the model wrote. */
	@ExceptionHandler(SqlBlockedException.class)
	ProblemDetail blocked(SqlBlockedException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
		problem.setType(URI.create("https://acme.example/problems/guardrail-violation"));
		problem.setTitle("Blocked by guardrail");
		problem.setProperty("guard", ex.getGuard());
		problem.setProperty("rule", ex.getRule());
		problem.setProperty("sql", ex.getSql());
		problem.setProperty("reasoning", ex.getReasoning());
		return problem;
	}

	@ExceptionHandler(SqlFailedException.class)
	ProblemDetail failed(SqlFailedException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
		problem.setTitle("Query failed");
		problem.setProperty("attempts", ex.getAttempts());
		return problem;
	}

	public record SqlQuestion(@NotBlank @Size(max = 500) String question) {
	}

}
