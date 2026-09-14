package org.iskcon.kms.meal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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
 * <p><strong>What a meal is identified by: its id (D-27).</strong> For as long as this service
 * existed before D-27 there was no meal row. It read dish rows back as meals by grouping them on a
 * date, a kind's name and — once events arrived — the event's name, and it kept the card number and
 * the recording on a second table keyed on the same three texts. That grouping was right on most
 * days and wrong on the ones that mattered: a kind renamed in Settings had to be cascaded by hand
 * through every table that stored the name, two events nobody named were one recording, and a
 * volunteer shift linked to "lunch" had to match a temple storing "Lunch" by folding case. Rajeev
 * ruled it out: <em>"identifying things by text is a terrible idea and one that WILL fail
 * eventually."</em> A meal is a row of its own now ({@code meals}, V136), its dishes point at it, and
 * every method here that names a meal takes that row's id.
 *
 * <p><strong>Why recording exists at all.</strong> Marking a meal cooked is the moment its
 * ingredients leave stock. Take it away and the store room never depletes and the shopping list
 * over-states what is on hand. What went was the theatre around it: a cook with hot oil in front of
 * them does not touch a screen, so the record is made by whoever is in the office, from the sheet,
 * once, for the whole meal.
 *
 * <p><strong>And why the actual figure.</strong> Stock is drawn against what actually went out, not
 * against what was planned. Over a month the gap between the two tells the temple its head counts are
 * wrong, in which direction and by how much. A dish marked "not made" draws nothing.
 */
@Service
public class ServedMealService {

	/** A child eats about six tenths of a portion, a senior about eight. The temple's own arithmetic. */
	private static final BigDecimal CHILD_PORTION = new BigDecimal("0.6");
	private static final BigDecimal SENIOR_PORTION = new BigDecimal("0.8");

	/** Above this a figure is a typing slip, not a temple. The largest festival here is in the low thousands. */
	private static final BigDecimal MAX_SERVINGS = BigDecimal.valueOf(100_000);

	private final JdbcTemplate jdbc;
	private final InventoryConsumptionService consumptionService;
	private final AuditService auditService;

	// No MealPlanService here any more, and that is deliberate rather than incidental. This service
	// used to read dish rows through the planner; the planner now reads meals through this one
	// (reuse, repeat, travel), so the dependency points one way and there is no circle to break.
	public ServedMealService(
			JdbcTemplate jdbc, InventoryConsumptionService consumptionService, AuditService auditService) {
		this.jdbc = jdbc;
		this.consumptionService = consumptionService;
		this.auditService = auditService;
	}

	// ---- Read -----------------------------------------------------------

	/**
	 * Every meal in the range, in the order the kitchen works: by date, then by when each is due.
	 *
	 * <p>Either end may be null, for "from the beginning" or "to the end". The volunteer shift is not
	 * attached here — see {@link ServedMeal#volunteerShift()} for who does, and why not this service.
	 */
	@Transactional(readOnly = true)
	public List<ServedMeal> list(LocalDate from, LocalDate to) {
		StringBuilder where = new StringBuilder(" WHERE 1 = 1");
		List<Object> args = new ArrayList<>();
		if (from != null) {
			where.append(" AND pd.plan_date >= ?");
			args.add(from);
		}
		if (to != null) {
			where.append(" AND pd.plan_date <= ?");
			args.add(to);
		}
		List<MealRow> meals = jdbc.query(MEAL_SELECT + where + MEAL_ORDER, MEAL_MAPPER, args.toArray());
		Map<UUID, List<MealDishView>> dishes = dishesIn(
				DISH_SELECT + " JOIN meals m ON m.id = d.meal_id"
						+ " JOIN meal_plan_days pd ON pd.id = m.meal_plan_day_id" + where + DISH_ORDER,
				args.toArray());
		return assembleAll(meals, dishes);
	}

	/** One meal by its id, or empty where this temple has no such meal. */
	@Transactional(readOnly = true)
	public Optional<ServedMeal> find(UUID mealId) {
		List<MealRow> meals = jdbc.query(MEAL_SELECT + " WHERE m.id = ?", MEAL_MAPPER, mealId);
		if (meals.isEmpty()) {
			return Optional.empty();
		}
		Map<UUID, List<MealDishView>> dishes = dishesIn(
				DISH_SELECT + " WHERE d.meal_id = ?" + DISH_ORDER, new Object[] {mealId});
		return Optional.of(assembleAll(meals, dishes).get(0));
	}

