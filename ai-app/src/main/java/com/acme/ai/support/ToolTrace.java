package com.acme.ai.support;

import java.util.ArrayList;
import java.util.List;

/**
 * Records every tool an agent executed during one request, for display in the UI.
 */
public class ToolTrace {

	private final List<Entry> entries = new ArrayList<>();

	public synchronized void add(String tool, String arguments, String result, String mode) {
		this.entries.add(new Entry(tool, arguments, result, mode));
	}

	public synchronized List<Entry> entries() {
		return List.copyOf(this.entries);
	}

	/**
	 * @param mode {@code auto} (safe tool), {@code approved} / {@code rejected} (human decision),
	 * or {@code mcp} (remote tool on an MCP server)
	 */
	public record Entry(String tool, String arguments, String result, String mode) {
	}

}
