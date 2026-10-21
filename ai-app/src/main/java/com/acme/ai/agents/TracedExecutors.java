package com.acme.ai.agents;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;

/**
 * Virtual-thread executor that carries the current observation (trace context) into each task,
 * so fanned-out LLM calls show up as children of the request's trace instead of new traces.
 */
final class TracedExecutors {

	private static final ContextSnapshotFactory SNAPSHOTS = ContextSnapshotFactory.builder().build();

	private TracedExecutors() {
	}

	static ExecutorService virtualThreads() {
		return ContextExecutorService.wrap(Executors.newVirtualThreadPerTaskExecutor(), SNAPSHOTS::captureAll);
	}

}
