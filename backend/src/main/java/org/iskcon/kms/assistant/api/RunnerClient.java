package org.iskcon.kms.assistant.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Calls the runner's {@code POST /run} and hands each NDJSON line to the caller as it arrives
 * (E10-T10; the runner's contract is E10-T9 and {@code runner/README.md}).
 *
 * <p><strong>Why lines go through a queue.</strong> The JDK client delivers the body on its own
 * threads; the caller reads it on the request thread, which is where the temple is set for RLS and
 * where the browser's response is written. Between the two sits a queue, and the request thread
 * waits on it with the time left before the deadline. That is what makes the deadline exact: a
 * blocking read on the body would wait for as long as a stalled runner liked, and closing the stream
 * from a watchdog thread does not reliably wake a read that is already waiting.
 *
 * <p><strong>Stopping a run.</strong> At the deadline, or when the caller throws (the browser went
 * away), the body subscription and the exchange are cancelled. That closes the connection; the runner
 * sees its caller disconnect and kills {@code claude} and anything it started (T9). Nothing here
 * waits for that to finish.
 *
 * <p>How a run ended is returned as an {@link Ending} rather than thrown, because every ending is
 * normal from here: the caller turns each into a run status and, for the person, a KMS code.
 */
@Component
public class RunnerClient {

	private static final Logger log = LoggerFactory.getLogger(RunnerClient.class);

	/** How a call to the runner ended, as far as the transport can tell. */
	enum Ending {
		/** The runner answered 200 and the stream ended. What the stream said is the reader's to judge. */
		COMPLETED,
		/** Refused, unreachable, busy, or not a 200: the person is told the assistant is unavailable. */
		UNAVAILABLE,
		/** The api's own deadline passed with the runner still going. */
		TIMED_OUT
	}

	private final AssistantSettings settings;
	private final RunnerCredentials credentials;
	private final ObjectMapper json;

	public RunnerClient(AssistantSettings settings, RunnerCredentials credentials, ObjectMapper json) {
		this.settings = settings;
		this.credentials = credentials;
		this.json = json;
	}

	/**
	 * Runs one prompt. {@code onLine} is called on this thread for every line of a 200 response; if it
	 * throws, the run is cancelled and the exception propagates.
	 */
	Ending run(String prompt, String agentToken, Consumer<String> onLine) {
		Duration timeout = settings.runTimeout();
		long deadline = System.nanoTime() + timeout.toNanos();

		HttpRequest request;
		try {
			request = request(prompt, agentToken, timeout);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("could not write the runner request", e);
		}

		HttpClient client = HttpClient.newBuilder()
				// The runner is plain node:http. Asking it to upgrade to HTTP/2 buys nothing and
				// adds a round of negotiation to every run.
				.version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(settings.connectTimeout())
				.build();

		LineQueue lines = new LineQueue();
		CompletableFuture<HttpResponse<Void>> exchange = client.sendAsync(request, info -> {
			lines.queue.add(new Status(info.statusCode()));
			return HttpResponse.BodySubscribers.fromLineSubscriber(lines);
		});
		exchange.whenComplete((response, failure) -> {
			if (failure != null) {
				lines.queue.add(new Failed(failure));
			}
		});

		try {
			Integer status = null;
			while (true) {
				long left = deadline - System.nanoTime();
				Object next = left <= 0 ? null : lines.queue.poll(left, TimeUnit.NANOSECONDS);
				if (next == null) {
					log.warn("assistant runner: no end to the run within {}; cancelling it", timeout);
					return Ending.TIMED_OUT;
				}
				if (next instanceof Status s) {
					status = s.code();
					if (status != 200) {
						// 503 busy, 400 for a profile the runner does not know, 403 from Cloud Run
						// without an identity token: none of them is anything the person can fix.
						log.warn("assistant runner answered HTTP {}", status);
						return Ending.UNAVAILABLE;
					}
				} else if (next instanceof String line) {
					onLine.accept(line);
				} else if (next instanceof Failed f) {
					if (f.cause() instanceof java.net.http.HttpTimeoutException) {
						return Ending.TIMED_OUT;
					}
					log.warn("assistant runner could not be reached or broke off: {}", f.cause().toString());
					return status == null ? Ending.UNAVAILABLE : Ending.COMPLETED;
				} else if (next == END) {
					return status == null ? Ending.UNAVAILABLE : Ending.COMPLETED;
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Ending.UNAVAILABLE;
		} finally {
			// Harmless when the stream has already ended; essential when it has not.
			lines.cancel();
			exchange.cancel(true);
			client.shutdownNow();
		}
	}

	private HttpRequest request(String prompt, String agentToken, Duration timeout) throws JsonProcessingException {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("profile", settings.profile());
		body.put("prompt", prompt);
		body.put("mcpUrl", settings.mcpUrl());
		body.put("agentToken", agentToken);

		HttpRequest.Builder builder = HttpRequest.newBuilder(java.net.URI.create(settings.runnerUrl() + "/run"))
				.timeout(timeout)
				.header("Content-Type", "application/json")
				.header("Accept", "application/x-ndjson")
				.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
		credentials.authorizationHeader().ifPresent(value -> builder.header("Authorization", value));
		return builder.build();
	}

	private record Status(int code) {
	}

	private record Failed(Throwable cause) {
	}

	private static final Object END = new Object();

	/** The body's lines, handed from the client's threads to the request thread. */
	private static final class LineQueue implements Flow.Subscriber<String> {

		final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
		private volatile Flow.Subscription subscription;

		@Override
		public void onSubscribe(Flow.Subscription subscription) {
			this.subscription = subscription;
			subscription.request(Long.MAX_VALUE);
		}

		@Override
		public void onNext(String item) {
			queue.add(item);
		}

		@Override
		public void onError(Throwable throwable) {
			queue.add(new Failed(throwable));
		}

		@Override
		public void onComplete() {
			queue.add(END);
		}

		void cancel() {
			Flow.Subscription s = subscription;
			if (s != null) {
				s.cancel();
			}
		}
	}
}
