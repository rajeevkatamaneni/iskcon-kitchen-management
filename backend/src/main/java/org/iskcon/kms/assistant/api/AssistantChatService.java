package org.iskcon.kms.assistant.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.iskcon.kms.assistant.action.AssistantProposal;
import org.iskcon.kms.assistant.action.PendingActionService;
import org.iskcon.kms.assistant.api.AssistantConversationStore.Finished;
import org.iskcon.kms.assistant.api.AssistantConversationStore.Started;
import org.iskcon.kms.assistant.api.RunStreamReader.SseEvent;
import org.iskcon.kms.assistant.token.AgentTokenService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.error.ErrorResponse;
import org.iskcon.kms.translation.Languages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

/**
 * One message to the assistant, from the question to the saved reply (E10-T10, plan §2).
 *
 * <p><strong>The stream is written on the request thread, by hand.</strong> Spring's
 * {@code SseEmitter} and {@code StreamingResponseBody} both hand the writing to another thread, and
 * this application's tenancy lives on the request thread: {@code TenantContext} is a thread-local,
 * and the connection's {@code app.tenant_id}, which RLS reads, is set from it on checkout. Saving
 * the reply from an executor thread would write with no temple set, and RLS would refuse it (or,
 * worse, a reused thread would carry another request's temple). Writing the response directly keeps
 * the whole exchange on one thread with one identity. The cost is a servlet thread held for the
 * length of a run (up to the runner's 60 s), which the daily limits and the runner's own
 * concurrency limit keep small, and which is revisited if the assistant ever has many users at once.
 *
 * <p><strong>Two kinds of failure, two shapes.</strong> Everything that can refuse a message before
 * anything is spent (permission, a conversation that is not yours, the two daily limits, a blank
 * message) throws before the first byte is written, and arrives as the ordinary JSON error with its
 * HTTP status. Once the stream has started the status is already 200, so a failure after that is the
 * stream's last event, {@code error}, carrying the same three things a JSON error does: the code,
 * what happened, and what to do.
 *
 * <p><strong>The events</strong>, in the order they can arrive: {@code text} (a piece of the reply),
 * {@code tool_call} (a lookup, by name and arguments; never its result), and one of {@code done}
 * (the reply's message id and the run id) or {@code error}. Just before that last one,
 * {@code language} ({@code {language, languageName}}, e.g. {@code {"kn", "Kannada"}}) is sent if the
 * person asked the assistant to switch language during this run (E10-LANG); the conversation's own
 * {@code language} field says the same thing on the next read. Before both of those, one
 * {@code proposal} event per draft card the run made (E10-S4), carrying the card as
 * {@code AssistantProposal}.
 */
@Service
public class AssistantChatService {

	private static final Logger log = LoggerFactory.getLogger(AssistantChatService.class);

	static final MediaType EVENT_STREAM = MediaType.TEXT_EVENT_STREAM;

	private final AssistantConversationStore store;
	private final RunnerClient runner;
	private final AgentTokenService tokens;
	private final AssistantSettings settings;
	private final ObjectMapper json;
	private final MeterRegistry meters;
	private final PendingActionService actions;

	public AssistantChatService(AssistantConversationStore store, RunnerClient runner, AgentTokenService tokens,
			AssistantSettings settings, ObjectMapper json, MeterRegistry meters, PendingActionService actions) {
		this.actions = actions;
		this.store = store;
		this.runner = runner;
		this.tokens = tokens;
		this.settings = settings;
		this.json = json;
		this.meters = meters;
	}

	/** Thrown out of the line handler when the browser has gone; stops the run. */
	private static final class BrowserGone extends RuntimeException {
		BrowserGone(IOException cause) {
			super(cause);
		}
	}

	public void send(AuthenticatedUser actor, UUID conversationId, String text, HttpServletResponse response)
			throws IOException {
		// Everything that may refuse, before a byte is written (see the class comment).
		Started run = store.begin(actor, conversationId, text, settings.model());
		long started = System.nanoTime();

		response.setStatus(HttpServletResponse.SC_OK);
		response.setContentType(EVENT_STREAM.toString());
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.setHeader("Cache-Control", "no-cache, no-store");
		// A buffering proxy (nginx, and some load balancers that honour this header) would hold the
		// reply back until the run ended, which is the opposite of streaming it.
		response.setHeader("X-Accel-Buffering", "no");
		OutputStream out = response.getOutputStream();

		RunStreamReader reader = new RunStreamReader(json);
		String agentToken = tokens.mint(actor.getUserId(), actor.getTenantId(), conversationId,
				Set.of(AgentTokenService.SCOPE_READ, AgentTokenService.SCOPE_PROPOSE));

		RunnerClient.Ending ending;
		boolean browserGone = false;
		try {
			// A first byte at once, so the browser knows the request was accepted and is waiting on
			// the model rather than on us. A comment line: EventSource and fetch readers skip it.
			write(out, ": run " + run.runId() + "\n\n");
			ending = runner.run(run.prompt(), agentToken, line -> {
				for (SseEvent event : reader.accept(line)) {
					try {
						write(out, event);
					} catch (IOException e) {
						throw new BrowserGone(e);
					}
				}
			});
		} catch (BrowserGone | IOException e) {
			ending = null;
			browserGone = true;
		}

		long durationMs = (System.nanoTime() - started) / 1_000_000;
		Finished how = outcome(reader, ending, browserGone, durationMs);
		UUID messageId;
		try {
			messageId = store.finish(actor, run, how);
		} catch (RuntimeException e) {
			// The response is already 200 and streaming, so the global handler cannot answer this
			// one: it would try to write JSON into an event stream. The run row stays RUNNING and
			// keeps counting against today's limits, which errs on the side of spending less.
			log.error("assistant run {} could not be recorded", run.runId(), e);
			if (!browserGone) {
				writeError(out, ErrorCode.UNEXPECTED_FAILURE, run.runId());
			}
			return;
		}
		meters.counter("assistant.runs", "status", how.status()).increment();
		log.info("assistant run {} {} in {} ms, {} tool calls, error {}", run.runId(), how.status(), durationMs,
				how.toolCalls(), how.errorCode());

		if (browserGone) {
			return;
		}
		try {
			if (how.reply() != null) {
				proposals(run, out);
			}
			languageIfChanged(actor, run, out);
			if (how.reply() != null) {
				Map<String, Object> done = new LinkedHashMap<>();
				done.put("messageId", messageId);
				done.put("runId", run.runId());
				write(out, new SseEvent("done", done));
			} else {
				writeError(out, ErrorCode.valueOf(how.errorCodeName()), run.runId());
			}
		} catch (IOException e) {
			// Gone between the last line and the end; the run is already recorded.
			log.debug("assistant: browser left before the run's last event", e);
		}
	}

