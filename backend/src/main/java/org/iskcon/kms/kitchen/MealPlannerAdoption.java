package org.iskcon.kms.kitchen;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What happens to the requests already in flight when a kitchen starts planning its own meals
 * (E10-S4).
 *
 * <p>There is one store and two doors out of it. A meal recorded in the planner draws
 * {@code CONSUMPTION}; an issued request draws {@code ISSUE}. A kitchen using both would take the
 * same rice off the temple's books twice, so a kitchen uses one door or the other and the flag on
 * the kitchen says which. The temple was told about the double-count risk and said it would be
 * careful, which is not a guarantee — so the system makes it unreachable instead of trusting it.
 *
 * <p>Turning the flag on is therefore not just a save. There may already be requests for that
 * kitchen sitting in every state, and this is the class that settles them. Rajeev's rule, in his
 * words: <em>"All requests with delivery dates before will live as recorded data. All requests that
 * are dated the day of opt-in or any time in the future — if already approved, will be moved to
 * denied state automatically; if in draft, delete permanently; if already in denied state,
 * no-op."</em>
 *
 * <table>
 *   <caption>What each request meets</caption>
 *   <tr><th>Status</th><th>{@code needed_on}</th><th>What happens</th></tr>
 *   <tr><td>DRAFT</td><td><strong>any date at all</strong></td><td>Deleted permanently.</td></tr>
 *   <tr><td>SUBMITTED</td><td>today or later</td><td>Denied.</td></tr>
 *   <tr><td>APPROVED</td><td>today or later</td><td>Denied.</td></tr>
 *   <tr><td>SUBMITTED or APPROVED</td><td>before today</td><td>Untouched. History is not rewritten.</td></tr>
 *   <tr><td>DENIED</td><td>any</td><td>Nothing. Already answered.</td></tr>
 *   <tr><td>ISSUED</td><td>any</td><td>Untouched.</td></tr>
 * </table>
 *
 * <p><strong>Every draft goes, whatever date it carries</strong> (Rajeev, 2026-08-31). The first
 * version of this kept past-dated drafts on the same "history is not rewritten" reasoning that
 * protects a submitted or approved one, and that reasoning does not survive contact with what a
 * draft is. A draft has no history in it — nobody has answered it, nothing was issued against it,
 * and the date on it is not a fact about the past but a field its author can still edit. Filtering
 * drafts by that date filters on something the person can change, which makes it a speed bump
 * rather than a rule.
 *
 * <p>The exploit that argument implies is, as it happens, already closed: while the flag is on,
 * {@code IngredientRequestService} refuses to update or submit anything naming this kitchen, so a
 * leftover draft cannot be dated forward and sent. But the guard only holds <em>while the flag is
 * on</em>. Opt the kitchen back out and every stale draft comes back to life with a date nobody has
 * looked at since. Deleting them closes that, and takes away a list of things that look actionable
 * and can never go anywhere.
 *
 * <p><strong>Two rows are not in Rajeev's original sentence</strong>, and both are decisions rather
 * than readings. {@code SUBMITTED} gets the same answer as {@code APPROVED}: it is a live request
 * awaiting an answer, and the answer is now no. {@code ISSUED} is left alone because it is not
 * really in flight — the goods have left the shelf, the stock movements are append-only, and
 * reversing one would mean writing compensating movements for food that is already cooked.
 *
 * <p><strong>The denial carries a person's name.</strong> {@code decided_by} is the administrator
 * who turned the flag on, because they did in fact cause it. An automatic denial that nobody's name
 * is on is one that nobody can be asked about.
 *
 * <p><strong>Every row is audited, deletions included.</strong> A permanent delete that leaves no
 * trace is precisely what the audit log exists to prevent, and these deletes are not even the
 * author's own doing.
 */
@Component
public class MealPlannerAdoption {

	/** The temple's own day. A request needed "today" is in flight; yesterday's is history. */
	private static final ZoneId TEMPLE_TIME = ZoneId.of("Asia/Kolkata");

	private final JdbcTemplate jdbc;
	private final AuditService auditService;

	public MealPlannerAdoption(JdbcTemplate jdbc, AuditService auditService) {
		this.jdbc = jdbc;
		this.auditService = auditService;
	}

