package org.iskcon.kms.assistant.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.iskcon.kms.assistant.api.RunStreamReader.SseEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reading of {@code claude -p}'s stream-json, line by line, against a recorded run (E10-T10).
 * {@link AssistantApiIT} drives the same recording through HTTP; this class pins the reading itself.
 */
class RunStreamReaderTest {

	private final ObjectMapper json = new ObjectMapper();

	static List<String> recording(String name) throws IOException {
		try (InputStream in = RunStreamReaderTest.class.getResourceAsStream("/assistant/" + name)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
		}
	}

	@Test
	@DisplayName("a recorded run: text as it streamed, one tool call by name and argument, and the run's figures")
	void recordedRun() throws IOException {
		RunStreamReader reader = new RunStreamReader(json);
		List<SseEvent> events = new ArrayList<>();
		for (String line : recording("runner-toor-dal.ndjson")) {
			events.addAll(reader.accept(line));
		}

		assertThat(events).extracting(SseEvent::name)
				.containsExactly("text", "text", "tool_call", "text", "text");
		assertThat(events.get(0).data()).containsEntry("text", "Let me ");
		assertThat(events.get(2).data()).containsEntry("tool", "get_inventory")
				.containsEntry("summary", "item_name=toor dal");
		assertThat(events).as("no tool result reaches the browser")
				.noneMatch(e -> e.data().toString().contains("806.088") && e.name().equals("tool_call"));

		assertThat(reader.succeeded()).isTrue();
		assertThat(reader.replyText()).isEqualTo(
				"Let me check.\nWe have 806.088 Kg of Toor Dal on hand, well above the reorder level of 60 Kg.");
		assertThat(reader.toolCalls()).isEqualTo(1);
		assertThat(reader.model()).isEqualTo("claude-sonnet-4-5-20250929");
		assertThat(reader.inputTokens()).as("input, cache writes and cache reads together").isEqualTo(18012);
		assertThat(reader.outputTokens()).isEqualTo(95);
		assertThat(reader.costUsd()).isEqualByComparingTo("0.0412345");
	}

	@Test
	@DisplayName("a runner_error line: not a success, and a timeout is told apart from other failures")
	void runnerError() {
		RunStreamReader timedOut = new RunStreamReader(json);
		timedOut.accept("{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Let me\"}}}");
		timedOut.accept("{\"type\":\"runner_error\",\"code\":\"timeout\",\"message\":\"killed at 60000 ms\"}");
		assertThat(timedOut.succeeded()).isFalse();
		assertThat(timedOut.runnerTimedOut()).isTrue();

		RunStreamReader crashed = new RunStreamReader(json);
		crashed.accept("{\"type\":\"runner_error\",\"code\":\"exit_nonzero\",\"exitCode\":1}");
		assertThat(crashed.succeeded()).isFalse();
		assertThat(crashed.runnerTimedOut()).isFalse();
	}

	@Test
	@DisplayName("a result the CLI marks as an error is not a success, even with text")
	void errorResult() {
		RunStreamReader reader = new RunStreamReader(json);
		reader.accept("{\"type\":\"result\",\"subtype\":\"error_max_turns\",\"is_error\":true,\"result\":\"stopped\"}");
		assertThat(reader.succeeded()).isFalse();
	}

	@Test
	@DisplayName("no partial messages: the result's text is sent once, at the end")
	void unstreamedReply() {
		RunStreamReader reader = new RunStreamReader(json);
		List<SseEvent> events = reader.accept(
				"{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"Plenty.\"}");
		assertThat(events).extracting(SseEvent::name).containsExactly("text");
		assertThat(reader.replyText()).isEqualTo("Plenty.");
		assertThat(reader.succeeded()).isTrue();
	}

	@Test
	@DisplayName("junk, blank lines and unknown event types are ignored, not fatal")
	void ignoresWhatItDoesNotKnow() {
		RunStreamReader reader = new RunStreamReader(json);
		assertThat(reader.accept("not json")).isEmpty();
		assertThat(reader.accept("")).isEmpty();
		assertThat(reader.accept("[1,2]")).isEmpty();
		assertThat(reader.accept("{\"type\":\"rate_limit_event\"}")).isEmpty();
		assertThat(reader.succeeded()).isFalse();
	}

	@Test
	@DisplayName("a tool call's summary is cut, and the MCP server's prefix is dropped")
	void summary() throws IOException {
		assertThat(RunStreamReader.toolName("mcp__ikms__list_low_stock")).isEqualTo("list_low_stock");
		String longInput = "{\"item_name\":\"" + "x".repeat(300) + "\"}";
		String summary = RunStreamReader.summarise(json.readTree(longInput));
		assertThat(summary).hasSize(RunStreamReader.SUMMARY_LIMIT).endsWith("…");
		assertThat(RunStreamReader.summarise(json.readTree("{}"))).isEmpty();
	}
}
