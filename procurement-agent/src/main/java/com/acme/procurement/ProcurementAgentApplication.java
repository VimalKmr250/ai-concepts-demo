package com.acme.procurement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Acme Procurement Agent - a separate AI agent owned by the procurement team.
 * <p>
 * Other agents reach it over the A2A (Agent2Agent) protocol: they read its agent card at
 * {@code /.well-known/agent-card.json} and send it a JSON-RPC {@code message/send} at {@code /}.
 * Inside, it is an ordinary Spring AI tool-calling agent; callers never see its tools, prompt or
 * model - only the task it returns.
 */
@SpringBootApplication
public class ProcurementAgentApplication {

	public static void main(String[] args) {
		SpringApplication.run(ProcurementAgentApplication.class, args);
	}

}
