package org.iskcon.kms.meal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * what the brief means every time it says "the meal" — and owns the two facts that belong to a whole
 * meal rather than to any dish of it: the number printed on its job card, and the moment somebody in
 * the office typed in what the returned card said.
 *
 * <p><strong>What a meal is identified by.</strong> A date, a kind, and — where the kind is an
 * event — the event's own name (V89, E4-S15 D1). The pair alone was right while every kind was one
 * meal a day, and stopped being right the moment every event started calling itself Event: a morning
 * children's reading and an evening Bhajan Prasadam on one Saturday would otherwise be one recording
 * and one job card, which is the very thing splitting events out of the main meals was for. The
 * three main meals carry no event name and are reached by exactly the pair they always were.
 *
 * <p><strong>Why recording exists at all.</strong> Marking a meal cooked is the moment its
 * ingredients leave stock. Take it away and the store room never depletes and the shopping list
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
			grouped.computeIfAbsent(Key.of(dish.planDate(), dish.mealKind(), dish.eventName()),
					k -> new ArrayList<>()).add(dish);
		}

		List<ServedMeal> meals = new ArrayList<>();
		grouped.forEach((key, rows) -> meals.add(assemble(key, rows, services.get(key))));
		return meals;
	}

	/**
	 * One meal, or empty when nothing at all is planned for that date, kind and event.
	 *
	 * <p>{@code eventName} is null for Breakfast, Lunch, Dinner and everything else that is not an
	 * event, and it is null too for an event nobody has named — V88 carried a handful of those across
	 * and they group together, which is the reading V89's header argues for at length.
	 */
	@Transactional(readOnly = true)
	public Optional<ServedMeal> find(LocalDate date, String mealKind, String eventName) {
		String kind = mealKindService.require(mealKind).name();
		Key wanted = Key.of(date, kind, eventName);
		return list(date, date).stream()
				.filter(m -> Key.of(m.planDate(), m.mealKind(), m.eventName()).equals(wanted))
				.findFirst();
	}

	/** One meal, or a refusal. The job card and the recording form both start here. */
	@Transactional(readOnly = true)
	public ServedMeal require(LocalDate date, String mealKind, String eventName) {
		return find(date, mealKind, eventName).orElseThrow(() -> new ApplicationException(
				ErrorCode.RESOURCE_NOT_FOUND, Map.of(
						"planDate", date,
						"mealKind", String.valueOf(mealKind),
						"eventName", String.valueOf(eventName))));
	}

	/** One meal by its own row, which is how a generated job card refers back to it. */
	@Transactional(readOnly = true)
	public ServedMeal requireByServiceId(UUID serviceId) {
		ServiceRow row = jdbc.query(SERVICE_SELECT + " WHERE ms.id = ?", SERVICE_MAPPER, serviceId)
				.stream().findFirst()
				.orElseThrow(() -> new ApplicationException(
						ErrorCode.RESOURCE_NOT_FOUND, Map.of("mealServiceId", serviceId)));
		return require(row.planDate(), row.mealKind(), row.eventName());
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
			// An event is named by its name and not by its kind. Every event of every temple is
			// called Event, so keying this on the kind would have two events on one Saturday
			// overwrite each other and the tile would report one of them — the same class of bug as
			// the 750-plate lunch, and silent in the same way. The name is also the only label a
			// reader can act on: "Event 30" says nothing, "Children's Gita Reading 30" does.
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
		ServedMeal meal = require(request.planDate(), kind, request.eventName());

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
			BigDecimal served = servedFigure(dish, entry.notMade(), entry.actualServings());

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
					SET status = ?, actual_servings = ?, consumed_quantity = ?, not_made = ?,
						cooked_at = ?, updated_at = now()
					WHERE id = ?
					""",
					entry.notMade() ? "CANCELLED" : "COOKED",
					served,
					consumedFigure(dish, entry.notMade(), entry.consumedQuantity(), served),
					entry.notMade(),
					entry.notMade() ? null : OffsetDateTime.now(java.time.ZoneOffset.UTC),
					dish.id());

			auditService.record(actor, AuditAction.MEAL_COOKED, AuditEntityType.MEAL_PLAN, dish.id(),
					Map.of("status", "PLANNED", "plannedServings", String.valueOf(dish.targetYield())),
					Map.of("status", entry.notMade() ? "CANCELLED" : "COOKED",
							"cooked", String.valueOf(served),
							"consumed", String.valueOf(
									consumedFigure(dish, entry.notMade(), entry.consumedQuantity(), served)),
							"notMade", String.valueOf(entry.notMade())),
					null);
		}

		// The name as the plans spell it, not as the request happened to type it: the two agree
		// case-insensitively or the meal would not have been found, and the row should carry the
		// planner's own words.
		UUID serviceId = ensureService(request.planDate(), kind, meal.eventName());
		jdbc.update("""
				UPDATE meal_services
				SET recorded_at = now(), recorded_by = ?, recording_note = ?, updated_at = now()
				WHERE id = ?
				""", actor.getUserId(), trimToNull(request.note()), serviceId);

		return require(request.planDate(), kind, request.eventName());
	}

	/**
	 * Corrects what a recorded meal actually served, moving the stock with it (T-007, docket S5/M2).
	 *
	 * <p><strong>Not a reopening.</strong> Rajeev settled the shape on 2026-09-07: the recording is
	 * not undone and re-entered, it is <em>answered</em>. Each dish keeps the figure it was first
	 * given (V106), each ledger draw keeps its place and gains a reverse entry beside it, and the
	 * meal is marked with who corrected it, when and why. So a screen can say <em>"640 cooked,
	 * corrected from 400 by Anand on 8 September"</em> rather than quietly showing a different number
	 * than it showed yesterday — and the audit trail falls out of that shape rather than being bolted
	 * onto it.
	 *
	 * <p><strong>Three things stood between the compensating primitive and this, and all three are
	 * here.</strong> {@code StockMovementService.compensate} has reversed a single movement since
	 * E3-S2, but cooking one dish writes one movement per (ingredient, batch) draw, so "the meal's
	 * stock" is a <em>set</em> — and nothing could enumerate it, because the ledger's history filtered
	 * by ingredient and type and nothing else. That read capability is now
	 * {@code StockMovementService.history(…, referenceId, …)}, the set reversal is
	 * {@code compensateAllFor}, and this method is the third piece: the meal record and the ledger
	 * moving together.
	 *
	 * <p><strong>Together, or the feature is a lie.</strong> One {@code @Transactional} over both
	 * halves. {@code InventoryConsumptionService} joins this transaction, so a dish that cannot be
	 * re-drawn — the shelf is short at the corrected figure — rolls back the mark, every earlier
	 * dish's reversal, and the row updates with it. A meal reading "corrected to 640" over a store
	 * room drawn against 400 would be worse than the defect this fixes, because it would look right.
	 *
	 * <p><strong>The meal is marked first and the stock moved after, deliberately</strong> — the same
	 * ordering, for the same reason, as {@code DonationVoidService.voidDonation}. The other way round
	 * makes the atomicity claim untestable: a stock failure would simply happen before anything had
	 * been written, and a green test would prove nothing about the rollback.
	 *
	 * <p><strong>A dish whose figures did not move is left entirely alone.</strong> The form restates
	 * the whole meal — a dish left out is refused rather than assumed unchanged, exactly as when
	 * recording — but restating a figure is not changing it. Reversing and re-drawing an unchanged
	 * dish would write two ledger rows that net to nothing, on a table whose only consumer is a sum,
	 * and would stamp {@code original_actual_servings} on a dish nobody corrected, so the planner
	 * would offer "640 cooked, corrected from 640" on every untouched preparation of the meal.
	 */
	@Transactional
	public ServedMeal correct(AuthenticatedUser actor, UUID serviceId, CorrectMealRequest request) {
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
		//
		// Read through a mapper that asks the driver for the types it wants, rather than through
		// queryForMap: a DATE column arrives from a generic read as java.sql.Date, and casting that
		// to LocalDate is a ClassCastException at run time that nothing at compile time objects to.
		CorrectionTarget service = jdbc.query("""
				SELECT id, plan_date, meal_kind, event_name, recorded_at, corrected_at
				FROM meal_services WHERE id = ? FOR UPDATE
				""", (rs, n) -> new CorrectionTarget(
						rs.getObject("plan_date", LocalDate.class),
						rs.getString("meal_kind"),
						rs.getString("event_name"),
						instant(rs, "recorded_at"),
						instant(rs, "corrected_at")),
				serviceId).stream().findFirst()
				.orElseThrow(() -> new ApplicationException(
						ErrorCode.RESOURCE_NOT_FOUND, Map.of("mealServiceId", serviceId)));

		if (service.recordedAt() == null) {
			// A meal_services row exists as soon as a job card is printed, so this is reachable: a
			// carded meal that never came back has nothing to correct. RESOURCE_NOT_FOUND rather than
			// MEAL_NOT_RECORDABLE, which says a cancelled meal never went to the kitchen and would be
			// a different and untrue explanation. What is missing is the recording, and the detail
			// says so for the log.
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND,
					Map.of("mealServiceId", serviceId, "missing", "recording"));
		}
		if (service.correctedAt() != null) {
			throw new ApplicationException(
					ErrorCode.MEAL_ALREADY_CORRECTED, Map.of("mealServiceId", serviceId));
		}

		ServedMeal meal = require(service.planDate(), service.mealKind(), service.eventName());

		// What the recording spoke about, and only that. A dish COOKED went into a pot; a dish
		// CANCELLED with not_made against it was called off at the stove and was part of the same
		// form. A dish cancelled in the *plan* never reached the recording at all, so it is not the
		// office's to correct here and naming it is refused below.
		List<MealPlanView> recorded = meal.dishes().stream()
				.filter(d -> d.status() == MealStatus.COOKED || d.notMade())
				.toList();

		Map<UUID, CorrectMealRequest.DishCorrection> given = new LinkedHashMap<>();
		for (CorrectMealRequest.DishCorrection dish : request.dishes()) {
			given.put(dish.mealPlanId(), dish);
		}
		for (UUID id : given.keySet()) {
			if (recorded.stream().noneMatch(d -> d.id().equals(id))) {
				throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("mealPlanId", id));
			}
		}

		// Every figure is checked before anything is written. The transaction would undo a late
		// refusal anyway; doing it in one pass first means the refusal names the dish the office
		// mistyped rather than whichever dish happened to be reached before the ledger was touched.
		Map<UUID, Figures> wanted = new LinkedHashMap<>();
		for (MealPlanView dish : recorded) {
			CorrectMealRequest.DishCorrection entry = given.get(dish.id());
			if (entry == null) {
				// Silence is not an answer here either. A dish nobody mentioned is a dish nobody said
				// anything about, and deciding on the office's behalf that it was right all along is
				// the same guess the recording form refuses to make.
				throw new ApplicationException(ErrorCode.SERVINGS_NOT_VALID,
						Map.of("mealPlanId", dish.id(), "recipe", dish.recipeName()));
			}
			BigDecimal served = servedFigure(dish, entry.notMade(), entry.actualServings());
			wanted.put(dish.id(), new Figures(entry.notMade(), served,
					consumedFigure(dish, entry.notMade(), entry.consumedQuantity(), served)));
		}

		List<MealPlanView> changed = recorded.stream()
				.filter(dish -> moved(dish, wanted.get(dish.id())))
				.toList();
		if (changed.isEmpty()) {
			// A correction that corrects nothing would still badge the meal as corrected and put a
			// name and an hour against a change nobody made. Refused rather than recorded.
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
					Map.of("field", "dishes", "mealServiceId", serviceId));
		}

		jdbc.update("""
				UPDATE meal_services
				SET corrected_at = now(), corrected_by = ?, correction_note = ?, updated_at = now()
				WHERE id = ?
				""", actor.getUserId(), note, serviceId);

		// Two passes over the changed dishes, and the split between them is the whole of T-083. Doing
		// one dish end to end reads correctly — give the 400 back, then draw the 640 — and stops being
		// correct the moment a second dish of the same meal shares an ingredient with the first. A lunch
		// whose khichadi goes 400 → 640 while its pulao goes 640 → 400 moves no rice at all on net, and
		// dish at a time the khichadi's re-draw is asked for while the pulao's old 640 is still standing:
		// FefoAllocator.loadPositiveBatches sums stock_movements, so it sees the one reversal that has
		// happened and none of the ones that are about to, and refuses a temple that is holding the rice
		// with INSUFFICIENT_STOCK. Reverse the plan's sort order and the identical correction succeeds,
		// which is the tell: whether the office is believed depended on mp.ready_by.
		//
		// So every changed dish gives back what it drew before any dish draws again. Through the whole of
		// the second pass the shelf stands where it stood before the meal was cooked at all, which is the
		// only level at which "is there enough?" has an answer that does not depend on the order of the
		// rows. It costs nothing: both passes are inside the one transaction that was already here, so
		// the moment where the shelf is briefly full again is no more observable than it was before.
		Map<UUID, Integer> reversals = new LinkedHashMap<>();
		for (MealPlanView dish : changed) {
			reversals.put(dish.id(), markAndReverse(actor, dish, wanted.get(dish.id()), note));
		}
		for (MealPlanView dish : changed) {
			redrawAndRecord(actor, dish, wanted.get(dish.id()), note, reversals.get(dish.id()));
		}

		return require(meal.planDate(), meal.mealKind(), meal.eventName());
	}

	/**
	 * Pass one for one dish: moves the row to its corrected figures and gives back everything it drew,
	 * answering with how many movements that took.
	 *
	 * <p>The row is written before the ledger for the reason given on {@link #correct}: the rollback
	 * has to have something to undo. The reversal then runs before <em>any</em> dish's re-draw, and
	 * that order is not cosmetic — a dish going from 400 servings to 640 has to give the 400 back
	 * first, or the re-draw meets a shelf that still believes the first 400 are gone and a temple with
	 * just enough rice is refused for a shortfall that does not exist. Stated for one dish that
	 * sentence has been true since T-007; what {@link #correct} adds is that it is now true across the
	 * dishes of a meal too, which is the case where the shortfall was not merely imaginary but
	 * order-dependent.
	 *
	 * <p>The count comes back rather than being filed here because it belongs to the audit entry, and
	 * the audit entry cannot be written until the dish has been re-drawn — {@link #redrawAndRecord}
	 * carries it the rest of the way.
	 */
	private int markAndReverse(
			AuthenticatedUser actor, MealPlanView dish, Figures figures, String note) {

		// A dish that never went into a pot has no hour it was cooked at. One corrected *into* having
		// been cooked keeps whatever hour it already had, and takes now() only when it had none —
		// nobody knows when it actually happened, and the alternative is to leave a cooked dish
		// claiming it was never cooked.
		OffsetDateTime cookedAt = figures.notMade()
				? null
				: dish.cookedAt() == null
						? OffsetDateTime.now(java.time.ZoneOffset.UTC)
						: OffsetDateTime.ofInstant(dish.cookedAt(), java.time.ZoneOffset.UTC);

		// original_* is read out of the row's own current values rather than from the view in hand,
		// so what is preserved is what the database actually holds at whatever scale it kept — the
		// same rule the audit snapshot follows, and for the same reason.
		jdbc.update("""
				UPDATE meal_plans
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

		// Everything this dish drew goes back, including the draws of a figure that was itself
		// already corrected by hand from the inventory screen — compensateAllFor skips those rather
		// than refusing, because for them the shelf is already where it should be.
		return consumptionService.reverse(actor, dish.id(), "Meal corrected: " + note);
	}

	/**
	 * Pass two for one dish: draws the corrected figure, and files what the correction actually did.
	 *
	 * <p>Runs only once every changed dish of the meal has been reversed, so the shelf this asks is the
	 * one the meal was cooked against rather than a half-unwound one. A dish corrected to "not made"
	 * draws nothing and still files its entry: it was corrected, and an audit trail that recorded only
	 * the dishes that took stock would be silent about exactly the correction somebody would go
	 * looking for.
	 *
	 * <p>{@code reversed} is passed in from pass one because that is where it happened. Recomputing it
	 * here would be answering a different question — by now the dish has draws against it again.
	 */
	private void redrawAndRecord(
			AuthenticatedUser actor, MealPlanView dish, Figures figures, String note, int reversed) {

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
	 * for rather than from what was stored. The same mistake here would have every correction report
	 * a figure at the scale the office typed rather than the scale {@code NUMERIC(12, 3)} kept, and
	 * an entry that is believed and wrong is worse than no entry at all.
	 *
	 * <p>{@code stockMovementsReversed} travels with it because the count is a fact about what
	 * happened rather than what was asked for: a dish some of whose draws had already been corrected
	 * by hand reverses fewer movements than it made, and the trail should say so.
	 */
	private Map<String, Object> correctedSnapshot(UUID mealPlanId, int reversed) {
		Map<String, Object> stored = jdbc.queryForMap("""
				SELECT status, actual_servings, consumed_quantity, not_made,
					   original_actual_servings, original_consumed_quantity
				FROM meal_plans WHERE id = ?
				""", mealPlanId);

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
	 * Whether a correction actually moves this dish.
	 *
	 * <p>{@code compareTo} and not {@code equals}: {@code BigDecimal.equals} compares scale as well as
	 * value, so a form resending 400 against a column holding {@code 400.000} would read as a change
	 * and write a pair of ledger rows netting to nothing on every untouched dish of every corrected
	 * meal.
	 *
	 * <p>Null is a value here and not a gap. A dish whose consumed figure was never given and still is
	 * not has not moved; one that gains a figure, or loses one, has.
	 */
	private static boolean moved(MealPlanView dish, Figures wanted) {
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

	/** The locked meal row, in the types the rest of this service works in. */
	private record CorrectionTarget(
			LocalDate planDate, String mealKind, String eventName,
			java.time.Instant recordedAt, java.time.Instant correctedAt) {
	}

	/**
	 * The number printed on this meal's job card, issuing one if the meal has never been printed.
	 *
	 * <p>Issued once and kept. A reprint after a dish was swapped is the same meal and carries the
	 * same number — the number exists so that a signed sheet in a folder can be traced back to this
	 * record six months later, which a number that changed between prints could not do.
	 */
	@Transactional
	public String issueCardNumber(LocalDate date, String mealKind, String eventName) {
		String kind = mealKindService.require(mealKind).name();
		// A meal with no dishes has no card; this refuses before a number is spent.
		ServedMeal meal = require(date, kind, eventName);
		UUID serviceId = ensureService(date, kind, meal.eventName());

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
	public UUID serviceFor(LocalDate date, String mealKind, String eventName) {
		ServedMeal meal = require(date, mealKindService.require(mealKind).name(), eventName);
		return ensureService(meal.planDate(), meal.mealKind(), meal.eventName());
	}

	/**
	 * The meal's own row, created on demand. Nothing is written until a card is printed or recorded.
	 *
	 * <p>The conflict target and the lookup both name {@code lower(COALESCE(event_name, ''))} because
	 * that is the expression V89's unique index is built on, and the three have to agree exactly: an
	 * upsert that inferred a different key would raise rather than find the row it meant, and a
	 * lookup that compared differently would return two rows for one meal.
	 */
	@Transactional
	public UUID ensureService(LocalDate date, String mealKind, String eventName) {
		String name = trimToNull(eventName);
		jdbc.update("""
				INSERT INTO meal_services (tenant_id, plan_date, meal_kind, event_name)
				VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?)
				ON CONFLICT (tenant_id, plan_date, meal_kind, lower(COALESCE(event_name, '')))
				DO NOTHING
				""", date, mealKind, name);
		return jdbc.queryForObject("""
				SELECT id FROM meal_services
				WHERE plan_date = ? AND meal_kind = ?
				  AND lower(COALESCE(event_name, '')) = lower(COALESCE(?::text, ''))
				""", UUID.class, date, mealKind, name);
	}

	// ---------------------------------------------------------------------

	/**
	 * The next card number for this temple: {@code LC-2026-0142}.
	 *
	 * <p>The counter is per temple and not per kind, so the number alone identifies one sheet however
	 * the prefix is derived. The prefix is a reading aid — a person holding a folder of paper wants to
	 * see at a glance that this was a lunch — so it takes the initial of each word of the kind's name,
	 * and a single-word kind gets its initial plus C for card: Lunch becomes LC, Breakfast BC, Deity
	 * Offering DO, Event EV. A kind the application has never seen gets the same treatment,
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

	/**
	 * What this dish actually went out at, or a refusal naming the dish rather than the form.
	 *
	 * <p>Takes the two figures rather than the request that carried them, so that recording and
	 * correcting are held to exactly the same rule. They were one method over one DTO until T-007
	 * added the second door; a figure the first door turns away must not be admissible through the
	 * second, and the cheapest way to guarantee that is for there to be only one rule.
	 */
	private BigDecimal servedFigure(MealPlanView dish, boolean notMade, BigDecimal actualServings) {
		if (notMade) {
			return BigDecimal.ZERO;
		}
		BigDecimal served = actualServings;
		if (served == null || served.signum() <= 0 || served.compareTo(MAX_SERVINGS) > 0) {
			throw new ApplicationException(ErrorCode.SERVINGS_NOT_VALID,
					Map.of("mealPlanId", dish.id(), "recipe", dish.recipeName(),
							"servings", String.valueOf(served)));
		}
		return served;
	}

	/**
	 * How much of what was cooked actually went out, or a refusal naming the dish.
	 *
	 * <p>Null is kept as null: a card that did not say what came back is not a card saying nothing
	 * came back. What is refused is a figure larger than what was cooked — a dish cannot serve more
	 * than it made, and a typed extra zero is exactly the mistake that would otherwise be recorded
	 * as a fact and then read back as a plan that is running short.
	 */
	private BigDecimal consumedFigure(
			MealPlanView dish, boolean notMade, BigDecimal consumedQuantity, BigDecimal cooked) {
		if (notMade) {
			return BigDecimal.ZERO;
		}
		BigDecimal consumed = consumedQuantity;
		if (consumed == null) {
			return null;
		}
		if (consumed.signum() < 0 || consumed.compareTo(cooked) > 0) {
			throw new ApplicationException(ErrorCode.SERVINGS_NOT_VALID,
					Map.of("mealPlanId", dish.id(), "recipe", dish.recipeName(),
							"servings", String.valueOf(consumed)));
		}
		return consumed;
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
				firstNonBlank(rows, MealPlanView::eventName),
				firstNonBlank(rows, MealPlanView::contactName),
				firstNonBlank(rows, MealPlanView::contactPhone),
				firstNonBlank(rows, MealPlanView::deliveryAddress),
				firstNonBlank(rows, MealPlanView::purpose),
				// The composer writes the same note onto every dish of a meal, so one of them is the
				// note. Joining them would print it three times on the card.
				firstNonBlank(rows, MealPlanView::kitchenNotes),
				firstNonBlank(rows, MealPlanView::serverNotes),
				service == null ? null : service.cardNumber(),
				service == null ? null : service.cardIssuedAt(),
				service != null && service.recordedAt() != null,
				service == null ? null : service.recordedAt(),
				service == null ? null : service.recordedByName(),
				service == null ? null : service.recordingNote(),
				service != null && service.correctedAt() != null,
				service == null ? null : service.correctedAt(),
				service == null ? null : service.correctedByName(),
				service == null ? null : service.correctionNote(),
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
			return dish.targetYield() == null ? 0 : dish.targetYield().intValue();
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
			byKey.put(Key.of(row.planDate(), row.mealKind(), row.eventName()), row);
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

	/**
	 * What the brief means by "the meal": a date, a kind, and the event's own name where there is one
	 * (V89).
	 *
	 * <p>The name is trimmed and folded to lower case, exactly as V89's unique index folds it, so
	 * "Bhajan Prasadam" and "bhajan prasadam" are one event here and one row there. An absent name
	 * and a blank one are the same thing and both become {@code ""} — a meal is never keyed on null,
	 * so a map lookup cannot quietly miss.
	 */
	private record Key(LocalDate date, String mealKind, String eventName) {

		static Key of(LocalDate date, String mealKind, String eventName) {
			String name = eventName == null || eventName.isBlank()
					? "" : eventName.trim().toLowerCase(Locale.ROOT);
			return new Key(date, mealKind, name);
		}
	}

	private record ServiceRow(
			UUID id, LocalDate planDate, String mealKind, String eventName, String cardNumber,
			java.time.Instant cardIssuedAt, java.time.Instant recordedAt, String recordedByName,
			String recordingNote, java.time.Instant correctedAt, String correctedByName,
			String correctionNote) {
	}

	/**
	 * <p>Two LEFT JOINs onto {@code users} and not one: the person who recorded a meal and the person
	 * who corrected it are different people on purpose — recording is everyday kitchen work on
	 * {@code MANAGE_MEAL_PLANS}, correcting is the Temple Admin's alone (D-4) — so the two names have
	 * to be resolved independently. Both are LEFT, because {@code recorded_by} is
	 * {@code ON DELETE SET NULL} and {@code corrected_by} likewise: the fact must outlive the name,
	 * and an inner join would make a corrected meal vanish from the planner the day its corrector
	 * left the temple.
	 */
	private static final String SERVICE_SELECT = """
			SELECT ms.id, ms.plan_date, ms.meal_kind, ms.event_name, ms.card_number, ms.card_issued_at,
				   ms.recorded_at, ms.recording_note, u.full_name AS recorded_by_name,
				   ms.corrected_at, ms.correction_note, c.full_name AS corrected_by_name
			FROM meal_services ms
			LEFT JOIN users u ON u.id = ms.recorded_by
			LEFT JOIN users c ON c.id = ms.corrected_by
			""";

	private static final RowMapper<ServiceRow> SERVICE_MAPPER = (rs, n) -> new ServiceRow(
			rs.getObject("id", UUID.class),
			rs.getObject("plan_date", LocalDate.class),
			rs.getString("meal_kind"),
			rs.getString("event_name"),
			rs.getString("card_number"),
			instant(rs, "card_issued_at"),
			instant(rs, "recorded_at"),
			rs.getString("recorded_by_name"),
			rs.getString("recording_note"),
			instant(rs, "corrected_at"),
			rs.getString("corrected_by_name"),
			rs.getString("correction_note"));

	private static java.time.Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}
}