	/**
	 * What turning the flag on would cost, without costing it.
	 *
	 * <p>The screen asks this before it saves, so the person ticking a checkbox is told that two of
	 * somebody's drafts are about to be deleted and three approvals withdrawn, and gets to change
	 * their mind. A checkbox that silently destroys work is not a checkbox anybody should be handed.
	 */
	@Transactional(readOnly = true)
	public Impact preview(UUID kitchenId) {
		LocalDate today = LocalDate.now(TEMPLE_TIME);
		return new Impact(countDrafts(kitchenId), countInFlight(kitchenId, today));
	}

	/**
	 * Settles every request in flight for this kitchen, and reports what it settled.
	 *
	 * <p>Runs inside the caller's transaction — the flag and the requests move together or neither
	 * does, so there is no instant in which a kitchen plans its meals and still holds live requests.
	 */
	@Transactional
	public Impact settle(AuthenticatedUser actor, UUID kitchenId, String kitchenName) {
		LocalDate today = LocalDate.now(TEMPLE_TIME);

		String note = "Denied automatically when %s started using the meal planner on %s."
				.formatted(kitchenName, today);

		List<Map<String, Object>> toDeny = jdbc.queryForList("""
				SELECT id, reference, status FROM ingredient_requests
				WHERE kitchen_id = ? AND needed_on >= ? AND status IN ('SUBMITTED', 'APPROVED')
				""", kitchenId, today);

		for (Map<String, Object> row : toDeny) {
			UUID id = (UUID) row.get("id");
			String was = (String) row.get("status");

			jdbc.update("""
					UPDATE ingredient_requests
					SET status = 'DENIED', decided_by = ?, decided_at = now(), decision_note = ?,
						updated_at = now()
					WHERE id = ?
					""", actor.getUserId(), note, id);

			jdbc.update("""
					INSERT INTO ingredient_request_events
						(tenant_id, request_id, event_type, detail, actor_user_id, actor_name)
					VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, 'DENIED', ?, ?, ?)
					""", id, note, actor.getUserId(), actor.getFullName());

			auditService.record(actor, AuditAction.INGREDIENT_REQUEST_DENIED,
					AuditEntityType.INGREDIENT_REQUEST, id,
					Map.of("status", was),
					Map.of("status", "DENIED", "reference", String.valueOf(row.get("reference"))),
					note);
		}

		// Drafts are read before they are deleted, because the audit row has to be written while
		// there is still something to describe.
		// No date predicate, deliberately — see the class note. Every draft goes.
		List<Map<String, Object>> toDelete = jdbc.queryForList("""
				SELECT id, reference FROM ingredient_requests
				WHERE kitchen_id = ? AND status = 'DRAFT'
				""", kitchenId);

		for (Map<String, Object> row : toDelete) {
			UUID id = (UUID) row.get("id");

			auditService.record(actor, AuditAction.INGREDIENT_REQUEST_DELETED,
					AuditEntityType.INGREDIENT_REQUEST, id,
					Map.of("status", "DRAFT", "reference", String.valueOf(row.get("reference"))),
					null,
					"Deleted automatically when %s started using the meal planner on %s. A draft is "
							.formatted(kitchenName, today)
							+ "unfinished and unanswered, so every one of this kitchen's drafts went, "
							+ "whatever date it carried.");

			jdbc.update("DELETE FROM ingredient_requests WHERE id = ?", id);
		}

		return new Impact(toDelete.size(), toDeny.size());
	}

	/** Every draft this kitchen holds, whatever date it carries. */
	private int countDrafts(UUID kitchenId) {
		Integer count = jdbc.queryForObject("""
				SELECT count(*) FROM ingredient_requests
				WHERE kitchen_id = ? AND status = 'DRAFT'
				""", Integer.class, kitchenId);
		return count == null ? 0 : count;
	}

	/** Requests awaiting or holding an answer for a day that has not passed. */
	private int countInFlight(UUID kitchenId, LocalDate today) {
		Integer count = jdbc.queryForObject("""
				SELECT count(*) FROM ingredient_requests
				WHERE kitchen_id = ? AND needed_on >= ? AND status IN ('SUBMITTED', 'APPROVED')
				""", Integer.class, kitchenId, today);
		return count == null ? 0 : count;
	}

	/**
	 * What the cascade did, or would do.
	 *
	 * @param draftsDeleted drafts that were, or would be, permanently removed
	 * @param requestsDenied requests awaiting or holding approval that were, or would be, refused
	 */
	public record Impact(int draftsDeleted, int requestsDenied) {

		/** True where turning the flag on would disturb nothing, and the screen need not ask. */
		public boolean isNothing() {
			return draftsDeleted == 0 && requestsDenied == 0;
		}
	}
}
