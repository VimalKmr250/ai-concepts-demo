package com.acme.ai.evaluation;

import java.util.List;
import java.util.Locale;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.Ordered;

/**
 * Spring AI's evaluators expect the judge to answer exactly "yes"/"no". Small models like to
 * add punctuation or explanations ("Yes. The response..."), so this advisor reduces the judge's
 * answer to the leading yes/no word.
 */
class VerdictNormalizerAdvisor implements CallAdvisor {

	@Override
	public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
		ChatClientResponse response = chain.nextCall(request);
		ChatResponse chatResponse = response.chatResponse();
		if (chatResponse == null || chatResponse.getResult() == null
				|| chatResponse.getResult().getOutput().getText() == null) {
			return response;
		}
		String text = chatResponse.getResult().getOutput().getText().strip().toLowerCase(Locale.ROOT);
		String verdict = text.startsWith("yes") ? "yes" : text.startsWith("no") ? "no" : text;
		ChatResponse normalized = ChatResponse.builder()
			.from(chatResponse)
			.generations(List.of(new Generation(new AssistantMessage(verdict), chatResponse.getResult().getMetadata())))
			.build();
		return response.mutate().chatResponse(normalized).build();
	}

	@Override
	public String getName() {
		return "VerdictNormalizerAdvisor";
	}

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE + 10;
	}

}
