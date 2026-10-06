package org.iskcon.kms.assistant.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads one run's {@code claude -p} stream-json, a line at a time, and says what the browser should
 * be shown and what the run row should record (E10-T10, plan §2 step 6).
 *
 * <p>Kept free of HTTP, the database and Spring so that the one piece of this task that interprets
 * somebody else's format can be tested line by line against a recorded stream, and so that when the
 * CLI's format moves (it is pinned in the runner, but it will be upgraded) there is one class to read.
 *
 * <p><strong>What is relayed and what is not.</strong> The browser gets the reply's text as it is
 * written, and one short line per tool call: the tool's name and its arguments. It never gets a
 * tool's <em>result</em>. Those are raw rows from the temple's data, sized for a model and not for a
 * person, and the reply that follows says what they meant. Nor does it get the init event's list of
 * tools and servers, the session id, or usage: those are ours, and they go on the run row.
 *
 * <p><strong>Where each piece comes from</strong>, the same way {@code tools/assistant-dev/chat.py}
 * reads it, since that is the reading T8 and the terminal chat proved against the real CLI:
 *
 * <ul>
 *   <li>Text from {@code stream_event} / {@code content_block_delta} / {@code text_delta}, which
 *       arrives a few words at a time because the runner starts {@code claude} with
 *       {@code --include-partial-messages}.
 *   <li>Tool calls from the complete {@code assistant} message, not from the partial stream: a tool's
 *       input arrives there as JSON fragments and is only whole once the message is.
 *   <li>Tokens, notional cost, success or failure from the single {@code result} event at the end.
 *   <li>The model's full name from {@code system} / {@code init}, so the run row says which model
 *       actually answered rather than the alias it was asked for.
 *   <li>The runner's own failure ({@code runner_error}: timeout, spawn_failed, exit_nonzero) from its
 *       last line, in the runner's shape rather than the CLI's (E10-T9).
 * </ul>
 *
 * <p>Anything else, and any line that is not JSON, is ignored. A new event type the CLI starts
 * sending must not break a reply that is otherwise fine.
 */
final class RunStreamReader {

	/** The MCP server's name in tool names: {@code mcp__ikms__get_inventory}. Not the person's business. */
	private static final String TOOL_PREFIX = "mcp__ikms__";

	/** How much of a tool call's arguments the browser is shown. Enough to say "toor dal", no more. */
	static final int SUMMARY_LIMIT = 120;

	private final ObjectMapper json;

	private final StringBuilder streamedText = new StringBuilder();
	private boolean textWasStreamed;
	private int toolCalls;
	private String model;
	private boolean resultSeen;
	private boolean resultIsError;
	private String resultText;
	private Integer inputTokens;
	private Integer outputTokens;
	private BigDecimal costUsd;
	private String runnerErrorCode;

	RunStreamReader(ObjectMapper json) {
		this.json = json;
	}

	/** What one line means for the browser: zero or more events, in order. */
	List<SseEvent> accept(String line) {
		if (line == null || line.isBlank()) {
			return List.of();
		}
		JsonNode event;
		try {
			event = json.readTree(line);
		} catch (Exception e) {
			return List.of();
		}
		if (event == null || !event.isObject()) {
			return List.of();
		}

		switch (event.path("type").asText()) {
			case "system" -> {
				if ("init".equals(event.path("subtype").asText()) && event.hasNonNull("model")) {
					model = event.get("model").asText();
				}
				return List.of();
			}
			case "stream_event" -> {
				JsonNode delta = event.path("event").path("delta");
				if ("content_block_delta".equals(event.path("event").path("type").asText())
						&& "text_delta".equals(delta.path("type").asText())) {
					String text = delta.path("text").asText("");
					if (text.isEmpty()) {
						return List.of();
					}
					streamedText.append(text);
					textWasStreamed = true;
					return List.of(SseEvent.text(text));
				}
				return List.of();
			}
			case "assistant" -> {
				List<SseEvent> calls = new ArrayList<>();
				for (JsonNode block : event.path("message").path("content")) {
					if (!"tool_use".equals(block.path("type").asText())) {
						continue;
					}
					toolCalls++;
					// "Let me check." then a lookup, then the answer: kept apart in the saved reply,
					// as chat.py keeps them, so the history sent with the next question reads as two
					// sentences rather than one run-on.
					if (!streamedText.isEmpty() && streamedText.charAt(streamedText.length() - 1) != '\n') {
						streamedText.append('\n');
					}
					calls.add(SseEvent.toolCall(toolName(block.path("name").asText("")),
							summarise(block.path("input"))));
				}
				return calls;
			}
			case "result" -> {
				resultSeen = true;
				resultIsError = event.path("is_error").asBoolean(false)
						|| !"success".equals(event.path("subtype").asText("success"));
				resultText = event.hasNonNull("result") ? event.get("result").asText() : null;
				JsonNode usage = event.path("usage");
				inputTokens = sumOfPresent(usage, "input_tokens", "cache_creation_input_tokens",
						"cache_read_input_tokens");
				outputTokens = sumOfPresent(usage, "output_tokens");
				if (event.path("total_cost_usd").isNumber()) {
					costUsd = event.get("total_cost_usd").decimalValue();
				}
				// The reply came whole rather than streamed (a CLI started without partial
				// messages): send it now, so the browser still shows something.
				if (!textWasStreamed && !resultIsError && resultText != null && !resultText.isBlank()) {
					streamedText.append(resultText);
					return List.of(SseEvent.text(resultText));
				}
				return List.of();
			}
			case "runner_error" -> {
				runnerErrorCode = event.path("code").asText("unknown");
				return List.of();
			}
			default -> {
				return List.of();
			}
		}
	}

