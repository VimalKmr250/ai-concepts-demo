package com.acme.ai.a2a;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

import io.a2a.A2A;
import io.a2a.client.transport.jsonrpc.JSONRPCTransport;
import io.a2a.spec.A2AClientException;
import io.a2a.spec.AgentCard;
import io.a2a.spec.Artifact;
import io.a2a.spec.DataPart;
import io.a2a.spec.EventKind;
import io.a2a.spec.Message;
import io.a2a.spec.MessageSendParams;
import io.a2a.spec.Part;
import io.a2a.spec.Task;
import io.a2a.spec.TextPart;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.acme.ai.config.AcmeProperties;
import com.acme.ai.config.ApiExceptionHandler.ServiceUnavailableException;

/**
 * Demo 12 - talks to the procurement team's agent over A2A with the official A2A Java SDK.
 * <p>
 * Discovery: the agent card is read from {@code /.well-known/agent-card.json} on first use (not at
 * startup, so ai-app runs without the other agent). Messaging: a JSON-RPC {@code message/send}
 * through the SDK's {@link JSONRPCTransport}; the agent answers with a Task (state, history,
 * artifacts). A task in {@code input-required} is continued by sending its taskId again.
 */
@Component
public class ProcurementAgentClient {

	static final String START_AGENT_HINT = "Start it with: ./mvnw -pl procurement-agent spring-boot:run (port 8082)";

	private static final int RECENT = 10;

	private final String baseUrl;

	private volatile AgentCard card;

	private final Deque<Exchange> recent = new ConcurrentLinkedDeque<>();

	public ProcurementAgentClient(AcmeProperties properties) {
		this.baseUrl = properties.a2a().procurementUrl();
	}

	public String baseUrl() {
		return this.baseUrl;
	}

	/** Discovery: who is the agent, where do I send messages, what can it do. */
	public AgentCard card() {
		AgentCard current = this.card;
		if (current == null) {
			try {
				current = A2A.getAgentCard(this.baseUrl);
			}
			catch (RuntimeException ex) {
				// A2AClientError (connection, HTTP status) and A2AClientJSONError are unchecked
				throw new ServiceUnavailableException(
						"The procurement agent is not reachable at " + this.baseUrl + ": " + ex.getMessage(),
						START_AGENT_HINT, ex);
			}
			this.card = current;
		}
		return current;
	}

	/**
	 * Sends one A2A message and waits for the task (blocking {@code message/send}).
	 * @param taskId null for a new task, or the id of a task waiting in {@code input-required}
	 * @param caller who asked, shown on the demo page ("demo page", "helpdesk agent")
	 */
	public Exchange send(String text, String taskId, String contextId, String caller) {
		AgentCard agent = card();
		Message.Builder message = new Message.Builder().role(Message.Role.USER).parts(List.of(new TextPart(text)));
		if (StringUtils.hasText(taskId)) {
			message.taskId(taskId).contextId(contextId);
		}
		WireTap wire = new WireTap();
		JSONRPCTransport transport = new JSONRPCTransport(wire, agent, agent.url(), null);
		long start = System.nanoTime();
		try {
			EventKind result = transport.sendMessage(new MessageSendParams.Builder().message(message.build()).build(),
					null);
			Exchange exchange = new Exchange(Instant.now().toString(), caller, text, TaskView.of(result),
					(System.nanoTime() - start) / 1_000_000, wire.request(), wire.response());
			remember(exchange);
			return exchange;
		}
		catch (A2AClientException ex) {
			if (wire.response() == null) {
				this.card = null; // read the card again next time; the agent may have moved or restarted
				throw new ServiceUnavailableException("The procurement agent did not answer: " + ex.getMessage(),
						START_AGENT_HINT, ex);
			}
			throw new AgentCallException("The procurement agent returned an error: " + ex.getMessage(), wire.request(),
					wire.response(), ex);
		}
	}

