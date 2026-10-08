package org.iskcon.kms.assistant.api;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.assistant.action.AssistantProposal;
import org.iskcon.kms.assistant.action.PendingActionService;
import org.iskcon.kms.assistant.api.AssistantViews.ConversationSummary;
import org.iskcon.kms.assistant.api.AssistantViews.ConversationView;
import org.iskcon.kms.assistant.api.AssistantViews.MessageView;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.translation.Languages;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The assistant's conversations, messages and runs in the database (E10-T10, tables from V160).
 *
 * <p>JdbcTemplate rather than JPA entities, like every other module here: the only two entities in
 * the application are {@code Tenant} and {@code User}. The brief suggested entities for the V160
 * tables; matching the code around them won, and with no entity there is nothing for
 * {@code ddl-auto=validate} to disagree with.
 *
 * <p><strong>Whose rows.</strong> RLS fixes the temple. Within it, every read and write here also
 * names the signed-in person ({@code user_id = ?}), because a conversation belongs to the one person
 * who started it: another cook at the same temple must not read it, and asking for someone else's
 * conversation is answered exactly as asking for one that does not exist (KMS-400030), so the
 * answer does not confirm that it does.
 *
 * <p><strong>Two transactions per message, with the run in between and outside both.</strong>
 * {@link #begin} checks the limits, saves the question and writes the run {@code RUNNING}, and
 * commits, so the budget of every other request sees the run at once. {@link #finish} saves the
 * reply and closes the run. The run itself, up to a minute of waiting on the runner, holds no
 * transaction and no connection.
 */
@Service
public class AssistantConversationStore {

	/** "Turns" as {@code tools/assistant-dev/chat.py} counts them: one question and its answer. */
	static final int HISTORY_TURNS = 10;

	/** Titles are cut here; the column allows 200, and a list of conversations wants far fewer. */
	static final int TITLE_LENGTH = 80;

	private final JdbcTemplate jdbc;
	private final AssistantBudget budget;
	private final AuditService audit;
	private final PendingActionService actions;

	public AssistantConversationStore(JdbcTemplate jdbc, AssistantBudget budget, AuditService audit,
			PendingActionService actions) {
		this.jdbc = jdbc;
		this.budget = budget;
		this.audit = audit;
		this.actions = actions;
	}

	/**
	 * Starts a conversation in the person's own language (E10-LANG): Rajeev's rule is that the
	 * assistant starts in English unless the person's account says otherwise. Read from their row in
	 * the same statement, so there is no window in which a change of preference lands between the
	 * read and the insert; 'en' if the row cannot be read, which under RLS it always can.
	 */
	@Transactional
	public ConversationSummary create(AuthenticatedUser actor) {
		return jdbc.queryForObject("""
				INSERT INTO assistant_conversations (tenant_id, user_id, language)
				VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?,
				        COALESCE((SELECT preferred_language FROM users WHERE id = ?), 'en'))
				RETURNING id, title, language, created_at, updated_at
				""", AssistantConversationStore::summary, actor.getUserId(), actor.getUserId());
	}

	@Transactional(readOnly = true)
	public List<ConversationSummary> list(AuthenticatedUser actor) {
		return jdbc.query("""
				SELECT id, title, language, created_at, updated_at FROM assistant_conversations
				WHERE user_id = ?
				ORDER BY updated_at DESC, id
				""", AssistantConversationStore::summary, actor.getUserId());
	}

	@Transactional(readOnly = true)
	public ConversationView get(AuthenticatedUser actor, UUID conversationId) {
		ConversationSummary conversation = owned(actor, conversationId, false);
		// Each reply carries the drafts its run made (E10-S4), found by the run id both rows hold.
		Map<UUID, List<AssistantProposal>> drafts = actions.forConversation(conversationId);
		List<MessageView> messages = jdbc.query("""
				SELECT id, role, content, created_at, run_id FROM assistant_messages
				WHERE conversation_id = ?
				ORDER BY created_at, id
				""", (rs, n) -> {
					UUID runId = rs.getObject("run_id", UUID.class);
					return new MessageView(
							rs.getObject("id", UUID.class), rs.getString("role"), rs.getString("content"),
							rs.getTimestamp("created_at").toInstant(),
							runId == null ? List.of() : drafts.getOrDefault(runId, List.of()));
				},
				conversationId);
		return new ConversationView(conversation.id(), conversation.title(), conversation.language(),
				conversation.createdAt(), conversation.updatedAt(), messages);
	}

	/**
	 * What {@link #begin} hands to the run: its id, the prompt to send, and the conversation's language
	 * when the run began, so the chat service can tell the browser if the run switched it.
	 */
	record Started(UUID conversationId, UUID runId, String prompt, String language) {
	}

	/**
	 * Plan §2 step 2, in order: the conversation is the person's, the limits allow a run, the question
	 * is saved, the run is written {@code RUNNING}. Throws (and writes nothing) if any check fails;
	 * nothing has been sent to the browser yet, so the refusal is an ordinary JSON error.
	 */
	@Transactional
	public Started begin(AuthenticatedUser actor, UUID conversationId, String text, String model) {
		ConversationSummary conversation = owned(actor, conversationId, true);

		budget.check(actor.getTenantId(), actor.getUserId());

		// Read before the new question is saved, so it is not in its own history.
		String prompt = promptWithHistory(conversation.language(), history(conversationId), text);

		jdbc.update("""
				INSERT INTO assistant_messages (tenant_id, conversation_id, role, content)
				VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, 'USER', ?)
				""", conversationId, text);

		UUID runId = jdbc.queryForObject("""
				INSERT INTO assistant_runs (tenant_id, conversation_id, user_id, model)
				VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?)
				RETURNING id
				""", UUID.class, conversationId, actor.getUserId(), model);

		jdbc.update("""
				UPDATE assistant_conversations
				SET title = COALESCE(title, ?), updated_at = now()
				WHERE id = ?
				""", title(text), conversationId);

		return new Started(conversationId, runId, prompt, conversation.language());
	}

	/**
	 * How a run ended, for the run row. {@code reply} is null when there is nothing to save;
	 * {@code errorCode} is the KMS reference stored on the row and {@code errorCodeName} the same code's
	 * enum name, for the error event.
	 */
	record Finished(
			String status, String errorCode, String reply, String model, long durationMs,
			Integer inputTokens, Integer outputTokens, BigDecimal costUsd, int toolCalls, String errorCodeName) {
	}

	/**
	 * Plan §2 step 6: the reply saved (if there is one), the run closed, the conversation moved to the
	 * top of the list, and the run audited. Returns the reply's id, or null.
	 */
	@Transactional
	public UUID finish(AuthenticatedUser actor, Started run, Finished how) {
		UUID messageId = null;
		if (how.reply() != null) {
			messageId = jdbc.queryForObject("""
					INSERT INTO assistant_messages (tenant_id, conversation_id, role, content, run_id)
					VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, 'ASSISTANT', ?, ?)
					RETURNING id
					""", UUID.class, run.conversationId(), how.reply(), run.runId());
		}

		// finished_at is the database's clock, like started_at, so the CHECK that one follows the
		// other compares like with like. duration_ms is the api's own measure of the same span.
		jdbc.update("""
				UPDATE assistant_runs
				SET status = ?, finished_at = now(), duration_ms = ?, input_tokens = ?, output_tokens = ?,
				    notional_cost_usd = ?, tool_calls = ?, error_code = ?, model = COALESCE(?, model)
				WHERE id = ? AND status = 'RUNNING'
				""", how.status(), (int) Math.min(Integer.MAX_VALUE, how.durationMs()), how.inputTokens(),
				how.outputTokens(), how.costUsd(), how.toolCalls(), how.errorCode(), how.model(), run.runId());

		jdbc.update("UPDATE assistant_conversations SET updated_at = now() WHERE id = ?", run.conversationId());

		Map<String, Object> after = new LinkedHashMap<>();
		after.put("conversationId", run.conversationId());
		after.put("status", how.status());
		if (how.errorCode() != null) {
			after.put("errorCode", how.errorCode());
		}
		audit.record(actor, AuditAction.ASSISTANT_RUN, AuditEntityType.ASSISTANT_RUN, run.runId(),
				null, after, null);
		return messageId;
	}

	/** The conversation, if it is this person's; otherwise the same 404 as for one that does not exist. */
	private ConversationSummary owned(AuthenticatedUser actor, UUID conversationId, boolean lock) {
		List<ConversationSummary> found = jdbc.query("""
				SELECT id, title, language, created_at, updated_at FROM assistant_conversations
				WHERE id = ? AND user_id = ?
				""" + (lock ? " FOR UPDATE" : ""),
				AssistantConversationStore::summary, conversationId, actor.getUserId());
		if (found.isEmpty()) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("assistantConversation", conversationId));
		}
		return found.get(0);
	}

	/**
	 * The conversation's language now. Read after a run, because the run may have changed it: the
	 * model calls {@code set_conversation_language} through the MCP server, on another request, and
	 * this is how the chat service learns of it to tell the browser.
	 */
	@Transactional(readOnly = true)
	public String language(AuthenticatedUser actor, UUID conversationId) {
		return owned(actor, conversationId, false).language();
	}

	/** A question and the reply that followed it. */
	record Turn(String question, String answer) {
	}

	/**
	 * The last {@link #HISTORY_TURNS} answered questions, oldest first. A question with no reply after
	 * it (its run failed, or the browser left) is left out: sending the model a question without its
	 * answer invites it to answer that one again instead of the new one.
	 */
	List<Turn> history(UUID conversationId) {
		// Enough rows for ten turns even with a few unanswered questions among them, newest first.
		List<String[]> rows = jdbc.query("""
				SELECT role, content FROM assistant_messages
				WHERE conversation_id = ?
				ORDER BY created_at DESC, id DESC
				LIMIT ?
				""", (rs, n) -> new String[] {rs.getString("role"), rs.getString("content")},
				conversationId, HISTORY_TURNS * 4);
		Collections.reverse(rows);

		List<Turn> turns = new ArrayList<>();
		for (int i = 0; i < rows.size(); i++) {
			if ("USER".equals(rows.get(i)[0]) && i + 1 < rows.size() && "ASSISTANT".equals(rows.get(i + 1)[0])) {
				turns.add(new Turn(rows.get(i)[1], rows.get(i + 1)[1]));
				i++;
			}
		}
		return turns.size() <= HISTORY_TURNS ? turns : turns.subList(turns.size() - HISTORY_TURNS, turns.size());
	}

	/**
	 * The shape {@code chat.py} sends, which T8 and the terminal chat tried against the real model —
	 * no history, the bare question; otherwise the earlier turns, labelled, then the new one — with
	 * the conversation's language stated first (E10-LANG).
	 *
	 * <p><strong>Why the language goes in every prompt and not only in the system prompt.</strong> The
	 * system prompt is the same file for every run and cannot know which language this conversation is
	 * in. It states the rule; this line states the fact the rule applies to. It is repeated on every
	 * message, not only the first, because the runner keeps nothing between messages: each run is a
	 * fresh {@code claude -p} that knows only what this prompt tells it. Named in words and by code
	 * ("Kannada (kn)"), because the model follows a named language more reliably than a bare code, and
	 * the tool that changes it takes the code.
	 */
	static String promptWithHistory(String language, List<Turn> history, String message) {
		String head = languageLine(language);
		if (history.isEmpty()) {
			return head + message;
		}
		StringBuilder out = new StringBuilder(head).append("Conversation so far (oldest first):\n\n");
		for (Turn turn : history) {
			out.append("User: ").append(turn.question()).append('\n')
					.append("Assistant: ").append(turn.answer()).append("\n\n");
		}
		out.append("New message from the user, to answer now:\n").append(message);
		return out.toString();
	}

	/** The first lines of every prompt: which language to reply in, and when that may change. */
	static String languageLine(String language) {
		String name = Languages.englishName(language);
		return "Conversation language: " + name + " (" + language + "). Reply only in " + name
				+ ", whatever language the message is written in. Change language only if the person asks"
				+ " you to, by calling set_conversation_language.\n\n";
	}

	static String title(String text) {
		String oneLine = text.strip().replaceAll("\\s+", " ");
		return oneLine.length() <= TITLE_LENGTH ? oneLine : oneLine.substring(0, TITLE_LENGTH - 1) + "…";
	}

	private static ConversationSummary summary(ResultSet rs, int n) throws SQLException {
		return new ConversationSummary(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("language"),
				rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
	}
}
