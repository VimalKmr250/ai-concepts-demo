package com.acme.procurement;

import java.util.List;

import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentProvider;
import io.a2a.spec.AgentSkill;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class AgentConfig {

	static final String SYSTEM_PROMPT = """
			You are the Acme Corp procurement agent. Agents from other teams send you hardware requests;
			the paying department is given at the end of the request.
			Handle the request with your tools, in this order:
			1. searchCatalog for the right category and choose the item whose audience matches the request.
			2. createPurchaseRequest with that SKU, the quantity and the paying department.
			   It checks the budget itself and tells you whether the request was raised.
			Use only SKUs, prices and PR numbers returned by the tools; never invent them.
			Asset tags such as LAP-1502 name the person's old device; they are not catalog SKUs.
			Reply in at most four short sentences: the item and total price, the budget result,
			the PR number with its status, and the expected delivery date. Write amounts like "1350 EUR".""";

	@Bean
	ChatClient procurementChatClient(ChatClient.Builder builder, ProcurementTools tools,
			@Value("${acme.llm.system-prompt-suffix:}") String suffix) {
		return builder.defaultSystem(suffix.isBlank() ? SYSTEM_PROMPT : SYSTEM_PROMPT + "\n" + suffix)
			.defaultTools(tools)
			.build();
	}

	/**
	 * The agent card is this agent's public contract: who it is, where to reach it, how to talk to
	 * it and what it can do. Callers discover it at {@code /.well-known/agent-card.json}.
	 */
	@Bean
	AgentCard agentCard(@Value("${acme.agent.public-url}") String publicUrl) {
		AgentSkill purchase = new AgentSkill.Builder().id("hardware-purchase")
			.name("Hardware purchase")
			.description("Picks an approved catalog model, checks the paying department's quarterly budget and raises "
					+ "a purchase request. Requests over 2000 EUR wait for finance approval.")
			.tags(List.of("procurement", "hardware", "budget"))
			.examples(List.of(
					"Order a replacement laptop for mgarcia (Engineering): LAP-1502 is broken and out of warranty",
					"Buy 3 noise-cancelling headsets for the Support team"))
			.inputModes(List.of("text"))
			.outputModes(List.of("text", "application/json"))
			.build();
		return new AgentCard.Builder().name("Acme Procurement Agent")
			.description("Buys approved hardware for Acme employees. Owned by the procurement team.")
			.url(publicUrl)
			.version("1.0.0")
			.provider(new AgentProvider("Acme Corp - Procurement", "https://acme.example/procurement"))
			.capabilities(new AgentCapabilities.Builder().streaming(false)
				.pushNotifications(false)
				.stateTransitionHistory(true)
				.build())
			.defaultInputModes(List.of("text"))
			.defaultOutputModes(List.of("text", "application/json"))
			.skills(List.of(purchase))
			.build();
	}

}
