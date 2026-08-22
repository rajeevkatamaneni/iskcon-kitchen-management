package org.iskcon.kms.meal;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.calendar.CalendarDayView;
import org.iskcon.kms.calendar.CalendarService;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.occasion.OccasionService;
import org.iskcon.kms.occasion.ResolvedOccasion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Meal planning (E4-S4, redesigned by E4-S7). A plan is a recipe, a target quantity, a kind of meal,
 * and the time it must be ready.
 *
 * <p>What a planner is <em>not</em> asked is what sort of day it is: weekend follows from the date,
 * festival from the calendar, and catering is now a kind of meal rather than a kind of day. The day
 * type is derived here and stored, because a festival still explains a large serving count a year
 * later — but nobody chooses it.
 *
 * <p>What this class no longer does is cook. Marking one dish cooked was a button beside every dish
 * on the planner, and the brief took it away: a cook with hot oil in front of them does not touch a
 * screen, and the temple wants what actually went out rather than a tick. Drawing stock now happens
 * once for a whole meal, from the returned job card, in {@link ServedMealService} — and a dish that
 * has been through that can no longer be edited or cancelled, because the stock has moved and a
 * mistake there is corrected with an inventory adjustment (E3-S7), not by erasing history.
 */
@Service
public class MealPlanService {

	private final JdbcTemplate jdbc;
	private final AuditService auditService;
	private final OccasionService occasionService;
	private final CalendarService calendarService;
	private final MealKindService mealKindService;
	private final EkadashiPolicy ekadashiPolicy;

	// No consumption service here any more: drawing stock belongs to recording a whole meal, which
	// ServedMealService owns. This class plans; it no longer cooks.
	public MealPlanService(
			JdbcTemplate jdbc, AuditService auditService, OccasionService occasionService,
			CalendarService calendarService,
			MealKindService mealKindService, EkadashiPolicy ekadashiPolicy) {
		this.jdbc = jdbc;
		this.auditService = auditService;
		this.occasionService = occasionService;
		this.calendarService = calendarService;
		this.mealKindService = mealKindService;
		this.ekadashiPolicy = ekadashiPolicy;
	}

	// ---- Day-type suggestion --------------------------------------------

	/** What the planner should pre-fill for a date: day-type, festival, servings, Ekadashi flag. */
	@Transactional(readOnly = true)
	public DayContext dayContext(LocalDate date) {
		boolean ekadashi = calendarService.day(date).map(CalendarDayView::isEkadashi).orElse(false);
		List<ResolvedOccasion> occasions = occasionService.resolve(date, date);
		if (!occasions.isEmpty()) {
			ResolvedOccasion o = occasions.get(0);
			return new DayContext(DayType.FESTIVAL, o.name(), o.defaultServings(), ekadashi);
		}
		DayOfWeek dow = date.getDayOfWeek();
		DayType suggested = (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY)
				? DayType.WEEKEND : DayType.REGULAR;
		return new DayContext(suggested, null, null, ekadashi);
	}

	/** Whether planning a recipe on a date raises an Ekadashi warning (E4-S6). */
	@Transactional(readOnly = true)
	public EkadashiCheck ekadashiCheck(LocalDate date, UUID recipeId) {
		boolean isEkadashi = calendarService.day(date).map(CalendarDayView::isEkadashi).orElse(false);
		EkadashiPolicy.Compatibility c = ekadashiPolicy.of(recipeId);
		return new EkadashiCheck(isEkadashi, c.compatible(), c.offendingIngredients());
	}

	/**
	 * Enforces the Ekadashi rule and returns whether an acknowledgment should be recorded on the plan.
	 * If the day is Ekadashi and the recipe is not compatible, planning is blocked unless the caller
	 * explicitly acknowledged it — the only, always-recorded path past the warning (no silent bypass).
	 */
	private boolean resolveEkadashiAck(LocalDate date, UUID recipeId, boolean acknowledged) {
		EkadashiCheck check = ekadashiCheck(date, recipeId);
		if (!check.isEkadashi() || check.compatible()) {
			return false;
		}
		if (!acknowledged) {
			throw new ApplicationException(ErrorCode.EKADASHI_NOT_ACKNOWLEDGED,
					Map.of("recipeId", recipeId, "offending", check.offendingIngredients()));
		}
		return true;
	}

