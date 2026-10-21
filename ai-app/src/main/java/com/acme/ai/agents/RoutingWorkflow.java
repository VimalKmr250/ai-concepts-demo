package com.acme.ai.agents;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import com.acme.ai.config.AiClients;

/**
 * Pattern 2 - Routing. A classifier call (structured output) picks the category; the input is
 * then handled by a specialist prompt for that category. Keeps each specialist prompt simple
 * and lets you send easy categories to a cheaper model.
 * <p>
 * Use case: Acme Connect support-inbox triage.
 */
@Component
public class RoutingWorkflow {

	public enum Route {

		BILLING, TECHNICAL, ACCOUNT

	}

	/**
	 * The reason comes first so the model explains before it decides (better accuracy).
	 */
	public record RouteDecision(
			@JsonPropertyDescription("One sentence describing the customer's actual problem and why it fits the category") String reason,
			Route route) {
	}

	public record RoutingResult(Route route, String reason, String reply, long classifyMillis, long replyMillis) {
	}

	private static final Map<Route, String> SPECIALISTS = Map.of(Route.BILLING, """
			You are an Acme Connect billing specialist. You cannot see the customer's account, so never
			claim you checked it. Explain what typically causes the issue, say a billing agent will review
			the invoice, and state the next step (refunds take 5-7 business days). Sign as "Acme Billing".""",
			Route.TECHNICAL, """
					You are an Acme Connect technical support engineer. Give at most 3 numbered troubleshooting
					steps, then say a technician visit can be booked if they do not help. Sign as "Acme Tech Support".""",
			Route.ACCOUNT, """
					You are an Acme Connect account manager. Handle plan changes, moves, cancellations and
					personal details. Mention any identity check needed. Sign as "Acme Accounts".""");

	private final ChatClient chatClient;

	public RoutingWorkflow(AiClients aiClients) {
		this.chatClient = aiClients.forFeature("agent-routing").build();
	}

	public RoutingResult run(String email) {
		long start = System.nanoTime();
		RouteDecision decision = this.chatClient.prompt()
			.system("""
					Classify the customer email for an internet provider's support inbox:
					BILLING = invoices, charges, refunds, payments.
					TECHNICAL = outages, slow speed, Wi-Fi, router or device problems.
					ACCOUNT = plan changes, moving house, cancellation, personal details, login.
					Return JSON with two fields:
					"reason": one full sentence describing the customer's actual problem and why it fits;
					"route": BILLING, TECHNICAL or ACCOUNT.""")
			.user(email)
			.call()
			.entity(RouteDecision.class, spec -> spec.useProviderStructuredOutput());
		long classified = System.nanoTime();
		Route route = decision != null && decision.route() != null ? decision.route() : Route.TECHNICAL;

		String reply = this.chatClient.prompt()
			.system(SPECIALISTS.get(route) + "\nReply to the customer email in at most 120 words.")
			.user(email)
			.call()
			.content();
		long done = System.nanoTime();
		return new RoutingResult(route, decision != null ? decision.reason() : "fallback route", reply,
				(classified - start) / 1_000_000, (done - classified) / 1_000_000);
	}

}
