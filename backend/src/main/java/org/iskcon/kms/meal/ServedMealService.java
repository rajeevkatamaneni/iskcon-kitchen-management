package org.iskcon.kms.meal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.inventory.ConsumeRequest;
import org.iskcon.kms.inventory.InventoryConsumptionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A meal as one thing, and the record of what came back from the kitchen (B5, brief §2).
 *
 * <p>The planner writes one row per dish. This service reads those rows back as meals — grouped on
 * the pair the brief means every time it says "the meal", {@code (plan_date, meal_kind)} — and owns
 * the two facts that belong to a whole meal rather than to any dish of it: the number printed on its
 * job card, and the moment somebody in the office typed in what the returned card said.
 *
 * <p><strong>Why recording exists at all.</strong> Marking a meal cooked is the moment its
 * ingredients leave stock. Take it away and the store room never depletes and the order list
 * over-states what is on hand. What went was the theatre around it: a cook with hot oil in front of
 * them does not touch a screen, so the record is made by whoever is in the office, from the sheet,
 * once, for the whole meal.
 *
 * <p><strong>And why the actual figure.</strong> Stock is drawn against what actually went out, not
 * against what was planned. That is the number the data entry is for: over a month the gap between
 * the two tells the temple its head counts are wrong, in which direction and by how much. A dish
 * marked "not made" draws nothing.
 */
@Service
public class ServedMealService {

	/** A child eats about six tenths of a portion, a senior about eight. The temple's own arithmetic. */
	private static final BigDecimal CHILD_PORTION = new BigDecimal("0.6");
	private static final BigDecimal SENIOR_PORTION = new BigDecimal("0.8");

	/** Above this a figure is a typing slip, not a temple. The largest festival here is in the low thousands. */
	private static final BigDecimal MAX_SERVINGS = BigDecimal.valueOf(100_000);

	private final JdbcTemplate jdbc;
	private final MealPlanService mealPlanService;
	private final MealKindService mealKindService;
	private final InventoryConsumptionService consumptionService;
	private final AuditService auditService;

	public ServedMealService(
			JdbcTemplate jdbc, MealPlanService mealPlanService, MealKindService mealKindService,
			InventoryConsumptionService consumptionService, AuditService auditService) {
		this.jdbc = jdbc;
		this.mealPlanService = mealPlanService;
		this.mealKindService = mealKindService;
		this.consumptionService = consumptionService;
		this.auditService = auditService;
	}

	// ---- Read -----------------------------------------------------------

	/** Every meal in the range, in the order the kitchen works: by date, then by when each is due. */
	@Transactional(readOnly = true)
	public List<ServedMeal> list(LocalDate from, LocalDate to) {
		List<MealPlanView> dishes = mealPlanService.list(from, to, null, null);
		Map<Key, ServiceRow> services = servicesIn(from, to);

		// LinkedHashMap: list() already returns plan_date, ready_by, meal_kind order, so grouping in
		// encounter order gives the meals back in that same order without a second sort.
		Map<Key, List<MealPlanView>> grouped = new LinkedHashMap<>();
		for (MealPlanView dish : dishes) {
			grouped.computeIfAbsent(new Key(dish.planDate(), dish.mealKind()), k -> new ArrayList<>()).add(dish);
		}

		List<ServedMeal> meals = new ArrayList<>();
		grouped.forEach((key, rows) -> meals.add(assemble(key, rows, services.get(key))));
		return meals;
	}

	/** One meal, or empty when nothing at all is planned for that date and kind. */
	@Transactional(readOnly = true)
	public Optional<ServedMeal> find(LocalDate date, String mealKind) {
		String kind = mealKindService.require(mealKind).name();
		return list(date, date).stream().filter(m -> m.mealKind().equals(kind)).findFirst();
	}

	/** One meal, or a refusal. The job card and the recording form both start here. */
	@Transactional(readOnly = true)
	public ServedMeal require(LocalDate date, String mealKind) {
		return find(date, mealKind).orElseThrow(() -> new ApplicationException(
				ErrorCode.RESOURCE_NOT_FOUND, Map.of("planDate", date, "mealKind", String.valueOf(mealKind))));
	}