	/**
	 * Tells the browser the conversation's language changed during this run (E10-LANG), just before
	 * the run's last event. The model changes it by calling {@code set_conversation_language}, which
	 * the MCP server handles on its own request; this re-reads the column rather than trusting the
	 * relayed {@code tool_call} event, because a call the tool refused (an unknown code) changes
	 * nothing and must not be announced. Sent whether or not the run then succeeded: a switch that
	 * happened is true either way, and the next message will be answered in the new language.
	 */
	private void languageIfChanged(AuthenticatedUser actor, Started run, OutputStream out) throws IOException {
		String now;
		try {
			now = store.language(actor, run.conversationId());
		} catch (RuntimeException e) {
			// Worth a log line, not a failed answer: the reply itself is saved and on its way.
			log.warn("assistant run {}: could not re-read the conversation's language", run.runId(), e);
			return;
		}
		if (!now.equals(run.language())) {
			Map<String, Object> data = new LinkedHashMap<>();
			data.put("language", now);
			data.put("languageName", Languages.englishName(now));
			write(out, new SseEvent("language", data));
		}
	}

	/**
	 * One {@code proposal} event per draft this run made (E10-S4), oldest first, before
	 * {@code language} and {@code done}. The propose tool stored them on its own MCP request, under
	 * this run's id; reading them back here, rather than trusting the relayed {@code tool_call}
	 * event, means a call the tool refused sends no card. Sent only with a reply: a run that failed
	 * saved no message to hang the card under, and its drafts simply expire unconfirmed.
	 */
	private void proposals(Started run, OutputStream out) throws IOException {
		List<AssistantProposal> drafts;
		try {
			drafts = actions.forRun(run.runId());
		} catch (RuntimeException e) {
			// The reply is saved; the cards come back with the conversation on its next read.
			log.warn("assistant run {}: could not read its drafts", run.runId(), e);
			return;
		}
		for (AssistantProposal draft : drafts) {
			@SuppressWarnings("unchecked")
			Map<String, Object> data = json.convertValue(draft, Map.class);
			write(out, new SseEvent("proposal", data));
		}
	}

	/** The stream's last event when there is no reply: the same code, text and next step as a JSON error. */
	private void writeError(OutputStream out, ErrorCode code, UUID runId) {
		ErrorResponse error = ErrorResponse.of(code);
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("code", error.code());
		data.put("message", error.message());
		data.put("action", error.action());
		data.put("runId", runId);
		try {
			write(out, new SseEvent("error", data));
		} catch (IOException e) {
			log.debug("assistant: browser left before the error event", e);
		}
	}

	/** The run row's status, code and figures from how the run ended. */
	static Finished outcome(RunStreamReader reader, RunnerClient.Ending ending, boolean browserGone, long durationMs) {
		String status;
		ErrorCode code;
		if (browserGone) {
			// Nobody was shown a code, so none is recorded: the row says the run did not finish,
			// and the absence of a code says it was not the assistant's fault.
			status = "FAILED";
			code = null;
		} else if (ending == RunnerClient.Ending.TIMED_OUT || reader.runnerTimedOut()) {
			status = "TIMED_OUT";
			code = ErrorCode.ASSISTANT_TOOK_TOO_LONG;
		} else if (ending == RunnerClient.Ending.COMPLETED && reader.succeeded()) {
			status = "SUCCEEDED";
			code = null;
		} else {
			// Unreachable, busy, a non-zero exit, a run the CLI itself reported as an error, or a
			// stream that ended without a result: the same to the person, and the runner's log and
			// ours say which.
			status = "FAILED";
			code = ErrorCode.ASSISTANT_UNAVAILABLE;
		}
		String reply = "SUCCEEDED".equals(status) ? reader.replyText() : null;
		return new Finished(status, code == null ? null : code.reference(), reply, reader.model(), durationMs,
				reader.inputTokens(), reader.outputTokens(), reader.costUsd(), reader.toolCalls(),
				code == null ? null : code.name());
	}

	private void write(OutputStream out, SseEvent event) throws IOException {
		write(out, "event: " + event.name() + "\ndata: " + json.writeValueAsString(event.data()) + "\n\n");
	}

	private static void write(OutputStream out, String frame) throws IOException {
		out.write(frame.getBytes(StandardCharsets.UTF_8));
		out.flush();
	}
}
