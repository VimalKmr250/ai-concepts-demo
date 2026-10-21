package com.acme.ai.chat;

import java.util.List;

import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

import com.acme.ai.config.AiClients;

/**
 * Demo 1 - Acme Connect customer-support assistant with persistent memory.
 * <p>
 * {@link MessageChatMemoryAdvisor} loads the last N messages of the conversation from
 * Postgres ({@code JdbcChatMemoryRepository}) before every call and saves the new turn
 * afterwards, so the assistant remembers the customer even after an app restart.
 */
@Service
public class SupportChatService {

	static final String SYSTEM_PROMPT = """
			You are "Ava", the customer-support assistant of Acme Connect, a home broadband provider.
			Plans: Basic (100 Mbps, EUR 25/month), Pro (500 Mbps + Wi-Fi 6 router, EUR 40/month),
			Ultra (1 Gbps + mesh Wi-Fi, EUR 60/month).
			Be friendly and concise (max 4 sentences unless troubleshooting steps are needed).
			Remember what the customer told you earlier in the conversation (name, plan, problem)
			and use it. Never invent account data you were not told.
			""";

	private final ChatClient chatClient;

	private final ChatMemoryRepository chatMemoryRepository;

	public SupportChatService(AiClients aiClients, ChatMemory chatMemory, ChatMemoryRepository chatMemoryRepository) {
		this.chatMemoryRepository = chatMemoryRepository;
		this.chatClient = aiClients.forFeature("chat")
			.defaultSystem(SYSTEM_PROMPT)
			.defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
			.build();
	}

	public String reply(String conversationId, String message) {
		return this.chatClient.prompt()
			.user(message)
			.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
			.call()
			.content();
	}

	public Flux<String> streamReply(String conversationId, String message) {
		return this.chatClient.prompt()
			.user(message)
			.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
			.stream()
			.content();
	}

	public List<Message> memory(String conversationId) {
		return this.chatMemoryRepository.findByConversationId(conversationId);
	}

	public void forget(String conversationId) {
		this.chatMemoryRepository.deleteByConversationId(conversationId);
	}

}
