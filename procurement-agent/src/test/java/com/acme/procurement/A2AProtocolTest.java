package com.acme.procurement;

import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The A2A wire protocol end to end (agent card, JSON-RPC {@code message/send}, task states,
 * history and artifacts), with a scripted model instead of an LLM.
 */
@SpringBootTest(properties = "spring.ai.model.chat=none")
@AutoConfigureMockMvc
@Import(A2AProtocolTest.ScriptedModelConfig.class)
class A2AProtocolTest {

	@TestConfiguration(proxyBeanMethods = false)
	static class ScriptedModelConfig {

		@Bean
		ScriptedChatModel chatModel() {
			return new ScriptedChatModel();
		}

	}

	@Autowired
	MockMvc mvc;

	@Autowired
	ScriptedChatModel model;

	@BeforeEach
	void reset() {
		this.model.reset();
	}

	private String send(String text, String taskId, String contextId) throws Exception {
		String ids = taskId == null ? "" : ",\"taskId\":\"%s\",\"contextId\":\"%s\"".formatted(taskId, contextId);
		String body = """
				{"jsonrpc":"2.0","id":"1","method":"message/send","params":{"message":{"kind":"message","role":"user",
				 "messageId":"%s","parts":[{"kind":"text","text":"%s"}]%s}}}""".formatted(UUID.randomUUID(), text, ids);
		MvcResult result = this.mvc.perform(post("/").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.jsonrpc").value("2.0"))
			.andExpect(jsonPath("$.id").value("1"))
			.andReturn();
		return result.getResponse().getContentAsString();
	}

	@Test
	void publishesTheAgentCard() throws Exception {
		this.mvc.perform(get("/.well-known/agent-card.json"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Acme Procurement Agent"))
			.andExpect(jsonPath("$.url").value("http://localhost:8082/"))
			.andExpect(jsonPath("$.preferredTransport").value("JSONRPC"))
			.andExpect(jsonPath("$.skills[0].id").value("hardware-purchase"));
	}

	@Test
	void completesATaskWithASummaryAndThePurchaseRequestAsData() throws Exception {
		this.model.callTool("searchCatalog", "{\"category\":\"laptop\"}")
			.callTool("createPurchaseRequest", """
					{"sku":"LAP-STD-14","quantity":1,"department":"Engineering","requestedFor":"mgarcia",
					 "justification":"LAP-1502 is broken"}""")
			.reply("<think></think>ThinkPad T14 for 1350 EUR, covered, PR raised.");

		String task = send("Order a replacement laptop for mgarcia (Engineering)", null, null);

		assertThat(JsonPath.<String>read(task, "$.result.kind")).isEqualTo("task");
		assertThat(JsonPath.<String>read(task, "$.result.status.state")).isEqualTo("completed");
		assertThat(JsonPath.<String>read(task, "$.result.artifacts[0].name")).isEqualTo("summary");
		// written from the ledger record, not from the model's prose
		assertThat(JsonPath.<String>read(task, "$.result.artifacts[0].parts[0].text")).startsWith("Raised PR-")
			.contains("1 x Lenovo ThinkPad T14 Gen 5 (LAP-STD-14) for 1350 EUR, paid by Engineering. Status APPROVED");
		assertThat(JsonPath.<String>read(task, "$.result.artifacts[1].parts[0].kind")).isEqualTo("data");
		assertThat(JsonPath.<String>read(task, "$.result.artifacts[1].parts[0].data.purchaseRequests[0].sku"))
			.isEqualTo("LAP-STD-14");
		// every tool call left a working status in the history: the caller's timeline
		assertThat(JsonPath.<java.util.List<String>>read(task, "$.result.history[*].metadata.state")).containsSubsequence(
				"submitted", "working", "working", "working", "working", "working");
		assertThat(this.model.prompts().get(0).getUserMessage().getText()).endsWith("Paying department: Engineering");
	}

	@Test
	void missingInformationParksTheTaskUntilTheCallerAnswers() throws Exception {
		String first = send("Order a laptop for tchen", null, null);

		assertThat(JsonPath.<String>read(first, "$.result.status.state")).isEqualTo("input-required");
		assertThat(JsonPath.<String>read(first, "$.result.status.message.parts[0].text"))
			.startsWith("Which department pays for this?");
		assertThat(JsonPath.<java.util.List<?>>read(first, "$.result.artifacts")).isNullOrEmpty();
		assertThat(this.model.prompts()).isEmpty(); // checked in code: the model never had a chance to guess

		this.model.reply("No PR needed after all.");
		String taskId = JsonPath.read(first, "$.result.id");
		String second = send("Marketing pays", taskId, JsonPath.read(first, "$.result.contextId"));

		assertThat(JsonPath.<String>read(second, "$.result.id")).isEqualTo(taskId);
		assertThat(JsonPath.<String>read(second, "$.result.status.state")).isEqualTo("completed");
		assertThat(this.model.prompts().get(0).getUserMessage().getText()).isEqualTo("""
				Order a laptop for tchen
				You asked: Which department pays for this? One of: Engineering, Finance, HR, Marketing, Operations, Sales, Support.
				The requester answered: Marketing pays
				Paying department: Marketing""");
	}

}
