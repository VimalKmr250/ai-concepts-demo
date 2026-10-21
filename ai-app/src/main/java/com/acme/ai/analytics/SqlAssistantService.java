package com.acme.ai.analytics;

import java.sql.ResultSetMetaData;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.acme.ai.config.AcmeProperties;
import com.acme.ai.config.AiClients;
import com.acme.ai.guardrails.GuardrailViolationException;

/**
 * Demo 10 - Text-to-SQL. A question in plain English becomes a SQL query over the helpdesk
 * data, which is validated, executed read-only and explained.
 * <p>
 * Defense in depth: the prompt asks for read-only SQL, {@link SqlGuard} enforces it on the
 * parsed statement, and the database enforces it again with a read-only transaction and a
 * statement timeout. Any one layer is enough to stop a write.
 */
@Service
public class SqlAssistantService {

	private static final String GENERATE = """
			You translate questions about Acme's IT helpdesk data into ONE PostgreSQL query.
			Today is {today}.

			Schema (only these tables exist for you):
			{schema}

			Rules:
			- Write a single SELECT statement. Never modify data.
			- Use only the tables and columns above; join on the referenced keys.
			- Prefer readable column aliases, e.g. count(*) AS open_tickets.
			- Compare enum-like columns with the exact uppercase values listed in the comments.

			Return JSON with two fields:
			"reasoning": one sentence on how the query answers the question;
			"sql": the PostgreSQL query, without a trailing semicolon.""";

	private static final String EXPLAIN = """
			Answer the user's question in at most 3 sentences using ONLY the query result below.
			If the result is empty, say that no matching data was found. Do not mention SQL.""";

	private final ChatClient chatClient;

	private final SchemaCatalog schema;

	private final SqlGuard guard;

	private final JdbcClient jdbc;

	private final TransactionTemplate readOnlyTx;

	private final int timeoutSeconds;

	private final JsonMapper json = JsonMapper.builder().build();

	public SqlAssistantService(AiClients aiClients, SchemaCatalog schema, JdbcClient jdbc,
			PlatformTransactionManager txManager, AcmeProperties properties) {
		this.chatClient = aiClients.forFeature("text-to-sql").build();
		this.schema = schema;
		this.jdbc = jdbc;
		this.guard = new SqlGuard(new HashSet<>(properties.sql().allowedTables()), properties.sql().maxRows());
		this.timeoutSeconds = properties.sql().timeoutSeconds();
		this.readOnlyTx = new TransactionTemplate(txManager);
		this.readOnlyTx.setReadOnly(true);
	}

	public SqlAnswer ask(String question) {
		List<Attempt> attempts = new ArrayList<>();
		String feedback = null;
		for (int attempt = 1; attempt <= 2; attempt++) {
			SqlQuery query = generate(question, feedback);
			String generated = query == null ? null : query.sql();
			SqlGuard.Checked checked;
			try {
				checked = this.guard.check(generated);
			}
			catch (GuardrailViolationException ex) {
				throw new SqlBlockedException(ex, generated, query == null ? null : query.reasoning());
			}
			try {
				QueryResult result = execute(checked.sql());
				attempts.add(new Attempt(attempt, generated, null));
				String answer = explain(question, result);
				return new SqlAnswer(question, query.reasoning(), checked.sql(), checked.notes(), result.columns(),
						result.rows(), result.rows().size(), answer, attempts);
			}
			catch (DataAccessException ex) {
				String error = rootMessage(ex);
				attempts.add(new Attempt(attempt, generated, error));
				feedback = "Your previous query failed.%nQuery: %s%nDatabase error: %s%nFix the query.".formatted(generated,
						error);
			}
		}
		throw new SqlFailedException("The model could not produce a working query after 2 attempts.", attempts);
	}

