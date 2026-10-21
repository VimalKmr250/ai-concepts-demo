package com.acme.ai.mcp;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import com.acme.ai.config.AiClients;
import com.acme.ai.config.ApiExceptionHandler.ServiceUnavailableException;
import com.acme.ai.support.ToolTrace;
import com.acme.ai.support.TracingToolCallback;

/**
 * Demo 7 - MCP client. The app discovers tools, resources and prompts at runtime from the Acme
 * Travel Desk MCP server (a separate process) and hands the tools to the model, exactly like
 * local {@code @Tool} methods. New server capabilities need no change in this app.
 */
@Service
public class TravelAssistantService {

	static final String START_SERVER_HINT = "Start it with: ./mvnw -pl mcp-server spring-boot:run (port 8081)";

	private final ChatClient chatClient;

	private final SyncMcpToolCallbackProvider mcpTools;

	private final List<McpSyncClient> mcpClients;

	public TravelAssistantService(AiClients aiClients, SyncMcpToolCallbackProvider mcpTools,
			List<McpSyncClient> mcpClients) {
		this.chatClient = aiClients.forFeature("mcp")
			.defaultSystem("""
					You are Acme Corp's travel assistant. Today is {today}. Use the travel desk tools for
					exchange rates, local times and public holidays - never guess these values.
					Answer in at most 6 sentences.""")
			.build();
		this.mcpTools = mcpTools;
		this.mcpClients = mcpClients;
	}

	public TravelAnswer ask(String question) {
		ToolTrace trace = new ToolTrace();
		String answer = this.chatClient.prompt()
			.system(s -> s.param("today", java.time.LocalDate.now().toString()))
			.user(question)
			.tools(tracedTools(trace))
			.call()
			.content();
		return new TravelAnswer(answer, trace.entries());
	}

	/**
	 * Fetches the server-defined "trip-briefing" prompt template, then runs it with the tools.
	 */
	public TravelAnswer tripBriefing(String city, String date, String budget) {
		Map<String, Object> args = new LinkedHashMap<>();
		args.put("city", city);
		args.put("date", date);
		if (budget != null && !budget.isBlank()) {
			args.put("budget", budget);
		}
		McpSchema.GetPromptResult prompt = withServer(
				() -> client().getPrompt(new McpSchema.GetPromptRequest("trip-briefing", args)));
		String promptText = prompt.messages()
			.stream()
			.map(McpSchema.PromptMessage::content)
			.filter(McpSchema.TextContent.class::isInstance)
			.map(c -> ((McpSchema.TextContent) c).text())
			.collect(Collectors.joining("\n"));
		TravelAnswer answer = ask(promptText);
		return new TravelAnswer(answer.answer(), answer.toolTrace(), promptText);
	}

	public ServerCatalog catalog() {
		return withServer(() -> {
			McpSyncClient client = client();
			if (!client.isInitialized()) {
				client.initialize();
			}
			McpSchema.Implementation server = client.getServerInfo();
			List<Item> tools = client.listTools().tools().stream().map(t -> new Item(t.name(), t.description())).toList();
			List<Item> resources = client.listResources()
				.resources()
				.stream()
				.map(r -> new Item(r.uri(), r.description()))
				.toList();
			List<Item> prompts = client.listPrompts()
				.prompts()
				.stream()
				.map(p -> new Item(p.name(), p.description()))
				.toList();
			return new ServerCatalog(server.name() + " " + server.version(), tools, resources, prompts);
		});
	}

	public String readResource(String uri) {
		McpSchema.ReadResourceResult result = withServer(
				() -> client().readResource(new McpSchema.ReadResourceRequest(uri)));
		return result.contents()
			.stream()
			.filter(McpSchema.TextResourceContents.class::isInstance)
			.map(c -> ((McpSchema.TextResourceContents) c).text())
			.collect(Collectors.joining("\n"));
	}

	private List<ToolCallback> tracedTools(ToolTrace trace) {
		ToolCallback[] callbacks = withServer(this.mcpTools::getToolCallbacks);
		return Arrays.stream(callbacks).map(cb -> (ToolCallback) new TracingToolCallback(cb, trace, "mcp")).toList();
	}

	private McpSyncClient client() {
		if (this.mcpClients.isEmpty()) {
			throw new ServiceUnavailableException("No MCP client connection configured", START_SERVER_HINT, null);
		}
		return this.mcpClients.get(0);
	}

	private static <T> T withServer(java.util.function.Supplier<T> call) {
		try {
			return call.get();
		}
		catch (ServiceUnavailableException ex) {
			throw ex;
		}
		catch (RuntimeException ex) {
			throw new ServiceUnavailableException("The Travel Desk MCP server is not reachable: " + ex.getMessage(),
					START_SERVER_HINT, ex);
		}
	}

	public record TravelAnswer(String answer, List<ToolTrace.Entry> toolTrace, String promptFromServer) {

		TravelAnswer(String answer, List<ToolTrace.Entry> toolTrace) {
			this(answer, toolTrace, null);
		}

	}

	public record Item(String name, String description) {
	}

	public record ServerCatalog(String server, List<Item> tools, List<Item> resources, List<Item> prompts) {
	}

}