	/**
	 * One meal, or a refusal. The job card, the recording form and the correction all start here.
	 *
	 * <p>An id belonging to another temple reads as absent — row-level security hides the row — which
	 * is the same refusal as an id that never existed, and deliberately so.
	 */
	@Transactional(readOnly = true)
	public ServedMeal require(UUID mealId) {
		return find(mealId).orElseThrow(() -> new ApplicationException(
				ErrorCode.RESOURCE_NOT_FOUND, Map.of("mealId", String.valueOf(mealId))));
	}

	/**
	 * How many plates the kitchen is cooking on a date, per meal — <em>Breakfast 100 · Lunch 250 ·
	 * Dinner 180</em> (brief §1d). Per meal and never a total, because a plate at breakfast and a
	 * plate at dinner are not the same plate, and adding them is how the tile came to report 750 for
	 * a lunch of three dishes.
	 */
	@Transactional(readOnly = true)
	public Map<String, Integer> platesByMealKind(LocalDate date) {
		Map<String, Integer> plates = new LinkedHashMap<>();
		for (ServedMeal meal : list(date, date)) {
			// A cancelled meal is not work the kitchen has to do, so it is not plates either.
			if (meal.status() == MealStatus.CANCELLED) {
				continue;
			}
			// An event is named by its name and not by its kind. Every event of every temple is
			// called Event, so keying this on the kind would have two events on one Saturday
			// overwrite each other and the tile would report one of them.
			plates.put(label(meal), meal.plates());
		}
		return plates;
	}

	/** What to call a meal on a screen: its event name where it is an event, else its kind. */
	private static String label(ServedMeal meal) {
		return meal.eventName() == null || meal.eventName().isBlank()
				? meal.mealKind() : meal.eventName();
	}

	/**
	 * How many meals in the range went out and were never written down — the count behind the nudge,
	 * <em>"3 meals from earlier this week not yet recorded"</em>.
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
	 *
	 * <p>The meal row is locked first. Two people in the office typing in the same card at once would
	 * otherwise both read "not recorded" and both draw the stock.
	 */
	@Transactional
	public ServedMeal record(AuthenticatedUser actor, UUID mealId, RecordMealRequest request) {
		lock(mealId);
		ServedMeal meal = require(mealId);

		if (meal.recorded()) {
			throw new ApplicationException(ErrorCode.MEAL_ALREADY_RECORDED, Map.of("mealId", mealId));
		}
		List<MealDishView> open = meal.dishes().stream()
				.filter(d -> d.status() == MealStatus.PLANNED).toList();
		if (open.isEmpty()) {
			// Nothing left to record is one of two different situations, and they get different
			// answers: a meal whose dishes are already cooked was recorded some other way, and a meal
			// whose dishes were all called off never went to the kitchen at all.
			boolean anyCooked = meal.dishes().stream().anyMatch(d -> d.status() == MealStatus.COOKED);
			throw new ApplicationException(
					anyCooked ? ErrorCode.MEAL_ALREADY_RECORDED : ErrorCode.MEAL_NOT_RECORDABLE,
					Map.of("mealId", mealId));
		}

		Map<UUID, RecordMealRequest.DishRecord> given = new LinkedHashMap<>();
		for (RecordMealRequest.DishRecord dish : request.dishes()) {
			given.put(dish.dishId(), dish);
		}
		for (UUID id : given.keySet()) {
			if (open.stream().noneMatch(d -> d.id().equals(id))) {
				throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("dishId", id));
			}
		}