	/** One meal by its own row, which is how a generated job card refers back to it. */
	@Transactional(readOnly = true)
	public ServedMeal requireByServiceId(UUID serviceId) {
		ServiceRow row = jdbc.query(SERVICE_SELECT + " WHERE ms.id = ?", SERVICE_MAPPER, serviceId)
				.stream().findFirst()
				.orElseThrow(() -> new ApplicationException(
						ErrorCode.RESOURCE_NOT_FOUND, Map.of("mealServiceId", serviceId)));
		return require(row.planDate(), row.mealKind());
	}

	/**
	 * How many plates the kitchen is cooking on a date, per meal kind — <em>Breakfast 100 · Lunch 250
	 * · Dinner 180</em> (brief §1d). Per kind and never a total, because a plate at breakfast and a
	 * plate at dinner are not the same plate, and adding them is how the tile came to report 750 for
	 * a lunch of three dishes.
	 */
	@Transactional(readOnly = true)
	public Map<String, Integer> platesByMealKind(LocalDate date) {
		Map<String, Integer> plates = new LinkedHashMap<>();
		for (ServedMeal meal : list(date, date)) {
			// A cancelled meal is not work the kitchen has to do, so it is not plates either.
			if (meal.dishes().stream().allMatch(d -> d.status() == MealStatus.CANCELLED)) {
				continue;
			}
			plates.put(meal.mealKind(), meal.plates());
		}
		return plates;
	}

	/**
	 * How many meals in the range went out and were never written down — the count behind the nudge,
	 * <em>"3 meals from earlier this week not yet recorded"</em>.
	 *
	 * <p>A nudge and not an alarm, but not decoration either: every unrecorded meal is stock the
	 * store room still believes it has.
	 */
	@Transactional(readOnly = true)
	public int unrecordedCount(LocalDate from, LocalDate to) {
		return (int) list(from, to).stream().filter(ServedMeal::awaitingRecord).count();
	}

	// ---- Write ----------------------------------------------------------

	/**
	 * Records what actually went out at one meal, and draws it from stock.
	 *
	 * <p>One transaction for the whole meal. The consumption service is all-or-nothing per dish, and
	 * this makes it all-or-nothing per meal: if the fourth dish is short of ghee, the first three are
	 * rolled back too and the meal stays open, rather than leaving a half-recorded lunch nobody can
	 * finish or repeat.
	 */
	@Transactional
	public ServedMeal record(AuthenticatedUser actor, RecordMealRequest request) {
		String kind = mealKindService.require(request.mealKind()).name();
		ServedMeal meal = require(request.planDate(), kind);

		if (meal.recorded()) {
			throw new ApplicationException(ErrorCode.MEAL_ALREADY_RECORDED,
					Map.of("planDate", request.planDate(), "mealKind", kind));
		}
		List<MealPlanView> open = meal.dishes().stream()
				.filter(d -> d.status() == MealStatus.PLANNED).toList();
		if (open.isEmpty()) {
			// Nothing left to record is one of two different situations, and they get different
			// answers: a meal whose dishes are already cooked was recorded some other way, and a meal
			// whose dishes were all called off never went to the kitchen at all.
			boolean anyCooked = meal.dishes().stream().anyMatch(d -> d.status() == MealStatus.COOKED);
			throw new ApplicationException(
					anyCooked ? ErrorCode.MEAL_ALREADY_RECORDED : ErrorCode.MEAL_NOT_RECORDABLE,
					Map.of("planDate", request.planDate(), "mealKind", kind));
		}

		Map<UUID, RecordMealRequest.DishRecord> given = new LinkedHashMap<>();
		for (RecordMealRequest.DishRecord dish : request.dishes()) {
			given.put(dish.mealPlanId(), dish);
		}
		for (UUID id : given.keySet()) {
			if (open.stream().noneMatch(d -> d.id().equals(id))) {
				throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("mealPlanId", id));
			}
		}