	// ---- Read -----------------------------------------------------------

	@Transactional(readOnly = true)
	public List<MealPlanView> list(LocalDate from, LocalDate to, MealStatus status, DayType dayType) {
		StringBuilder sql = new StringBuilder(SELECT + " WHERE 1 = 1");
		List<Object> args = new ArrayList<>();
		if (from != null) {
			sql.append(" AND mp.plan_date >= ?");
			args.add(from);
		}
		if (to != null) {
			sql.append(" AND mp.plan_date <= ?");
			args.add(to);
		}
		if (status != null) {
			sql.append(" AND mp.status = ?");
			args.add(status.name());
		}
		if (dayType != null) {
			sql.append(" AND mp.day_type = ?");
			args.add(dayType.name());
		}
		sql.append(" ORDER BY mp.plan_date, mp.ready_by, mp.meal_kind");
		return jdbc.query(sql.toString(), MAPPER, args.toArray());
	}

	@Transactional(readOnly = true)
	public MealPlanView get(UUID id) {
		return findById(id).orElseThrow(() -> notFound(id));
	}

	// ---- Write ----------------------------------------------------------

	/**
	 * Copies the previous week's meals into the week beginning {@code weekStart} (E3).
	 *
	 * <p>Most weeks in a temple kitchen look like the last one, so this is the planner's shortcut —
	 * but a shortcut that destroys work is worse than no shortcut. So it only ever adds: a day with
	 * anything already planned on it is left exactly as it is, which makes pressing the button twice
	 * harmless, and pressing it on a half-planned week safe.
	 *
	 * <p>Each meal is copied through {@link #create}, not by inserting a row, so every rule that
	 * governs a meal still governs a copied one. That matters most for the two things that depend on
	 * the date rather than the meal: a Sunday feast copied onto an ordinary Wednesday is an ordinary
	 * Wednesday meal, and a meal landing on a fast day it does not suit is refused rather than
	 * acknowledged on the planner's behalf — nobody is looking at that meal to say it is all right.
	 */
	@Transactional
	public DuplicateWeekResult duplicateWeek(AuthenticatedUser actor, LocalDate weekStart) {
		LocalDate sourceStart = weekStart.minusWeeks(1);
		// Against the enum, not its name: status() is a MealStatus, so comparing it to a String is
		// quietly always false and would copy cancelled meals back into life.
		List<MealPlanView> source = list(sourceStart, sourceStart.plusDays(6), null, null).stream()
				.filter(m -> m.status() != MealStatus.CANCELLED)
				.toList();
		if (source.isEmpty()) {
			return new DuplicateWeekResult(0, 0, 0, true);
		}

		int copied = 0;
		int daysAlreadyPlanned = 0;
		int refusedOnFast = 0;

		for (int offset = 0; offset < 7; offset++) {
			LocalDate target = weekStart.plusDays(offset);
			LocalDate from = sourceStart.plusDays(offset);

			List<MealPlanView> thatDay = source.stream()
					.filter(m -> m.planDate().equals(from))
					.toList();
			if (thatDay.isEmpty()) {
				continue;
			}
			boolean occupied = list(target, target, null, null).stream()
					.anyMatch(m -> m.status() != MealStatus.CANCELLED);
			if (occupied) {
				daysAlreadyPlanned++;
				continue;
			}

			for (MealPlanView meal : thatDay) {
				EkadashiCheck check = ekadashiCheck(target, meal.recipeId());
				if (check.isEkadashi() && !check.compatible()) {
					refusedOnFast++;
					continue;
				}
				// The occasion is deliberately not carried across. A feast copied onto an ordinary
				// Wednesday is not last week's festival, and the derivation on the target date is the
				// only thing that can say what it is. The crew figure does carry: three dishes for a
				// hundred take the same hands whatever the date.
				create(actor, new CreateMealPlanRequest(
						target, meal.mealKind(), meal.recipeId(), meal.targetYield(), meal.readyBy(),
						meal.clientName(), meal.clientContact(), meal.venue(), meal.purpose(), null,
						meal.adults(), meal.children(), meal.seniors(), meal.crewRequired(),
						meal.kitchenNotes(), false));
				copied++;
			}
		}
		return new DuplicateWeekResult(copied, daysAlreadyPlanned, refusedOnFast, false);
	}

