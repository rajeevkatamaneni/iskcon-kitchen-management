package org.iskcon.kms.meal;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.calendar.CalendarDayView;
import org.iskcon.kms.calendar.CalendarService;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.geo.GeocodingProvider;
import org.iskcon.kms.geo.PlaceSuggestionProvider;
import org.iskcon.kms.geo.TravelTimeProvider;
import org.iskcon.kms.ingredient.IngredientUnits;
import org.iskcon.kms.ingredient.Unit;
import org.iskcon.kms.kitchen.KitchenOrder;
import org.iskcon.kms.kitchen.KitchenOrder.KitchenRef;
import org.iskcon.kms.occasion.OccasionService;
import org.iskcon.kms.occasion.ResolvedOccasion;
import org.iskcon.kms.shift.MealShiftDraft;
import org.iskcon.kms.shift.ShiftService;
import org.iskcon.kms.shift.ShiftView;
import org.iskcon.kms.tenancy.TempleClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Meal planning (E4-S4, redesigned by E4-S7, rebuilt on meal rows by D-27).
 *
 * <p><strong>A meal is a row of its own.</strong> Rajeev's design, in his words: <em>"Meal Plan for
 * each day wil be saved in a dedicated table with its own unique ID and related info. Then each Meal
 * for that day (breakfast, Lunch, Dinner ....etc) will be created in a dedicated Meal table. Each
 * meal gets its own unique ID and related infomration and a Foreign Key relation to the Meal Plan's
 * ID."</em> So planning writes three things, in order: the day ({@code meal_plan_days}, one per temple
 * per date, found or created), the meal ({@code meals}, found or created by its day, its kind's id and
 * its event name), and the dishes ({@code meal_dishes}, each pointing at the meal). Before D-27 there
 * was only the third, and a meal was whatever dish rows happened to share a date and two names.
 *
 * <p><strong>A meal and its volunteer shift are saved together.</strong> His ruling: <em>"The Sift
 * when saved shoudl be left uncommited until the meal is saved. Once the meal is saved, we take the ID
 * of the meal and update the Volenteer reruest with that ID and then commit everything."</em> Every
 * save here runs in one transaction and hands the meal's id to {@link ShiftService#saveForMeal} once
 * the meal row exists, so a refusal anywhere — a dish, the head count, the shift — leaves nothing
 * behind: no meal, no dishes, no shift.
 *
 * <p>What a planner is <em>not</em> asked is what sort of day it is: weekend follows from the date
 * and festival from the calendar. The day type is derived, stored on the day, and never chosen.
 *
 * <p><strong>Three main meals, and everything else is an event.</strong> Breakfast, Lunch and Dinner
 * work from a head count. An event is quantified by how much to make, and its head count is context:
 * thirty laddus and some chiwda is a real thing a temple cooks.
 *
 * <p>What this class does not do is cook. Drawing stock happens once for a whole meal, from the
 * returned job card, in {@link ServedMealService} — and a meal that has been through that can no
 * longer be edited or cancelled, because the stock has moved.
 */
@Service
public class MealPlanService {

	private static final Logger log = LoggerFactory.getLogger(MealPlanService.class);

	private final TempleClock clock;
	private final JdbcTemplate jdbc;
	private final AuditService auditService;
	private final OccasionService occasionService;
	private final CalendarService calendarService;
	private final MealKindService mealKindService;
	private final EkadashiPolicy ekadashiPolicy;
	private final GeocodingProvider geocodingProvider;
	private final TravelTimeProvider travelTimeProvider;
	private final PlaceSuggestionProvider placeSuggestionProvider;
	private final ServedMealService servedMealService;
	private final ShiftService shiftService;
	private final KitchenOrder kitchenOrder;

	/**
	 * How long a geocoded coordinate may be kept before it is looked up again (E4-S16 D4). Maps
	 * Platform ToS §6.3.1 permits thirty days.
	 */
	private static final int GEOCODE_LIFE_DAYS = 30;

	/** How many event names the autocomplete offers (E4-S15 D9): read while typing, so ten. */
	private static final int MAX_EVENT_SUGGESTIONS = 10;

	/**
	 * How long before the guests eat we assume the vehicle leaves, when asking what the traffic will
	 * be like at that hour. Being wrong about it moves the estimate by minutes, not by the answer.
	 */
	private static final Duration ASSUMED_DEPARTURE_LEAD = Duration.ofHours(1);

	/**
	 * What volunteers are told when a meal is cancelled and the planner gave no reason. True, and all
	 * a volunteer needs to stop coming.
	 */
	static final String DEFAULT_CANCEL_REASON = "The meal this shift was for has been cancelled.";