		for (MealPlanView dish : open) {
			RecordMealRequest.DishRecord entry = given.get(dish.id());
			if (entry == null) {
				// Silence is not an answer. Deciding on the office's behalf whether an unmentioned dish
				// was cooked is exactly the guess this form exists to avoid.
				throw new ApplicationException(ErrorCode.SERVINGS_NOT_VALID,
						Map.of("mealPlanId", dish.id(), "recipe", dish.recipeName()));
			}
			BigDecimal served = servedFigure(dish, entry);

			if (!entry.notMade()) {
				// Against the actual figure, not the planned one — the whole point of collecting it.
				consumptionService.consume(actor, new ConsumeRequest(
						dish.recipeId(), served, dish.id(), null, trimToNull(request.note())));
			}

			// A dish that never went into a pot did not get cooked, and there are only three states to
			// say that in. CANCELLED with not_made recorded beside it says the true thing: it was
			// called off at the stove rather than in the plan, and it drew nothing.
			jdbc.update("""
					UPDATE meal_plans
					SET status = ?, actual_servings = ?, not_made = ?, cooked_at = ?, updated_at = now()
					WHERE id = ?
					""",
					entry.notMade() ? "CANCELLED" : "COOKED",
					served,
					entry.notMade(),
					entry.notMade() ? null : OffsetDateTime.now(java.time.ZoneOffset.UTC),
					dish.id());

			auditService.record(actor, AuditAction.MEAL_COOKED, AuditEntityType.MEAL_PLAN, dish.id(),
					Map.of("status", "PLANNED", "plannedServings", String.valueOf(dish.targetServings())),
					Map.of("status", entry.notMade() ? "CANCELLED" : "COOKED",
							"actualServings", String.valueOf(served),
							"notMade", String.valueOf(entry.notMade())),
					null);
		}

		UUID serviceId = ensureService(request.planDate(), kind);
		jdbc.update("""
				UPDATE meal_services
				SET recorded_at = now(), recorded_by = ?, recording_note = ?, updated_at = now()
				WHERE id = ?
				""", actor.getUserId(), trimToNull(request.note()), serviceId);