	@Transactional
	public UUID create(AuthenticatedUser actor, CreateMealPlanRequest request) {
		MealKindView kind = mealKindService.require(request.mealKind());
		RecipeRef recipe = findRecipe(request.recipeId());

		LocalTime readyBy = resolveReadyBy(kind, request.readyBy());
		requireKindFields(kind, request.clientName(), request.venue(), request.purpose());
		DayType dayType = deriveDayType(kind, request.planDate());
		String occasionName = resolveOccasionName(kind, dayType, request.planDate(), request.occasionName());
		boolean recordAck = resolveEkadashiAck(request.planDate(), request.recipeId(), request.ekadashiAcknowledged());

		UUID id = UUID.randomUUID();
		jdbc.update(connection -> {
			var ps = connection.prepareStatement("""
					INSERT INTO meal_plans (
						id, tenant_id, plan_date, meal_kind, ready_by, recipe_id, target_yield,
						day_type, occasion_name, status, client_name, client_contact, venue, purpose,
						adults, children, seniors, crew_required, kitchen_notes,
						ekadashi_ack_by, ekadashi_ack_at, created_by)
					VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid,
						?, ?, ?, ?, ?, ?, ?, 'PLANNED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
					""");
			ps.setObject(1, id);
			ps.setObject(2, request.planDate());
			ps.setString(3, kind.name());
			ps.setObject(4, readyBy);
			ps.setObject(5, request.recipeId());
			ps.setBigDecimal(6, request.targetYield());
			ps.setString(7, dayType.name());
			ps.setString(8, occasionName);
			ps.setString(9, trimToNull(request.clientName()));
			ps.setString(10, trimToNull(request.clientContact()));
			ps.setString(11, trimToNull(request.venue()));
			ps.setString(12, trimToNull(request.purpose()));
			ps.setObject(13, request.adults(), java.sql.Types.INTEGER);
			ps.setObject(14, request.children(), java.sql.Types.INTEGER);
			ps.setObject(15, request.seniors(), java.sql.Types.INTEGER);
			ps.setObject(16, request.crewRequired(), java.sql.Types.INTEGER);
			ps.setString(17, trimToNull(request.kitchenNotes()));
			ps.setObject(18, recordAck ? actor.getUserId() : null);
			ps.setObject(19, recordAck ? OffsetDateTime.now(java.time.ZoneOffset.UTC) : null);
			ps.setObject(20, actor.getUserId());
			return ps;
		});

		auditService.record(actor, AuditAction.MEAL_PLANNED, AuditEntityType.MEAL_PLAN, id,
				null, snapshot(request.planDate(), kind.name(), readyBy, recipe.name(), dayType), null);
		return id;
	}