	/**
	 * {@code @Lazy} on the shift service, and the reason is a boundary rather than a cycle that exists
	 * today. The shift package is another builder's, and the week view already reaches this package
	 * from the other side (WorkforceService → ShiftService, MealCrewService → WorkforceService). A
	 * proxy resolved on first use means a later constructor dependency from shifts back into meals is
	 * a design question somebody can answer, not an application that will not start.
	 */
	public MealPlanService(
			JdbcTemplate jdbc, AuditService auditService, OccasionService occasionService,
			CalendarService calendarService,
			MealKindService mealKindService, EkadashiPolicy ekadashiPolicy,
			GeocodingProvider geocodingProvider, TravelTimeProvider travelTimeProvider,
			PlaceSuggestionProvider placeSuggestionProvider, TempleClock clock,
			ServedMealService servedMealService, @Lazy ShiftService shiftService, KitchenOrder kitchenOrder) {
		this.kitchenOrder = kitchenOrder;
		this.clock = clock;
		this.placeSuggestionProvider = placeSuggestionProvider;
		this.jdbc = jdbc;
		this.auditService = auditService;
		this.occasionService = occasionService;
		this.calendarService = calendarService;
		this.mealKindService = mealKindService;
		this.ekadashiPolicy = ekadashiPolicy;
		this.geocodingProvider = geocodingProvider;
		this.travelTimeProvider = travelTimeProvider;
		this.servedMealService = servedMealService;
		this.shiftService = shiftService;
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
	 * Enforces the Ekadashi rule and returns whether an acknowledgment should be recorded on the dish.
	 * If the day is Ekadashi and the recipe is not compatible, planning is blocked unless the caller
	 * explicitly acknowledged it — the only, always-recorded path past the warning.
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

	/**
	 * The meals in a range as the planner reads them: each with its dishes, its card and recording,
	 * and its live volunteer shift.
	 *
	 * <p>The shifts are read once for the range rather than once per meal. A meal shift's date always
	 * comes from its meal (D-27, answer 4), so the range's own shifts are the ones that can belong to
	 * these meals; the window is widened by a day each way only so that nothing about how a shift's
	 * date is written can drop one off the edge of a week, and each is then matched by id.
	 */
	@Transactional(readOnly = true)
	public List<ServedMeal> meals(LocalDate from, LocalDate to) {
		List<ServedMeal> meals = servedMealService.list(from, to);
		if (meals.isEmpty()) {
			return meals;
		}
		Map<UUID, ShiftView> byMeal = new HashMap<>();
		for (ShiftView shift : shiftService.list(
				from == null ? null : from.minusDays(1), to == null ? null : to.plusDays(1), false)) {
			if (shift.mealId() != null) {
				byMeal.putIfAbsent(shift.mealId(), shift);
			}
		}
		return meals.stream().map(m -> m.withVolunteerShift(byMeal.get(m.mealId()))).toList();
	}

	/** One meal as the planner reads it, with its live volunteer shift. */
	@Transactional(readOnly = true)
	public ServedMeal meal(UUID mealId) {
		ServedMeal meal = servedMealService.require(mealId);
		return meal.withVolunteerShift(shiftService.findForMeal(mealId).orElse(null));
	}

	/**
	 * {@link #meals(LocalDate, LocalDate)} with each meal's kitchens in the order this person sees them
	 * (Epic 12): their own kitchen first when it is on the meal, else the main kitchen, then Settings
	 * order. What the planner's endpoints answer with.
	 */
	@Transactional(readOnly = true)
	public List<ServedMeal> meals(LocalDate from, LocalDate to, AuthenticatedUser viewer) {
		return servedMealService.forViewer(meals(from, to), viewer.getUserId());
	}

	/** {@link #meal(UUID)} with its kitchens ordered for this person, as {@link #meals(LocalDate, LocalDate, AuthenticatedUser)}. */
	@Transactional(readOnly = true)
	public ServedMeal meal(UUID mealId, AuthenticatedUser viewer) {
		return servedMealService.forViewer(meal(mealId), viewer.getUserId());
	}

	// ---- Reusing a plan (2026-09-05) -------------------------------------

	/**
	 * What is in a source window, and what reusing it would do — without writing anything.
	 *
	 * <p>The same walk the commit performs, which is the point: a preview computed a second way is a
	 * preview that can disagree with the thing it previews. {@link #reusePlan} walks the same
	 * {@link #reuseWalk} and writes what it found.
	 */
	@Transactional(readOnly = true)
	public ReusePlanPreview previewReuse(ReusePlanRequest request) {
		return reuseWalk(request).preview();
	}

	/**
	 * Writes what {@link #previewReuse} said it would, meal by meal.
	 *
	 * <p>Each copy goes through {@link #create}, never an insert, so every rule that governs a meal
	 * governs a copied one — the head count, the event's own fields, the audit entry, and the
	 * find-or-create that makes pressing it twice harmless. The occasion is the one thing deliberately
	 * not carried, for the reason a feast is not offered at all. No volunteer shift is carried either:
	 * a shift is somebody asking for help with one meal, and nobody has asked for this one yet.
	 *
	 * <p>The kitchens carry (Epic 12): each copy has its source's kitchens with their People needed, and
	 * each dish its own kitchen. A dish of a kitchen that no longer plans meals is left behind, and the
	 * preview says so beside it.
	 */
	@Transactional
	public ReusePlanResult reusePlan(AuthenticatedUser actor, ReusePlanRequest request) {
		ReuseWalk walk = reuseWalk(request);
		ReusePlanPreview preview = walk.preview();
		if (preview.sourceWasEmpty()) {
			return new ReusePlanResult(0, 0, 0, 0, true);
		}
		int copied = 0;
		Set<UUID> planning = plannerKitchens(kitchenOrder.settingsOrder());
		for (ReuseCopy copy : walk.copies()) {
			create(actor, copyOf(copy.source(), copy.dishes(), copy.target(), planning));
			copied += copy.dishes().size();
		}
		ReusePlanPreview.Totals t = preview.totals();
		return new ReusePlanResult(copied, t.daysWritten(), t.daysLeftAlone(), t.notCopied(), false);
	}

	/** One meal the reuse would write: which source meal, onto which date, with which of its dishes. */
	private record ReuseCopy(ServedMeal source, LocalDate target, List<MealDishView> dishes) {
	}

	/** The preview and the copies it describes, computed once by one walk. */
	private record ReuseWalk(ReusePlanPreview preview, List<ReuseCopy> copies) {
	}

	private ReuseWalk reuseWalk(ReusePlanRequest request) {
		LocalDate sourceEnd = request.sourceStart().plusDays(request.days() - 1L);
		List<ServedMeal> source = servedMealService.list(request.sourceStart(), sourceEnd).stream()
				.filter(m -> m.status() != MealStatus.CANCELLED)
				.toList();
		if (source.isEmpty()) {
			return new ReuseWalk(new ReusePlanPreview(true, List.of(), List.of(), List.of(), List.of(),
					List.of(), new ReusePlanPreview.Totals(0, 0, 0, 0)), List.of());
		}

		// Kinds by their id, never by name: the meal points at the kind, and a kind renamed since the
		// source week was cooked is still that kind (D-27).
		Map<UUID, MealKindView> kinds = new LinkedHashMap<>();
		List<ReusePlanPreview.KindFound> kindsFound = new ArrayList<>();
		List<ReusePlanPreview.EventFound> eventsFound = new ArrayList<>();
		List<ReusePlanPreview.Excluded> excluded = new ArrayList<>();

		Map<String, List<ServedMeal>> byKind = new LinkedHashMap<>();
		Map<String, List<ServedMeal>> byEvent = new LinkedHashMap<>();
		for (ServedMeal meal : source) {
			MealKindView kind = kinds.computeIfAbsent(meal.mealKindId(), mealKindService::requireById);
			if (kind.isEvent()) {
				byEvent.computeIfAbsent(nameOf(meal), k -> new ArrayList<>()).add(meal);
			} else if (kind.needsOccasion()) {
				// A feast belongs to its date: the occasion comes from the calendar on the day it is
				// cooked, so the same dishes on an ordinary Wednesday are a large lunch wearing the
				// wrong name. Never offered, and the screen says why rather than staying silent.
				excluded.add(new ReusePlanPreview.Excluded(
						kind.name() + (meal.occasionName() == null ? "" : " · " + meal.occasionName()),
						"A feast takes its occasion from the calendar on the day it is cooked.",
						meal.planDate()));
			} else {
				byKind.computeIfAbsent(kind.name(), k -> new ArrayList<>()).add(meal);
			}
		}
		byKind.forEach((name, meals) -> kindsFound.add(new ReusePlanPreview.KindFound(
				name, (int) meals.stream().map(ServedMeal::planDate).distinct().count(), meals.size())));
		byEvent.forEach((name, meals) -> eventsFound.add(new ReusePlanPreview.EventFound(
				name,
				(int) meals.stream().map(ServedMeal::planDate).distinct().count(),
				meals.stream().anyMatch(ServedMeal::isOutside),
				meals.stream().map(ServedMeal::planDate).max(LocalDate::compareTo).orElse(null))));

		List<ReusePlanPreview.HeadCount> headCounts = new ArrayList<>();
		byKind.forEach((name, meals) -> {
			ServedMeal largest = meals.stream()
					.max(Comparator.comparingInt(m -> m.adults() == null ? 0 : m.adults()))
					.orElse(meals.get(0));
			headCounts.add(new ReusePlanPreview.HeadCount(
					name, largest.adults(), largest.children(), largest.seniors()));
		});

		// What would land, day by day. Chosen kinds default to every main meal found and no event:
		// an event was arranged, and arranging it again is a decision rather than an inheritance.
		Set<String> wantedKinds = request.mealKinds() == null
				? new LinkedHashSet<>(byKind.keySet()) : new LinkedHashSet<>(request.mealKinds());
		Set<String> wantedEvents = request.eventNames() == null
				? Set.of() : new LinkedHashSet<>(request.eventNames());

		// Which kitchens can still take a meal (Epic 12). A copy goes to the same kitchens as its source,
		// and a kitchen archived or taken off the planner since then cannot be given one — so a dish of
		// that kitchen is left behind and the screen says why, exactly as a dish the fast forbids is,
		// rather than the whole reuse being refused over one old kitchen.
		Set<UUID> planning = plannerKitchens(kitchenOrder.settingsOrder());

		List<ReusePlanPreview.TargetDay> days = new ArrayList<>();
		List<ReuseCopy> copies = new ArrayList<>();
		int dishesCopied = 0;
		int daysWritten = 0;
		int daysLeftAlone = 0;
		int notCopied = 0;

		for (int offset = 0; offset < request.days(); offset++) {
			LocalDate from = request.sourceStart().plusDays(offset);
			LocalDate target = request.targetStart().plusDays(offset);

			List<ServedMeal> thatDay = source.stream()
					.filter(m -> m.planDate().equals(from))
					.filter(m -> wanted(m, kinds.get(m.mealKindId()), wantedKinds, wantedEvents))
					.toList();
			if (thatDay.isEmpty()) {
				continue;
			}
			boolean occupied = servedMealService.list(target, target).stream()
					.anyMatch(m -> m.status() != MealStatus.CANCELLED);
			if (occupied) {
				daysLeftAlone++;
				days.add(new ReusePlanPreview.TargetDay(target, from, true, null, List.of()));
				continue;
			}

			List<ReusePlanPreview.PlannedMeal> landing = new ArrayList<>();
			String fastName = null;
			for (ServedMeal meal : thatDay) {
				List<MealDishView> copyable = new ArrayList<>();
				for (MealDishView dish : live(meal)) {
					EkadashiCheck check = ekadashiCheck(target, dish.recipeId());
					boolean fasting = check.isEkadashi() && !check.compatible();
					boolean kitchenGone = !planning.contains(dish.kitchenId());
					boolean refused = fasting || kitchenGone;
					if (check.isEkadashi()) {
						// The fast's own name comes from the calendar rather than from the check, which
						// only answers whether a recipe suits it.
						fastName = calendarService.day(target).map(CalendarDayView::ekadashiName).orElse(null);
					}
					landing.add(new ReusePlanPreview.PlannedMeal(
							meal.mealKind(), meal.eventName(), dish.recipeName(), !refused,
							fasting ? refusedBecause(dish, check)
									: kitchenGone ? kitchenGoneBecause(meal, dish) : null));
					if (refused) {
						notCopied++;
					} else {
						dishesCopied++;
						copyable.add(dish);
					}
				}
				if (!copyable.isEmpty()) {
					copies.add(new ReuseCopy(meal, target, copyable));
				}
			}
			if (landing.stream().anyMatch(ReusePlanPreview.PlannedMeal::copied)) {
				daysWritten++;
			}
			days.add(new ReusePlanPreview.TargetDay(target, from, false, fastName, landing));
		}

		ReusePlanPreview preview = new ReusePlanPreview(false, kindsFound, eventsFound, excluded, headCounts,
				days, new ReusePlanPreview.Totals(dishesCopied, daysWritten, daysLeftAlone, notCopied));
		return new ReuseWalk(preview, copies);
	}

	/** The name an event is grouped under, or the kind's own name where it has none. */
	private static String nameOf(ServedMeal meal) {
		return meal.eventName() == null || meal.eventName().isBlank()
				? meal.mealKind() : meal.eventName();
	}

	private static boolean wanted(
			ServedMeal meal, MealKindView kind, Set<String> wantedKinds, Set<String> wantedEvents) {
		if (kind == null || kind.needsOccasion()) {
			return false;
		}
		return kind.isEvent() ? wantedEvents.contains(nameOf(meal)) : wantedKinds.contains(kind.name());
	}

	/** Why a dish was left behind, in the words the screen prints. */
	private static String refusedBecause(MealDishView dish, EkadashiCheck check) {
		return check.offendingIngredients().isEmpty()
				? dish.recipeName() + " does not suit the fast on this day."
				: dish.recipeName() + " contains "
						+ String.join(", ", check.offendingIngredients()) + ", which the fast forbids.";
	}

	/** Why a dish of a kitchen that no longer plans meals was left behind, in the screen's words. */
	private static String kitchenGoneBecause(ServedMeal meal, MealDishView dish) {
		String kitchen = meal.kitchens().stream()
				.filter(k -> k.kitchenId().equals(dish.kitchenId()))
				.map(MealKitchenView::kitchenName)
				.findFirst().orElse("Its kitchen");
		return kitchen + " no longer plans its meals here, so " + dish.recipeName() + " has no kitchen to cook it.";
	}

	/** The kitchens a meal may be planned for today: ACTIVE and using the planner. */
	private static Set<UUID> plannerKitchens(List<KitchenRef> settingsOrder) {
		return settingsOrder.stream().filter(KitchenRef::plansMeals).map(KitchenRef::id)
				.collect(Collectors.toSet());
	}

	/** The dishes of a meal that are still meant to be, or were, cooked. */
	private static List<MealDishView> live(ServedMeal meal) {
		return meal.dishes().stream().filter(d -> d.status() != MealStatus.CANCELLED).toList();
	}

	// ---- Write ----------------------------------------------------------

	/**
	 * Plans a meal: finds or creates its day and its row, writes its facts, adds its dishes, and saves
	 * the volunteer shift it asks for — all in this one transaction (D-27).
	 *
	 * <p><strong>Find, or create.</strong> A meal is its day, its kind and its event name compared
	 * without regard to case ({@code meals_one_per_meal}, V136). Planning one that already exists
	 * reuses its row — its id, its card number and any shift already linked to it — and adds these
	 * dishes to it. That includes a meal that was cancelled: V136's header explains why the index is
	 * deliberately not partial, and a cancelled Lunch planned again is the same Lunch rather than a
	 * second row nobody could tell apart from the first. A meal already recorded is refused, because
	 * its stock has moved and its dishes are history.
	 *
	 * <p><strong>The shift is last, and inside.</strong> {@link ShiftService#saveForMeal} is called
	 * only once the meal row exists, because the shift takes its date from that row, and it runs in
	 * this transaction, so a shift that is refused takes the meal and its dishes with it.
	 *
	 * <p><strong>Which kitchens (Epic 12).</strong> The meal's kitchens and every dish's kitchen are
	 * checked before anything is written ({@link #resolveSections}, {@link #dishKitchen}), each with its
	 * People needed, and written before the dishes. See {@link SaveMealRequest#kitchens()} for what an
	 * absent list means.
	 */
	@Transactional
	public SavedMeal create(AuthenticatedUser actor, SaveMealRequest request) {
		MealKindView kind = mealKindService.requireById(request.mealKindId());
		LocalDate date = request.planDate();
		LocalTime readyBy = resolveReadyBy(kind, request.readyBy());
		Event event = requireEventFields(kind, request.eventName(), request.isOutside(), request.handover(),
				request.contactName(), request.contactPhone(), request.deliveryAddress(),
				request.deliverySubLocation(), request.deliveryPlaceId(), request.deliveryLatitude(),
				request.deliveryLongitude(), request.guestsEatAt(), request.travelMinutes(),
				request.travelMinutesManual(), readyBy);
		requireHeadCount(request.adults(), request.children(), request.seniors(), date, kind);
		DayType dayType = deriveDayType(date);
		String occasionName = resolveOccasionName(kind, dayType, date, request.occasionName());

		for (SaveMealRequest.DishDraft dish : request.dishes()) {
			if (dish.id() != null) {
				// A dish id belongs to a meal that already exists, and changing that meal is an
				// update. Accepting one here would let a plan quietly edit a dish of some other meal.
				throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
						Map.of("field", "dishes", "dishId", dish.id()));
			}
		}
		List<Section> sections = resolveSections(actor, request.kitchens());
		List<NewDish> dishes = checkDishes(date, request.dishes(), request.ekadashiAcknowledged(),
				d -> dishKitchen(d, sections, null));

		UUID dayId = dayFor(date, dayType);
		MealRow existing = findMeal(dayId, kind.id(), event.name()).orElse(null);
		if (existing != null && existing.recordedAt() != null) {
			throw new ApplicationException(ErrorCode.MEAL_ALREADY_RECORDED, Map.of("mealId", existing.id()));
		}
		Map<String, Object> before = existing == null ? null : snapshot(existing.id());

		Located located = existing == null
				? place(event, null, null, null, null)
				: place(event, existing.deliveryAddress(), existing.deliveryLatitude(),
						existing.deliveryLongitude(), existing.geocodedAt());

		UUID mealId = existing != null ? existing.id() : insertMeal(dayId, kind.id(), event.name(), readyBy);
		writeMealFacts(mealId, event, located, occasionName, readyBy, request.purpose(), request.adults(),
				request.children(), request.seniors(), request.kitchenNotes(), request.serverNotes());
		// Sections before dishes: a dish's kitchen must already be on its meal (the composite key). A
		// meal that already existed keeps any kitchen this save does not name — planning onto it adds,
		// as it always has for dishes, and taking a kitchen off is an update's decision.
		writeSections(mealId, sections);
		for (NewDish dish : dishes) {
			insertDish(actor, mealId, dish);
		}

		saveShift(actor, mealId, request.volunteerShift());
		if (existing != null) {
			// Planning onto a meal that is already there changes that one occurrence; the repeat, which
			// also comes through here, clears the mark again on the copies it makes.
			markEditedInSeries(mealId);
		}

		auditService.record(actor, AuditAction.MEAL_PLANNED, AuditEntityType.MEAL, mealId,
				before, snapshot(mealId), null);
		return new SavedMeal(mealId, located.warning());
	}

