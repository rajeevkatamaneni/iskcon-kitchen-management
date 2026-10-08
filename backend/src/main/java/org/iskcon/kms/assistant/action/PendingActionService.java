package org.iskcon.kms.assistant.action;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.iskcon.kms.assistant.token.AgentPrincipal;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The assistant's drafts, from the propose tool that makes one to the Confirm that carries it out
 * (E10-S4; plan §2 step 7 and decision D-a: every write is a draft the server runs only when the
 * person presses Confirm).
 *
 * <h2>What is stored, and what the hash covers</h2>
 *
 * <p>One {@code assistant_pending_actions} row per draft (V160, V163). Its {@code payload} is
 * {@code {request, card}}: the request exactly as the existing service takes it, and the card exactly
 * as the person reads it. {@code payload_sha256} is the SHA-256 of the payload's text <em>as the
 * database holds it</em> ({@code payload::text}), computed by the database in the same INSERT. Confirm
 * reads that same text back, hashes it here, refuses on a mismatch (KMS-400195), and then parses and
 * carries out that very text. So the bytes checked are the bytes run, and the card is under the same
 * hash as the request: a card altered after the draft was made is refused like an altered request,
 * because confirming what you were shown is the whole point.
 *
 * <p>Hashing the database's own rendering, rather than the JSON this class wrote, is on purpose:
 * {@code jsonb} reorders keys and normalises whitespace, so a hash of what went in would never match
 * what comes out.
 *
 * <h2>Idempotency, both ends</h2>
 *
 * <ul>
 *   <li><strong>A retried propose call</strong> makes no second card. The key is the run, the kind and
 *       a hash of the payload, unique per temple (V160's index), and the INSERT does nothing on a
 *       conflict and reads the first one back. A second, <em>different</em> draft in the same run is a
 *       second row; the prompt is what asks for one draft per request.
 *   <li><strong>A repeated Confirm</strong> of a CONFIRMED draft runs nothing and answers with the
 *       same card and the same result. A double click, a retry after a dropped connection, two tabs:
 *       none of those is the person asking for a second order. The row is locked {@code FOR UPDATE}
 *       while it is checked and carried out, so two Confirms at once queue, and the second sees
 *       CONFIRMED. Confirming a REJECTED draft, or rejecting a decided one, is refused (KMS-400196).
 * </ul>
 *
 * <h2>Whose draft</h2>
 *
 * <p>Only the person whose conversation it is can confirm or reject it. The row is found through its
 * conversation's {@code user_id}, under RLS for the temple, and anything else — another person at the
 * same temple, another temple — is the same 404 as a draft that does not exist (KMS-400030), as for
 * the conversation itself.
 *
 * <h2>Whose authority</h2>
 *
 * <p>Confirm runs on the person's own request, with their own Firebase sign-in, not the agent token:
 * the endpoint needs {@code CONFIRM_ASSISTANT_ACTION}, and this class then requires the handler's own
 * permission (for an order, {@code MANAGE_PURCHASE_ORDERS}) from the same authentication before the
 * existing service runs. Holding the assistant's permission never stands in for the thing's own.
 */
@Service
public class PendingActionService {

	/** How long a draft can be confirmed for (plan §4 T19). After that, ask again: figures move. */
	static final String EXPIRY = "24 hours";

	private final JdbcTemplate jdbc;
	private final ObjectMapper json;
	private final AuditService audit;
	private final Map<String, PendingActionHandler> handlers;

	public PendingActionService(JdbcTemplate jdbc, ObjectMapper json, AuditService audit,
			List<PendingActionHandler> handlers) {
		this.jdbc = jdbc;
		this.json = json;
		this.audit = audit;
		this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(PendingActionHandler::kind,
				Function.identity()));
	}

	/** The run a propose tool was called from is not running any more, or never was this person's. */
	public static final class NoRunningRun extends RuntimeException {
		NoRunningRun() {
			super("no RUNNING assistant run for this conversation and person");
		}
	}

	/**
	 * Stores a draft for the run the agent is answering, and returns its card. Changes nothing in the
	 * kitchen.
	 *
	 * <p>The run is not in the agent token, which names only the person and the conversation, so it is
	 * found as the conversation's run that is still {@code RUNNING}: a propose tool is only ever called
	 * from inside a run, and {@code AssistantConversationStore.begin} writes the row before the runner
	 * is started. The chat service then finds this draft by that run id to send its {@code proposal}
	 * event, and the conversation read finds it under the reply the same run saved.
	 *
	 * @throws NoRunningRun if the conversation has no run in progress for this person
	 */
	@Transactional
	public AssistantProposal propose(AgentPrincipal agent, String kind, Object request, AssistantProposal.Card card) {
		if (!handlers.containsKey(kind)) {
			throw new IllegalArgumentException("no handler for kind " + kind);
		}
		List<UUID> runs = jdbc.queryForList("""
				SELECT id FROM assistant_runs
				WHERE conversation_id = ? AND user_id = ? AND status = 'RUNNING'
				ORDER BY started_at DESC
				LIMIT 1
				""", UUID.class, agent.conversationId(), agent.userId());
		if (runs.isEmpty()) {
			throw new NoRunningRun();
		}
		UUID runId = runs.get(0);

		ObjectNode payload = json.createObjectNode();
		payload.set("request", json.valueToTree(request));
		payload.set("card", json.valueToTree(card));
		String text = write(payload);
		String key = runId + ":" + kind + ":" + sha256(text);

		jdbc.update("""
				INSERT INTO assistant_pending_actions (
					tenant_id, conversation_id, run_id, kind, payload, payload_sha256, idempotency_key, expires_at)
				VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?::jsonb,
					encode(sha256(convert_to((?::jsonb)::text, 'UTF8')), 'hex'), ?, now() + interval '24 hours')
				ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
				""", agent.conversationId(), runId, kind, text, text, key);
		return jdbc.queryForObject(SELECT + " WHERE idempotency_key = ?", this::proposal, key);
	}

	/**
	 * Carries a draft out: see the class comment for every check, in the order they are made. Returns
	 * the card, CONFIRMED, with what it became.
	 */
	@Transactional
	public AssistantProposal confirm(AuthenticatedUser actor, UUID actionId) {
		Stored row = lockOwned(actor, actionId);
		if ("CONFIRMED".equals(row.status())) {
			// Idempotent: the same answer, nothing run again.
			return row.view();
		}
		if ("REJECTED".equals(row.status())) {
			throw new ApplicationException(ErrorCode.ASSISTANT_DRAFT_ALREADY_HANDLED, Map.of("actionId", actionId));
		}
		if (row.expired()) {
			throw new ApplicationException(ErrorCode.ASSISTANT_DRAFT_EXPIRED, Map.of("actionId", actionId));
		}
		PendingActionHandler handler = handlers.get(row.kind());
		if (handler == null) {
			throw new IllegalStateException("pending action " + actionId + " has kind " + row.kind()
					+ " and no handler carries it out");
		}
		requireAuthority(handler.permission());
		if (!sha256(row.payloadText()).equals(row.payloadSha256())) {
			throw new ApplicationException(ErrorCode.ASSISTANT_DRAFT_CHANGED, Map.of("actionId", actionId));
		}
		JsonNode request = read(row.payloadText()).get("request");
		if (request == null || request.isNull()) {
			// Hashes, but is not a draft this code wrote. The same refusal: it is not what was made.
			throw new ApplicationException(ErrorCode.ASSISTANT_DRAFT_CHANGED, Map.of("actionId", actionId));
		}

		AssistantProposal.Result result = handler.execute(actor, request);

		jdbc.update("""
				UPDATE assistant_pending_actions
				SET status = 'CONFIRMED', decided_by = ?, decided_at = now(), result_label = ?, result_href = ?
				WHERE id = ? AND status = 'PROPOSED'
				""", actor.getUserId(), result.label(), result.href(), actionId);
		Map<String, Object> after = new LinkedHashMap<>();
		after.put("kind", row.kind());
		after.put("status", "CONFIRMED");
		after.put("result", result.label());
		after.put("conversationId", row.conversationId());
		audit.record(actor, AuditAction.ASSISTANT_ACTION_CONFIRMED, AuditEntityType.ASSISTANT_ACTION, actionId,
				Map.of("status", "PROPOSED"), after, null);
		return jdbc.queryForObject(SELECT + " WHERE id = ?", this::proposal, actionId);
	}

	/** Turns a draft down. Nothing is carried out; the card shows it rejected. */
	@Transactional
	public AssistantProposal reject(AuthenticatedUser actor, UUID actionId) {
		Stored row = lockOwned(actor, actionId);
		if (!"PROPOSED".equals(row.status())) {
			if ("EXPIRED".equals(row.status())) {
				throw new ApplicationException(ErrorCode.ASSISTANT_DRAFT_EXPIRED, Map.of("actionId", actionId));
			}
			throw new ApplicationException(ErrorCode.ASSISTANT_DRAFT_ALREADY_HANDLED, Map.of("actionId", actionId));
		}
		if (row.expired()) {
			throw new ApplicationException(ErrorCode.ASSISTANT_DRAFT_EXPIRED, Map.of("actionId", actionId));
		}
		jdbc.update("""
				UPDATE assistant_pending_actions
				SET status = 'REJECTED', decided_by = ?, decided_at = now()
				WHERE id = ? AND status = 'PROPOSED'
				""", actor.getUserId(), actionId);
		Map<String, Object> after = new LinkedHashMap<>();
		after.put("kind", row.kind());
		after.put("status", "REJECTED");
		after.put("conversationId", row.conversationId());
		audit.record(actor, AuditAction.ASSISTANT_ACTION_REJECTED, AuditEntityType.ASSISTANT_ACTION, actionId,
				Map.of("status", "PROPOSED"), after, null);
		return jdbc.queryForObject(SELECT + " WHERE id = ?", this::proposal, actionId);
	}

	/** The drafts one run made, oldest first: what the chat service sends as {@code proposal} events. */
	@Transactional(readOnly = true)
	public List<AssistantProposal> forRun(UUID runId) {
		return jdbc.query(SELECT + " WHERE run_id = ? ORDER BY created_at, id", this::proposal, runId);
	}

	/**
	 * Every draft in a conversation, by the run that made it, so each can be put under the reply that
	 * run saved. The caller has already checked the conversation is the reader's own.
	 */
	@Transactional(readOnly = true)
	public Map<UUID, List<AssistantProposal>> forConversation(UUID conversationId) {
		Map<UUID, List<AssistantProposal>> byRun = new LinkedHashMap<>();
		jdbc.query(SELECT + " WHERE conversation_id = ? ORDER BY created_at, id", rs -> {
			byRun.computeIfAbsent(rs.getObject("run_id", UUID.class), k -> new ArrayList<>())
					.add(proposal(rs, 0));
		}, conversationId);
		return byRun;
	}

	// ---------------------------------------------------------------------

	private static final String SELECT = """
			SELECT id, kind, status, payload::text AS payload_text, payload_sha256, expires_at, result_label,
				result_href, conversation_id, run_id,
				(status = 'PROPOSED' AND expires_at <= now()) AS expired
			FROM assistant_pending_actions""";

	/** A row as Confirm and Reject need it. */
	private record Stored(UUID id, String kind, String status, String payloadText, String payloadSha256,
			boolean expired, UUID conversationId, AssistantProposal view) {
	}

	/**
	 * The draft, locked, if it is in one of this person's conversations; otherwise the same 404 as one
	 * that does not exist. RLS has already removed every other temple's rows.
	 */
	private Stored lockOwned(AuthenticatedUser actor, UUID actionId) {
		List<Stored> found = jdbc.query("""
				SELECT a.id, a.kind, a.status, a.payload::text AS payload_text, a.payload_sha256, a.expires_at,
					a.result_label, a.result_href, a.conversation_id, a.run_id,
					(a.status = 'PROPOSED' AND a.expires_at <= now()) AS expired
				FROM assistant_pending_actions a
				JOIN assistant_conversations c ON c.tenant_id = a.tenant_id AND c.id = a.conversation_id
				WHERE a.id = ? AND c.user_id = ?
				FOR UPDATE OF a
				""", (rs, n) -> new Stored(
						rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("status"),
						rs.getString("payload_text"), rs.getString("payload_sha256"), rs.getBoolean("expired"),
						rs.getObject("conversation_id", UUID.class), proposal(rs, n)),
				actionId, actor.getUserId());
		if (found.isEmpty()) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("assistantAction", actionId));
		}
		return found.get(0);
	}

	/**
	 * The handler's own permission, from the caller's authentication. A plain 403 (NOT_PERMITTED), the
	 * same answer as any {@code @PreAuthorize} refusal, saying nothing about which permission it was.
	 */
	private static void requireAuthority(String permission) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		boolean held = authentication != null && authentication.getAuthorities().stream()
				.map(GrantedAuthority::getAuthority)
				.anyMatch(permission::equals);
		if (!held) {
			throw new AccessDeniedException("confirming this draft needs " + permission);
		}
	}

	private AssistantProposal proposal(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
		JsonNode payload = read(rs.getString("payload_text"));
		AssistantProposal.Card card;
		try {
			card = json.treeToValue(payload.path("card"), AssistantProposal.Card.class);
		} catch (JsonProcessingException | IllegalArgumentException e) {
			card = null;
		}
		if (card == null) {
			// Only possible for a row this code did not write; shown as a bare card rather than
			// failing the whole conversation read. Confirm refuses it on the hash or the request.
			card = new AssistantProposal.Card("Draft", List.of(), List.of(), "");
		}
		String status = rs.getBoolean("expired") ? "EXPIRED" : rs.getString("status");
		String label = rs.getString("result_label");
		AssistantProposal.Result result = label == null ? null
				: new AssistantProposal.Result(label, rs.getString("result_href"));
		Instant expiresAt = rs.getTimestamp("expires_at").toInstant();
		return new AssistantProposal(rs.getObject("id", UUID.class), rs.getString("kind"), status, card.title(),
				card.facts() == null ? List.of() : card.facts(), card.lines() == null ? List.of() : card.lines(),
				card.confirmSays(), expiresAt, result);
	}

	private String write(JsonNode node) {
		try {
			return json.writeValueAsString(node);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("could not write a draft's payload", e);
		}
	}

	private JsonNode read(String text) {
		try {
			return json.readTree(text);
		} catch (JsonProcessingException e) {
			return json.createObjectNode();
		}
	}

	static String sha256(String text) {
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is missing from this JVM", e);
		}
	}
}