	/**
	 * Swaps or edits a dish in place (B4) — the recipe, the servings, the head count, the notes.
	 *
	 * <p>Allowed right up until the meal is recorded, and refused the moment it is. A cooked dish has
	 * had its ingredients drawn against a figure, and letting somebody change the figure afterwards
	 * would leave the stock ledger describing a meal that never happened; a mistake there is corrected
	 * with an inventory adjustment (E3-S7), not by rewriting the past.
	 *
	 * <p>The two refusals say different things on purpose. A cooked dish, or one belonging to a meal
	 * whose card has already been typed in, is MEAL_ALREADY_RECORDED — the change is too late.
	 * A cancelled dish is MEAL_PLAN_NOT_OPEN — the change is beside the point.
	 */
	@Transactional
	public void update(AuthenticatedUser actor, UUID id, UpdateMealPlanRequest request) {
		MealPlanRow before = findRow(id).orElseThrow(() -> notFound(id));
		if (before.status() == MealStatus.COOKED || mealRecorded(before)) {
			throw new ApplicationException(ErrorCode.MEAL_ALREADY_RECORDED, Map.of("mealPlanId", id));
		}
		if (before.status() != MealStatus.PLANNED) {
			throw new ApplicationException(ErrorCode.MEAL_PLAN_NOT_OPEN, Map.of("mealPlanId", id));
		}
		MealKindView kind = mealKindService.require(request.mealKind());
		RecipeRef recipe = findRecipe(request.recipeId());
		LocalTime readyBy = resolveReadyBy(kind, request.readyBy());
		requireKindFields(kind, request.clientName(), request.venue(), request.purpose());
		DayType dayType = deriveDayType(kind, request.planDate());
		String occasionName = resolveOccasionName(kind, dayType, request.planDate(), request.occasionName());
		boolean recordAck = resolveEkadashiAck(request.planDate(), request.recipeId(), request.ekadashiAcknowledged());

		jdbc.update("""
				UPDATE meal_plans
				SET plan_date = ?, meal_kind = ?, ready_by = ?, recipe_id = ?, target_yield = ?,
					day_type = ?, occasion_name = ?, client_name = ?, client_contact = ?, venue = ?,
					purpose = ?, adults = ?, children = ?, seniors = ?, crew_required = ?,
					kitchen_notes = ?,
					ekadashi_ack_by = ?, ekadashi_ack_at = ?, updated_at = now()
				WHERE id = ?
				""",
				request.planDate(), kind.name(), readyBy, request.recipeId(), request.targetYield(),
				dayType.name(), occasionName, trimToNull(request.clientName()),
				trimToNull(request.clientContact()), trimToNull(request.venue()),
				trimToNull(request.purpose()),
				request.adults(), request.children(), request.seniors(), request.crewRequired(),
				trimToNull(request.kitchenNotes()),
				recordAck ? actor.getUserId() : null,
				recordAck ? OffsetDateTime.now(java.time.ZoneOffset.UTC) : null,
				id);

		auditService.record(actor, AuditAction.MEAL_PLAN_UPDATED, AuditEntityType.MEAL_PLAN, id,
				snapshot(before.planDate(), before.mealKind(), before.readyBy(), recipe.name(), before.dayType()),
				snapshot(request.planDate(), kind.name(), readyBy, recipe.name(), dayType), null);
	}

	@Transactional
	public void cancel(AuthenticatedUser actor, UUID id) {
		MealPlanRow row = findRow(id).orElseThrow(() -> notFound(id));
		if (row.status() == MealStatus.COOKED) {
			throw new ApplicationException(ErrorCode.CANNOT_CANCEL_COOKED_MEAL, Map.of("mealPlanId", id));
		}
		if (row.status() == MealStatus.CANCELLED) {
			return;
		}
		jdbc.update("UPDATE meal_plans SET status = 'CANCELLED', updated_at = now() WHERE id = ?", id);
		auditService.record(actor, AuditAction.MEAL_PLAN_CANCELLED, AuditEntityType.MEAL_PLAN, id,
				Map.of("status", "PLANNED"), Map.of("status", "CANCELLED"), null);
	}

	// ---------------------------------------------------------------------

	/**
	 * Whether the meal this dish belongs to has already had its job card typed in.
	 *
	 * <p>Asked with a query rather than through {@link ServedMealService}, which is what actually owns
	 * this fact: that service reads meals through this one, and injecting it back would close the
	 * circle. One column read is a cheaper answer than a service both ways round.
	 */
	private boolean mealRecorded(MealPlanRow row) {
		Integer recorded = jdbc.queryForObject("""
				SELECT count(*) FROM meal_services
				WHERE plan_date = ? AND meal_kind = ? AND recorded_at IS NOT NULL
				""", Integer.class, row.planDate(), row.mealKind());
		return recorded != null && recorded > 0;
	}