	/**
	 * Changes a meal that has not been recorded — its facts, its dishes and its volunteer shift — in
	 * one transaction (D-27, answer 7: <em>"nothing saved until the meal is saved : Aggreed"</em>).
	 *
	 * <p>Allowed right up until the meal is recorded, and refused the moment it is. A cooked dish has
	 * had its ingredients drawn against a figure, and letting somebody change the figure afterwards
	 * would leave the stock ledger describing a meal that never happened; a mistake there is corrected
	 * (T-007), not rewritten.
	 *
	 * <p>The dishes are the whole list: kept, added, or — a planned dish the list leaves out —
	 * cancelled. A dish named by an id that is not this meal's is refused as not found; one that is
	 * this meal's but no longer planned is {@code MEAL_PLAN_NOT_OPEN}, because the change is beside
	 * the point.
	 *
	 * <p>A null {@code volunteerShift} leaves the meal's shift exactly as it is.
	 *
	 * <p><strong>The kitchens are the whole list too (Epic 12).</strong> A kitchen named is added or has
	 * its People needed replaced; a kitchen on the meal and not named is taken off, once none of the
	 * dishes sent is under it ({@link #removeSectionsLeftOut}). A null list leaves them as they are.
	 */
	@Transactional
	public SavedMeal update(AuthenticatedUser actor, UUID mealId, UpdateMealRequest request) {
		MealRow row = lockMeal(mealId);
		ServedMeal meal = servedMealService.require(mealId);
		if (meal.recorded() || meal.dishes().stream().anyMatch(d -> d.status() == MealStatus.COOKED)) {
			throw new ApplicationException(ErrorCode.MEAL_ALREADY_RECORDED, Map.of("mealId", mealId));
		}

		MealKindView kind = mealKindService.requireById(row.mealKindId());
		LocalDate date = row.planDate();
		LocalTime readyBy = resolveReadyBy(kind, request.readyBy());
		Event event = requireEventFields(kind, request.eventName(), request.isOutside(), request.handover(),
				request.contactName(), request.contactPhone(), request.deliveryAddress(),
				request.deliverySubLocation(), request.deliveryPlaceId(), request.deliveryLatitude(),
				request.deliveryLongitude(), request.guestsEatAt(), request.travelMinutes(),
				request.travelMinutesManual(), readyBy);
		requireHeadCount(request.adults(), request.children(), request.seniors(), date, kind);
		String occasionName = resolveOccasionName(kind, deriveDayType(date), date, request.occasionName());

		if (!sameEvent(event.name(), row.eventName())) {
			// Renaming an event onto the name of another event that day would make two meals one
			// identity. The index refuses it; asking first means the refusal names the field.
			Optional<MealRow> clash = findMeal(row.mealPlanDayId(), row.mealKindId(), event.name());
			if (clash.isPresent() && !clash.get().id().equals(mealId)) {
				throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
						Map.of("field", "eventName", "mealId", mealId));
			}
		}

