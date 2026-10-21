package com.acme.ai.analytics;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;

import com.acme.ai.guardrails.GuardrailViolationException;

/**
 * Validates model-generated SQL before it touches the database. The model is not trusted:
 * whatever the prompt said, only a single read-only SELECT over allowlisted tables may run.
 * <p>
 * Parsing into an AST (JSqlParser) is far more reliable than string checks: comments, casing
 * and formatting tricks don't hide a DELETE or a forbidden table from the parser.
 */
public class SqlGuard {

	/** Functions that read files, change settings, sleep or reach other databases. */
	private static final Pattern DENIED_FUNCTIONS = Pattern.compile(
			"\\b(pg_\\w+|dblink\\w*|lo_\\w+|set_config|current_setting|query_to_xml\\w*|copy)\\s*\\(",
			Pattern.CASE_INSENSITIVE);

	private final Set<String> allowedTables;

	private final int maxRows;

	public SqlGuard(Set<String> allowedTables, int maxRows) {
		this.allowedTables = allowedTables.stream().map(t -> t.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
		this.maxRows = maxRows;
	}

	/**
	 * @return the SQL to execute (possibly with a clamped LIMIT) and notes about what changed
	 * @throws GuardrailViolationException when the SQL must not run
	 */
	public Checked check(String sql) {
		if (sql == null || sql.isBlank()) {
			throw violation("empty", "The model did not produce a query.");
		}
		Statements statements;
		try {
			statements = CCJSqlParserUtil.parseStatements(sql);
		}
		catch (JSQLParserException ex) {
			throw violation("unparseable", "The generated SQL could not be parsed, so it was not run.");
		}
		if (statements.size() != 1) {
			throw violation("multiple-statements", "Only a single statement is allowed.");
		}
		Statement statement = statements.get(0);
		if (!(statement instanceof Select select)) {
			throw violation("not-a-select",
					"Only read-only SELECT queries are allowed; this was a " + statement.getClass().getSimpleName()
						.toUpperCase(Locale.ROOT) + ".");
		}
		if (select.getForMode() != null) {
			throw violation("row-locking", "SELECT … FOR UPDATE/SHARE takes locks and is not allowed.");
		}
		if (select instanceof PlainSelect plain && plain.getIntoTables() != null && !plain.getIntoTables().isEmpty()) {
			throw violation("select-into", "SELECT … INTO creates a table and is not allowed.");
		}
		Matcher fn = DENIED_FUNCTIONS.matcher(sql);
		if (fn.find()) {
			throw violation("denied-function", "The function " + fn.group(1) + "() is not allowed.");
		}

		Set<String> tables = new TablesNamesFinder<>().getTables((Statement) select)
			.stream()
			.map(SqlGuard::normalize)
			.collect(Collectors.toSet());
		List<String> forbidden = tables.stream().filter(t -> !this.allowedTables.contains(t)).sorted().toList();
		if (!forbidden.isEmpty()) {
			throw violation("table-not-allowed",
					"Not allowed to read " + forbidden + ". Allowed tables: " + this.allowedTables.stream().sorted().toList()
							+ ".");
		}

		List<String> notes = new ArrayList<>();
		notes.add("parsed: single SELECT");
		notes.add("tables allowed: " + tables.stream().sorted().toList());
		Limit limit = select.getLimit();
		Long current = limit == null || limit.getRowCount() == null ? null
				: limit.getRowCount() instanceof LongValue lv ? lv.getValue() : null;
		if (current == null || current > this.maxRows) {
			select.setLimit(new Limit().withRowCount(new LongValue(this.maxRows)));
			notes.add(current == null ? "added LIMIT " + this.maxRows : "LIMIT " + current + " clamped to " + this.maxRows);
			return new Checked(select.toString(), tables, notes);
		}
		return new Checked(sql.strip().replaceAll(";\\s*$", ""), tables, notes);
	}

	private static String normalize(String table) {
		String name = table.contains(".") ? table.substring(table.lastIndexOf('.') + 1) : table;
		return name.replace("\"", "").toLowerCase(Locale.ROOT);
	}

	private static GuardrailViolationException violation(String rule, String message) {
		return new GuardrailViolationException("sql-guard", rule, message);
	}

	/**
	 * @param sql the statement that will run
	 * @param tables tables it reads
	 * @param notes what the guard checked or changed, shown in the UI
	 */
	public record Checked(String sql, Set<String> tables, List<String> notes) {
	}

}