		return require(request.planDate(), kind);
	}

	/**
	 * The number printed on this meal's job card, issuing one if the meal has never been printed.
	 *
	 * <p>Issued once and kept. A reprint after a dish was swapped is the same meal and carries the
	 * same number — the number exists so that a signed sheet in a folder can be traced back to this
	 * record six months later, which a number that changed between prints could not do.
	 */
	@Transactional
	public String issueCardNumber(LocalDate date, String mealKind) {
		String kind = mealKindService.require(mealKind).name();
		require(date, kind); // A meal with no dishes has no card; refuses before a number is spent.
		UUID serviceId = ensureService(date, kind);

		String existing = jdbc.queryForObject(
				"SELECT card_number FROM meal_services WHERE id = ?", String.class, serviceId);
		if (existing != null) {
			return existing;
		}
		String number = nextCardNumber(kind, date);
		jdbc.update("""
				UPDATE meal_services SET card_number = ?, card_issued_at = now(), updated_at = now()
				WHERE id = ?
				""", number, serviceId);
		return number;
	}

	/**
	 * The row for one meal, named the way a screen names it — a date and whatever the caller typed for
	 * the kind — created if this is the first time anything has been printed or recorded for it.
	 * Refuses if nothing is planned for that meal at all.
	 */
	@Transactional
	public UUID serviceFor(LocalDate date, String mealKind) {
		ServedMeal meal = require(date, mealKindService.require(mealKind).name());
		return ensureService(meal.planDate(), meal.mealKind());
	}

	/** The meal's own row, created on demand. Nothing is written until a card is printed or recorded. */
	@Transactional
	public UUID ensureService(LocalDate date, String mealKind) {
		jdbc.update("""
				INSERT INTO meal_services (tenant_id, plan_date, meal_kind)
				VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?)
				ON CONFLICT (tenant_id, plan_date, meal_kind) DO NOTHING
				""", date, mealKind);
		return jdbc.queryForObject(
				"SELECT id FROM meal_services WHERE plan_date = ? AND meal_kind = ?",
				UUID.class, date, mealKind);
	}

	// ---------------------------------------------------------------------

	/**
	 * The next card number for this temple: {@code LC-2026-0142}.
	 *
	 * <p>The counter is per temple and not per kind, so the number alone identifies one sheet however
	 * the prefix is derived. The prefix is a reading aid — a person holding a folder of paper wants to
	 * see at a glance that this was a lunch — so it takes the initial of each word of the kind's name,
	 * and a single-word kind gets its initial plus C for card: Lunch becomes LC, Breakfast BC, Deity
	 * Offering DO, Outside event OE. A kind the application has never seen gets the same treatment,
	 * and a name with no letters in it at all falls back to MC. Two kinds sharing a prefix is harmless
	 * precisely because the prefix is not the identity.
	 *
	 * <p>Like the PO counter it is gap-tolerant: the atomic increment row-locks per tenant so two
	 * simultaneous prints never share a number, and a print that rolls back simply leaves a gap.
	 */
	private String nextCardNumber(String mealKind, LocalDate date) {
		Integer seq = jdbc.queryForObject("""
				INSERT INTO meal_card_sequence (tenant_id, last_number)
				VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, 1)
				ON CONFLICT (tenant_id) DO UPDATE SET last_number = meal_card_sequence.last_number + 1
				RETURNING last_number
				""", Integer.class);
		return cardPrefix(mealKind) + "-" + date.getYear() + "-" + String.format("%04d", seq);
	}

	/** The two-letter prefix for a kind. Deterministic, and never consulted to find anything. */
	static String cardPrefix(String mealKind) {
		StringBuilder initials = new StringBuilder();
		for (String word : String.valueOf(mealKind).trim().split("\\s+")) {
			for (int i = 0; i < word.length(); i++) {
				if (Character.isLetter(word.charAt(i))) {
					initials.append(Character.toUpperCase(word.charAt(i)));
					break;
				}
			}
			if (initials.length() == 2) {
				break;
			}
		}
		if (initials.length() == 0) {
			return "MC";
		}
		return initials.length() == 1 ? initials.append('C').toString() : initials.toString();
	}

	/** What this dish actually went out at, or a refusal naming the dish rather than the form. */
	private BigDecimal servedFigure(MealPlanView dish, RecordMealRequest.DishRecord entry) {
		if (entry.notMade()) {
			return BigDecimal.ZERO;
		}
		BigDecimal served = entry.actualServings();
		if (served == null || served.signum() <= 0 || served.compareTo(MAX_SERVINGS) > 0) {
			throw new ApplicationException(ErrorCode.SERVINGS_NOT_VALID,
					Map.of("mealPlanId", dish.id(), "recipe", dish.recipeName(),
							"servings", String.valueOf(served)));
		}
		return served;
	}

	/**
	 * Builds one meal from its dish rows.
	 *
	 * <p>The dishes of a meal normally agree about everything but the recipe, because the composer
	 * writes them in one pass. They can disagree if a dish was added later against a changed head
	 * count, so where they do, the largest wins — the kitchen has to cook for whoever turns up, and a
	 * card that under-states the hall is worse than one that over-states it. Never the sum: three
	 * dishes at 250 servings each is 250 plates.
	 */
	private ServedMeal assemble(Key key, List<MealPlanView> rows, ServiceRow service) {
		MealPlanView largest = rows.stream()
				.max(Comparator.comparingInt(ServedMealService::platesOf))
				.orElseThrow();

		MealPlanView first = rows.get(0);
		return new ServedMeal(
				service == null ? null : service.id(),
				key.date(),
				key.mealKind(),
				first.readyBy(),
				largest.adults(),
				largest.children(),
				largest.seniors(),
				platesOf(largest),
				crewOf(rows),
				first.dayType(),
				firstNonBlank(rows, MealPlanView::occasionName),
				firstNonBlank(rows, MealPlanView::clientName),
				firstNonBlank(rows, MealPlanView::clientContact),
				firstNonBlank(rows, MealPlanView::venue),
				firstNonBlank(rows, MealPlanView::purpose),
				// The composer writes the same note onto every dish of a meal, so one of them is the
				// note. Joining them would print it three times on the card.
				firstNonBlank(rows, MealPlanView::kitchenNotes),
				service == null ? null : service.cardNumber(),
				service == null ? null : service.cardIssuedAt(),
				service != null && service.recordedAt() != null,
				service == null ? null : service.recordedAt(),
				service == null ? null : service.recordedByName(),
				service == null ? null : service.recordingNote(),
				rows);
	}

	/**
	 * How many people this meal takes to execute (item 24).
	 *
	 * <p>The largest of what its dish rows say, for the same reason the head count takes the largest:
	 * the composer writes one figure onto every dish of a meal, so they normally agree, and where a
	 * dish added later disagrees the kitchen still has to staff the bigger job. Null when no dish
	 * carries a figure at all — nobody has said yet, and a made-up number would be worse.
	 */
	private static Integer crewOf(List<MealPlanView> rows) {
		return rows.stream()
				.map(MealPlanView::crewRequired)
				.filter(java.util.Objects::nonNull)
				.max(Integer::compareTo)
				.orElse(null);
	}

	/**
	 * What one dish row scales to: the head count if the planner gave one, otherwise the dish's own
	 * servings figure, which is all a meal planned before V51 has.
	 */
	private static int platesOf(MealPlanView dish) {
		if (dish.adults() == null && dish.children() == null && dish.seniors() == null) {
			return dish.targetServings() == null ? 0 : dish.targetServings().intValue();
		}
		BigDecimal total = BigDecimal.valueOf(dish.adults() == null ? 0 : dish.adults())
				.add(CHILD_PORTION.multiply(BigDecimal.valueOf(dish.children() == null ? 0 : dish.children())))
				.add(SENIOR_PORTION.multiply(BigDecimal.valueOf(dish.seniors() == null ? 0 : dish.seniors())));
		return total.setScale(0, RoundingMode.HALF_UP).intValue();
	}

	private static String firstNonBlank(
			List<MealPlanView> rows, java.util.function.Function<MealPlanView, String> field) {
		for (MealPlanView row : rows) {
			String value = field.apply(row);
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		return null;
	}

	private Map<Key, ServiceRow> servicesIn(LocalDate from, LocalDate to) {
		StringBuilder sql = new StringBuilder(SERVICE_SELECT + " WHERE 1 = 1");
		List<Object> args = new ArrayList<>();
		if (from != null) {
			sql.append(" AND ms.plan_date >= ?");
			args.add(from);
		}
		if (to != null) {
			sql.append(" AND ms.plan_date <= ?");
			args.add(to);
		}
		Map<Key, ServiceRow> byKey = new LinkedHashMap<>();
		for (ServiceRow row : jdbc.query(sql.toString(), SERVICE_MAPPER, args.toArray())) {
			byKey.put(new Key(row.planDate(), row.mealKind()), row);
		}
		return byKey;
	}

	private static String trimToNull(String s) {
		if (s == null) {
			return null;
		}
		String t = s.trim();
		return t.isEmpty() ? null : t;
	}

	/** The pair the brief means by "the meal". */
	private record Key(LocalDate date, String mealKind) {
	}

	private record ServiceRow(
			UUID id, LocalDate planDate, String mealKind, String cardNumber,
			java.time.Instant cardIssuedAt, java.time.Instant recordedAt, String recordedByName,
			String recordingNote) {
	}

	private static final String SERVICE_SELECT = """
			SELECT ms.id, ms.plan_date, ms.meal_kind, ms.card_number, ms.card_issued_at,
				   ms.recorded_at, ms.recording_note, u.full_name AS recorded_by_name
			FROM meal_services ms
			LEFT JOIN users u ON u.id = ms.recorded_by
			""";

	private static final RowMapper<ServiceRow> SERVICE_MAPPER = (rs, n) -> new ServiceRow(
			rs.getObject("id", UUID.class),
			rs.getObject("plan_date", LocalDate.class),
			rs.getString("meal_kind"),
			rs.getString("card_number"),
			instant(rs, "card_issued_at"),
			instant(rs, "recorded_at"),
			rs.getString("recorded_by_name"),
			rs.getString("recording_note"));

	private static java.time.Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}
}
