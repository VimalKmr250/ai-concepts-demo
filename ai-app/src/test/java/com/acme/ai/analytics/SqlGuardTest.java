package com.acme.ai.analytics;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.acme.ai.guardrails.GuardrailViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlGuardTest {

	private final SqlGuard guard = new SqlGuard(Set.of("employee", "asset", "ticket"), 200);

	@Test
	void acceptsJoinsAndAddsALimit() {
		SqlGuard.Checked checked = this.guard.check("""
				SELECT e.full_name, a.asset_tag FROM asset a JOIN employee e ON e.username = a.assigned_to
				WHERE a.warranty_until < CURRENT_DATE""");

		assertThat(checked.tables()).containsExactlyInAnyOrder("asset", "employee");
		assertThat(checked.sql()).endsWith("LIMIT 200");
		assertThat(checked.notes()).contains("added LIMIT 200");
	}

	@Test
	void acceptsCtesWithoutTreatingThemAsTables() {
		SqlGuard.Checked checked = this.guard.check("""
				WITH open_tickets AS (SELECT * FROM ticket WHERE status <> 'RESOLVED')
				SELECT priority, count(*) FROM open_tickets GROUP BY priority LIMIT 10""");

		assertThat(checked.tables()).containsExactly("ticket");
		assertThat(checked.sql()).contains("LIMIT 10");
	}

	@Test
	void clampsLargeLimits() {
		SqlGuard.Checked checked = this.guard.check("SELECT * FROM ticket LIMIT 5000");

		assertThat(checked.sql()).endsWith("LIMIT 200");
		assertThat(checked.notes()).contains("LIMIT 5000 clamped to 200");
	}

	@Test
	void acceptsSchemaQualifiedAndQuotedNames() {
		assertThat(this.guard.check("SELECT count(*) FROM public.\"ticket\"").tables()).containsExactly("ticket");
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"DELETE FROM ticket WHERE status = 'RESOLVED'                   | not-a-select",
			"UPDATE employee SET manager = NULL                             | not-a-select",
			"DROP TABLE ticket                                              | not-a-select",
			"SELECT 1; DELETE FROM ticket                                   | multiple-statements",
			"SELECT * FROM spring_ai_chat_memory                            | table-not-allowed",
			"SELECT * FROM ticket t JOIN audit_log a ON true                | table-not-allowed",
			"SELECT pg_sleep(10) FROM ticket                                | denied-function",
			"SELECT pg_read_file('/etc/passwd')                             | denied-function",
			"SELECT * FROM ticket FOR UPDATE                                | row-locking",
			"SELECT * INTO ticket_copy FROM ticket                          | select-into",
			"this is not sql                                                | unparseable" })
	void rejects(String sql, String rule) {
		assertThatThrownBy(() -> this.guard.check(sql)).isInstanceOf(GuardrailViolationException.class)
			.satisfies(ex -> assertThat(((GuardrailViolationException) ex).getRule()).isEqualTo(rule));
	}

}
