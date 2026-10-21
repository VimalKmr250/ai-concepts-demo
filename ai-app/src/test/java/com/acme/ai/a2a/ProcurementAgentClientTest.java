package com.acme.ai.a2a;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.acme.ai.a2a.ProcurementAgentClient.AgentCallException;
import com.acme.ai.a2a.ProcurementAgentClient.Exchange;
import com.acme.ai.a2a.ProcurementAgentClient.Step;
import com.acme.ai.config.AcmeProperties;
import com.acme.ai.config.ApiExceptionHandler.ServiceUnavailableException;
import com.acme.ai.support.TestFixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The A2A client against a stub agent on a real HTTP socket, so the SDK's HTTP client, JSON-RPC
 * envelope and (de)serialization all run.
 */
class ProcurementAgentClientTest {

	private static final String COMPLETED_TASK = """
			{"jsonrpc":"2.0","id":"1","result":{"kind":"task","id":"t-1","contextId":"c-1",
			 "status":{"state":"completed","timestamp":"2026-10-21T10:00:05Z",
			   "message":{"kind":"message","role":"agent","messageId":"m3","parts":[{"kind":"text","text":"Finished: raised PR-3101"}],
			              "metadata":{"state":"completed","at":"2026-10-21T10:00:05Z"}}},
			 "history":[
			   {"kind":"message","role":"user","messageId":"m1","parts":[{"kind":"text","text":"Order a laptop"}]},
			   {"kind":"message","role":"agent","messageId":"m2","parts":[{"kind":"text","text":"Checking the Engineering budget for 1350 EUR"}],
			    "metadata":{"state":"working","at":"2026-10-21T10:00:02Z"}}],
			 "artifacts":[
			   {"artifactId":"a1","name":"summary","parts":[{"kind":"text","text":"PR-3101 approved."}]},
			   {"artifactId":"a2","name":"purchase-request","parts":[{"kind":"data","data":{"purchaseRequests":[{"number":"PR-3101"}]}}]}]}}
			""";

	private HttpServer server;

	private volatile String lastRequest;

	private volatile String nextResponse = COMPLETED_TASK;

	private ProcurementAgentClient client;

	@BeforeEach
	void startStubAgent() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		String base = "http://localhost:" + this.server.getAddress().getPort();
		this.server.createContext("/.well-known/agent-card.json", ex -> respond(ex, """
				{"name":"Stub Procurement Agent","description":"test","url":"%s/","version":"1.0.0",
				 "capabilities":{"streaming":false,"pushNotifications":false,"stateTransitionHistory":true},
				 "defaultInputModes":["text"],"defaultOutputModes":["text"],
				 "skills":[{"id":"hardware-purchase","name":"Hardware purchase","description":"buys","tags":["x"]}],
				 "preferredTransport":"JSONRPC","protocolVersion":"0.3.0"}""".formatted(base)));
		this.server.createContext("/", ex -> {
			this.lastRequest = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			respond(ex, this.nextResponse);
		});
		this.server.start();
		this.client = new ProcurementAgentClient(withUrl(base));
	}

	@AfterEach
	void stop() {
		this.server.stop(0);
	}

	private static AcmeProperties withUrl(String url) {
		AcmeProperties p = TestFixtures.properties();
		return new AcmeProperties(p.llm(), p.rag(), p.guardrails(), p.extraction(), p.agents(), p.evaluation(), p.sql(),
				p.cache(), new AcmeProperties.A2a(url));
	}

	private static void respond(HttpExchange exchange, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

	@Test
	void discoversTheAgentAndReturnsTheTask() {
		assertThat(this.client.card().name()).isEqualTo("Stub Procurement Agent");

		Exchange exchange = this.client.send("Order a laptop", null, null, "demo page");

		assertThat(this.lastRequest).contains("\"method\":\"message/send\"", "\"role\":\"user\"", "Order a laptop")
			.doesNotContain("taskId");
		assertThat(exchange.request()).isEqualTo(this.lastRequest);
		assertThat(exchange.response()).isEqualTo(COMPLETED_TASK);
		assertThat(exchange.task().state()).isEqualTo("completed");
		assertThat(exchange.task().timeline()).extracting(Step::role, Step::state, Step::text)
			.containsExactly(org.assertj.core.groups.Tuple.tuple("user", "", "Order a laptop"),
					org.assertj.core.groups.Tuple.tuple("agent", "working", "Checking the Engineering budget for 1350 EUR"),
					org.assertj.core.groups.Tuple.tuple("agent", "completed", "Finished: raised PR-3101"));
		assertThat(exchange.task().summary()).isEqualTo("PR-3101 approved.");
		assertThat(exchange.task().data()).containsExactly(Map.of("purchaseRequests", List.of(Map.of("number", "PR-3101"))));
		assertThat(this.client.recent()).containsExactly(exchange);
	}

	@Test
	void answeringATaskThatWaitsForInputReusesItsIds() {
		this.client.send("Marketing pays", "t-1", "c-1", "demo page");

		assertThat(this.lastRequest).contains("\"taskId\":\"t-1\"", "\"contextId\":\"c-1\"");
	}

	@Test
	void jsonRpcErrorsKeepTheWirePayloads() {
		this.nextResponse = """
				{"jsonrpc":"2.0","id":"1","error":{"code":-32603,"message":"Agent execution failed: boom"}}""";

		assertThatThrownBy(() -> this.client.send("Order a laptop", null, null, "demo page"))
			.isInstanceOfSatisfying(AgentCallException.class, ex -> {
				assertThat(ex.getMessage()).contains("boom");
				assertThat(ex.response()).contains("-32603");
			});
	}

	@Test
	void anAbsentAgentIsA503WithAHint() {
		this.server.stop(0);
		ProcurementAgentClient offline = new ProcurementAgentClient(withUrl("http://localhost:1"));

		assertThatThrownBy(offline::card).isInstanceOfSatisfying(ServiceUnavailableException.class,
				ex -> assertThat(ex.getHint()).contains("procurement-agent"));
	}

}
