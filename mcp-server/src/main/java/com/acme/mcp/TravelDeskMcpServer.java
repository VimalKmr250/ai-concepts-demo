package com.acme.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Acme Travel Desk - a standalone MCP server (Streamable HTTP at {@code /mcp}).
 * <p>
 * Any MCP client can use it: the ai-app showcase, Claude Desktop, Claude Code, IDE agents...
 * Tools, resources and prompts are plain Spring beans annotated with {@code @McpTool},
 * {@code @McpResource} and {@code @McpPrompt}.
 */
@SpringBootApplication
public class TravelDeskMcpServer {

	public static void main(String[] args) {
		SpringApplication.run(TravelDeskMcpServer.class, args);
	}

}
