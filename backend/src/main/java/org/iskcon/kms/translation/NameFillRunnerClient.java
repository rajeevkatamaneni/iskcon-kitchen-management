package org.iskcon.kms.translation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.iskcon.kms.assistant.api.RunnerCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Asks the runner's {@code name-fill} profile for a list of names and returns its structured answer
 * (E10-NAMES-A).
 *
 * <h2>Why not the assistant's {@code RunnerClient}</h2>
 *
 * <p>That client streams a conversation to a person who is waiting: a deadline measured in seconds,
 * each line handed on as it arrives, an agent token and the api's {@code /mcp} address in every
 * request. None of that fits here. A fill has nobody watching it, runs for a minute or more, takes
 * no tools and so no token, and its whole answer is one JSON line at the end. So this is a plain
 * request and response; it shares only the runner's address and the way the api proves who it is to
 * the runner on Cloud Run ({@link RunnerCredentials}), so the two cannot drift apart.
 *
 * <h2>The profile is the runner's, not ours</h2>
 *
 * <p>The system prompt, the JSON schema, the model and the absence of tools are fixed in
 * {@code runner/src/profiles.ts}. This sends only the profile's name and the message: the language
 * and the numbered items.
 */
@Component
public class NameFillRunnerClient {

	static final String PROFILE = "name-fill";

	private final RunnerCredentials credentials;
	private final ObjectMapper json;
	private volatile String runnerUrl;
	private final Duration timeout;
	private final Duration connectTimeout;

	public NameFillRunnerClient(
			RunnerCredentials credentials,
			ObjectMapper json,
			@Value("${kms.assistant.runner.url:http://localhost:8090}") String runnerUrl,
			// The runner kills a name-fill run at five minutes (its profile's timeoutMs); this is a
			// little longer, for a runner that has stopped answering altogether.
			@Value("${kms.translation.name-fill.timeout:PT330S}") Duration timeout,
			@Value("${kms.assistant.runner.connect-timeout:PT5S}") Duration connectTimeout) {
		this.credentials = credentials;
		this.json = json;
		this.runnerUrl = stripTrailingSlash(runnerUrl);
		this.timeout = timeout;
		this.connectTimeout = connectTimeout;
	}

	/**
	 * Runs the profile once and returns Claude's {@code structured_output}: an object with a
	 * {@code names} array, still to be checked item by item by the caller.
	 *
	 * @throws NameFillFailed for anything short of a successful run with an answer: the runner
	 *     unreachable, busy, refusing, timed out, or Claude finishing without a structured answer.
	 */
	JsonNode name(String message) {
		HttpRequest.Builder builder;
		try {
			builder = HttpRequest.newBuilder(URI.create(runnerUrl + "/run"))
					.timeout(timeout)
					.header("Content-Type", "application/json")
					.header("Accept", "application/x-ndjson")
					.POST(HttpRequest.BodyPublishers.ofString(
							json.writeValueAsString(Map.of("profile", PROFILE, "prompt", message))));
		} catch (IOException e) {
			throw new IllegalStateException("could not write the runner request", e);
		}
		credentials.authorizationHeader().ifPresent(value -> builder.header("Authorization", value));

		HttpClient client = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(connectTimeout)
				.build();
		HttpResponse<String> response;
		try {
			response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
		} catch (IOException e) {
			throw new NameFillFailed("runner unreachable or timed out: " + e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new NameFillFailed("interrupted while waiting for the runner");
		} finally {
			client.shutdownNow();
		}
		if (response.statusCode() != 200) {
			throw new NameFillFailed("runner answered HTTP " + response.statusCode());
		}
		return structuredOutput(response.body());
	}

	/**
	 * The {@code structured_output} of the run's result line. The runner passes {@code claude}'s
	 * single JSON result through as one line, and appends a {@code runner_error} line if the run did
	 * not end cleanly; either that or a result marked as an error means there is no answer to use.
	 */
	JsonNode structuredOutput(String ndjson) {
		JsonNode result = null;
		for (String line : ndjson.split("\n")) {
			if (line.isBlank()) {
				continue;
			}
			JsonNode node;
			try {
				node = json.readTree(line);
			} catch (IOException e) {
				throw new NameFillFailed("runner sent a line that is not JSON");
			}
			String type = node.path("type").asText();
			if ("runner_error".equals(type)) {
				throw new NameFillFailed("runner_error " + node.path("code").asText());
			}
			if ("result".equals(type)) {
				result = node;
			}
		}
		if (result == null) {
			throw new NameFillFailed("the run ended with no result");
		}
		if (result.path("is_error").asBoolean(false)) {
			throw new NameFillFailed("the run's result is an error: " + result.path("subtype").asText());
		}
		JsonNode out = result.get("structured_output");
		if (out == null || !out.isObject()) {
			throw new NameFillFailed("the run's result has no structured answer");
		}
		return out;
	}

	// --- For tests only, the same pattern as AssistantSettings: one context, a fake runner's port. ---

	void setRunnerUrl(String url) {
		this.runnerUrl = stripTrailingSlash(url);
	}

	String runnerUrl() {
		return runnerUrl;
	}

	private static String stripTrailingSlash(String url) {
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

	/** The fill could not get an answer. Logged, never shown: nobody is waiting on a fill. */
	static final class NameFillFailed extends RuntimeException {
		NameFillFailed(String message) {
			super(message);
		}
	}
}