		for (MealDishView dish : open) {
			RecordMealRequest.DishRecord entry = given.get(dish.id());
			if (entry == null) {
				// Silence is not an answer. Deciding on the office's behalf whether an unmentioned dish
				// was cooked is exactly the guess this form exists to avoid.
				throw new ApplicationException(ErrorCode.SERVINGS_NOT_VALID,
						Map.of("dishId", dish.id(), "recipe", dish.recipeName()));
			}
			BigDecimal served = servedFigure(dish, entry.notMade(), entry.actualServings());
			BigDecimal consumed = consumedFigure(dish, entry.notMade(), entry.consumedQuantity(), served);

			if (!entry.notMade()) {
				// Against the actual figure, not the planned one — the whole point of collecting it.
				consumptionService.consume(actor, new ConsumeRequest(
						dish.recipeId(), served, dish.id(), null, trimToNull(request.note())));
			}

			// A dish that never went into a pot did not get cooked, and there are only three states to
			// say that in. CANCELLED with not_made recorded beside it says the true thing: it was
			// called off at the stove rather than in the plan, and it drew nothing.
			jdbc.update("""
					UPDATE meal_dishes
					SET status = ?, actual_servings = ?, consumed_quantity = ?, not_made = ?,
						cooked_at = ?, updated_at = now()
					WHERE id = ?
					""",
					entry.notMade() ? "CANCELLED" : "COOKED",
					served,
					consumed,
					entry.notMade(),
					entry.notMade() ? null : OffsetDateTime.now(java.time.ZoneOffset.UTC),
					dish.id());

			auditService.record(actor, AuditAction.MEAL_COOKED, AuditEntityType.MEAL_PLAN, dish.id(),
					Map.of("status", "PLANNED", "plannedServings", String.valueOf(dish.targetYield())),
					Map.of("status", entry.notMade() ? "CANCELLED" : "COOKED",
							"cooked", String.valueOf(served),
							"consumed", String.valueOf(consumed),
							"notMade", String.valueOf(entry.notMade())),
					null);
		}

		jdbc.update("""
				UPDATE meals
				SET recorded_at = now(), recorded_by = ?, recording_note = ?, updated_at = now()
				WHERE id = ?
				""", actor.getUserId(), trimToNull(request.note()), mealId);