	/**
	 * The time this meal must be ready: what was entered, or the kind's own default. A kind with no
	 * default — a deity offering, a catering order — has none to fall back on, and is refused rather
	 * than given a guessed hour.
	 */
	private LocalTime resolveReadyBy(MealKindView kind, LocalTime entered) {
		if (entered != null) {
			return entered;
		}
		if (kind.defaultReadyTime() == null) {
			throw new ApplicationException(
					ErrorCode.READY_BY_TIME_REQUIRED, Map.of("mealKind", kind.name()));
		}
		return kind.defaultReadyTime();
	}

	/**
	 * What a kind needs beyond a recipe: someone to cook it for, somewhere to send it, a reason for
	 * cooking it at all, or none of those. Each is asked for because the kind's own flag says so, so a
	 * temple that puts a purpose on its catering orders needs no code change to be obeyed here.
	 *
	 * <p>The client and the venue each have a refusal of their own because each was worth its own
	 * sentence to the person planning. A missing purpose is a plain empty required field and says so
	 * through VALIDATION_FAILED, naming the field — the form asks for it in the same breath as the
	 * venue, so a reader is never left wondering which box is empty.
	 */
	private void requireKindFields(MealKindView kind, String clientName, String venue, String purpose) {
		if (kind.needsClient() && (clientName == null || clientName.isBlank())) {
			throw new ApplicationException(ErrorCode.MEAL_CLIENT_REQUIRED, Map.of("mealKind", kind.name()));
		}
		if (kind.needsVenue() && (venue == null || venue.isBlank())) {
			throw new ApplicationException(ErrorCode.MEAL_VENUE_REQUIRED, Map.of("mealKind", kind.name()));
		}
		if (kind.needsPurpose() && (purpose == null || purpose.isBlank())) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
					Map.of("field", "purpose", "mealKind", kind.name()));
		}
	}

	/**
	 * What kind of day this meal is cooked on — derived, never asked (E4-S7). Food cooked for an
	 * outside client is catering whatever the date; otherwise the calendar decides, and a festival
	 * outranks a weekend because it is what explains the quantity.
	 */
	private DayType deriveDayType(MealKindView kind, LocalDate date) {
		return kind.needsClient() ? DayType.CATERING : dayContext(date).suggestedDayType();
	}

	/**
	 * Which festival this meal is for.
	 *
	 * <p>For every ordinary kind it is derived and nobody is asked: a meal on a festival day carries
	 * the calendar's name for it, a meal on any other day carries none. A feast — a kind flagged
	 * {@code needsOccasion} — is the one place a person may choose, because a temple anniversary or a
	 * local festival the calendar does not carry is still a feast, and the calendar cannot know that.
	 * What is chosen defaults to the calendar's answer, so the common case is one field already
	 * filled in.
	 *
	 * <p>A feast with nothing to name is refused. That is the flag's whole meaning, and a feast with
	 * no occasion is a large lunch nobody can look up next year.
	 */
	private String resolveOccasionName(
			MealKindView kind, DayType dayType, LocalDate date, String provided) {

		if (kind.needsOccasion()) {
			String chosen = trimToNull(provided);
			if (chosen == null) {
				chosen = trimToNull(dayContext(date).occasionName());
			}
			if (chosen == null) {
				throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
						Map.of("field", "occasionName", "mealKind", kind.name()));
			}
			return chosen;
		}
		if (dayType != DayType.FESTIVAL) {
			return null;
		}
		return dayContext(date).occasionName();
	}

	private RecipeRef findRecipe(UUID recipeId) {
		return jdbc.query("SELECT id, name FROM recipes WHERE id = ? AND status = 'ACTIVE'",
				(rs, n) -> new RecipeRef(rs.getObject("id", UUID.class), rs.getString("name")), recipeId)
				.stream().findFirst()
				.orElseThrow(() -> new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("recipeId", recipeId)));
	}

	private Optional<MealPlanView> findById(UUID id) {
		return jdbc.query(SELECT + " WHERE mp.id = ?", MAPPER, id).stream().findFirst();
	}

	private Optional<MealPlanRow> findRow(UUID id) {
		return jdbc.query("""
				SELECT id, plan_date, meal_kind, ready_by, recipe_id, target_yield, day_type, status
				FROM meal_plans WHERE id = ?
				""", ROW_MAPPER, id).stream().findFirst();
	}

	private Map<String, Object> snapshot(
			LocalDate date, String mealKind, LocalTime readyBy, String recipe, DayType dayType) {
		Map<String, Object> s = new LinkedHashMap<>();
		s.put("date", date.toString());
		s.put("mealKind", mealKind);
		s.put("readyBy", String.valueOf(readyBy));
		s.put("recipe", recipe);
		s.put("dayType", dayType.name());
		return s;
	}

	private static String trimToNull(String s) {
		if (s == null) {
			return null;
		}
		String t = s.trim();
		return t.isEmpty() ? null : t;
	}

	private ApplicationException notFound(UUID id) {
		return new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("mealPlanId", id));
	}

	private record RecipeRef(UUID id, String name) {
	}

	private record MealPlanRow(
			UUID id, LocalDate planDate, String mealKind, LocalTime readyBy, UUID recipeId,
			BigDecimal targetYield, DayType dayType, MealStatus status) {
	}

	private static final String SELECT = """
			SELECT mp.id, mp.plan_date, mp.meal_kind, mp.ready_by, mp.recipe_id, r.name AS recipe_name,
				   mp.target_yield, mp.day_type, mp.occasion_name, mp.status, mp.client_name,
				   mp.client_contact, mp.venue, mp.purpose, mp.adults, mp.children, mp.seniors,
				   mp.crew_required, mp.kitchen_notes, mp.actual_servings, mp.not_made,
				   mp.cooked_at, mp.ekadashi_ack_at, mp.created_at
			FROM meal_plans mp
			JOIN recipes r ON r.id = mp.recipe_id
			""";

	private static Instant instant(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
		OffsetDateTime odt = rs.getObject(col, OffsetDateTime.class);
		return odt == null ? null : odt.toInstant();
	}

	private static final RowMapper<MealPlanView> MAPPER = (rs, n) -> new MealPlanView(
			rs.getObject("id", UUID.class),
			rs.getObject("plan_date", LocalDate.class),
			rs.getString("meal_kind"),
			rs.getObject("ready_by", LocalTime.class),
			rs.getObject("recipe_id", UUID.class),
			rs.getString("recipe_name"),
			rs.getBigDecimal("target_yield"),
			DayType.valueOf(rs.getString("day_type")),
			rs.getString("occasion_name"),
			MealStatus.valueOf(rs.getString("status")),
			rs.getString("client_name"),
			rs.getString("client_contact"),
			rs.getString("venue"),
			rs.getString("purpose"),
			(Integer) rs.getObject("adults"),
			(Integer) rs.getObject("children"),
			(Integer) rs.getObject("seniors"),
			(Integer) rs.getObject("crew_required"),
			rs.getString("kitchen_notes"),
			rs.getBigDecimal("actual_servings"),
			rs.getBoolean("not_made"),
			instant(rs, "cooked_at"),
			instant(rs, "ekadashi_ack_at") != null,
			instant(rs, "created_at"));

	private static final RowMapper<MealPlanRow> ROW_MAPPER = (rs, n) -> new MealPlanRow(
			rs.getObject("id", UUID.class),
			rs.getObject("plan_date", LocalDate.class),
			rs.getString("meal_kind"),
			rs.getObject("ready_by", LocalTime.class),
			rs.getObject("recipe_id", UUID.class),
			rs.getBigDecimal("target_yield"),
			DayType.valueOf(rs.getString("day_type")),
			MealStatus.valueOf(rs.getString("status")));
}
