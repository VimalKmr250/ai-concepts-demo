package com.acme.ai.analytics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.acme.ai.config.AcmeProperties;

/**
 * Describes the allowlisted tables to the model: columns, types and the business meaning stored
 * as Postgres comments (V3__analytics_comments.sql). Read from the live database, so the prompt
 * never drifts from the real schema.
 */
@Component
public class SchemaCatalog {

	private final JdbcClient jdbc;

	private final List<String> tables;

	private volatile String cached;

	public SchemaCatalog(JdbcClient jdbc, AcmeProperties properties) {
		this.jdbc = jdbc;
		this.tables = properties.sql().allowedTables();
	}

	public String describe() {
		String result = this.cached;
		if (result == null) {
			result = load();
			this.cached = result;
		}
		return result;
	}

	private String load() {
		Map<String, StringBuilder> byTable = new LinkedHashMap<>();
		this.jdbc.sql("""
				SELECT c.table_name, c.column_name, c.data_type, c.is_nullable,
				       obj_description(format('%I.%I', c.table_schema, c.table_name)::regclass, 'pg_class') AS table_comment,
				       col_description(format('%I.%I', c.table_schema, c.table_name)::regclass, c.ordinal_position) AS column_comment
				FROM information_schema.columns c
				WHERE c.table_schema = current_schema() AND c.table_name IN (:tables)
				ORDER BY c.table_name, c.ordinal_position""")
			.param("tables", this.tables)
			.query((rs, n) -> {
				String table = rs.getString("table_name");
				StringBuilder sb = byTable.computeIfAbsent(table, t -> {
					String comment = rsString(rs, "table_comment");
					return new StringBuilder("TABLE " + t + (comment.isEmpty() ? "" : "  -- " + comment) + "\n");
				});
				String comment = rsString(rs, "column_comment");
				sb.append("  ")
					.append(rs.getString("column_name"))
					.append(' ')
					.append(rs.getString("data_type"))
					.append("NO".equals(rs.getString("is_nullable")) ? " NOT NULL" : "")
					.append(comment.isEmpty() ? "" : "  -- " + comment)
					.append('\n');
				return table;
			})
			.list();
		return byTable.values().stream().map(StringBuilder::toString).collect(Collectors.joining("\n"));
	}

	private static String rsString(java.sql.ResultSet rs, String column) {
		try {
			String value = rs.getString(column);
			return value == null ? "" : value;
		}
		catch (java.sql.SQLException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
