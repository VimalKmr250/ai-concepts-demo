package com.acme.ai.analytics;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.acme.ai.analytics.SqlAssistantService.SqlAnswer;
import com.acme.ai.analytics.SqlAssistantService.SqlBlockedException;
import com.acme.ai.guardrails.GuardrailViolationException;
import com.acme.ai.support.PgVectorContainerConfig;
import com.acme.ai.support.StubChatModel;
import com.acme.ai.support.StubModelsConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Text-to-SQL against the real seeded Postgres, with a scripted model standing in for the LLM.
 */
@SpringBootTest(properties = { "spring.ai.model.chat=none", "spring.ai.model.embedding=none",
		"acme.rag.ingest-on-startup=false" })
@Import({ PgVectorContainerConfig.class, StubModelsConfig.class })
class TextToSqlIT {

	@Autowired
	SqlAssistantService assistant;

	@Autowired
	StubChatModel model;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void resetModel() {
		this.model.reset();
	}

	private void modelWritesSql(String sql) {
		this.model.reply("{\"reasoning\": \"test\", \"sql\": \"" + sql.replace("\"", "\\\"") + "\"}");
	}

	@Test
	void answersFromTheSeedData() {
		modelWritesSql("SELECT priority, count(*) AS tickets FROM ticket WHERE status <> 'RESOLVED' GROUP BY priority ORDER BY priority");
		this.model.reply("There are open tickets in every priority.");

		SqlAnswer answer = this.assistant.ask("How many open tickets per priority?");

		List<Map<String, Object>> expected = this.jdbc
			.sql("SELECT priority, count(*) AS tickets FROM ticket WHERE status <> 'RESOLVED' GROUP BY priority ORDER BY priority")
			.query()
			.listOfRows();
		assertThat(answer.columns()).containsExactly("priority", "tickets");
		assertThat(answer.rowCount()).isEqualTo(expected.size());
		assertThat(answer.sql()).endsWith("LIMIT 200");
		assertThat(answer.answer()).isEqualTo("There are open tickets in every priority.");
	}

	@Test
	void repairsAQueryThatFails() {
		modelWritesSql("SELECT no_such_column FROM ticket");
		modelWritesSql("SELECT count(*) AS total FROM ticket");
		this.model.reply("There are some tickets.");

		SqlAnswer answer = this.assistant.ask("How many tickets are there?");

		assertThat(answer.attempts()).hasSize(2);
		assertThat(answer.attempts().get(0).error()).contains("no_such_column");
		assertThat(this.model.prompts().get(1).getUserMessage().getText()).contains("Database error");
	}

	@Test
	void guardBlocksWritesBeforeTheDatabase() {
		modelWritesSql("DELETE FROM ticket WHERE status = 'RESOLVED'");

		assertThatThrownBy(() -> this.assistant.ask("Delete all resolved tickets"))
			.isInstanceOf(GuardrailViolationException.class)
			.isInstanceOfSatisfying(SqlBlockedException.class, ex -> {
				assertThat(ex.getRule()).isEqualTo("not-a-select");
				assertThat(ex.getSql()).startsWith("DELETE FROM ticket");
			});
	}

	@Test
	void databaseRefusesWritesEvenWithoutTheGuard() {
		int before = this.jdbc.sql("SELECT count(*) FROM ticket").query(Integer.class).single();

		assertThatThrownBy(() -> this.assistant.execute("DELETE FROM ticket")).isInstanceOf(DataAccessException.class)
			.hasMessageContaining("read-only");
		assertThat(this.jdbc.sql("SELECT count(*) FROM ticket").query(Integer.class).single()).isEqualTo(before);
	}

	@Test
	void repeatedColumnNamesDoNotOverwriteEachOther() {
		// models like SELECT * over joins; asset.id and employee.id must both survive
		var result = this.assistant.execute(
				"SELECT a.id, a.status, e.id, t.status FROM asset a JOIN employee e ON a.assigned_to = e.username "
						+ "JOIN ticket t ON t.username = e.username WHERE a.asset_tag = 'LAP-1502'");

		assertThat(result.columns()).containsExactly("id", "status", "id_2", "status_2");
		assertThat(result.rows()).allSatisfy(row -> assertThat(row).containsKeys("id", "id_2", "status", "status_2")
			.containsEntry("status", "IN_USE"));
	}

	@Test
	void schemaPromptCarriesBusinessMeaning() {
		modelWritesSql("SELECT 1 AS one FROM ticket");
		this.model.reply("ok");

		this.assistant.ask("anything");

		String system = this.model.prompts().get(0).getSystemMessage().getText();
		assertThat(system).contains("TABLE asset", "warranty_until", "Out of warranty means")
			.doesNotContain("spring_ai_chat_memory", "audit_log");
	}

}
