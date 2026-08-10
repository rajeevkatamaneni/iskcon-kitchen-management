package org.iskcon.kms.meal;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
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
import org.iskcon.kms.inventory.ConsumeRequest;
import org.iskcon.kms.inventory.ConsumptionPlan;
import org.iskcon.kms.inventory.InventoryConsumptionService;
import org.iskcon.kms.occasion.OccasionService;
import org.iskcon.kms.occasion.ResolvedOccasion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Meal planning (E4-S4). A plan is a recipe cooked at a target on a date and slot, in a day-type
 * context the calendar suggests (festival by occasion, weekend by weekday, else regular; catering is
 * always explicit). Marking a plan cooked draws its ingredients from stock through the consumption
 * service (E3-S6) and flips it to COOKED; a cooked plan can no longer be cancelled — the stock has
 * moved, and a mistake is corrected with an inventory adjustment (E3-S7), not by erasing history.
 */
@Service
public class MealPlanService {

	private final JdbcTemplate jdbc;
	private final AuditService auditService;
	private final OccasionService occasionService;
	private final CalendarService calendarService;
	private final InventoryConsumptionService consumptionService;
	private final MealSlotService mealSlotService;
	private final EkadashiPolicy ekadashiPolicy;

	public MealPlanService(
			JdbcTemplate jdbc, AuditService auditService, OccasionService occasionService,
			CalendarService calendarService, InventoryConsumptionService consumptionService,
			MealSlotService mealSlotService, EkadashiPolicy ekadashiPolicy) {
		this.jdbc = jdbc;
		this.auditService = auditService;
		this.occasionService = occasionService;
		this.calendarService = calendarService;
		this.consumptionService = consumptionService;
		this.mealSlotService = mealSlotService;
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
		sql.append(" ORDER BY mp.plan_date, mp.slot");
		return jdbc.query(sql.toString(), MAPPER, args.toArray());
	}

	@Transactional(readOnly = true)
	public MealPlanView get(UUID id) {
		return findById(id).orElseThrow(() -> notFound(id));
	}

	// ---- Write ----------------------------------------------------------

	@Transactional
	public UUID create(AuthenticatedUser actor, CreateMealPlanRequest request) {
		validateSlot(request.slot());
		RecipeRef recipe = findRecipe(request.recipeId());

		DayType dayType = request.dayType() != null
				? request.dayType() : dayContext(request.planDate()).suggestedDayType();
		String occasionName = resolveOccasionName(dayType, request.planDate(), request.occasionName());
		requireClientForCatering(dayType, request.clientName());
		boolean recordAck = resolveEkadashiAck(request.planDate(), request.recipeId(), request.ekadashiAcknowledged());

		UUID id = UUID.randomUUID();
		jdbc.update(connection -> {
			var ps = connection.prepareStatement("""
					INSERT INTO meal_plans (
						id, tenant_id, plan_date, slot, recipe_id, target_servings, day_type,
						occasion_name, status, client_name, client_contact, venue, delivery_time,
						ekadashi_ack_by, ekadashi_ack_at, created_by)
					VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid,
						?, ?, ?, ?, ?, ?, 'PLANNED', ?, ?, ?, ?, ?, ?, ?)
					""");
			ps.setObject(1, id);
			ps.setObject(2, request.planDate());
			ps.setString(3, request.slot().trim());
			ps.setObject(4, request.recipeId());
			ps.setBigDecimal(5, request.targetServings());
			ps.setString(6, dayType.name());
			ps.setString(7, occasionName);
			ps.setString(8, trimToNull(request.clientName()));
			ps.setString(9, trimToNull(request.clientContact()));
			ps.setString(10, trimToNull(request.venue()));
			ps.setObject(11, request.deliveryTime() == null ? null : OffsetDateTime.ofInstant(
					request.deliveryTime(), java.time.ZoneOffset.UTC));
			ps.setObject(12, recordAck ? actor.getUserId() : null);
			ps.setObject(13, recordAck ? OffsetDateTime.now(java.time.ZoneOffset.UTC) : null);
			ps.setObject(14, actor.getUserId());
			return ps;
		});

		auditService.record(actor, AuditAction.MEAL_PLANNED, AuditEntityType.MEAL_PLAN, id,
				null, snapshot(request.planDate(), request.slot().trim(), recipe.name(), dayType), null);
		return id;
	}

