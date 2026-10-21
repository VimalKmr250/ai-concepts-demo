package com.acme.ai.a2a;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import io.a2a.client.http.A2AHttpClient;
import io.a2a.client.http.A2AHttpResponse;
import io.a2a.client.http.JdkA2AHttpClient;

/**
 * Wraps the A2A SDK's HTTP client and keeps the raw JSON-RPC request and response of the last
 * POST, so the demo page can show exactly what went over the wire. One instance per call.
 */
class WireTap implements A2AHttpClient {

	private final A2AHttpClient delegate = new JdkA2AHttpClient();

	private volatile String request;

	private volatile String response;

	String request() {
		return this.request;
	}

	String response() {
		return this.response;
	}

	@Override
	public GetBuilder createGet() {
		return this.delegate.createGet();
	}

	@Override
	public DeleteBuilder createDelete() {
		return this.delegate.createDelete();
	}

	@Override
	public PostBuilder createPost() {
		PostBuilder post = this.delegate.createPost();
		return new PostBuilder() {

			@Override
			public PostBuilder url(String url) {
				post.url(url);
				return this;
			}

			@Override
			public PostBuilder addHeaders(Map<String, String> headers) {
				post.addHeaders(headers);
				return this;
			}

			@Override
			public PostBuilder addHeader(String name, String value) {
				post.addHeader(name, value);
				return this;
			}

			@Override
			public PostBuilder body(String body) {
				WireTap.this.request = body;
				post.body(body);
				return this;
			}

			@Override
			public A2AHttpResponse post() throws IOException, InterruptedException {
				A2AHttpResponse result = post.post();
				WireTap.this.response = result.body();
				return result;
			}

			@Override
			public CompletableFuture<Void> postAsyncSSE(Consumer<String> messageConsumer,
					Consumer<Throwable> errorConsumer, Runnable completeRunnable) throws IOException, InterruptedException {
				return post.postAsyncSSE(messageConsumer, errorConsumer, completeRunnable);
			}

		};
	}

}