	/** Latest first: what this app sent to other agents, including calls made by the helpdesk agent. */
	public List<Exchange> recent() {
		return List.copyOf(this.recent);
	}

	private void remember(Exchange exchange) {
		this.recent.addFirst(exchange);
		while (this.recent.size() > RECENT) {
			this.recent.pollLast();
		}
	}

	/**
	 * One request/response, with the raw JSON-RPC payloads for the demo page.
	 */
	public record Exchange(String at, String caller, String text, TaskView task, long elapsedMs, String request,
			String response) {
	}

	/**
	 * The parts of an A2A task the UI and the helpdesk agent need. The SDK types stay inside this
	 * package.
	 */
	public record TaskView(String id, String contextId, String state, String statusMessage, List<Step> timeline,
			List<ArtifactView> artifacts) {

		static TaskView of(EventKind result) {
			if (result instanceof Message reply) {
				// an agent may also answer with a plain message instead of a task
				return new TaskView(null, reply.getContextId(), "message", textOf(reply.getParts()),
						List.of(Step.of(reply)), List.of());
			}
			Task task = (Task) result;
			List<Message> messages = new ArrayList<>(task.getHistory() == null ? List.of() : task.getHistory());
			if (task.getStatus().message() != null) {
				messages.add(task.getStatus().message());
			}
			Set<String> seen = new HashSet<>();
			List<Step> timeline = messages.stream().filter(m -> seen.add(m.getMessageId())).map(Step::of).toList();
			List<ArtifactView> artifacts = task.getArtifacts() == null ? List.of()
					: task.getArtifacts().stream().map(ArtifactView::of).toList();
			return new TaskView(task.getId(), task.getContextId(), task.getStatus().state().asString(),
					task.getStatus().message() == null ? null : textOf(task.getStatus().message().getParts()), timeline,
					artifacts);
		}

		public boolean waitingForInput() {
			return "input-required".equals(this.state);
		}

		/** Text of the first artifact (the agent's summary), or the status message. */
		public String summary() {
			return this.artifacts.stream()
				.map(ArtifactView::text)
				.filter(StringUtils::hasText)
				.findFirst()
				.orElse(this.statusMessage);
		}

		/** All structured data the agent returned (data parts). */
		public List<Map<String, Object>> data() {
			return this.artifacts.stream().map(ArtifactView::data).filter(d -> !d.isEmpty()).toList();
		}

	}

	/**
	 * @param state the task state the agent reported with this message (from its metadata)
	 */
	public record Step(String role, String state, String at, String text) {

		static Step of(Message message) {
			Map<String, Object> meta = message.getMetadata() == null ? Map.of() : message.getMetadata();
			return new Step(message.getRole().asString(), (String) meta.getOrDefault("state", ""),
					(String) meta.getOrDefault("at", ""), textOf(message.getParts()));
		}

	}

	public record ArtifactView(String name, String text, Map<String, Object> data) {

		static ArtifactView of(Artifact artifact) {
			Map<String, Object> data = new LinkedHashMap<>();
			artifact.parts().stream().filter(DataPart.class::isInstance).forEach(p -> data.putAll(((DataPart) p).getData()));
			return new ArtifactView(artifact.name(), textOf(artifact.parts()), data);
		}

	}

	private static String textOf(List<Part<?>> parts) {
		return parts == null ? ""
				: parts.stream()
					.filter(TextPart.class::isInstance)
					.map(p -> ((TextPart) p).getText())
					.collect(Collectors.joining("\n"));
	}

	/**
	 * The agent answered, but with a JSON-RPC error.
	 */
	public static class AgentCallException extends RuntimeException {

		private final String request;

		private final String response;

		AgentCallException(String message, String request, String response, Throwable cause) {
			super(message, cause);
			this.request = request;
			this.response = response;
		}

		public String request() {
			return this.request;
		}

		public String response() {
			return this.response;
		}

	}

}