	@Transactional
	public void update(AuthenticatedUser actor, UUID id, UpdateMealPlanRequest request) {
		MealPlanRow before = findRow(id).orElseThrow(() -> notFound(id));
		if (before.status() != MealStatus.PLANNED) {
			throw new ApplicationException(ErrorCode.MEAL_PLAN_NOT_OPEN, Map.of("mealPlanId", id));
		}
		validateSlot(request.slot());
		RecipeRef recipe = findRecipe(request.recipeId());
		DayType dayType = request.dayType() != null
				? request.dayType() : dayContext(request.planDate()).suggestedDayType();
		String occasionName = resolveOccasionName(dayType, request.planDate(), request.occasionName());
		requireClientForCatering(dayType, request.clientName());
		boolean recordAck = resolveEkadashiAck(request.planDate(), request.recipeId(), request.ekadashiAcknowledged());

		jdbc.update("""
				UPDATE meal_plans
				SET plan_date = ?, slot = ?, recipe_id = ?, target_servings = ?, day_type = ?,
					occasion_name = ?, client_name = ?, client_contact = ?, venue = ?, delivery_time = ?,
					ekadashi_ack_by = ?, ekadashi_ack_at = ?, updated_at = now()
				WHERE id = ?
				""",
				request.planDate(), request.slot().trim(), request.recipeId(), request.targetServings(),
				dayType.name(), occasionName, trimToNull(request.clientName()),
				trimToNull(request.clientContact()), trimToNull(request.venue()),
				request.deliveryTime() == null ? null
						: OffsetDateTime.ofInstant(request.deliveryTime(), java.time.ZoneOffset.UTC),
				recordAck ? actor.getUserId() : null,
				recordAck ? OffsetDateTime.now(java.time.ZoneOffset.UTC) : null,
				id);

		auditService.record(actor, AuditAction.MEAL_PLAN_UPDATED, AuditEntityType.MEAL_PLAN, id,
				snapshot(before.planDate(), before.slot(), recipe.name(), before.dayType()),
				snapshot(request.planDate(), request.slot().trim(), recipe.name(), dayType), null);
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

	/**
	 * Marks a planned meal cooked: draws its ingredients from stock (E3-S6, all-or-nothing) and flips
	 * the status. If stock is short the consumption refuses and this whole call rolls back, leaving
	 * the plan planned. Returns what was drawn.
	 */
	@Transactional
	public ConsumptionPlan markCooked(AuthenticatedUser actor, UUID id, MarkCookedRequest request) {
		MealPlanRow row = findRow(id).orElseThrow(() -> notFound(id));
		if (row.status() != MealStatus.PLANNED) {
			throw new ApplicationException(ErrorCode.MEAL_PLAN_NOT_OPEN, Map.of("mealPlanId", id));
		}

		ConsumptionPlan drawn = consumptionService.consume(actor, new ConsumeRequest(
				row.recipeId(), row.targetServings(), id,
				request == null ? null : request.batchOverrides(),
				request == null ? null : request.note()));

		jdbc.update("UPDATE meal_plans SET status = 'COOKED', cooked_at = now(), updated_at = now() WHERE id = ?", id);
		auditService.record(actor, AuditAction.MEAL_COOKED, AuditEntityType.MEAL_PLAN, id,
				Map.of("status", "PLANNED"), Map.of("status", "COOKED"), null);
		return drawn;
	}

	// ---------------------------------------------------------------------

	private void validateSlot(String slot) {
		if (slot == null || !mealSlotService.names().contains(slot.trim())) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("field", "slot", "value", String.valueOf(slot)));
		}
	}

	private void requireClientForCatering(DayType dayType, String clientName) {
		if (dayType == DayType.CATERING && (clientName == null || clientName.isBlank())) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("field", "clientName"));
		}
	}

	private String resolveOccasionName(DayType dayType, LocalDate date, String provided) {
		if (dayType != DayType.FESTIVAL) {
			return null;
		}
		if (provided != null && !provided.isBlank()) {
			return provided.trim();
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
				SELECT id, plan_date, slot, recipe_id, target_servings, day_type, status
				FROM meal_plans WHERE id = ?
				""", ROW_MAPPER, id).stream().findFirst();
	}

	private Map<String, Object> snapshot(LocalDate date, String slot, String recipe, DayType dayType) {
		Map<String, Object> s = new LinkedHashMap<>();
		s.put("date", date.toString());
		s.put("slot", slot);
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
			UUID id, LocalDate planDate, String slot, UUID recipeId, BigDecimal targetServings,
			DayType dayType, MealStatus status) {
	}

	private static final String SELECT = """
			SELECT mp.id, mp.plan_date, mp.slot, mp.recipe_id, r.name AS recipe_name, mp.target_servings,
				   mp.day_type, mp.occasion_name, mp.status, mp.client_name, mp.client_contact, mp.venue,
				   mp.delivery_time, mp.cooked_at, mp.ekadashi_ack_at, mp.created_at
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
			rs.getString("slot"),
			rs.getObject("recipe_id", UUID.class),
			rs.getString("recipe_name"),
			rs.getBigDecimal("target_servings"),
			DayType.valueOf(rs.getString("day_type")),
			rs.getString("occasion_name"),
			MealStatus.valueOf(rs.getString("status")),
			rs.getString("client_name"),
			rs.getString("client_contact"),
			rs.getString("venue"),
			instant(rs, "delivery_time"),
			instant(rs, "cooked_at"),
			instant(rs, "ekadashi_ack_at") != null,
			instant(rs, "created_at"));

	private static final RowMapper<MealPlanRow> ROW_MAPPER = (rs, n) -> new MealPlanRow(
			rs.getObject("id", UUID.class),
			rs.getObject("plan_date", LocalDate.class),
			rs.getString("slot"),
			rs.getObject("recipe_id", UUID.class),
			rs.getBigDecimal("target_servings"),
			DayType.valueOf(rs.getString("day_type")),
			MealStatus.valueOf(rs.getString("status")));
}