	/** Exposed for tests: run SQL exactly the way model output is run. */
	QueryResult execute(String sql) {
		return this.readOnlyTx.execute(status -> {
			// belt and braces: the transaction is read-only even if the guard were bypassed
			this.jdbc.sql("SET TRANSACTION READ ONLY").update();
			this.jdbc.sql("SET LOCAL statement_timeout = '" + this.timeoutSeconds + "s'").update();
			List<String> columns = new ArrayList<>();
			List<Map<String, Object>> rows = this.jdbc.sql(sql).query((rs, n) -> {
				ResultSetMetaData md = rs.getMetaData();
				if (columns.isEmpty()) {
					columns.addAll(uniqueLabels(md));
				}
				Map<String, Object> row = new LinkedHashMap<>();
				for (int i = 1; i <= md.getColumnCount(); i++) {
					Object value = rs.getObject(i);
					row.put(columns.get(i - 1), value instanceof Number || value instanceof Boolean || value == null
							? value : String.valueOf(value));
				}
				return row;
			}).list();
			return new QueryResult(columns, rows);
		});
	}

	/** A join with {@code SELECT *} repeats names like id or status; number the repeats. */
	private static List<String> uniqueLabels(ResultSetMetaData md) throws java.sql.SQLException {
		List<String> labels = new ArrayList<>();
		Map<String, Integer> seen = new HashMap<>();
		for (int i = 1; i <= md.getColumnCount(); i++) {
			String label = md.getColumnLabel(i);
			int count = seen.merge(label, 1, Integer::sum);
			labels.add(count == 1 ? label : label + "_" + count);
		}
		return labels;
	}

	private SqlQuery generate(String question, String feedback) {
		return this.chatClient.prompt()
			.system(s -> s.text(GENERATE).param("today", LocalDate.now().toString()).param("schema", this.schema.describe()))
			.user(feedback == null ? question : question + "\n\n" + feedback)
			.call()
			.entity(SqlQuery.class, spec -> spec.useProviderStructuredOutput());
	}

	private String explain(String question, QueryResult result) {
		List<Map<String, Object>> sample = result.rows().stream().limit(50).toList();
		return this.chatClient.prompt()
			.system(EXPLAIN)
			.user("Question: %s%n%nQuery result (%d rows, first %d shown as JSON):%n%s".formatted(question,
					result.rows().size(), sample.size(), this.json.writeValueAsString(sample)))
			.call()
			.content();
	}

	private static String rootMessage(Throwable ex) {
		Throwable t = ex;
		while (t.getCause() != null && t.getCause() != t) {
			t = t.getCause();
		}
		String message = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
		return message.lines().findFirst().orElse(message);
	}

	public record SqlQuery(
			@JsonPropertyDescription("One sentence on how the query answers the question") String reasoning,
			@JsonPropertyDescription("A single PostgreSQL SELECT statement") String sql) {
	}

	record QueryResult(List<String> columns, List<Map<String, Object>> rows) {
	}

	/** @param error database error of this attempt, or null if it ran */
	public record Attempt(int attempt, String sql, String error) {
	}

	public record SqlAnswer(String question, String reasoning, String sql, List<String> guardNotes,
			List<String> columns, List<Map<String, Object>> rows, int rowCount, String answer,
			List<Attempt> attempts) {
	}

	public static class SqlFailedException extends RuntimeException {

		private final transient List<Attempt> attempts;

		SqlFailedException(String message, List<Attempt> attempts) {
			super(message);
			this.attempts = attempts;
		}

		public List<Attempt> getAttempts() {
			return this.attempts;
		}

	}

	/**
	 * The guard refused the generated query; carries the SQL so the caller can see what was blocked.
	 */
	public static class SqlBlockedException extends GuardrailViolationException {

		private final String sql;

		private final String reasoning;

		SqlBlockedException(GuardrailViolationException cause, String sql, String reasoning) {
			super(cause.getGuard(), cause.getRule(), cause.getMessage());
			this.sql = sql;
			this.reasoning = reasoning;
		}

		public String getSql() {
			return this.sql;
		}

		public String getReasoning() {
			return this.reasoning;
		}

	}

}
