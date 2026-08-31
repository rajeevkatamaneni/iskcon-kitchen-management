package org.iskcon.kms.ingredientrequest;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.auth.Permission;
import org.iskcon.kms.auth.RolePermissions;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.ingredient.Unit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A kitchen asking the store for ingredients, and somebody answering (E10-S5 and E10-S6).
 *
 * <p>This is the second door stock leaves the store by. The first is a meal recorded in the planner,
 * which only works for the kitchen whose meals are in this application; a temple's other kitchens
 * cook food this system never sees and draw from the same store room. So they write down what they
 * need and when, somebody with the authority answers, and — in {@link IngredientIssueService} — the
 * storekeeper records what actually went over the counter.
 *
 * <p><strong>Authorisation is two layers, and they answer different questions.</strong> The
 * permission on the endpoint decides <em>which kind of person</em> may attempt the act;
 * the ownership checks here decide <em>which rows</em> this particular person may touch. A cook
 * holds {@code REQUEST_INGREDIENTS} and may therefore reach the edit endpoint, and is still refused
 * somebody else's draft with {@link ErrorCode#NOT_YOUR_INGREDIENT_REQUEST}. That second layer is
 * expressed as a permission and never as a role: "a Temple Admin may delete anybody's draft" is
 * written here as "anybody holding {@code APPROVE_INGREDIENT_REQUESTS} may", so a temple that gives
 * that permission to its Kitchen Managers gets the behaviour without anybody editing this class.
 *
 * <p><strong>Everyone may read everyone's drafts.</strong> Stated out loud because it is unusual: a
 * draft here is not private. The alternative is two people separately drafting a request for the
 * same feast, and in a temple kitchen that happens.
 *
 * <p><strong>Self-approval is allowed.</strong> Forbidding it would deadlock a temple whose
 * administrator is its only approver, which is most of them. It is recorded in the audit log and
 * printed on the work order as such, so the fact sits on the paper rather than in a log nobody
 * opens.
 *
 * <p><strong>Every foreign id is re-checked through RLS before it is used.</strong> A foreign key
 * check runs as the table owner and is not subject to row-level security, so a kitchen id or an
 * ingredient id belonging to another temple would satisfy the constraint and quietly bind this
 * temple's request to a stranger's row. Every one of them is looked up first through an ordinary
 * query, which RLS does confine, and an id this tenant cannot see is refused as unknown.
 *
 * <p><strong>One code per illegal transition, including the early ones.</strong> The design named a
 * code for every move out of a decided or terminal state, and missed the moves that are wrong only
 * because the request has not got far enough — approving or withdrawing something still in draft,
 * submitting something already submitted. Those first shipped as {@link ErrorCode#VALIDATION_FAILED},
 * which was true and useless: a person told their input is invalid looks at their input, and there is
 * nothing wrong with it. They now raise {@link ErrorCode#INGREDIENT_REQUEST_NOT_SUBMITTED}, which
 * says what is actually the matter.
 */
@Service
public class IngredientRequestService {

	private final JdbcTemplate jdbc;
	private final AuditService auditService;

	public IngredientRequestService(JdbcTemplate jdbc, AuditService auditService) {
		this.jdbc = jdbc;
		this.auditService = auditService;
	}

	// ---- Reading --------------------------------------------------------

	/**
	 * Every request the temple has, newest first, optionally narrowed to one status.
	 *
	 * <p>No ownership filter and no "mine" variant. Any member of staff may read any request,
	 * including a draft, and the screen that needs only their own filters on the requester it can
	 * already see.
	 */
	@Transactional(readOnly = true)
	public List<IngredientRequestSummary> list(IngredientRequestStatus status) {
		if (status == null) {
			return jdbc.query(SELECT_SUMMARY + " ORDER BY r.created_at DESC", SUMMARY_MAPPER);
		}
		return jdbc.query(SELECT_SUMMARY + " WHERE r.status = ? ORDER BY r.created_at DESC",
				SUMMARY_MAPPER, status.name());
	}

	/** One whole request: what it asks for, what is being cooked, and everything done to it. */
	@Transactional(readOnly = true)
	public IngredientRequestView get(UUID id) {
		List<IngredientRequestSummary> found =
				jdbc.query(SELECT_SUMMARY + " WHERE r.id = ?", SUMMARY_MAPPER, id);
		if (found.isEmpty()) {
			throw notFound(id);
		}
		return new IngredientRequestView(found.get(0), lines(id), dishes(id), events(id));
	}

	// ---- The draft (E10-S5) ---------------------------------------------

	/**
	 * Raises a request. It lands in {@code DRAFT} and nobody is asked to answer it yet.
	 *
	 * <p>Lines and dishes may both be empty here. A cook writing down what a festival needs does it
	 * over a morning; the discipline that a request says what it is for arrives at
	 * {@link #submit}, which is the moment somebody else is asked to read it.
	 */
	@Transactional
	public UUID create(AuthenticatedUser actor, CreateIngredientRequest input) {
		resolveKitchen(input.kitchenId());
		validateLines(input.lines());
		validateDishes(input.dishes());

		UUID id = UUID.randomUUID();
		String reference = nextReference(LocalDate.now());
		jdbc.update("""
				INSERT INTO ingredient_requests (
					id, tenant_id, reference, kitchen_id, needed_on, purpose, status, requested_by)
				VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid,
					?, ?, ?, ?, 'DRAFT', ?)
				""", id, reference, input.kitchenId(), input.neededOn(),
				trimToNull(input.purpose()), actor.getUserId());

		writeLines(id, input.lines());
		writeDishes(id, input.dishes());

		// Not audited, and deliberately: a draft is a note to oneself until it is sent, and the five
		// audit actions this epic defines are the five acts somebody else is affected by. What a
		// person reads instead is the event trail, which starts here.
		recordEvent(id, "CREATED", reference + " raised as a draft", actor);
		return id;
	}

	/**
	 * Rewrites a request that has not been answered yet.
	 *
	 * <p>A draft belongs to its author alone. Once it is submitted, an approver may edit it too —
	 * they are the person being asked to answer it, and correcting an obvious slip is faster for
	 * everybody than sending it back. Neither may touch it after it has been decided.
	 */
	@Transactional
	public void update(AuthenticatedUser actor, UUID id, UpdateIngredientRequest input) {
		RequestRow row = row(id);
		requireEditable(row);
		requireMayEdit(actor, row);
		resolveKitchen(input.kitchenId());
		validateLines(input.lines());
		validateDishes(input.dishes());

		jdbc.update("""
				UPDATE ingredient_requests
				SET kitchen_id = ?, needed_on = ?, purpose = ?, updated_at = now()
				WHERE id = ?
				""", input.kitchenId(), input.neededOn(), trimToNull(input.purpose()), id);

		writeLines(id, input.lines());
		writeDishes(id, input.dishes());

		recordEvent(id, "EDITED", row.status() == IngredientRequestStatus.DRAFT
				? "Draft edited" : "Edited while awaiting review", actor);
	}

	/**
	 * Removes a draft outright.
	 *
	 * <p>Its author may, and so may anybody who could have approved it — that is the "a Temple Admin
	 * can delete anybody's draft" rule, written as the permission rather than the role. Nothing else
	 * can be deleted at all: a submitted request is withdrawn to a draft first, and a decided one is
	 * part of the record of what the temple was asked for and what it answered.
	 */
	@Transactional
	public void delete(AuthenticatedUser actor, UUID id) {
		RequestRow row = row(id);
		requireDeletable(row);
		if (!isAuthor(actor, row) && !mayApprove(actor)) {
			throw notYours(id);
		}

		// Audited before the row goes, so the entry describes something that still exists. There is
		// no after-state: there is no after.
		auditService.record(actor, AuditAction.INGREDIENT_REQUEST_DELETED,
				AuditEntityType.INGREDIENT_REQUEST, id, snapshot(row), null,
				isAuthor(actor, row) ? "Deleted by its author." : "Deleted by an approver.");

		jdbc.update("DELETE FROM ingredient_requests WHERE id = ?", id);
	}

	/**
	 * Sends a draft for review.
	 *
	 * <p>Two refusals here, and both are the point of the screen rather than paperwork. A request
	 * that asks for nothing has nothing to answer. A request that does not say what is being cooked
	 * cannot be judged at all: the whole reason the dish list exists is so that "40 kg of rice" can
	 * be read against "200 servings of khichdi", by the approver now and by an auditor later, and a
	 * field that is optional would be blank on exactly the requests where it matters.
	 */
	@Transactional
	public void submit(AuthenticatedUser actor, UUID id) {
		RequestRow row = row(id);
		requireDraft(row);
		if (!isAuthor(actor, row)) {
			throw notYours(id);
		}
		// Re-checked at submission, not only at create: the kitchen may have joined the meal planner
		// or been closed while the draft sat there.
		resolveKitchen(row.kitchenId());

		if (countChildren("ingredient_request_lines", id) == 0) {
			throw new ApplicationException(ErrorCode.INGREDIENT_REQUEST_EMPTY,
					Map.of("ingredientRequestId", id));
		}
		if (countChildren("ingredient_request_dishes", id) == 0) {
			throw new ApplicationException(ErrorCode.INGREDIENT_REQUEST_NEEDS_DISHES,
					Map.of("ingredientRequestId", id));
		}

		jdbc.update("""
				UPDATE ingredient_requests
				SET status = 'SUBMITTED', submitted_at = now(), updated_at = now()
				WHERE id = ?
				""", id);

		auditService.record(actor, AuditAction.INGREDIENT_REQUEST_SUBMITTED,
				AuditEntityType.INGREDIENT_REQUEST, id,
				Map.of("status", "DRAFT"), Map.of("status", "SUBMITTED"), null);
		recordEvent(id, "SUBMITTED", row.reference() + " sent for review", actor);
	}

	// ---- The answer (E10-S6) --------------------------------------------

	/**
	 * Answers yes. The store may now issue against it; nothing has moved yet, because approval is a
	 * decision and issuing is a physical event.
	 */
	@Transactional
	public void approve(AuthenticatedUser actor, UUID id, String note) {
		decide(actor, id, IngredientRequestStatus.APPROVED, note,
				AuditAction.INGREDIENT_REQUEST_APPROVED, "APPROVED");
	}

	/**
	 * Answers no. Terminal: a refusal that can be edited into a different request and shown again is
	 * not a refusal. The kitchen raises a fresh request, and this one stays on the record with its
	 * note for whoever asks later.
	 */
	@Transactional
	public void deny(AuthenticatedUser actor, UUID id, String note) {
		decide(actor, id, IngredientRequestStatus.DENIED, note,
				AuditAction.INGREDIENT_REQUEST_DENIED, "DENIED");
	}

	/**
	 * Takes a submitted request back to a draft.
	 *
	 * <p>Strictly less power than the edit its author already has, and it stops an approver reading
	 * a request somebody is halfway through rewriting. Available to the author or to an approver —
	 * the latter because "this is not ready, have it back" is a reasonable answer that is not a
	 * denial.
	 */
	@Transactional
	public void withdraw(AuthenticatedUser actor, UUID id) {
		RequestRow row = requireAwaitingReview(id);
		if (!isAuthor(actor, row) && !mayApprove(actor)) {
			throw notYours(id);
		}

		jdbc.update("""
				UPDATE ingredient_requests
				SET status = 'DRAFT', submitted_at = NULL, updated_at = now()
				WHERE id = ?
				""", id);

		// No audit action exists for this and none is wanted: nothing was decided and nothing left
		// the store. The trail carries it, which is where somebody would look.
		recordEvent(id, "WITHDRAWN", row.reference() + " withdrawn to a draft", actor);
	}

	/**
	 * The one place a decision is written, shared by {@link #approve} and {@link #deny} exactly as
	 * {@code LeaveService.decide} is shared by its two. The guard runs first, so both verbs refuse
	 * the same illegal moves with the same codes, and neither can drift from the other.
	 */
	private void decide(AuthenticatedUser actor, UUID id, IngredientRequestStatus outcome,
			String note, AuditAction action, String eventType) {

		RequestRow row = requireAwaitingReview(id);

		jdbc.update("""
				UPDATE ingredient_requests
				SET status = ?, decided_by = ?, decided_at = now(), decision_note = ?, updated_at = now()
				WHERE id = ?
				""", outcome.name(), actor.getUserId(), trimToNull(note), id);

		boolean ownRequest = isAuthor(actor, row);
		auditService.record(actor, action, AuditEntityType.INGREDIENT_REQUEST, id,
				Map.of("status", "SUBMITTED"), Map.of("status", outcome.name()),
				ownRequest
						// Allowed, and recorded as what it is. Forbidding it would deadlock a temple
						// whose administrator is its only approver.
						? "Answered by the person who raised it."
						: trimToNull(note));

		String detail = row.reference() + " " + outcome.name().toLowerCase(java.util.Locale.ENGLISH)
				+ (ownRequest ? " by the person who raised it" : "")
				+ (trimToNull(note) == null ? "" : " — " + trimToNull(note));
		recordEvent(id, eventType, detail, actor);
	}

	// ---- The guard ------------------------------------------------------

	/**
	 * The request must be waiting for an answer. Every illegal source state gets the code that says
	 * the actual thing that is wrong, so the person is told whether somebody already answered, or
	 * the goods have already gone, rather than a single "no".
	 */
	private RequestRow requireAwaitingReview(UUID id) {
		RequestRow row = row(id);
		// A switch expression rather than a chain of ifs, so that a sixth status added to the enum
		// fails to compile here instead of falling quietly through the guard.
		return switch (row.status()) {
			case SUBMITTED -> row;
			case DRAFT -> throw wrongStatus(id, row.status());
			case APPROVED, DENIED -> throw alreadyDecided(id, row.status());
			case ISSUED -> throw alreadyIssued(id);
		};
	}

	/** Editing is open while nobody has answered — a draft, or one out for review. */
	private void requireEditable(RequestRow row) {
		switch (row.status()) {
			case DRAFT, SUBMITTED -> {
				// Open.
			}
			// Changing what somebody approved would make the approval describe something else.
			case APPROVED -> throw alreadyDecided(row.id(), row.status());
			case DENIED -> throw notEditable(row.id(), row.status());
			case ISSUED -> throw alreadyIssued(row.id());
		}
	}

	/**
	 * Only a draft is deleted. A submitted request is withdrawn first, which is a deliberate second
	 * act rather than an obstacle — it means nobody can pull a request out from under the approver
	 * reading it.
	 */
	private void requireDeletable(RequestRow row) {
		switch (row.status()) {
			case DRAFT -> {
				// Open.
			}
			case SUBMITTED, DENIED -> throw notEditable(row.id(), row.status());
			case APPROVED -> throw alreadyDecided(row.id(), row.status());
			case ISSUED -> throw alreadyIssued(row.id());
		}
	}

	private void requireDraft(RequestRow row) {
		switch (row.status()) {
			case DRAFT -> {
				// Open.
			}
			case SUBMITTED -> throw wrongStatus(row.id(), row.status());
			case APPROVED, DENIED -> throw alreadyDecided(row.id(), row.status());
			case ISSUED -> throw alreadyIssued(row.id());
		}
	}

	/** A draft is its author's alone; a submitted request is also the approver's to correct. */
	private void requireMayEdit(AuthenticatedUser actor, RequestRow row) {
		if (isAuthor(actor, row)) {
			return;
		}
		if (row.status() == IngredientRequestStatus.SUBMITTED && mayApprove(actor)) {
			return;
		}
		throw notYours(row.id());
	}

	// ---- Validation -----------------------------------------------------

	/**
	 * The kitchen is one of this temple's, is open, and still asks the store for its ingredients.
	 *
	 * <p>The last of those is the one that matters most. A kitchen that plans its meals here draws
	 * its stock when a meal is recorded; letting it also raise a request would issue the same food
	 * twice, once through each door. Checked on create, on edit and again at submission, because the
	 * flag can be turned on while a draft sits there.
	 */
	private void resolveKitchen(UUID kitchenId) {
		List<KitchenFacts> found = jdbc.query("""
				SELECT id, status, uses_meal_planner FROM kitchens WHERE id = ?
				""", (rs, n) -> new KitchenFacts(
						rs.getObject("id", UUID.class),
						rs.getString("status"),
						rs.getBoolean("uses_meal_planner")), kitchenId);

		if (found.isEmpty()) {
			throw new ApplicationException(ErrorCode.KITCHEN_NOT_FOUND, Map.of("kitchenId", kitchenId));
		}
		KitchenFacts kitchen = found.get(0);
		if ("ARCHIVED".equals(kitchen.status())) {
			throw new ApplicationException(ErrorCode.KITCHEN_ARCHIVED, Map.of("kitchenId", kitchenId));
		}
		if (kitchen.usesMealPlanner()) {
			throw new ApplicationException(ErrorCode.KITCHEN_PLANS_ITS_OWN_MEALS,
					Map.of("kitchenId", kitchenId));
		}
	}

	/**
	 * Every ingredient is one this temple holds, and every unit is one that ingredient can be
	 * measured in.
	 *
	 * <p>The family is what is compared, not the unit itself. Half a kilo of a rice the catalogue
	 * holds in kilograms is 500 gm and is the same rice; three litres of it is not a quantity of rice
	 * at all, and a request that says so would arrive at the store room as a question nobody can
	 * answer. {@code InventoryItemService.adjust} already refuses it on the same grounds.
	 */
	private void validateLines(List<IngredientRequestLineInput> lines) {
		if (lines == null || lines.isEmpty()) {
			return;
		}
		List<UUID> ids = lines.stream().map(IngredientRequestLineInput::ingredientId).distinct().toList();
		Map<UUID, Unit> canonical = loadCanonicalUnits(ids);

		for (int i = 0; i < lines.size(); i++) {
			IngredientRequestLineInput line = lines.get(i);
			Unit ingredientUnit = canonical.get(line.ingredientId());
			if (ingredientUnit == null) {
				// Not found through RLS, so either it does not exist or it is another temple's. The
				// foreign key would have taken it: FK checks run as the table owner.
				throw new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of(
						"field", "lines[" + i + "].ingredientId", "value", line.ingredientId()));
			}
			if (line.unit().family() != ingredientUnit.family()) {
				throw new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of(
						"field", "lines[" + i + "].unit",
						"value", line.unit().name(),
						"expectedFamily", ingredientUnit.family().name()));
			}
		}
	}

	/**
	 * A dish may be measured in anything food is genuinely made in, servings included — 200 servings
	 * of khichdi, four litres of sweet rice, six kilos of pickle. There is nothing to check it
	 * against, because a dish is a name and a number and points at no catalogue row.
	 */
	private void validateDishes(List<IngredientRequestDishInput> dishes) {
		if (dishes == null) {
			return;
		}
		for (int i = 0; i < dishes.size(); i++) {
			if (dishes.get(i).quantity().signum() <= 0) {
				throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
						Map.of("field", "dishes[" + i + "].quantity"));
			}
		}
	}

	/**
	 * How much is waiting for somebody to answer, for the morning screen (E10 follow-on).
	 *
	 * <p>Counted in the database rather than by listing and sizing: Today shows a number and a way
	 * in, and pulling every pending row to count it would be the morning screen doing the work of
	 * the page it links to.
	 *
	 * @param soonBy the far end of "needed soon" — tomorrow. A request for a feast three weeks out
	 *               and one needed this afternoon are not the same news.
	 */
	@Transactional(readOnly = true)
	public AwaitingReview awaitingReview(LocalDate soonBy) {
		return jdbc.queryForObject("""
				SELECT count(*) AS total,
					   count(*) FILTER (WHERE needed_on <= ?) AS soon
				FROM ingredient_requests
				WHERE status = 'SUBMITTED'
				""",
				(rs, n) -> new AwaitingReview(rs.getInt("total"), rs.getInt("soon")),
				soonBy);
	}

	/**
	 * @param total requests sent for review and not yet answered
	 * @param soon  of those, the ones needed today or tomorrow
	 */
	public record AwaitingReview(int total, int soon) {
	}

	// ---- Writing the children -------------------------------------------

	/**
	 * Replaces the ingredient lines outright.
	 *
	 * <p>Deleted and rewritten rather than diffed. A line's identity is nothing but its place in the
	 * list — it carries no history and nothing points at it until the issue is recorded, which is
	 * after the last edit — so matching old rows to new ones would buy nothing and could only get it
	 * wrong.
	 */
	private void writeLines(UUID requestId, List<IngredientRequestLineInput> lines) {
		jdbc.update("DELETE FROM ingredient_request_lines WHERE request_id = ?", requestId);
		if (lines == null) {
			return;
		}
		int lineNo = 1;
		for (IngredientRequestLineInput line : lines) {
			jdbc.update("""
					INSERT INTO ingredient_request_lines (
						id, tenant_id, request_id, line_no, ingredient_id, quantity, unit, note)
					VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?, ?)
					""", UUID.randomUUID(), requestId, lineNo++, line.ingredientId(),
					line.quantity(), line.unit().name(), trimToNull(line.note()));
		}
	}

	/** Replaces the dish list outright, for the same reason the lines are replaced. */
	private void writeDishes(UUID requestId, List<IngredientRequestDishInput> dishes) {
		jdbc.update("DELETE FROM ingredient_request_dishes WHERE request_id = ?", requestId);
		if (dishes == null) {
			return;
		}
		int lineNo = 1;
		for (IngredientRequestDishInput dish : dishes) {
			jdbc.update("""
					INSERT INTO ingredient_request_dishes (
						id, tenant_id, request_id, line_no, dish_name, quantity, unit)
					VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?)
					""", UUID.randomUUID(), requestId, lineNo++, dish.dishName().trim(),
					dish.quantity(), dish.unit().name());
		}
	}

	// ---- Shared with the issue service -----------------------------------

	/**
	 * One line of the request's readable history.
	 *
	 * <p>Package-private because {@link IngredientIssueService} writes the last entry on most
	 * requests. Copied from {@code PurchaseOrderService.recordEvent}, which answers the same
	 * question for a purchase order, so the two screens read the same way. The actor's name is stored
	 * rather than joined, so the trail keeps naming them after they leave the temple.
	 */
	void recordEvent(UUID requestId, String eventType, String detail, AuthenticatedUser actor) {
		jdbc.update(connection -> {
			var ps = connection.prepareStatement("""
					INSERT INTO ingredient_request_events (
						tenant_id, request_id, event_type, detail, actor_user_id, actor_name)
					VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?)
					""");
			ps.setObject(1, requestId);
			ps.setString(2, eventType);
			ps.setString(3, detail);
			ps.setObject(4, actor == null ? null : actor.getUserId());
			ps.setString(5, actor == null ? null : actor.getFullName());
			return ps;
		});
	}

	/**
	 * The request's heading as the state machine needs it. RLS-scoped, so another temple's request
	 * is simply not found.
	 */
	RequestRow row(UUID id) {
		List<RequestRow> rows = jdbc.query("""
				SELECT id, reference, kitchen_id, needed_on, status, requested_by
				FROM ingredient_requests WHERE id = ?
				""", ROW_MAPPER, id);
		if (rows.isEmpty()) {
			throw notFound(id);
		}
		return rows.get(0);
	}

	static ApplicationException notFound(UUID id) {
		return new ApplicationException(ErrorCode.INGREDIENT_REQUEST_NOT_FOUND,
				Map.of("ingredientRequestId", id));
	}

	/** The request's heading, as the guard and the audit snapshot need it. */
	record RequestRow(
			UUID id, String reference, UUID kitchenId, LocalDate neededOn,
			IngredientRequestStatus status, UUID requestedBy) {
	}

	// ---------------------------------------------------------------------

	/**
	 * The next reference for this temple: {@code IR-2026-0041}.
	 *
	 * <p>One counter per temple, minted inside the caller's transaction. The atomic increment row
	 * locks per tenant, so two people raising a request in the same instant never share a number, and
	 * a creation that rolls back simply leaves a gap — a gap is harmless, a duplicate is not. The
	 * year is the year it was minted in and is a reading aid, not part of the key: the unique index
	 * is on the whole reference, and the counter does not restart.
	 */
	private String nextReference(LocalDate today) {
		Integer seq = jdbc.queryForObject("""
				INSERT INTO ingredient_request_sequence (tenant_id, last_number)
				VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, 1)
				ON CONFLICT (tenant_id)
					DO UPDATE SET last_number = ingredient_request_sequence.last_number + 1
				RETURNING last_number
				""", Integer.class);
		return "IR-" + today.getYear() + "-" + String.format("%04d", seq);
	}

	private Map<UUID, Unit> loadCanonicalUnits(List<UUID> ingredientIds) {
		Map<UUID, Unit> units = new LinkedHashMap<>();
		for (UUID ingredientId : ingredientIds) {
			jdbc.query("SELECT canonical_unit FROM ingredients WHERE id = ?",
					rs -> {
						units.put(ingredientId, Unit.valueOf(rs.getString("canonical_unit")));
					}, ingredientId);
		}
		return units;
	}

	private int countChildren(String table, UUID requestId) {
		Integer count = jdbc.queryForObject(
				"SELECT count(*) FROM " + table + " WHERE request_id = ?", Integer.class, requestId);
		return count == null ? 0 : count;
	}

	private List<IngredientRequestLineView> lines(UUID requestId) {
		return jdbc.query("""
				SELECT l.id, l.line_no, l.ingredient_id, i.name AS ingredient_name,
					   l.quantity, l.unit, l.issued_quantity, l.issued_unit, l.note
				FROM ingredient_request_lines l
				JOIN ingredients i ON i.id = l.ingredient_id
				WHERE l.request_id = ?
				ORDER BY l.line_no
				""", LINE_MAPPER, requestId);
	}

	private List<IngredientRequestDishView> dishes(UUID requestId) {
		return jdbc.query("""
				SELECT id, line_no, dish_name, quantity, unit
				FROM ingredient_request_dishes WHERE request_id = ? ORDER BY line_no
				""", DISH_MAPPER, requestId);
	}

	private List<IngredientRequestEventView> events(UUID requestId) {
		return jdbc.query("""
				SELECT id, event_type, detail, actor_name, created_at
				FROM ingredient_request_events WHERE request_id = ? ORDER BY created_at, id
				""", EVENT_MAPPER, requestId);
	}

	private boolean isAuthor(AuthenticatedUser actor, RequestRow row) {
		return row.requestedBy() != null && row.requestedBy().equals(actor.getUserId());
	}

	/**
	 * Whether this person could have answered the request. Asked as a permission and never as a
	 * role, so the policy stays in {@code RolePermissions} where it can be read as a document.
	 */
	private boolean mayApprove(AuthenticatedUser actor) {
		return actor.getRole() != null
				&& RolePermissions.has(actor.getRole(), Permission.APPROVE_INGREDIENT_REQUESTS);
	}

	private static Map<String, Object> snapshot(RequestRow row) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("reference", row.reference());
		snapshot.put("kitchenId", row.kitchenId());
		snapshot.put("neededOn", row.neededOn().toString());
		snapshot.put("status", row.status().name());
		return snapshot;
	}

	private static ApplicationException notYours(UUID id) {
		return new ApplicationException(ErrorCode.NOT_YOUR_INGREDIENT_REQUEST,
				Map.of("ingredientRequestId", id));
	}

	private static ApplicationException alreadyDecided(UUID id, IngredientRequestStatus status) {
		return new ApplicationException(ErrorCode.INGREDIENT_REQUEST_ALREADY_DECIDED,
				Map.of("ingredientRequestId", id, "status", status.name()));
	}

	private static ApplicationException alreadyIssued(UUID id) {
		return new ApplicationException(ErrorCode.INGREDIENT_REQUEST_ALREADY_ISSUED,
				Map.of("ingredientRequestId", id));
	}

	private static ApplicationException notEditable(UUID id, IngredientRequestStatus status) {
		return new ApplicationException(ErrorCode.INGREDIENT_REQUEST_NOT_EDITABLE,
				Map.of("ingredientRequestId", id, "status", status.name()));
	}

	/**
	 * Right act, wrong moment — approving, denying, withdrawing or re-submitting something that has
	 * not been sent for review yet.
	 *
	 * <p>This used to raise {@code VALIDATION_FAILED}, which was true and useless: a person told
	 * their input was invalid looks at their input, and there is nothing wrong with it. The request
	 * is simply earlier in its life than the act assumes, and the message says so.
	 */
	private static ApplicationException wrongStatus(UUID id, IngredientRequestStatus status) {
		return new ApplicationException(ErrorCode.INGREDIENT_REQUEST_NOT_SUBMITTED,
				Map.of("ingredientRequestId", id, "status", status.name()));
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private record KitchenFacts(UUID id, String status, boolean usesMealPlanner) {
	}

	// ---------------------------------------------------------------------

	private static final String SELECT_SUMMARY = """
			SELECT r.id, r.reference, r.kitchen_id, k.name AS kitchen_name, r.needed_on, r.purpose,
				   r.status, r.requested_by, requester.full_name AS requested_by_name, r.submitted_at,
				   decider.full_name AS decided_by_name, r.decided_at, r.issued_at,
				   (SELECT count(*) FROM ingredient_request_lines l WHERE l.request_id = r.id) AS line_count,
				   (SELECT count(*) FROM ingredient_request_dishes d WHERE d.request_id = r.id) AS dish_count
			FROM ingredient_requests r
			JOIN kitchens k ON k.id = r.kitchen_id
			LEFT JOIN users requester ON requester.id = r.requested_by
			LEFT JOIN users decider ON decider.id = r.decided_by
			""";

	private static final RowMapper<IngredientRequestSummary> SUMMARY_MAPPER = (rs, n) ->
			new IngredientRequestSummary(
					rs.getObject("id", UUID.class),
					rs.getString("reference"),
					rs.getObject("kitchen_id", UUID.class),
					rs.getString("kitchen_name"),
					rs.getObject("needed_on", LocalDate.class),
					rs.getString("purpose"),
					IngredientRequestStatus.valueOf(rs.getString("status")),
					rs.getObject("requested_by", UUID.class),
					rs.getString("requested_by_name"),
					instant(rs.getObject("submitted_at", OffsetDateTime.class)),
					rs.getString("decided_by_name"),
					instant(rs.getObject("decided_at", OffsetDateTime.class)),
					instant(rs.getObject("issued_at", OffsetDateTime.class)),
					rs.getInt("line_count"),
					rs.getInt("dish_count"));

	private static final RowMapper<IngredientRequestLineView> LINE_MAPPER = (rs, n) ->
			new IngredientRequestLineView(
					rs.getObject("id", UUID.class),
					rs.getInt("line_no"),
					rs.getObject("ingredient_id", UUID.class),
					rs.getString("ingredient_name"),
					rs.getBigDecimal("quantity"),
					rs.getString("unit"),
					rs.getBigDecimal("issued_quantity"),
					rs.getString("issued_unit"),
					rs.getString("note"));

	private static final RowMapper<IngredientRequestDishView> DISH_MAPPER = (rs, n) ->
			new IngredientRequestDishView(
					rs.getObject("id", UUID.class),
					rs.getInt("line_no"),
					rs.getString("dish_name"),
					rs.getBigDecimal("quantity"),
					rs.getString("unit"));

	private static final RowMapper<IngredientRequestEventView> EVENT_MAPPER = (rs, n) ->
			new IngredientRequestEventView(
					rs.getObject("id", UUID.class),
					rs.getString("event_type"),
					rs.getString("detail"),
					rs.getString("actor_name"),
					instant(rs.getObject("created_at", OffsetDateTime.class)));

	private static final RowMapper<RequestRow> ROW_MAPPER = (rs, n) -> new RequestRow(
			rs.getObject("id", UUID.class),
			rs.getString("reference"),
			rs.getObject("kitchen_id", UUID.class),
			rs.getObject("needed_on", LocalDate.class),
			IngredientRequestStatus.valueOf(rs.getString("status")),
			rs.getObject("requested_by", UUID.class));

	private static java.time.Instant instant(OffsetDateTime value) {
		return value == null ? null : value.toInstant();
	}
}