		return require(mealId);
	}

	/**
	 * Corrects what a recorded meal actually served, moving the stock with it (T-007, docket S5/M2).
	 *
	 * <p><strong>Not a reopening.</strong> Rajeev settled the shape on 2026-09-07: the recording is
	 * not undone and re-entered, it is <em>answered</em>. Each dish keeps the figure it was first
	 * given (V106), each ledger draw keeps its place and gains a reverse entry beside it, and the
	 * meal is marked with who corrected it, when and why.
	 *
	 * <p><strong>Together, or the feature is a lie.</strong> One {@code @Transactional} over both
	 * halves. {@code InventoryConsumptionService} joins this transaction, so a dish that cannot be
	 * re-drawn — the shelf is short at the corrected figure — rolls back the mark, every earlier
	 * dish's reversal, and the row updates with it.
	 *
	 * <p><strong>The meal is marked first and the stock moved after, deliberately</strong> — the same
	 * ordering, for the same reason, as {@code DonationVoidService.voidDonation}. The other way round
	 * makes the atomicity claim untestable: a stock failure would simply happen before anything had
	 * been written, and a green test would prove nothing about the rollback.
	 *
	 * <p><strong>A dish whose figures did not move is left entirely alone.</strong> Restating a figure
	 * is not changing it. Reversing and re-drawing an unchanged dish would write two ledger rows that
	 * net to nothing and stamp {@code original_actual_servings} on a dish nobody corrected.
	 */
	@Transactional
	public ServedMeal correct(AuthenticatedUser actor, UUID mealId, CorrectMealRequest request) {
		String note = trimToNull(request.note());
		if (note == null) {
			// Bean validation refuses this at the controller. Here as well, because the column's own
			// CHECK refuses it behind both and an exception from a constraint names nothing a reader
			// could act on.
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("field", "note"));
		}

		// FOR UPDATE, because the check below and the write after it must not straddle another
		// admin's correction. Without the lock two tabs both read "not corrected" and both reverse
		// the same movements — and the second reversal is the one nobody would ever go looking for.
		CorrectionTarget target = jdbc.query("""
				SELECT recorded_at, corrected_at FROM meals WHERE id = ? FOR UPDATE
				""", (rs, n) -> new CorrectionTarget(
						instant(rs, "recorded_at"), instant(rs, "corrected_at")),
				mealId).stream().findFirst()
				.orElseThrow(() -> new ApplicationException(
						ErrorCode.RESOURCE_NOT_FOUND, Map.of("mealId", mealId)));

		if (target.recordedAt() == null) {
			// A meal that was planned but never came back has nothing to correct. RESOURCE_NOT_FOUND
			// rather than MEAL_NOT_RECORDABLE, which says a cancelled meal never went to the kitchen
			// and would be a different and untrue explanation. What is missing is the recording.
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND,
					Map.of("mealId", mealId, "missing", "recording"));
		}
		if (target.correctedAt() != null) {
			throw new ApplicationException(ErrorCode.MEAL_ALREADY_CORRECTED, Map.of("mealId", mealId));
		}

		ServedMeal meal = require(mealId);

		// What the recording spoke about, and only that. A dish COOKED went into a pot; a dish
		// CANCELLED with not_made against it was called off at the stove and was part of the same
		// form. A dish cancelled in the *plan* never reached the recording at all.
		List<MealDishView> recorded = meal.dishes().stream()
				.filter(d -> d.status() == MealStatus.COOKED || d.notMade())
				.toList();

		Map<UUID, CorrectMealRequest.DishCorrection> given = new LinkedHashMap<>();
		for (CorrectMealRequest.DishCorrection dish : request.dishes()) {
			given.put(dish.dishId(), dish);
		}
		for (UUID id : given.keySet()) {
			if (recorded.stream().noneMatch(d -> d.id().equals(id))) {
				throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("dishId", id));
			}
		}

		// Every figure is checked before anything is written, so the refusal names the dish the
		// office mistyped rather than whichever dish happened to be reached first.
		Map<UUID, Figures> wanted = new LinkedHashMap<>();
		for (MealDishView dish : recorded) {
			CorrectMealRequest.DishCorrection entry = given.get(dish.id());
			if (entry == null) {
				throw new ApplicationException(ErrorCode.SERVINGS_NOT_VALID,
						Map.of("dishId", dish.id(), "recipe", dish.recipeName()));
			}
			BigDecimal served = servedFigure(dish, entry.notMade(), entry.actualServings());
			wanted.put(dish.id(), new Figures(entry.notMade(), served,
					consumedFigure(dish, entry.notMade(), entry.consumedQuantity(), served)));
		}

		List<MealDishView> changed = recorded.stream()
				.filter(dish -> moved(dish, wanted.get(dish.id())))
				.toList();
		if (changed.isEmpty()) {
			// A correction that corrects nothing would still badge the meal as corrected and put a
			// name and an hour against a change nobody made. Refused rather than recorded.
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
					Map.of("field", "dishes", "mealId", mealId));
		}

		jdbc.update("""
				UPDATE meals
				SET corrected_at = now(), corrected_by = ?, correction_note = ?, updated_at = now()
				WHERE id = ?
				""", actor.getUserId(), note, mealId);

		// Two passes over the changed dishes, and the split between them is the whole of T-083. A lunch
		// whose khichadi goes 400 → 640 while its pulao goes 640 → 400 moves no rice at all on net, and
		// dish at a time the khichadi's re-draw is asked for while the pulao's old 640 is still standing:
		// FefoAllocator sums stock_movements, sees the one reversal that has happened and none of the
		// ones about to, and refuses a temple that is holding the rice. So every changed dish gives back
		// what it drew before any dish draws again.
		Map<UUID, Integer> reversals = new LinkedHashMap<>();
		for (MealDishView dish : changed) {
			reversals.put(dish.id(), markAndReverse(actor, dish, wanted.get(dish.id()), note));
		}
		for (MealDishView dish : changed) {
			redrawAndRecord(actor, dish, wanted.get(dish.id()), note, reversals.get(dish.id()));
		}

		return require(mealId);
	}

	/**
	 * Pass one for one dish: moves the row to its corrected figures and gives back everything it drew,
	 * answering with how many movements that took. The row is written before the ledger so the
	 * rollback has something to undo.
	 */
	private int markAndReverse(
			AuthenticatedUser actor, MealDishView dish, Figures figures, String note) {

		// A dish that never went into a pot has no hour it was cooked at. One corrected *into* having
		// been cooked keeps whatever hour it already had, and takes now() only when it had none.
		OffsetDateTime cookedAt = figures.notMade()
				? null
				: dish.cookedAt() == null
						? OffsetDateTime.now(java.time.ZoneOffset.UTC)
						: OffsetDateTime.ofInstant(dish.cookedAt(), java.time.ZoneOffset.UTC);

		// original_* is read out of the row's own current values rather than from the view in hand,
		// so what is preserved is what the database actually holds at whatever scale it kept.
		jdbc.update("""
				UPDATE meal_dishes
				SET original_actual_servings   = actual_servings,
					original_consumed_quantity = consumed_quantity,
					status = ?, actual_servings = ?, consumed_quantity = ?, not_made = ?,
					cooked_at = ?, updated_at = now()
				WHERE id = ?
				""",
				figures.notMade() ? "CANCELLED" : "COOKED",
				figures.served(),
				figures.consumed(),
				figures.notMade(),
				cookedAt,
				dish.id());

		// Everything this dish drew goes back, including the draws of a figure already corrected by
		// hand from the inventory screen — compensateAllFor skips those rather than refusing.
		return consumptionService.reverse(actor, dish.id(), "Meal corrected: " + note);
	}

	/**
	 * Pass two for one dish: draws the corrected figure, and files what the correction actually did.
	 * A dish corrected to "not made" draws nothing and still files its entry: it was corrected.
	 */
	private void redrawAndRecord(
			AuthenticatedUser actor, MealDishView dish, Figures figures, String note, int reversed) {

		if (!figures.notMade()) {
			consumptionService.consume(actor, new ConsumeRequest(
					dish.recipeId(), figures.served(), dish.id(), null, "Corrected: " + note));
		}

		auditService.record(actor, AuditAction.MEAL_CORRECTED, AuditEntityType.MEAL_PLAN, dish.id(),
				Map.of("status", String.valueOf(dish.status()),
						"cooked", String.valueOf(dish.actualServings()),
						"consumed", String.valueOf(dish.consumedQuantity()),
						"notMade", String.valueOf(dish.notMade())),
				correctedSnapshot(dish.id(), reversed),
				note);
	}

	/**
	 * The dish as it now stands — <strong>read back from the row, never rebuilt from the request.</strong>
	 *
	 * <p>Wave 4b found an audit entry claiming a temple's coordinates had moved from "12.971600" to
	 * "12.9716" in a field nobody had edited, because the after-state was built from what was asked
	 * for rather than from what was stored. An entry that is believed and wrong is worse than none.
	 */
	private Map<String, Object> correctedSnapshot(UUID dishId, int reversed) {
		Map<String, Object> stored = jdbc.queryForMap("""
				SELECT status, actual_servings, consumed_quantity, not_made,
					   original_actual_servings, original_consumed_quantity
				FROM meal_dishes WHERE id = ?
				""", dishId);

		Map<String, Object> after = new LinkedHashMap<>();
		after.put("status", stored.get("status"));
		after.put("cooked", plain(stored.get("actual_servings")));
		after.put("consumed", plain(stored.get("consumed_quantity")));
		after.put("notMade", String.valueOf(stored.get("not_made")));
		after.put("originalCooked", plain(stored.get("original_actual_servings")));
		after.put("originalConsumed", plain(stored.get("original_consumed_quantity")));
		after.put("stockMovementsReversed", reversed);
		return after;
	}

	private static String plain(Object value) {
		return value instanceof BigDecimal amount ? amount.toPlainString() : String.valueOf(value);
	}

	/**
	 * Whether a correction actually moves this dish. {@code compareTo} and not {@code equals}, because
	 * a form resending 400 against a column holding {@code 400.000} is not a change. Null is a value
	 * here: a figure gained or lost is a change, a figure absent both times is not.
	 */
	private static boolean moved(MealDishView dish, Figures wanted) {
		if (dish.notMade() != wanted.notMade()) {
			return true;
		}
		if (!sameFigure(dish.actualServings(), wanted.served())) {
			return true;
		}
		return !sameFigure(dish.consumedQuantity(), wanted.consumed());
	}

	private static boolean sameFigure(BigDecimal held, BigDecimal wanted) {
		if (held == null || wanted == null) {
			return held == null && wanted == null;
		}
		return held.compareTo(wanted) == 0;
	}

	/** One dish's corrected figures, once validated: what was cooked, what went out, and whether. */
	private record Figures(boolean notMade, BigDecimal served, BigDecimal consumed) {
	}

	/** The locked meal row's two facts the correction is guarded by. */
	private record CorrectionTarget(java.time.Instant recordedAt, java.time.Instant correctedAt) {
	}

	/**
	 * The number printed on this meal's job card, issuing one if the meal has never been printed.
	 *
	 * <p>Issued once and kept. A reprint after a dish was swapped is the same meal and carries the
	 * same number — the number exists so that a signed sheet in a folder can be traced back to this
	 * record six months later.
	 *
	 * <p>The write only lands on a meal that still has no number, and the number is read back from
	 * the row afterwards. Two prints at the same moment therefore agree on one number; the loser's
	 * increment of the counter is a gap, which the counter already tolerates.
	 */
	@Transactional
	public String issueCardNumber(UUID mealId) {
		ServedMeal meal = require(mealId);
		if (meal.cardNumber() != null) {
			return meal.cardNumber();
		}
		String number = nextCardNumber(meal.mealKind(), meal.planDate());
		jdbc.update("""
				UPDATE meals SET card_number = ?, card_issued_at = now(), updated_at = now()
				WHERE id = ? AND card_number IS NULL
				""", number, mealId);
		return jdbc.queryForObject("SELECT card_number FROM meals WHERE id = ?", String.class, mealId);
	}

	// ---------------------------------------------------------------------

	/** Takes the meal row's lock for the rest of the transaction, or refuses a meal that is not there. */
	private void lock(UUID mealId) {
		List<UUID> found = jdbc.queryForList("SELECT id FROM meals WHERE id = ? FOR UPDATE", UUID.class, mealId);
		if (found.isEmpty()) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("mealId", mealId));
		}
	}

	/**
	 * The next card number for this temple: {@code LC-2026-0142}.
	 *
	 * <p>The counter is per temple and not per kind, so the number alone identifies one sheet however
	 * the prefix is derived. The prefix is a reading aid — Lunch becomes LC, Breakfast BC, Deity
	 * Offering DO, Event EV — and two kinds sharing a prefix is harmless because the prefix is not the
	 * identity. Like the PO counter it is gap-tolerant.
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

	/**
	 * What this dish actually went out at, or a refusal naming the dish rather than the form. Takes
	 * the two figures rather than the request that carried them, so recording and correcting are held
	 * to exactly the same rule.
	 */
	private BigDecimal servedFigure(MealDishView dish, boolean notMade, BigDecimal actualServings) {
		if (notMade) {
			return BigDecimal.ZERO;
		}
		BigDecimal served = actualServings;
		if (served == null || served.signum() <= 0 || served.compareTo(MAX_SERVINGS) > 0) {
			throw new ApplicationException(ErrorCode.SERVINGS_NOT_VALID,
					Map.of("dishId", dish.id(), "recipe", dish.recipeName(),
							"servings", String.valueOf(served)));
		}
		return served;
	}

	/**
	 * How much of what was cooked actually went out, or a refusal naming the dish. Null is kept as
	 * null; a figure larger than what was cooked is refused, because a dish cannot serve more than it
	 * made and a typed extra zero is exactly the mistake that would otherwise be recorded as a fact.
	 */
	private BigDecimal consumedFigure(
			MealDishView dish, boolean notMade, BigDecimal consumedQuantity, BigDecimal cooked) {
		if (notMade) {
			return BigDecimal.ZERO;
		}
		BigDecimal consumed = consumedQuantity;
		if (consumed == null) {
			return null;
		}
		if (consumed.signum() < 0 || consumed.compareTo(cooked) > 0) {
			throw new ApplicationException(ErrorCode.SERVINGS_NOT_VALID,
					Map.of("dishId", dish.id(), "recipe", dish.recipeName(),
							"servings", String.valueOf(consumed)));
		}
		return consumed;
	}

	private List<ServedMeal> assembleAll(List<MealRow> meals, Map<UUID, List<MealDishView>> dishes) {
		List<ServedMeal> out = new ArrayList<>(meals.size());
		for (MealRow meal : meals) {
			out.add(assemble(meal, dishes.getOrDefault(meal.id(), List.of())));
		}
		return out;
	}

	/**
	 * One meal from its row and its dishes. The head count, the notes and every other whole-meal fact
	 * come from the one row; before D-27 they were copied onto every dish and read back from "the
	 * largest" or "the first non-blank", which is a rule nobody should have to write twice.
	 */
	private static ServedMeal assemble(MealRow m, List<MealDishView> dishes) {
		return new ServedMeal(
				m.id(), m.mealKindId(), m.planDate(), m.mealKind(), m.readyBy(),
				m.adults(), m.children(), m.seniors(), platesOf(m, dishes), m.crewRequired(),
				m.dayType(), m.occasionName(), m.eventName(), m.isOutside(), m.handover(),
				m.contactName(), m.contactPhone(), m.deliveryAddress(), m.deliverySubLocation(),
				m.deliveryPlaceId(), m.deliveryLatitude(), m.deliveryLongitude(), m.guestsEatAt(),
				m.travelMinutes(), m.travelMinutesSource(), m.purpose(), m.kitchenNotes(), m.serverNotes(),
				statusOf(dishes),
				m.cardNumber(), m.cardIssuedAt(),
				m.recordedAt() != null, m.recordedAt(), m.recordedByName(), m.recordingNote(),
				m.correctedAt() != null, m.correctedAt(), m.correctedByName(), m.correctionNote(),
				Collections.unmodifiableList(dishes),
				null);
	}

	/** The meal's state as its dishes say it; see {@link ServedMeal#status()}. */
	private static MealStatus statusOf(List<MealDishView> dishes) {
		if (dishes.stream().anyMatch(d -> d.status() == MealStatus.COOKED)) {
			return MealStatus.COOKED;
		}
		if (dishes.stream().anyMatch(d -> d.status() == MealStatus.PLANNED)) {
			return MealStatus.PLANNED;
		}
		return MealStatus.CANCELLED;
	}

	/**
	 * What one meal scales to: the head count if the planner gave one, otherwise the largest dish's
	 * own figure, which is all a meal planned without a head count has. Never the sum of the dishes.
	 */
	private static int platesOf(MealRow meal, List<MealDishView> dishes) {
		if (meal.adults() == null && meal.children() == null && meal.seniors() == null) {
			return dishes.stream()
					.map(MealDishView::targetYield)
					.filter(java.util.Objects::nonNull)
					.max(Comparator.naturalOrder())
					.map(BigDecimal::intValue)
					.orElse(0);
		}
		BigDecimal total = BigDecimal.valueOf(meal.adults() == null ? 0 : meal.adults())
				.add(CHILD_PORTION.multiply(BigDecimal.valueOf(meal.children() == null ? 0 : meal.children())))
				.add(SENIOR_PORTION.multiply(BigDecimal.valueOf(meal.seniors() == null ? 0 : meal.seniors())));
		return total.setScale(0, RoundingMode.HALF_UP).intValue();
	}

	private Map<UUID, List<MealDishView>> dishesIn(String sql, Object[] args) {
		Map<UUID, List<MealDishView>> byMeal = new LinkedHashMap<>();
		for (MealDishView dish : jdbc.query(sql, DISH_MAPPER, args)) {
			byMeal.computeIfAbsent(dish.mealId(), k -> new ArrayList<>()).add(dish);
		}
		return byMeal;
	}

	private static String trimToNull(String s) {
		if (s == null) {
			return null;
		}
		String t = s.trim();
		return t.isEmpty() ? null : t;
	}

	/** Every dish of the given meals, for callers that hold meal ids rather than a date range. */
	@Transactional(readOnly = true)
	public Map<UUID, List<MealDishView>> dishesOf(Collection<UUID> mealIds) {
		if (mealIds.isEmpty()) {
			return Map.of();
		}
		String placeholders = String.join(",", Collections.nCopies(mealIds.size(), "?"));
		return dishesIn(DISH_SELECT + " WHERE d.meal_id IN (" + placeholders + ")" + DISH_ORDER,
				mealIds.toArray());
	}

	private record MealRow(
			UUID id, UUID mealKindId, LocalDate planDate, String mealKind, DayType dayType,
			LocalTime readyBy, Integer adults, Integer children, Integer seniors, Integer crewRequired,
			String occasionName, String eventName, boolean isOutside, Handover handover,
			String contactName, String contactPhone, String deliveryAddress, String deliverySubLocation,
			String deliveryPlaceId, BigDecimal deliveryLatitude, BigDecimal deliveryLongitude,
			LocalTime guestsEatAt, Integer travelMinutes, String travelMinutesSource, String purpose,
			String kitchenNotes, String serverNotes, String cardNumber, java.time.Instant cardIssuedAt,
			java.time.Instant recordedAt, String recordedByName, String recordingNote,
			java.time.Instant correctedAt, String correctedByName, String correctionNote) {
	}

	/**
	 * The meal, its day and its kind's current name.
	 *
	 * <p>Two LEFT JOINs onto {@code users} and not one: the person who recorded a meal and the person
	 * who corrected it are different people on purpose — recording is everyday kitchen work on
	 * {@code MANAGE_MEAL_PLANS}, correcting is the Temple Admin's alone (D-4). Both are LEFT, because
	 * both columns are {@code ON DELETE SET NULL}: the fact must outlive the name.
	 *
	 * <p>No tenant predicate, because there must not be one: row-level security on all three tables
	 * answers that from the verified token.
	 */
	private static final String MEAL_SELECT = """
			SELECT m.id, m.meal_kind_id, pd.plan_date, k.name AS meal_kind, pd.day_type, m.ready_by,
				   m.adults, m.children, m.seniors, m.crew_required, m.occasion_name, m.event_name,
				   m.is_outside, m.handover, m.contact_name, m.contact_phone, m.delivery_address,
				   m.delivery_sub_location, m.delivery_place_id, m.delivery_latitude, m.delivery_longitude,
				   m.guests_eat_at, m.travel_minutes, m.travel_minutes_source, m.purpose,
				   m.kitchen_notes, m.server_notes, m.card_number, m.card_issued_at,
				   m.recorded_at, m.recording_note, u.full_name AS recorded_by_name,
				   m.corrected_at, m.correction_note, c.full_name AS corrected_by_name
			FROM meals m
			JOIN meal_plan_days pd ON pd.id = m.meal_plan_day_id
			JOIN meal_kinds k ON k.id = m.meal_kind_id
			LEFT JOIN users u ON u.id = m.recorded_by
			LEFT JOIN users c ON c.id = m.corrected_by
			""";

	/** The order the kitchen works in: the day, then when each meal is due, then the kinds' own order. */
	private static final String MEAL_ORDER =
			" ORDER BY pd.plan_date, m.ready_by, k.sort_order, lower(COALESCE(m.event_name, '')), m.id";

	private static final String DISH_SELECT = """
			SELECT d.id, d.meal_id, d.recipe_id, r.name AS recipe_name,
				   r.base_yield_unit AS target_yield_unit, d.target_yield, d.status,
				   d.actual_servings, d.consumed_quantity, d.not_made,
				   d.original_actual_servings, d.original_consumed_quantity,
				   d.cooked_at, d.ekadashi_ack_at, d.created_at
			FROM meal_dishes d
			JOIN recipes r ON r.id = d.recipe_id
			""";

	/** The order the planner added them in; the id breaks a tie inside one statement's now(). */
	private static final String DISH_ORDER = " ORDER BY d.created_at, d.id";

	private static final RowMapper<MealRow> MEAL_MAPPER = (rs, n) -> new MealRow(
			rs.getObject("id", UUID.class),
			rs.getObject("meal_kind_id", UUID.class),
			rs.getObject("plan_date", LocalDate.class),
			rs.getString("meal_kind"),
			DayType.valueOf(rs.getString("day_type")),
			rs.getObject("ready_by", LocalTime.class),
			(Integer) rs.getObject("adults"),
			(Integer) rs.getObject("children"),
			(Integer) rs.getObject("seniors"),
			(Integer) rs.getObject("crew_required"),
			rs.getString("occasion_name"),
			rs.getString("event_name"),
			rs.getBoolean("is_outside"),
			rs.getString("handover") == null ? null : Handover.valueOf(rs.getString("handover")),
			rs.getString("contact_name"),
			rs.getString("contact_phone"),
			rs.getString("delivery_address"),
			rs.getString("delivery_sub_location"),
			rs.getString("delivery_place_id"),
			rs.getBigDecimal("delivery_latitude"),
			rs.getBigDecimal("delivery_longitude"),
			rs.getObject("guests_eat_at", LocalTime.class),
			(Integer) rs.getObject("travel_minutes"),
			rs.getString("travel_minutes_source"),
			rs.getString("purpose"),
			rs.getString("kitchen_notes"),
			rs.getString("server_notes"),
			rs.getString("card_number"),
			instant(rs, "card_issued_at"),
			instant(rs, "recorded_at"),
			rs.getString("recorded_by_name"),
			rs.getString("recording_note"),
			instant(rs, "corrected_at"),
			rs.getString("corrected_by_name"),
			rs.getString("correction_note"));

	private static final RowMapper<MealDishView> DISH_MAPPER = (rs, n) -> new MealDishView(
			rs.getObject("id", UUID.class),
			rs.getObject("meal_id", UUID.class),
			rs.getObject("recipe_id", UUID.class),
			rs.getString("recipe_name"),
			rs.getBigDecimal("target_yield"),
			rs.getString("target_yield_unit"),
			MealStatus.valueOf(rs.getString("status")),
			rs.getBigDecimal("actual_servings"),
			rs.getBigDecimal("consumed_quantity"),
			rs.getBoolean("not_made"),
			rs.getBigDecimal("original_actual_servings"),
			rs.getBigDecimal("original_consumed_quantity"),
			instant(rs, "cooked_at"),
			instant(rs, "ekadashi_ack_at") != null,
			instant(rs, "created_at"));

	private static java.time.Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}
}