		Map<UUID, MealDishView> current = new LinkedHashMap<>();
		meal.dishes().forEach(d -> current.put(d.id(), d));
		List<SaveMealRequest.DishDraft> kept = new ArrayList<>();
		List<SaveMealRequest.DishDraft> added = new ArrayList<>();
		for (SaveMealRequest.DishDraft draft : request.dishes()) {
			if (draft.id() == null) {
				added.add(draft);
				continue;
			}
			MealDishView dish = current.get(draft.id());
			if (dish == null) {
				throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("dishId", draft.id()));
			}
			if (dish.status() != MealStatus.PLANNED) {
				throw new ApplicationException(ErrorCode.MEAL_PLAN_NOT_OPEN, Map.of("dishId", draft.id()));
			}
			kept.add(draft);
		}

		// The kitchens are the whole list, like the dishes (Epic 12). Absent — a caller from before
		// kitchens — leaves the meal's own exactly as they are, and a kept dish sent without a kitchen
		// stays in the one it is in: an old-shaped edit of the head count must not move the meal to the
		// editor's kitchen. With a list, every dish's kitchen is checked against that list.
		boolean keepSections = request.kitchens() == null;
		List<Section> sections = keepSections
				? meal.kitchens().stream().map(k -> new Section(k.kitchenId(), k.crewRequired())).toList()
				: resolveSections(actor, request.kitchens());
		List<NewDish> keptChecked = checkDishes(date, kept, request.ekadashiAcknowledged(),
				d -> dishKitchen(d, sections, keepSections ? current.get(d.id()).kitchenId() : null));
		List<NewDish> addedChecked = checkDishes(date, added, request.ekadashiAcknowledged(),
				d -> dishKitchen(d, sections, null));

		Map<String, Object> before = snapshot(mealId);
		Located located = place(event, row.deliveryAddress(), row.deliveryLatitude(),
				row.deliveryLongitude(), row.geocodedAt());

		writeMealFacts(mealId, event, located, occasionName, readyBy, request.purpose(), request.adults(),
				request.children(), request.seniors(), request.kitchenNotes(), request.serverNotes());
		if (!keepSections) {
			// Added and re-figured first, so a dish moved into a kitchen new to this meal has somewhere
			// to go. The ones left out are taken off at the end, once no dish is still under them.
			writeSections(mealId, sections);
		}

		// Dropped first, so the meal never holds both the old list and the new one mid-statement.
		Set<UUID> keptIds = kept.stream().map(SaveMealRequest.DishDraft::id).collect(Collectors.toSet());
		for (MealDishView dish : meal.dishes()) {
			if (dish.status() == MealStatus.PLANNED && !keptIds.contains(dish.id())) {
				jdbc.update("""
						UPDATE meal_dishes SET status = 'CANCELLED', updated_at = now()
						WHERE id = ? AND status = 'PLANNED'
						""", dish.id());
			}
		}
		for (int i = 0; i < kept.size(); i++) {
			NewDish dish = keptChecked.get(i);
			jdbc.update("""
					UPDATE meal_dishes
					SET recipe_id = ?, target_yield = ?, ekadashi_ack_by = ?, ekadashi_ack_at = ?,
						kitchen_id = ?, updated_at = now()
					WHERE id = ?
					""", dish.recipeId(), dish.targetYield(),
					dish.acknowledged() ? actor.getUserId() : null,
					dish.acknowledged() ? OffsetDateTime.now(java.time.ZoneOffset.UTC) : null,
					dish.kitchenId(), kept.get(i).id());
		}
		for (NewDish dish : addedChecked) {
			insertDish(actor, mealId, dish);
		}
		if (!keepSections) {
			removeSectionsLeftOut(mealId, meal.kitchens(), sections);
		}

		saveShift(actor, mealId, request.volunteerShift());
		markEditedInSeries(mealId);

		auditService.record(actor, AuditAction.MEAL_PLAN_UPDATED, AuditEntityType.MEAL, mealId,
				before, snapshot(mealId), null);
		return new SavedMeal(mealId, located.warning());
	}

	/**
	 * Cancels a meal and, with it, the meal's volunteer shift (D-27, answer 5: <em>"Yes, warn then
	 * cancel both."</em>) — or, for a repeating event, this meal and every later one (T-307).
	 *
	 * <p>Both in this transaction. {@link ShiftService#cancelForMeal} closes the shift here and sends
	 * the existing cancellation message to everyone signed up or waitlisted only after this commits,
	 * so a cancel that fails tells nobody anything. The warning before it — how many are signed up —
	 * is read off the meal view's {@code volunteerShift}, which is what the planner already holds.
	 *
	 * <p>A meal that has been cooked cannot be cancelled: its stock has moved. Cancelling a meal that
	 * is already cancelled is a quiet no-op, as it always was for a dish, apart from a shift somebody
	 * left open on it, which is closed.
	 *
	 * <p><strong>This and every later one.</strong> Rajeev, 2026-09-19: <em>"a cancel of this repeating
	 * event should ask JUST this event OR all events from this point onwards"</em>. With
	 * {@code THIS_AND_LATER} this meal is cancelled and so is every occurrence {@link #laterInSeries}
	 * names — each one exactly as a cancel of it alone would be, with its own shift closed and its own
	 * audit entry — in this one transaction, so a refusal or a failure on the fifth leaves the first
	 * four standing too. What "later" means is that method's, and nothing else is ever touched: an
	 * occurrence in the past, cooked, recorded or already cancelled stays exactly as it is.
	 *
	 * <p>The confirmation showed the planner a list; {@code expectedMealIds} is that list. The later
	 * set is recomputed here under the row locks, and if it is not the same set — somebody cooked one,
	 * cancelled one or repeated the event further while the dialog was open — nothing is cancelled
	 * (KMS-400179), because the planner would otherwise cancel meals they were never shown.
	 *
	 * <p>Afterwards the series ends on the last occurrence still standing, so it no longer claims to
	 * run to a date nothing is planned on. Absent a scope, this is exactly the cancel it always was.
	 */
	@Transactional
	public CancelledMeals cancel(AuthenticatedUser actor, UUID mealId, CancelMealRequest request) {
		String reason = request == null ? null : request.reason();
		CancelMealRequest.Scope scope = request == null ? CancelMealRequest.Scope.THIS : request.scopeOrThis();
		MealRow row = lockMeal(mealId);

		if (scope == CancelMealRequest.Scope.THIS) {
			Cancelled one = cancelOne(actor, mealId, reason);
			return new CancelledMeals(one.told(), one.count(),
					row.seriesId() == null ? null : lastStanding(row.seriesId()));
		}

		if (row.seriesId() == null) {
			throw new ApplicationException(ErrorCode.MEAL_NOT_IN_SERIES, Map.of("mealId", mealId));
		}
		requireNotCooked(mealId);
		List<LaterRow> later = laterRows(row, true);
		if (request.expectedMealIds() != null) {
			Set<UUID> expected = new LinkedHashSet<>(request.expectedMealIds());
			Set<UUID> actual = later.stream().map(LaterRow::mealId).collect(Collectors.toCollection(LinkedHashSet::new));
			if (!expected.equals(actual)) {
				throw new ApplicationException(ErrorCode.SERIES_CHANGED_SINCE_CHECKED,
						Map.of("mealId", mealId, "expected", expected.size(), "found", actual.size()));
			}
		}

		Cancelled first = cancelOne(actor, mealId, reason);
		int told = first.told();
		int cancelled = first.count();
		for (LaterRow occurrence : later) {
			Cancelled next = cancelOne(actor, occurrence.mealId(), reason);
			told += next.told();
			cancelled += next.count();
		}

		LocalDate last = lastStanding(row.seriesId());
		if (last != null) {
			jdbc.update("UPDATE meal_series SET until_date = ?, updated_at = now() WHERE id = ?",
					last, row.seriesId());
		}
		return new CancelledMeals(told, cancelled, last);
	}

	/** What cancelling one meal did: how many volunteers are told, and whether it had a dish to cancel. */
	private record Cancelled(int told, int count) {
	}

	/**
	 * One meal cancelled, exactly as {@link #cancel} always did it for one: its planned dishes, its
	 * shift, and an audit entry read back from the rows. The caller holds the meal's lock.
	 */
	private Cancelled cancelOne(AuthenticatedUser actor, UUID mealId, String reason) {
		requireNotCooked(mealId);
		Map<String, Object> before = snapshot(mealId);
		int dishes = jdbc.update("""
				UPDATE meal_dishes SET status = 'CANCELLED', updated_at = now()
				WHERE meal_id = ? AND status = 'PLANNED'
				""", mealId);

		// Always asked, never pre-checked: the shift service answers 0 for a meal with no live shift, and
		// it counts the people it will tell under the same row lock it cancels under, so a separate
		// "is there a shift?" read here would be a second answer that could disagree with the first.
		String why = reason == null || reason.isBlank() ? DEFAULT_CANCEL_REASON : reason.trim();
		int told = shiftService.cancelForMeal(mealId, why);

		if (dishes > 0) {
			auditService.record(actor, AuditAction.MEAL_PLAN_CANCELLED, AuditEntityType.MEAL, mealId,
					before, snapshot(mealId), trimToNull(reason));
		}
		return new Cancelled(told, dishes > 0 ? 1 : 0);
	}

	/** Refuses a meal whose stock has moved: recorded, or with any dish cooked. */
	private void requireNotCooked(UUID mealId) {
		ServedMeal meal = servedMealService.require(mealId);
		if (meal.recorded() || meal.dishes().stream().anyMatch(d -> d.status() == MealStatus.COOKED)) {
			throw new ApplicationException(ErrorCode.CANNOT_CANCEL_COOKED_MEAL, Map.of("mealId", mealId));
		}
	}

	/**
	 * What "Cancel this and every later one" would cancel, for the confirmation to show (T-307).
	 *
	 * <p>Read-only, and the same {@link #laterRows} the cancel itself recomputes under its locks, so
	 * the list shown and the list acted on are one definition. A meal in no series is refused
	 * (KMS-400178): there is nothing later to show.
	 */
	@Transactional(readOnly = true)
	public LaterInSeries laterInSeries(UUID mealId) {
		MealRow row = jdbc.query(ROW_SELECT + " WHERE m.id = ?", ROW_MAPPER, mealId).stream().findFirst()
				.orElseThrow(() -> new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("mealId", mealId)));
		if (row.seriesId() == null) {
			throw new ApplicationException(ErrorCode.MEAL_NOT_IN_SERIES, Map.of("mealId", mealId));
		}
		List<LaterRow> later = laterRows(row, false);
		int toTell = volunteersToTell(mealId) + later.stream().mapToInt(LaterRow::toTell).sum();
		return new LaterInSeries(row.seriesId(),
				later.stream().map(l -> new LaterInSeries.Later(
						l.mealId(), l.planDate(), l.edited(), l.signedUp())).toList(),
				later.isEmpty() ? null : later.get(later.size() - 1).planDate(),
				toTell);
	}

	/** One later occurrence, with what the confirmation says about it and whom its cancel would tell. */
	private record LaterRow(UUID mealId, LocalDate planDate, boolean edited, int signedUp, int toTell) {
	}

	/**
	 * The later occurrences of this meal's series that "this and every later one" means (T-307).
	 *
	 * <p>Every condition is one Rajeev's wording or the kitchen's facts demand. <em>From this point
	 * onwards</em>: dated after this meal, and not before today at the temple — an occurrence last
	 * month that nobody recorded is history to be recorded, not a plan to call off. <em>Still to
	 * cook</em>: at least one dish PLANNED, none COOKED and the meal not recorded, because a cooked
	 * meal's stock has moved (the same rule {@link #cancel} refuses on) and an already-cancelled one has
	 * nothing left to cancel. So a past, cooked, recorded or cancelled occurrence is never in this list
	 * and never touched.
	 *
	 * <p>The volunteer counts are read in the same statement rather than asked of the shift service
	 * once per meal. They use the two predicates {@code ShiftService.rosterToTellOfCancellation} uses
	 * — a signup not released, a waitlist place neither promoted nor left — on the meal's shift that is
	 * not cancelled, so the number the confirmation shows is the number {@code cancelForMeal} tells.
	 *
	 * @param lock {@code FOR UPDATE} on the meals, for the cancel: the set compared with what the
	 *             planner was shown must not change between the comparison and the cancelling.
	 */
	private List<LaterRow> laterRows(MealRow row, boolean lock) {
		return jdbc.query("""
				SELECT m.id, pd.plan_date, m.series_edited_at IS NOT NULL AS edited,
					   (SELECT count(*) FROM shifts sh JOIN shift_signups ss ON ss.shift_id = sh.id
						WHERE sh.meal_id = m.id AND sh.status <> 'CANCELLED' AND ss.released_at IS NULL) AS signed_up,
					   (SELECT count(*) FROM shifts sh JOIN shift_waitlist sw ON sw.shift_id = sh.id
						WHERE sh.meal_id = m.id AND sh.status <> 'CANCELLED'
						  AND sw.promoted_at IS NULL AND sw.left_at IS NULL) AS waitlisted
				FROM meals m
				JOIN meal_plan_days pd ON pd.id = m.meal_plan_day_id
				WHERE m.series_id = ?
				  AND m.id <> ?
				  AND pd.plan_date > ?
				  AND pd.plan_date >= ?
				  AND m.recorded_at IS NULL
				  AND EXISTS (SELECT 1 FROM meal_dishes d WHERE d.meal_id = m.id AND d.status = 'PLANNED')
				  AND NOT EXISTS (SELECT 1 FROM meal_dishes d WHERE d.meal_id = m.id AND d.status = 'COOKED')
				ORDER BY pd.plan_date, m.ready_by, m.id
				""" + (lock ? " FOR UPDATE OF m" : ""),
				(rs, n) -> new LaterRow(
						rs.getObject("id", UUID.class),
						rs.getObject("plan_date", LocalDate.class),
						rs.getBoolean("edited"),
						rs.getInt("signed_up"),
						rs.getInt("signed_up") + rs.getInt("waitlisted")),
				row.seriesId(), row.id(), row.planDate(), clock.today());
	}

	/** Whom cancelling this one meal's shift would tell, counted as {@link #laterRows} counts. */
	private int volunteersToTell(UUID mealId) {
		Integer n = jdbc.queryForObject("""
				SELECT (SELECT count(*) FROM shifts sh JOIN shift_signups ss ON ss.shift_id = sh.id
						WHERE sh.meal_id = ? AND sh.status <> 'CANCELLED' AND ss.released_at IS NULL)
					 + (SELECT count(*) FROM shifts sh JOIN shift_waitlist sw ON sw.shift_id = sh.id
						WHERE sh.meal_id = ? AND sh.status <> 'CANCELLED'
						  AND sw.promoted_at IS NULL AND sw.left_at IS NULL)
				""", Integer.class, mealId, mealId);
		return n == null ? 0 : n;
	}

	/** The date of the series' last occurrence still standing — a dish PLANNED or COOKED — or null. */
	private LocalDate lastStanding(UUID seriesId) {
		return jdbc.queryForObject("""
				SELECT max(pd.plan_date) FROM meals m
				JOIN meal_plan_days pd ON pd.id = m.meal_plan_day_id
				WHERE m.series_id = ?
				  AND EXISTS (SELECT 1 FROM meal_dishes d WHERE d.meal_id = m.id AND d.status <> 'CANCELLED')
				""", LocalDate.class, seriesId);
	}

	/**
	 * Notes that one occurrence of a repeating event was changed on its own (T-307), so the "cancel this
	 * and every later one" confirmation can say so before it cancels it. A meal in no series is left
	 * alone by the predicate; V149's CHECK refuses the mark on one anyway.
	 */
	private void markEditedInSeries(UUID mealId) {
		jdbc.update("UPDATE meals SET series_edited_at = now() WHERE id = ? AND series_id IS NOT NULL", mealId);
	}

	/** Hands the meal's shift draft to the shift service, inside the caller's transaction. */
	private void saveShift(AuthenticatedUser actor, UUID mealId, MealShiftDraft draft) {
		if (draft != null) {
			shiftService.saveForMeal(actor, mealId, draft);
		}
	}

	// ---------------------------------------------------------------------

	/**
	 * A dish checked and ready to write: its recipe exists here, the fast has been answered, and it is
	 * under one of the meal's kitchens.
	 */
	private record NewDish(UUID recipeId, BigDecimal targetYield, boolean acknowledged, UUID kitchenId) {
	}

	private List<NewDish> checkDishes(
			LocalDate date, List<SaveMealRequest.DishDraft> drafts, boolean acknowledged,
			java.util.function.Function<SaveMealRequest.DishDraft, UUID> kitchenOf) {
		List<NewDish> out = new ArrayList<>(drafts.size());
		// A counted thing cannot be a fraction (T-423). A dish's target is in the recipe's own yield
		// unit, which is nowhere on the request — the composer sends a recipe id and a number — so
		// this is the service's question and could not be an annotation on DishDraft. Planning 200.5
		// idlis is planning something nobody can cook, and the figure is the one the job card, the
		// stock forecast and the shopping list all scale from.
		//
		// Collected across the meal: the composer saves every dish in one press (D-27), so refusing
		// one at a time would make the planner press it once per mistake.
		IngredientUnits.Whole whole = IngredientUnits.wholeNumbers();
		for (SaveMealRequest.DishDraft draft : drafts) {
			UUID kitchen = kitchenOf.apply(draft);
			PlannableRecipe recipe = findRecipe(draft.recipeId());
			whole.check(recipe.name(), draft.targetYield(), recipe.yieldUnit());
			out.add(new NewDish(draft.recipeId(), draft.targetYield(),
					resolveEkadashiAck(date, draft.recipeId(), acknowledged), kitchen));
		}
		whole.refuseAnyPart();
		return out;
	}

	// ---- Which kitchen is cooking (Epic 12) ------------------------------

	/**
	 * One kitchen's section of the meal as it will be stored: the kitchen, and its People needed with
	 * a 0 already turned into "not said".
	 */
	private record Section(UUID kitchenId, Integer crewRequired) {
	}

	/**
	 * The kitchens a save names, checked, in the order it named them (T-354).
	 *
	 * <p><strong>The rules</strong> (docs/work/DISPATCH.md, Epic 12, "API default"):
	 * <ul>
	 *   <li>None named at all ({@code null}) is a caller from before kitchens: one section, in the
	 *       saver's default planning kitchen — their own if it plans meals here, else the main kitchen,
	 *       else the first planner kitchen in Settings order ({@link KitchenOrder#defaultPlanningKitchen}).
	 *   <li>An empty list is KMS-400180. Somebody said nobody is cooking this meal, which is not a plan.
	 *   <li>A kitchen this temple does not have — including another temple's, which row-level security
	 *       hides from {@link KitchenOrder#settingsOrder} — is KMS-400108, as anywhere else a kitchen is
	 *       named.
	 *   <li>One that is archived or does not use the planner is KMS-400181: its meals are not planned
	 *       here, so it cannot be given one.
	 *   <li>The same kitchen twice is KMS-400182. Two sections for one kitchen would be two People
	 *       needed figures for the same hands, and the table's unique key refuses it anyway.
	 * </ul>
	 *
	 * <p><strong>No kitchen is ever created here.</strong> Until this task a save at a temple with no
	 * planner kitchen seeded one (T-350's interim code). That was scaffolding for test temples: a real
	 * temple is given a main kitchen when it is provisioned, and V150 gave one to every temple that
	 * existed before. A temple with none has had its kitchens archived or taken off the planner by its
	 * own Temple Admin, and quietly making a new one behind their back would undo that — so it is
	 * KMS-400180, whose next step is to add a kitchen.
	 */
	private List<Section> resolveSections(AuthenticatedUser actor, List<MealKitchenDraft> drafts) {
		List<KitchenRef> order = kitchenOrder.settingsOrder();
		if (drafts == null) {
			return List.of(new Section(defaultKitchen(actor, order), null));
		}
		if (drafts.isEmpty()) {
			throw new ApplicationException(ErrorCode.MEAL_NEEDS_A_KITCHEN, Map.of("field", "kitchens"));
		}
		Map<UUID, KitchenRef> byId = new HashMap<>();
		order.forEach(k -> byId.put(k.id(), k));
		Map<UUID, Section> out = new LinkedHashMap<>();
		for (MealKitchenDraft draft : drafts) {
			// A draft with no kitchen comes only from SaveMealRequest's legacy constructor, and means the
			// saver's default kitchen; bean validation refuses one from a client.
			UUID id = draft.kitchenId() != null ? draft.kitchenId() : defaultKitchen(actor, order);
			KitchenRef kitchen = byId.get(id);
			if (kitchen == null) {
				throw new ApplicationException(ErrorCode.KITCHEN_NOT_FOUND, Map.of("kitchenId", id));
			}
			if (!kitchen.plansMeals()) {
				throw new ApplicationException(ErrorCode.KITCHEN_DOES_NOT_PLAN_MEALS,
						Map.of("kitchenId", id, "kitchen", kitchen.name()));
			}
			if (out.containsKey(id)) {
				throw new ApplicationException(ErrorCode.DISH_KITCHEN_NOT_ON_MEAL,
						Map.of("kitchenId", id, "reason", "kitchen named twice"));
			}
			out.put(id, new Section(id, draft.storedCrew()));
		}
		return List.copyOf(out.values());
	}

	/** The saver's default planning kitchen, or KMS-400180 where the temple has no kitchen planning meals. */
	private UUID defaultKitchen(AuthenticatedUser actor, List<KitchenRef> order) {
		UUID own = kitchenOrder.kitchenOf(actor.getUserId()).orElse(null);
		return KitchenOrder.defaultPlanningKitchen(own, order)
				.orElseThrow(() -> new ApplicationException(ErrorCode.MEAL_NEEDS_A_KITCHEN,
						Map.of("reason", "no kitchen plans its meals here")));
	}

	/**
	 * Which of the meal's kitchens a dish goes under. Named, it must be one of them. Unnamed, it goes to
	 * {@code fallback} where there is one (a kept dish on an edit that named no kitchens stays where it
	 * is), else to the meal's only kitchen; with two or more there is no honest guess, and it is
	 * KMS-400182.
	 */
	private static UUID dishKitchen(SaveMealRequest.DishDraft dish, List<Section> sections, UUID fallback) {
		if (dish.kitchenId() != null) {
			if (sections.stream().noneMatch(s -> s.kitchenId().equals(dish.kitchenId()))) {
				throw new ApplicationException(ErrorCode.DISH_KITCHEN_NOT_ON_MEAL,
						Map.of("recipeId", dish.recipeId(), "kitchenId", dish.kitchenId()));
			}
			return dish.kitchenId();
		}
		if (fallback != null) {
			return fallback;
		}
		if (sections.size() == 1) {
			return sections.get(0).kitchenId();
		}
		throw new ApplicationException(ErrorCode.DISH_KITCHEN_NOT_ON_MEAL,
				Map.of("recipeId", dish.recipeId(), "reason", "no kitchen named on a meal with several"));
	}

	/**
	 * Puts each section on the meal with its People needed: added where the kitchen is new to the meal,
	 * its figure replaced where it is already there. The job-card version and fingerprint of a section
	 * already there are left alone — they belong to the card (T-356), not to the plan.
	 */
	private void writeSections(UUID mealId, List<Section> sections) {
		for (Section section : sections) {
			jdbc.update("""
					INSERT INTO meal_kitchens (tenant_id, meal_id, kitchen_id, crew_required)
					VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?)
					ON CONFLICT (meal_id, kitchen_id)
					DO UPDATE SET crew_required = EXCLUDED.crew_required, updated_at = now()
					""", mealId, section.kitchenId(), section.crewRequired());
		}
	}

	/**
	 * Takes off the meal every kitchen an edit left out (T-354).
	 *
	 * <p>By the time this runs no dish that was sent is under one of them — {@link #dishKitchen} refused
	 * that — and every planned dish of theirs that was not sent has been cancelled like any dish left
	 * out. What can still be under one is history: dishes cancelled earlier, or just now. The database
	 * will not let a section go while a dish points at it (V150's composite key, RESTRICT, and its
	 * comment leaves this decision here), so those cancelled dishes are moved to the first kitchen the
	 * edit kept. A cancelled dish is part of the record of what was decided and must not be deleted;
	 * which kitchen would have cooked a dish nobody cooked is the least important thing about it, and the
	 * alternative — keeping a section the planner took off, to hold dishes nobody is cooking — would put
	 * the kitchen back on the screen they just removed it from.
	 */
	private void removeSectionsLeftOut(UUID mealId, List<MealKitchenView> before, List<Section> after) {
		Set<UUID> kept = after.stream().map(Section::kitchenId).collect(Collectors.toSet());
		UUID home = after.get(0).kitchenId();
		for (MealKitchenView old : before) {
			if (kept.contains(old.kitchenId())) {
				continue;
			}
			jdbc.update("""
					UPDATE meal_dishes SET kitchen_id = ?, updated_at = now()
					WHERE meal_id = ? AND kitchen_id = ? AND status = 'CANCELLED'
					""", home, mealId, old.kitchenId());
			jdbc.update("DELETE FROM meal_kitchens WHERE meal_id = ? AND kitchen_id = ?", mealId, old.kitchenId());
		}
	}

	/**
	 * The temple's plan for a date, created the first time anything is planned on it.
	 *
	 * <p>The day type is written when the day is first made and never re-derived: it is a record of
	 * what was true on the day (V136's column comment), and a festival added to the calendar later
	 * must not quietly relabel a Tuesday somebody already cooked on. The conflict is on the table's
	 * own unique constraint, so two planners on the same date resolve to one day.
	 */
	private UUID dayFor(LocalDate date, DayType dayType) {
		jdbc.update("""
				INSERT INTO meal_plan_days (tenant_id, plan_date, day_type)
				VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?)
				ON CONFLICT (tenant_id, plan_date) DO NOTHING
				""", date, dayType.name());
		return jdbc.queryForObject("SELECT id FROM meal_plan_days WHERE plan_date = ?", UUID.class, date);
	}

	/**
	 * The meal on a day of this kind with this event name, compared the way the unique index compares
	 * it, locked for the rest of the transaction.
	 */
	private Optional<MealRow> findMeal(UUID dayId, UUID kindId, String eventName) {
		return jdbc.query(ROW_SELECT + """
				 WHERE m.meal_plan_day_id = ? AND m.meal_kind_id = ?
				   AND lower(COALESCE(m.event_name, '')) = lower(COALESCE(?::text, ''))
				FOR UPDATE OF m
				""", ROW_MAPPER, dayId, kindId, eventName).stream().findFirst();
	}

	/**
	 * A new meal row with only what its NOT NULL columns demand; {@link #writeMealFacts} writes the
	 * rest. The conflict target names V136's index expression exactly, because an ON CONFLICT that
	 * inferred a different key would raise rather than find the row it meant: two planners pressing
	 * save on the same lunch at the same moment get one meal between them.
	 */
	private UUID insertMeal(UUID dayId, UUID kindId, String eventName, LocalTime readyBy) {
		jdbc.update("""
				INSERT INTO meals (tenant_id, meal_plan_day_id, meal_kind_id, event_name, ready_by)
				VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?)
				ON CONFLICT (meal_plan_day_id, meal_kind_id, lower(COALESCE(event_name, ''))) DO NOTHING
				""", dayId, kindId, eventName, readyBy);
		return findMeal(dayId, kindId, eventName).map(MealRow::id).orElseThrow();
	}

	private void writeMealFacts(
			UUID mealId, Event event, Located located, String occasionName, LocalTime readyBy,
			String purpose, Integer adults, Integer children, Integer seniors,
			String kitchenNotes, String serverNotes) {
		// No crew figure here since Epic 12: People needed is each kitchen's, on meal_kitchens, and the
		// meal's column is gone (V151). writeSections writes it.
		jdbc.update("""
				UPDATE meals
				SET event_name = ?, occasion_name = ?, ready_by = ?, is_outside = ?, handover = ?,
					contact_name = ?, contact_phone = ?, delivery_address = ?, delivery_sub_location = ?,
					delivery_place_id = ?, guests_eat_at = ?, travel_minutes = ?, travel_minutes_source = ?,
					delivery_latitude = ?, delivery_longitude = ?, geocoded_at = ?,
					purpose = ?, adults = ?, children = ?, seniors = ?,
					kitchen_notes = ?, server_notes = ?, updated_at = now()
				WHERE id = ?
				""",
				event.name(), occasionName, readyBy, event.outside(),
				event.handover() == null ? null : event.handover().name(),
				event.contactName(), event.contactPhone(), event.deliveryAddress(), event.subLocation(),
				event.placeId(), event.guestsEatAt(), event.travelMinutes(), event.travelSource(),
				located.latitude(), located.longitude(), located.at(),
				trimToNull(purpose), adults, children, seniors,
				trimToNull(kitchenNotes), trimToNull(serverNotes),
				mealId);
	}

	private void insertDish(AuthenticatedUser actor, UUID mealId, NewDish dish) {
		jdbc.update("""
				INSERT INTO meal_dishes (id, tenant_id, meal_id, recipe_id, target_yield, status,
					ekadashi_ack_by, ekadashi_ack_at, created_by, kitchen_id)
				VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, 'PLANNED', ?, ?, ?, ?)
				""",
				UUID.randomUUID(), mealId, dish.recipeId(), dish.targetYield(),
				dish.acknowledged() ? actor.getUserId() : null,
				dish.acknowledged() ? OffsetDateTime.now(java.time.ZoneOffset.UTC) : null,
				actor.getUserId(), dish.kitchenId());
	}

	/** The meal row, locked, or a refusal. Read before anything about it is decided. */
	private MealRow lockMeal(UUID mealId) {
		return jdbc.query(ROW_SELECT + " WHERE m.id = ? FOR UPDATE OF m", ROW_MAPPER, mealId)
				.stream().findFirst()
				.orElseThrow(() -> new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("mealId", mealId)));
	}

	/**
	 * What the audit trail says a meal was or became — <strong>read back from the rows</strong>, never
	 * built from the request (wave 4b, T-008: a trail built from what was asked for claimed a temple's
	 * coordinates had moved in a field nobody edited).
	 */
	private Map<String, Object> snapshot(UUID mealId) {
		ServedMeal meal = servedMealService.require(mealId);
		Map<String, Object> s = new LinkedHashMap<>();
		s.put("date", meal.planDate().toString());
		s.put("mealKind", meal.mealKind());
		if (meal.eventName() != null) {
			s.put("eventName", meal.eventName());
		}
		s.put("readyBy", String.valueOf(meal.readyBy()));
		s.put("dayType", meal.dayType().name());
		s.put("status", meal.status().name());
		s.put("dishes", meal.dishes().stream()
				.filter(d -> d.status() != MealStatus.CANCELLED)
				.map(MealPlanService::dishLabel)
				.toList());
		// Which kitchens, with their People needed and what each is cooking (Epic 12) — read back from
		// meal_kitchens and meal_dishes like everything above, so a kitchen moved, added or taken off
		// shows in the trail as what the rows now say, not as what the request asked for.
		List<Map<String, Object>> kitchens = new ArrayList<>();
		for (MealKitchenView k : meal.kitchens()) {
			Map<String, Object> section = new LinkedHashMap<>();
			section.put("kitchen", k.kitchenName());
			section.put("crewRequired", k.crewRequired());
			section.put("dishes", meal.dishes().stream()
					.filter(d -> d.status() != MealStatus.CANCELLED && k.kitchenId().equals(d.kitchenId()))
					.map(MealPlanService::dishLabel)
					.toList());
			kitchens.add(section);
		}
		s.put("kitchens", kitchens);
		return s;
	}

	private static String dishLabel(MealDishView d) {
		return d.recipeName() + " " + d.targetYield().stripTrailingZeros().toPlainString();
	}

	/**
	 * The time this meal must be ready: what was entered, or the kind's own default. A kind with no
	 * default is refused rather than given a guessed hour.
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
	 * What an event needs beyond a recipe, asked in a chain and never all at once (E4-S15 D6).
	 *
	 * <p>An event has a <strong>name</strong>. An event <strong>going outside</strong> has somebody to
	 * contact, name and phone both. A <strong>delivered</strong> one also has an address and the time
	 * the guests eat. An <strong>in-house</strong> event stops at its name. Breakfast, Lunch and
	 * Dinner reach none of this: a kind that is not an event has every one of these fields dropped on
	 * the way in, so a caller sending an address on a Lunch stores nothing.
	 */
	private Event requireEventFields(
			MealKindView kind, String eventName, boolean outside, Handover handover,
			String contactName, String contactPhone, String deliveryAddress, String subLocation,
			String placeId, BigDecimal latitude, BigDecimal longitude, LocalTime guestsEatAt,
			Integer travelMinutes, boolean travelManual, LocalTime readyBy) {

		if (!kind.isEvent()) {
			return Event.none();
		}
		String name = trimToNull(eventName);
		if (name == null) {
			throw new ApplicationException(ErrorCode.EVENT_NAME_REQUIRED, Map.of("mealKind", kind.name()));
		}
		if (!outside) {
			return new Event(name, false, null, null, null, null, null, null, null, null, null, null, null);
		}
		String who = trimToNull(contactName);
		String phone = trimToNull(contactPhone);
		if (who == null || phone == null) {
			throw new ApplicationException(ErrorCode.EVENT_CONTACT_REQUIRED, Map.of("eventName", name));
		}
		if (handover != Handover.DELIVERY) {
			// Pickup, or an outside plan that predates the question. Neither needs an address.
			return new Event(name, true, handover, who, phone, null, null, null, null, null, null, null, null);
		}
		String address = trimToNull(deliveryAddress);
		if (address == null || guestsEatAt == null) {
			throw new ApplicationException(ErrorCode.EVENT_DELIVERY_DETAILS_REQUIRED,
					Map.of("eventName", name));
		}
		// The source is derived rather than accepted, so a client cannot claim a figure was set by a
		// person when it was not — that claim is what stops the job card refreshing it (V93).
		String travelSource = travelMinutes == null ? null : (travelManual ? "MANUAL" : "ESTIMATED");
		requireItCanArriveInTime(readyBy, guestsEatAt, travelMinutes);
		return new Event(name, true, Handover.DELIVERY, who, phone, address,
				trimToNull(subLocation), trimToNull(placeId), latitude, longitude,
				guestsEatAt, travelMinutes, travelSource);
	}

	/**
	 * How many people this meal is for. A main meal is never planned without one.
	 *
	 * <p>Every figure the plan is worth is derived from this number: how much of each preparation to
	 * make, what the day's food costs, what a serving of it costs. Absent and zero are refused alike,
	 * and it is checked as three counters rather than as the weighted total, because a hall of one
	 * child weighs 0.6 and that is a real head count. An event is exempt (E4-S15 D2): it is quantified
	 * by how much to make.
	 */
	private static void requireHeadCount(
			Integer adults, Integer children, Integer seniors, LocalDate date, MealKindView kind) {

		if (kind.isEvent()) {
			return;
		}
		if (zeroOrAbsent(adults) && zeroOrAbsent(children) && zeroOrAbsent(seniors)) {
			throw new ApplicationException(ErrorCode.MEAL_HEAD_COUNT_REQUIRED,
					Map.of("planDate", date, "mealKind", kind.name()));
		}
	}

	private static boolean zeroOrAbsent(Integer count) {
		return count == null || count == 0;
	}

	/** What kind of day this meal is cooked on — derived from the calendar, never asked (E4-S7). */
	private DayType deriveDayType(LocalDate date) {
		return dayContext(date).suggestedDayType();
	}

	/**
	 * Which festival this meal is for. Derived for every ordinary kind; a feast — a kind flagged
	 * {@code needsOccasion} — may choose, defaulting to the calendar's answer, and a feast with
	 * nothing to name is refused.
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

	// ---- Going outside ---------------------------------------------------

	/**
	 * Everything this temple has undertaken to send out of the building, soonest first (E4-S15 D4).
	 *
	 * <p>Future only, cancelled meals dropped, one row per meal. Today is today <em>at the
	 * temple</em>, so a list read in Bengaluru at half past six in the morning has not dropped this
	 * morning's delivery because the server is still on yesterday in UTC.
	 */
	@Transactional(readOnly = true)
	public List<OutsideCommitment> outsideCommitments() {
		return jdbc.query("""
				SELECT m.id, pd.plan_date, m.event_name, k.name AS meal_kind, m.handover, m.contact_name,
					   m.contact_phone, m.delivery_address, m.ready_by, m.guests_eat_at,
					   (SELECT count(*) FROM meal_dishes d
						WHERE d.meal_id = m.id AND d.status <> 'CANCELLED') AS preparations
				FROM meals m
				JOIN meal_plan_days pd ON pd.id = m.meal_plan_day_id
				JOIN meal_kinds k ON k.id = m.meal_kind_id
				WHERE m.is_outside
				  AND pd.plan_date >= ?
				  AND EXISTS (SELECT 1 FROM meal_dishes d WHERE d.meal_id = m.id AND d.status <> 'CANCELLED')
				ORDER BY pd.plan_date, m.ready_by, m.event_name
				""",
				(rs, n) -> new OutsideCommitment(
						rs.getObject("id", UUID.class),
						rs.getObject("plan_date", LocalDate.class),
						rs.getString("event_name"),
						rs.getString("meal_kind"),
						rs.getString("handover") == null ? null : Handover.valueOf(rs.getString("handover")),
						rs.getString("contact_name"),
						rs.getString("contact_phone"),
						rs.getString("delivery_address"),
						rs.getObject("ready_by", LocalTime.class),
						rs.getObject("guests_eat_at", LocalTime.class),
						rs.getInt("preparations")),
				LocalDate.now(clock.zone()));
	}

	/**
	 * The event names this temple has used before, newest first, with what each was last time
	 * (E4-S15 D9).
	 *
	 * <p>Ten at most. Distinct by name ignoring case, with the newest spelling and the newest contact.
	 * A cancelled meal is not a name the temple uses. Newest is by plan date, so an event already
	 * planned for next month is the freshest thing the temple has said about it. Matched with
	 * {@code starts_with} rather than {@code LIKE}, so {@code %} and {@code _} are ordinary characters.
	 */
	@Transactional(readOnly = true)
	public List<EventSuggestion> eventNames(String prefix) {
		String q = prefix == null ? "" : prefix.trim();
		return jdbc.query("""
				SELECT DISTINCT ON (lower(m.event_name))
					   m.event_name, m.is_outside, m.handover, m.contact_name,
					   m.contact_phone, m.delivery_address, pd.plan_date, m.created_at
				FROM meals m
				JOIN meal_plan_days pd ON pd.id = m.meal_plan_day_id
				WHERE m.event_name IS NOT NULL
				  AND EXISTS (SELECT 1 FROM meal_dishes d WHERE d.meal_id = m.id AND d.status <> 'CANCELLED')
				  AND starts_with(lower(m.event_name), lower(?))
				ORDER BY lower(m.event_name), pd.plan_date DESC, m.created_at DESC
				""", SUGGESTION_MAPPER, q).stream()
				// DISTINCT ON has to sort by the name it is distinct on, so the ordering the caller
				// actually wants — most recently used first — is applied to the result of that.
				.sorted(Comparator
						.comparing(Suggestion::planDate, Comparator.reverseOrder())
						.thenComparing(Suggestion::createdAt, Comparator.reverseOrder())
						.thenComparing(s -> s.suggestion().eventName(), String.CASE_INSENSITIVE_ORDER))
				.limit(MAX_EVENT_SUGGESTIONS)
				.map(Suggestion::suggestion)
				.toList();
	}

	/**
	 * The furthest ahead a repeat may run: a year from today at the temple (T-307, KMS-400176). The
	 * calendar that decides the fast days is computed about that far ahead, and a plan further out than
	 * that is a guess about a kitchen nobody has staffed yet.
	 */
	private static final int REPEAT_HORIZON_YEARS = 1;

	/** The gaps the screen offers between occurrences, in weeks (KMS-400175). */
	private static final int MIN_REPEAT_WEEKS = 1;
	private static final int MAX_REPEAT_WEEKS = 12;

	/**
	 * Repeats an event "once every N weeks until a date" as a series (T-307).
	 *
	 * <p><strong>A series, where it used to be copies.</strong> E4-S15 D8 made repeating produce
	 * unrelated copies, precisely so that no screen would ever have to ask "this one or all of them?".
	 * Rajeev asked for that question on 2026-09-19: <em>"Let us change for many weeks to 'until' a date
	 * and also give them the option to pick the duration between repeats, and a cancel of this
	 * repeating event should ask JUST this event OR all events from this point onwards."</em> So the
	 * source and every copy now share a {@code meal_series} row (V149). What did not change is what a
	 * copy is: an ordinary meal, made through {@link #create} so every rule that governs a meal governs
	 * it, editable and cancellable on its own, and read by everything else like any other meal.
	 *
	 * <p>Which meal, by its id, with every dish it still has — never "the event's dishes" found by
	 * matching text, which is what D-27 ruled out. No volunteer shift is copied: a shift is somebody
	 * asking for help with one meal, and nobody has asked for these yet.
	 *
	 * <p>Everything is decided by {@link #repeatWalk}, which {@link #previewRepeat} also runs, so the
	 * dates the confirmation shows are the dates this makes. All of it is one transaction.
	 */
	@Transactional
	public RepeatEventResult repeat(AuthenticatedUser actor, UUID mealId, int everyWeeks, LocalDate until) {
		return repeatWalk(actor, mealId, everyWeeks, until, false);
	}

	/** What {@link #repeat} would do with the same answers, writing nothing (T-307). */
	@Transactional(readOnly = true)
	public RepeatEventResult previewRepeat(UUID mealId, int everyWeeks, LocalDate until) {
		return repeatWalk(null, mealId, everyWeeks, until, true);
	}

	/**
	 * The one walk behind both the preview and the repeat — the reuse screen's rule, for the same
	 * reason: a preview computed a second way is a preview that can disagree with what it previews.
	 *
	 * <p><strong>The dates</strong> are the source's date plus every {@code everyWeeks} weeks, for as
	 * long as they fall on or before {@code until} and not before the temple's today. None at all is
	 * refused (KMS-400177) — the planner chose an end date before the first copy that could still be
	 * cooked. Each date is then either made or skipped, and every skip
	 * is named by its date so the screen can say which Saturday and why:
	 *
	 * <ul>
	 *   <li><strong>Already planned.</strong> This same event — the day, kind and event name compared
	 *       as {@link #findMeal} compares them — is already there with a dish to cook or cooked, or has
	 *       been recorded. Planning it again would have {@link #create} add a second set of the same
	 *       dishes to it, which is what the old repeat did and what nobody meant. A same-named meal that
	 *       was cancelled is not in the way: {@code create} brings it back, as it always has, and it
	 *       joins the series as a fresh copy.
	 *   <li><strong>Fasting.</strong> A dish does not suit an Ekadashi falling there. Refused rather
	 *       than acknowledged on the planner's behalf — nobody is looking at that meal to say it is all
	 *       right.
	 * </ul>
	 *
	 * <p><strong>The series.</strong> A source in no series starts one and joins it. A source already
	 * in one — somebody repeating from the third occurrence to run it further — extends it: the gap
	 * becomes the one just chosen, and the end date the later of the two, so extending never shortens.
	 * Where every date was skipped nothing is written at all, not even a series of one.
	 */
	private RepeatEventResult repeatWalk(
			AuthenticatedUser actor, UUID mealId, int everyWeeks, LocalDate until, boolean dryRun) {

		if (everyWeeks < MIN_REPEAT_WEEKS || everyWeeks > MAX_REPEAT_WEEKS) {
			throw new ApplicationException(ErrorCode.REPEAT_INTERVAL_OUT_OF_RANGE,
					Map.of("everyWeeks", everyWeeks));
		}
		LocalDate latest = clock.today().plusYears(REPEAT_HORIZON_YEARS);
		if (until.isAfter(latest)) {
			throw new ApplicationException(ErrorCode.REPEAT_END_DATE_TOO_FAR,
					Map.of("until", until.toString(), "latest", latest.toString()));
		}

		// The real run locks the source first, so two planners repeating it at once make one series
		// between them rather than two, and neither sees the other's copies half-made.
		MealRow row = dryRun
				? jdbc.query(ROW_SELECT + " WHERE m.id = ?", ROW_MAPPER, mealId).stream().findFirst()
						.orElseThrow(() -> new ApplicationException(
								ErrorCode.RESOURCE_NOT_FOUND, Map.of("mealId", mealId)))
				: lockMeal(mealId);
		ServedMeal source = servedMealService.require(mealId);
		List<MealDishView> dishes = live(source);
		if (dishes.isEmpty()) {
			// A cancelled meal has nothing to repeat. The old repeat quietly made no copies; a series of
			// nothing is not something to confirm, so it is refused with the meal's own state.
			throw new ApplicationException(ErrorCode.MEAL_PLAN_NOT_OPEN, Map.of("mealId", mealId));
		}
		// Every copy goes to the source's kitchens (Epic 12). A dish of a kitchen archived or taken off the
		// planner since has no kitchen to go to on any date, so — unlike a fast, which falls on some dates
		// and not others — it is not a date to skip but the whole repeat that cannot be made as asked.
		// Refused, naming the kitchen, before the preview promises anything; the planner moves the dish
		// to another kitchen on this meal and repeats that.
		Set<UUID> planning = plannerKitchens(kitchenOrder.settingsOrder());
		for (MealDishView dish : dishes) {
			if (!planning.contains(dish.kitchenId())) {
				throw new ApplicationException(ErrorCode.KITCHEN_DOES_NOT_PLAN_MEALS,
						Map.of("mealId", mealId, "kitchenId", dish.kitchenId()));
			}
		}

		// Never a copy in the past (T-310). Repeating last month's event would otherwise plan meals on
		// days already gone — meals nobody can cook, which then sit in the history as if they had been
		// planned then. Past dates are simply not candidates: not made, not named as skipped, not
		// counted, because a skip is something the planner can act on and a day gone is not. The grid
		// stays anchored on the source's own date, so the first copy is the first date on that grid
		// that is today or later — today itself included, since today's meal can still be cooked. The
		// frontend mirrors this rule; the walk is still the one place that decides.
		LocalDate today = clock.today();
		List<LocalDate> candidates = new ArrayList<>();
		for (LocalDate d = source.planDate().plusWeeks(everyWeeks); !d.isAfter(until); d = d.plusWeeks(everyWeeks)) {
			if (!d.isBefore(today)) {
				candidates.add(d);
			}
		}
		if (candidates.isEmpty()) {
			throw new ApplicationException(ErrorCode.REPEAT_MAKES_NO_COPIES,
					Map.of("from", source.planDate().toString(), "until", until.toString(), "everyWeeks", everyWeeks));
		}

		Set<LocalDate> taken = sameEventTaken(source, candidates.get(0), candidates.get(candidates.size() - 1));
		List<LocalDate> dates = new ArrayList<>();
		List<LocalDate> skippedFasting = new ArrayList<>();
		List<LocalDate> skippedAlreadyPlanned = new ArrayList<>();
		for (LocalDate target : candidates) {
			if (taken.contains(target)) {
				skippedAlreadyPlanned.add(target);
			} else if (dishes.stream().anyMatch(d -> {
				EkadashiCheck check = ekadashiCheck(target, d.recipeId());
				return check.isEkadashi() && !check.compatible();
			})) {
				skippedFasting.add(target);
			} else {
				dates.add(target);
			}
		}

		MealSeriesView series;
		if (dates.isEmpty()) {
			series = source.series();
		} else if (dryRun) {
			MealSeriesView current = source.series();
			series = current == null
					? new MealSeriesView(null, everyWeeks, until, 1, 1 + dates.size())
					: new MealSeriesView(current.seriesId(), everyWeeks,
							until.isAfter(current.until()) ? until : current.until(),
							current.position(), current.count() + dates.size());
		} else {
			UUID seriesId = row.seriesId();
			if (seriesId == null) {
				seriesId = jdbc.queryForObject("""
						INSERT INTO meal_series (tenant_id, every_weeks, until_date, created_by)
						VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?)
						RETURNING id
						""", UUID.class, everyWeeks, until, actor.getUserId());
				jdbc.update("UPDATE meals SET series_id = ?, updated_at = now() WHERE id = ?", seriesId, mealId);
			} else {
				jdbc.update("""
						UPDATE meal_series
						SET every_weeks = ?, until_date = GREATEST(until_date, ?), updated_at = now()
						WHERE id = ?
						""", everyWeeks, until, seriesId);
			}
			for (LocalDate target : dates) {
				UUID copy = create(actor, copyOf(source, dishes, target, planning)).id();
				// A cancelled meal brought back is a fresh copy, whatever series it was in before and
				// whatever was done to it there.
				jdbc.update("""
						UPDATE meals SET series_id = ?, series_edited_at = NULL, updated_at = now()
						WHERE id = ?
						""", seriesId, copy);
			}
			// Read back rather than worked out, so the answer is what the database now says.
			series = servedMealService.require(mealId).series();
		}

		return new RepeatEventResult(
				dates.size(), dates.size() * dishes.size(), List.copyOf(dates), List.copyOf(skippedFasting),
				List.copyOf(skippedAlreadyPlanned), dates.isEmpty() ? null : dates.get(dates.size() - 1), series);
	}

	/**
	 * The dates in a window on which this same event already stands: the same kind and event name as
	 * {@link #findMeal} compares them, with a dish that is not cancelled, or recorded. One statement for
	 * the whole window rather than one per date.
	 */
	private Set<LocalDate> sameEventTaken(ServedMeal source, LocalDate from, LocalDate to) {
		return new LinkedHashSet<>(jdbc.queryForList("""
				SELECT pd.plan_date FROM meals m
				JOIN meal_plan_days pd ON pd.id = m.meal_plan_day_id
				WHERE m.meal_kind_id = ?
				  AND lower(COALESCE(m.event_name, '')) = lower(COALESCE(?::text, ''))
				  AND pd.plan_date BETWEEN ? AND ?
				  AND (m.recorded_at IS NOT NULL
					   OR EXISTS (SELECT 1 FROM meal_dishes d WHERE d.meal_id = m.id AND d.status <> 'CANCELLED'))
				""", LocalDate.class, source.mealKindId(), source.eventName(), from, to));
	}

	private static boolean sameEvent(String a, String b) {
		return a == null ? b == null : a.equalsIgnoreCase(b);
	}

	/**
	 * One meal with some of its dishes, ready to be planned again on another date.
	 *
	 * <p>The occasion is deliberately not carried: a feast copied onto an ordinary Wednesday is not
	 * last week's festival. Everything else carries, the event's own fields included — except the
	 * coordinates: a copy carries the place id and the save resolves the pin from that, because the
	 * pin's thirty-day licence belongs to the lookup that produced it, not to the copy.
	 */
	private static SaveMealRequest copyOf(
			ServedMeal meal, List<MealDishView> dishes, LocalDate target, Set<UUID> planning) {
		// The same kitchens, each with its People needed, and each dish under its own kitchen (Epic 12).
		// A section whose kitchen no longer plans meals is not carried: the reuse left its dishes behind
		// and said why, and the repeat refused before getting here.
		List<MealKitchenDraft> kitchens = meal.kitchens().stream()
				.filter(k -> planning.contains(k.kitchenId()))
				.map(k -> new MealKitchenDraft(k.kitchenId(), k.crewRequired()))
				.toList();
		return new SaveMealRequest(
				target, meal.mealKindId(), meal.readyBy(),
				meal.eventName(), meal.isOutside(), meal.handover(), meal.contactName(),
				meal.contactPhone(), meal.deliveryAddress(), meal.deliverySubLocation(),
				meal.deliveryPlaceId(),
				null, null,
				// The travel figure carries with its source intact. Somebody's manual correction must
				// survive the copy or they would have to make it again every week.
				meal.travelMinutes(), "MANUAL".equals(meal.travelMinutesSource()),
				meal.guestsEatAt(),
				meal.purpose(), null,
				meal.adults(), meal.children(), meal.seniors(), kitchens,
				meal.kitchenNotes(), meal.serverNotes(), false,
				dishes.stream()
						.map(d -> new SaveMealRequest.DishDraft(null, d.recipeId(), d.targetYield(), d.kitchenId()))
						.toList(),
				null);
	}

	// ---- Getting there (E4-S16) ------------------------------------------

	/**
	 * When to leave the temple for this delivery, and how long the drive is expected to take.
	 *
	 * <p>Computed backwards from the time the guests eat, from the <em>pessimistic</em> end of the
	 * range. Every way this can fail to produce a number is an answer rather than an error, and
	 * nothing about the drive is stored.
	 */
	@Transactional
	public TravelEstimate travelEstimate(UUID mealId) {
		ServedMeal meal = servedMealService.require(mealId);
		if (meal.handover() != Handover.DELIVERY) {
			return TravelEstimate.unavailable("NOT_A_DELIVERY");
		}
		if (meal.guestsEatAt() == null) {
			return TravelEstimate.unavailable("NO_SERVING_TIME");
		}
		if (!travelTimeProvider.configured()) {
			return TravelEstimate.unavailable("NO_MAP_SERVICE");
		}
		GeocodingProvider.Coordinates destination = deliveryCoordinates(mealId);
		GeocodingProvider.Coordinates origin = templeCoordinates();
		if (destination == null || origin == null) {
			return TravelEstimate.unavailable("ADDRESS_NOT_FOUND");
		}

		Instant sitDown = LocalDateTime.of(meal.planDate(), meal.guestsEatAt())
				.atZone(clock.zone()).toInstant();
		Optional<TravelTimeProvider.TravelTime> drive;
		try {
			drive = travelTimeProvider.drive(origin, destination, sitDown.minus(ASSUMED_DEPARTURE_LEAD));
		} catch (RuntimeException e) {
			// The port's contract is that it never raises. This is the belt to that pair of braces.
			log.warn("The routing provider raised ({}); the planner shows no estimate", e.toString());
			drive = Optional.empty();
		}
		if (drive.isEmpty()) {
			return TravelEstimate.unavailable("NO_ROUTE");
		}

		int optimistic = minutes(drive.get().optimistic());
		int pessimistic = minutes(drive.get().pessimistic());
		return new TravelEstimate(
				true, meal.guestsEatAt().minusMinutes(pessimistic),
				optimistic, pessimistic, meal.guestsEatAt(), null);
	}

	/**
	 * Refuses a delivery whose van is still on the road when the guests sit down (KMS-400079).
	 *
	 * <p>Rajeev, 2026-09-05: <em>"People Sit to eat time MUST be = Ready by time + transit time at a
	 * minumum."</em> The floor is enforced; everything above it is time nobody here can measure. It
	 * only fires when all three figures are supplied, and a serving time at or before the ready-by is
	 * a meal running past midnight, which arithmetic that does not understand the clock must not refuse.
	 */
	private static void requireItCanArriveInTime(
			LocalTime readyBy, LocalTime guestsEatAt, Integer travelMinutes) {

		if (readyBy == null || guestsEatAt == null || travelMinutes == null) {
			return;
		}
		if (!guestsEatAt.isAfter(readyBy)) {
			return;
		}
		LocalTime arrives = readyBy.plusMinutes(travelMinutes);
		if (arrives.isBefore(readyBy) || arrives.isAfter(guestsEatAt)) {
			throw new ApplicationException(ErrorCode.DELIVERY_CANNOT_ARRIVE_IN_TIME, Map.of(
					"readyBy", readyBy.toString(),
					"travelMinutes", String.valueOf(travelMinutes),
					"guestsEatAt", guestsEatAt.toString(),
					"arrivesAt", arrives.toString()));
		}
	}

	/**
	 * The same estimate, for a delivery nobody has saved yet — what the composer asks while somebody
	 * is still typing. It takes a place rather than a meal id, because in a form there is no meal.
	 */
	public TravelEstimate travelEstimateFor(
			String placeId, Double latitude, Double longitude, LocalDate planDate, LocalTime eatAt) {

		if (planDate == null || eatAt == null) {
			return TravelEstimate.unavailable("NO_SERVING_TIME");
		}
		if (!travelTimeProvider.configured()) {
			return TravelEstimate.unavailable("NO_MAP_SERVICE");
		}
		GeocodingProvider.Coordinates destination = latitude != null && longitude != null
				? new GeocodingProvider.Coordinates(latitude, longitude)
				: placeSuggestionProvider.resolve(placeId, null)
						.map(PlaceSuggestionProvider.Place::at).orElse(null);
		GeocodingProvider.Coordinates origin = templeCoordinates();
		if (destination == null || origin == null) {
			return TravelEstimate.unavailable("ADDRESS_NOT_FOUND");
		}

		Instant sitDown = LocalDateTime.of(planDate, eatAt).atZone(clock.zone()).toInstant();
		Optional<TravelTimeProvider.TravelTime> drive;
		try {
			drive = travelTimeProvider.drive(origin, destination, sitDown.minus(ASSUMED_DEPARTURE_LEAD));
		} catch (RuntimeException e) {
			log.warn("The routing provider raised ({}); the composer shows no estimate", e.toString());
			drive = Optional.empty();
		}
		if (drive.isEmpty()) {
			return TravelEstimate.unavailable("NO_ROUTE");
		}
		int optimistic = minutes(drive.get().optimistic());
		int pessimistic = minutes(drive.get().pessimistic());
		return new TravelEstimate(
				true, eatAt.minusMinutes(pessimistic), optimistic, pessimistic, eatAt, null);
	}

	/**
	 * Google's estimate for this delivery right now, in minutes, stored onto the meal.
	 *
	 * <p>Called when a job card is printed, and only lands on a figure nobody has edited — the
	 * statement's own predicate says so, so a person's correction cannot be overwritten however this
	 * is reached. Returns null and changes nothing whenever an estimate cannot be had.
	 */
	@Transactional
	public Integer refreshTravelEstimate(UUID mealId) {
		TravelEstimate estimate = travelEstimate(mealId);
		if (!estimate.available() || estimate.pessimisticMinutes() == null) {
			return null;
		}
		int minutes = estimate.pessimisticMinutes();
		jdbc.update("""
				UPDATE meals
				SET travel_minutes = ?, travel_minutes_source = 'ESTIMATED', updated_at = now()
				WHERE id = ? AND (travel_minutes_source IS NULL OR travel_minutes_source = 'ESTIMATED')
				""", minutes, mealId);
		return minutes;
	}

	/**
	 * Where this delivery is going, geocoding it if the coordinates are missing or out of licence.
	 * Public because the job card needs it for the map on the delivery sheet.
	 */
	@Transactional
	public GeocodingProvider.Coordinates deliveryCoordinatesFor(UUID mealId) {
		return deliveryCoordinates(mealId);
	}

	/** Rounded up. Half a minute of slack is worth having and no driver counts seconds. */
	private static int minutes(Duration duration) {
		return (int) ((duration.getSeconds() + 59) / 60);
	}

	/**
	 * Where this delivery is going, looking the address up again if what we hold has expired. The
	 * thirty-day life is the licence (E4-S16 D4), enforced here as well as on save.
	 */
	private GeocodingProvider.Coordinates deliveryCoordinates(UUID mealId) {
		MealRow row = jdbc.query(ROW_SELECT + " WHERE m.id = ?", ROW_MAPPER, mealId).stream().findFirst()
				.orElseThrow(() -> new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("mealId", mealId)));
		if (fresh(row.deliveryLatitude(), row.deliveryLongitude(), row.geocodedAt())) {
			return new GeocodingProvider.Coordinates(
					row.deliveryLatitude().doubleValue(), row.deliveryLongitude().doubleValue());
		}
		Located located = geocode(row.deliveryAddress());
		jdbc.update("""
				UPDATE meals
				SET delivery_latitude = ?, delivery_longitude = ?, geocoded_at = ?
				WHERE id = ?
				""", located.latitude(), located.longitude(), located.at(), mealId);
		if (located.latitude() == null) {
			return null;
		}
		return new GeocodingProvider.Coordinates(
				located.latitude().doubleValue(), located.longitude().doubleValue());
	}

	/**
	 * Where this event is going, preferring the pin somebody actually chose.
	 *
	 * <p><strong>A picked place is never geocoded.</strong> Found by driving the live app on
	 * 2026-09-05: the save discarded a chosen place's coordinates and asked the geocoder to find the
	 * address text from scratch, which failed. The coordinates come with the place id and are used as
	 * given; a place id with none beside it asks Places; only then the address text.
	 */
	private Located place(
			Event event, String previousAddress, BigDecimal latitude, BigDecimal longitude,
			OffsetDateTime geocodedAt) {

		if (event.isPlaced()) {
			return new Located(event.latitude(), event.longitude(), OffsetDateTime.now(), null);
		}
		if (event.placeId() != null) {
			Optional<GeocodingProvider.Coordinates> at =
					placeSuggestionProvider.resolve(event.placeId(), null)
							.map(PlaceSuggestionProvider.Place::at);
			if (at.isPresent()) {
				return new Located(
						BigDecimal.valueOf(at.get().latitude()), BigDecimal.valueOf(at.get().longitude()),
						OffsetDateTime.now(), null);
			}
		}
		return locate(event.deliveryAddress(), previousAddress, latitude, longitude, geocodedAt);
	}

	/**
	 * Looked up only when it has to be: an address that has not changed and was placed inside the
	 * last thirty days keeps the coordinates it has. Editing the head count on a delivery is not a
	 * reason to spend a geocoding request.
	 */
	private Located locate(
			String address, String previousAddress, BigDecimal latitude, BigDecimal longitude,
			OffsetDateTime geocodedAt) {

		if (address == null) {
			return Located.NOWHERE;
		}
		if (address.equalsIgnoreCase(previousAddress == null ? "" : previousAddress.trim())
				&& fresh(latitude, longitude, geocodedAt)) {
			return new Located(latitude, longitude, geocodedAt, null);
		}
		return geocode(address);
	}

	private Located geocode(String address) {
		if (address == null || address.isBlank()) {
			return Located.NOWHERE;
		}
		Optional<GeocodingProvider.Coordinates> found;
		try {
			found = geocodingProvider.locate(address);
		} catch (RuntimeException e) {
			// A map service having a bad day must never cost somebody the meal plan they have just
			// typed — the save goes through and the estimate is simply absent.
			log.warn("The geocoder raised ({}); the address is stored unplaced", e.toString());
			return Located.NOWHERE;
		}
		if (found.isEmpty()) {
			// Reported only when somebody actually looked (E4-S16, UAT-086 step 50).
			return new Located(null, null, null,
					geocodingProvider.configured() ? ErrorCode.DELIVERY_ADDRESS_NOT_FOUND : null);
		}
		return new Located(
				BigDecimal.valueOf(found.get().latitude()),
				BigDecimal.valueOf(found.get().longitude()),
				OffsetDateTime.now(java.time.ZoneOffset.UTC),
				null);
	}

	private static boolean fresh(BigDecimal latitude, BigDecimal longitude, OffsetDateTime at) {
		return isCoordinate(latitude) && isCoordinate(longitude) && at != null
				&& at.toInstant().isAfter(Instant.now().minus(Duration.ofDays(GEOCODE_LIFE_DAYS)));
	}

	/**
	 * A degree figure that could have come from a map service rather than from a client with nothing
	 * to send. Zero is refused on either axis: 0.000000 is what an empty box serialises to, and the
	 * point where both are zero is five hundred kilometres off the coast of Ghana (T-044).
	 */
	private static boolean isCoordinate(BigDecimal degrees) {
		return degrees != null && degrees.signum() != 0;
	}

	/** The temple's own coordinates — the origin of every delivery. */
	private GeocodingProvider.Coordinates templeCoordinates() {
		return jdbc.query("""
				SELECT latitude, longitude FROM tenants
				WHERE id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""", (rs, n) -> {
					BigDecimal lat = rs.getBigDecimal("latitude");
					BigDecimal lon = rs.getBigDecimal("longitude");
					return lat == null || lon == null
							? null
							: new GeocodingProvider.Coordinates(lat.doubleValue(), lon.doubleValue());
				}).stream().findFirst().orElse(null);
	}

	/**
	 * The recipe, as a planned dish needs it: it is this temple's and it is active, plus the two facts
	 * the whole-number rule reads. Counting rows was enough until T-423 needed the name and the unit
	 * as well, and one read is the same cost as the count it replaces.
	 */
	private PlannableRecipe findRecipe(UUID recipeId) {
		return jdbc.query("""
				SELECT name, base_yield_unit FROM recipes WHERE id = ? AND status = 'ACTIVE'
				""", (rs, n) -> new PlannableRecipe(
						rs.getString("name"), Unit.valueOf(rs.getString("base_yield_unit"))), recipeId)
				.stream().findFirst()
				.orElseThrow(() -> new ApplicationException(
						ErrorCode.RESOURCE_NOT_FOUND, Map.of("recipeId", recipeId)));
	}

	/** What a dish's target yield is a number of, and what to call the dish in a refusal. */
	private record PlannableRecipe(String name, Unit yieldUnit) {
	}

	private static String trimToNull(String s) {
		if (s == null) {
			return null;
		}
		String t = s.trim();
		return t.isEmpty() ? null : t;
	}

	/** A suggestion with the two facts that order it and that the caller has no use for. */
	private record Suggestion(EventSuggestion suggestion, LocalDate planDate, Instant createdAt) {
	}

	private static final RowMapper<Suggestion> SUGGESTION_MAPPER = (rs, n) -> new Suggestion(
			new EventSuggestion(
					rs.getString("event_name"),
					rs.getBoolean("is_outside"),
					rs.getString("handover") == null ? null : Handover.valueOf(rs.getString("handover")),
					rs.getString("contact_name"),
					rs.getString("contact_phone"),
					rs.getString("delivery_address")),
			rs.getObject("plan_date", LocalDate.class),
			instant(rs, "created_at"));

	/** The parts of a meal row the planner decides with: its identity, whether it is recorded, and its pin. */
	private record MealRow(
			UUID id, UUID mealPlanDayId, UUID mealKindId, LocalDate planDate, String eventName,
			Instant recordedAt, String deliveryAddress, BigDecimal deliveryLatitude,
			BigDecimal deliveryLongitude, OffsetDateTime geocodedAt, UUID seriesId) {
	}

	private static final String ROW_SELECT = """
			SELECT m.id, m.meal_plan_day_id, m.meal_kind_id, pd.plan_date, m.event_name, m.recorded_at,
				   m.delivery_address, m.delivery_latitude, m.delivery_longitude, m.geocoded_at, m.series_id
			FROM meals m
			JOIN meal_plan_days pd ON pd.id = m.meal_plan_day_id
			""";

	private static final RowMapper<MealRow> ROW_MAPPER = (rs, n) -> new MealRow(
			rs.getObject("id", UUID.class),
			rs.getObject("meal_plan_day_id", UUID.class),
			rs.getObject("meal_kind_id", UUID.class),
			rs.getObject("plan_date", LocalDate.class),
			rs.getString("event_name"),
			instant(rs, "recorded_at"),
			rs.getString("delivery_address"),
			rs.getBigDecimal("delivery_latitude"),
			rs.getBigDecimal("delivery_longitude"),
			rs.getObject("geocoded_at", OffsetDateTime.class),
			rs.getObject("series_id", UUID.class));

	/**
	 * The event fields as they will actually be stored, after the chain of D6 has been walked.
	 * Everything below the first {@code null} is null: an in-house event keeps no contact, and a
	 * pickup keeps no address.
	 */
	private record Event(
			String name, boolean outside, Handover handover, String contactName, String contactPhone,
			String deliveryAddress, String subLocation, String placeId, BigDecimal latitude,
			BigDecimal longitude, LocalTime guestsEatAt, Integer travelMinutes, String travelSource) {

		static Event none() {
			return new Event(null, false, null, null, null, null, null, null, null, null, null, null, null);
		}

		/**
		 * A place somebody chose from the list: a place id <em>with</em> the pin that came back with
		 * it, and neither axis the zero an empty box serialises to (T-044).
		 */
		boolean isPlaced() {
			return placeId != null && isCoordinate(latitude) && isCoordinate(longitude);
		}
	}

	/**
	 * Where a delivery address is, and whether anybody could say.
	 *
	 * @param warning {@code DELIVERY_ADDRESS_NOT_FOUND} where a map service looked and found nothing,
	 *                and null where there was no map service to look.
	 */
	private record Located(
			BigDecimal latitude, BigDecimal longitude, OffsetDateTime at, ErrorCode warning) {

		static final Located NOWHERE = new Located(null, null, null, null);
	}

	private static Instant instant(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
		OffsetDateTime odt = rs.getObject(col, OffsetDateTime.class);
		return odt == null ? null : odt.toInstant();
	}
}
