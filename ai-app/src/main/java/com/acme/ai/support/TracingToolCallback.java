package com.acme.ai.support;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Decorates any {@link ToolCallback} (local or MCP) to record its input and output in a
 * {@link ToolTrace}.
 */
public class TracingToolCallback implements ToolCallback {

	private final ToolCallback delegate;

	private final ToolTrace trace;

	private final String mode;

	public TracingToolCallback(ToolCallback delegate, ToolTrace trace, String mode) {
		this.delegate = delegate;
		this.trace = trace;
		this.mode = mode;
	}

	@Override
	public ToolDefinition getToolDefinition() {
		return this.delegate.getToolDefinition();
	}

	@Override
	public ToolMetadata getToolMetadata() {
		return this.delegate.getToolMetadata();
	}

	@Override
	public String call(String toolInput) {
		return call(toolInput, null);
	}

	@Override
	public String call(String toolInput, ToolContext toolContext) {
		String result;
		try {
			result = this.delegate.call(toolInput, toolContext);
		}
		catch (RuntimeException ex) {
			this.trace.add(getToolDefinition().name(), toolInput, "ERROR: " + ex.getMessage(), this.mode);
			throw ex;
		}
		this.trace.add(getToolDefinition().name(), toolInput, result, this.mode);
		return result;
	}

}