	/** The reply to save: what the person watched being written, or the result's text if nothing streamed. */
	String replyText() {
		String streamed = streamedText.toString().strip();
		if (!streamed.isEmpty()) {
			return streamed;
		}
		return resultText == null ? "" : resultText.strip();
	}

	boolean succeeded() {
		return resultSeen && !resultIsError && runnerErrorCode == null && !replyText().isEmpty();
	}

	boolean runnerTimedOut() {
		return "timeout".equals(runnerErrorCode);
	}

	String runnerErrorCode() {
		return runnerErrorCode;
	}

	int toolCalls() {
		return toolCalls;
	}

	String model() {
		return model;
	}

	Integer inputTokens() {
		return inputTokens;
	}

	Integer outputTokens() {
		return outputTokens;
	}

	BigDecimal costUsd() {
		return costUsd;
	}

	static String toolName(String name) {
		return name.startsWith(TOOL_PREFIX) ? name.substring(TOOL_PREFIX.length()) : name;
	}

	/**
	 * {@code item_name=toor dal}: the arguments as a person would read them, cut at
	 * {@link #SUMMARY_LIMIT}. Nested values are shown as their JSON, which for this tool set (flat
	 * string and number arguments) never happens, and is still short and harmless if it does.
	 */
	static String summarise(JsonNode input) {
		if (input == null || !input.isObject() || input.isEmpty()) {
			return "";
		}
		StringBuilder out = new StringBuilder();
		Iterator<Map.Entry<String, JsonNode>> fields = input.fields();
		while (fields.hasNext()) {
			Map.Entry<String, JsonNode> field = fields.next();
			if (!out.isEmpty()) {
				out.append(", ");
			}
			JsonNode value = field.getValue();
			out.append(field.getKey()).append('=').append(value.isValueNode() ? value.asText() : value.toString());
		}
		String summary = out.toString().replaceAll("\\s+", " ");
		return summary.length() <= SUMMARY_LIMIT ? summary : summary.substring(0, SUMMARY_LIMIT - 1) + "…";
	}

	/**
	 * Input tokens are the three kinds added together. With prompt caching most of a run's input is
	 * reported as cache reads, and {@code input_tokens} alone is a handful — a figure that would make
	 * every run look free and say nothing about how much context the history window costs.
	 */
	private static Integer sumOfPresent(JsonNode usage, String... fields) {
		Integer total = null;
		for (String field : fields) {
			if (usage.path(field).canConvertToInt()) {
				total = (total == null ? 0 : total) + usage.get(field).asInt();
			}
		}
		return total;
	}

	/** One server-sent event: its name and its JSON data. */
	record SseEvent(String name, Map<String, Object> data) {

		static SseEvent text(String text) {
			return new SseEvent("text", Map.of("text", text));
		}

		static SseEvent toolCall(String tool, String summary) {
			Map<String, Object> data = new LinkedHashMap<>();
			data.put("tool", tool);
			data.put("summary", summary);
			return new SseEvent("tool_call", data);
		}
	}
}
