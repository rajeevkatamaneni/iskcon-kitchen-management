package org.iskcon.kms.meal;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
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
import org.iskcon.kms.geo.GeocodingProvider;
import org.iskcon.kms.geo.PlaceSuggestionProvider;
import org.iskcon.kms.geo.TravelTimeProvider;
import org.iskcon.kms.occasion.OccasionService;
import org.iskcon.kms.occasion.ResolvedOccasion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Meal planning (E4-S4, redesigned by E4-S7). A plan is a recipe, a target quantity, a kind of meal,
 * and the time it must be ready.
 *
 * <p>What a planner is <em>not</em> asked is what sort of day it is: weekend follows from the date
 * and festival from the calendar. The day type is derived here and stored, because a festival still
 * explains a large serving count a year later — but nobody chooses it. CATERING was the fourth value
 * and is gone (E4-S15): catering was never a kind of day, and a temple that caters now plans an
 * event that is going outside.
 *
 * <p><strong>Three main meals, and everything else is an event.</strong> Breakfast, Lunch and Dinner
 * are cooked 365 days a year for a small army and work from a head count. An event — a Bhajan
 * Prasadam, a Saturday reading for the children, food going to a school — is quantified by how much
 * to make, and its head count is context: thirty laddus and some chiwda is a real thing a temple
 * cooks, and the temple's own FHC Sabjis sheet plans bulk distribution in gross kilograms per dish
 * with no head count anywhere on it. So an event saves with an amount and nobody counted; a
 * Breakfast still does not.
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

	private static final Logger log = LoggerFactory.getLogger(MealPlanService.class);

	private final JdbcTemplate jdbc;
	private final AuditService auditService;
	private final OccasionService occasionService;
	private final CalendarService calendarService;
	private final MealKindService mealKindService;
	private final EkadashiPolicy ekadashiPolicy;
	private final GeocodingProvider geocodingProvider;
	private final TravelTimeProvider travelTimeProvider;
	private final PlaceSuggestionProvider placeSuggestionProvider;

	/**
	 * How long a geocoded coordinate may be kept before it is looked up again (E4-S16 D4). Maps
	 * Platform ToS §6.3.1 permits thirty days; §6.3.2's indefinite permission is deliberately not
	 * relied on, because it requires the cache to be isolated to one end user and ours is read by
	 * everyone at the temple.
	 */
	private static final int GEOCODE_LIFE_DAYS = 30;

	/**
	 * How many event names the autocomplete offers (E4-S15 D9).
	 *
	 * <p>Ten, because this is read while somebody is typing. A longer list is not a better list: past
	 * about ten the reader stops scanning it and goes back to typing the name out, which is the cost
	 * this exists to remove.
	 */
	private static final int MAX_EVENT_SUGGESTIONS = 10;

	/**
	 * How long before the guests eat we assume the vehicle leaves, when asking what the traffic will
	 * be like at that hour. A traffic-aware route needs a departure time before it can tell you how
	 * long the drive is, and the drive is what we are asking for — so something has to be assumed to
	 * break the circle. An hour is close enough for a traffic model that reasons in bands of hours,
	 * and being wrong about it moves the estimate by minutes, not by the answer.
	 */
	private static final Duration ASSUMED_DEPARTURE_LEAD = Duration.ofHours(1);

	// No consumption service here any more: drawing stock belongs to recording a whole meal, which
	// ServedMealService owns. This class plans; it no longer cooks.
	public MealPlanService(
			JdbcTemplate jdbc, AuditService auditService, OccasionService occasionService,
			CalendarService calendarService,
			MealKindService mealKindService, EkadashiPolicy ekadashiPolicy,
			GeocodingProvider geocodingProvider, TravelTimeProvider travelTimeProvider,
			PlaceSuggestionProvider placeSuggestionProvider) {
		this.placeSuggestionProvider = placeSuggestionProvider;
		this.jdbc = jdbc;
		this.auditService = auditService;
		this.occasionService = occasionService;
		this.calendarService = calendarService;
		this.mealKindService = mealKindService;
		this.ekadashiPolicy = ekadashiPolicy;
		this.geocodingProvider = geocodingProvider;
		this.travelTimeProvider = travelTimeProvider;
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

	// ---- Reusing a plan (2026-09-05) -------------------------------------

	/**
	 * What is in a source window, and what reusing it would do — without writing anything.
	 *
	 * <p>The same walk the commit performs, which is the point: a preview computed a second way is a
	 * preview that can disagree with the thing it previews. {@link #reusePlan} calls this and then
	 * writes what it said.
	 */
	@Transactional(readOnly = true)
	public ReusePlanPreview previewReuse(ReusePlanRequest request) {
		LocalDate sourceEnd = request.sourceStart().plusDays(request.days() - 1L);
		List<MealPlanView> source = list(request.sourceStart(), sourceEnd, null, null).stream()
				.filter(m -> m.status() != MealStatus.CANCELLED)
				.toList();
		if (source.isEmpty()) {
			return new ReusePlanPreview(true, List.of(), List.of(), List.of(), List.of(), List.of(),
					new ReusePlanPreview.Totals(0, 0, 0, 0));
		}

		// What is on offer, and what is not. A kind is offered when it is not an event and does not
		// take its occasion from the calendar; an event is offered by name with the count that says
		// how routine it is.
		Map<String, MealKindView> kinds = new LinkedHashMap<>();
		List<ReusePlanPreview.KindFound> kindsFound = new ArrayList<>();
		List<ReusePlanPreview.EventFound> eventsFound = new ArrayList<>();
		List<ReusePlanPreview.Excluded> excluded = new ArrayList<>();

		Map<String, List<MealPlanView>> byKind = new LinkedHashMap<>();
		Map<String, List<MealPlanView>> byEvent = new LinkedHashMap<>();
		for (MealPlanView meal : source) {
			MealKindView kind = kinds.computeIfAbsent(meal.mealKind(), mealKindService::require);
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
				byKind.computeIfAbsent(meal.mealKind(), k -> new ArrayList<>()).add(meal);
			}
		}
		byKind.forEach((name, meals) -> kindsFound.add(new ReusePlanPreview.KindFound(
				name, (int) meals.stream().map(MealPlanView::planDate).distinct().count(), meals.size())));
		byEvent.forEach((name, meals) -> eventsFound.add(new ReusePlanPreview.EventFound(
				name,
				(int) meals.stream().map(MealPlanView::planDate).distinct().count(),
				meals.stream().anyMatch(MealPlanView::isOutside),
				meals.stream().map(MealPlanView::planDate).max(LocalDate::compareTo).orElse(null))));

		List<ReusePlanPreview.HeadCount> headCounts = new ArrayList<>();
		byKind.forEach((name, meals) -> {
			MealPlanView largest = meals.stream()
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

		List<ReusePlanPreview.TargetDay> days = new ArrayList<>();
		int meals = 0;
		int daysWritten = 0;
		int daysLeftAlone = 0;
		int notCopied = 0;

		for (int offset = 0; offset < request.days(); offset++) {
			LocalDate from = request.sourceStart().plusDays(offset);
			LocalDate target = request.targetStart().plusDays(offset);

			List<MealPlanView> thatDay = source.stream()
					.filter(m -> m.planDate().equals(from))
					.filter(m -> wanted(m, kinds, wantedKinds, wantedEvents))
					.toList();
			if (thatDay.isEmpty()) {
				continue;
			}
			boolean occupied = list(target, target, null, null).stream()
					.anyMatch(m -> m.status() != MealStatus.CANCELLED);
			if (occupied) {
				daysLeftAlone++;
				days.add(new ReusePlanPreview.TargetDay(target, from, true, null, List.of()));
				continue;
			}

			List<ReusePlanPreview.PlannedMeal> landing = new ArrayList<>();
			String fastName = null;
			for (MealPlanView meal : thatDay) {
				EkadashiCheck check = ekadashiCheck(target, meal.recipeId());
				boolean refused = check.isEkadashi() && !check.compatible();
				if (check.isEkadashi()) {
					// The fast's own name comes from the calendar rather than from the check, which
					// only answers whether a recipe suits it.
					fastName = calendarService.day(target).map(CalendarDayView::ekadashiName).orElse(null);
				}
				landing.add(new ReusePlanPreview.PlannedMeal(
						meal.mealKind(), meal.eventName(), meal.recipeName(), !refused,
						refused ? refusedBecause(meal, check) : null));
				if (refused) {
					notCopied++;
				} else {
					meals++;
				}
			}
			if (landing.stream().anyMatch(ReusePlanPreview.PlannedMeal::copied)) {
				daysWritten++;
			}
			days.add(new ReusePlanPreview.TargetDay(target, from, false, fastName, landing));
		}

		return new ReusePlanPreview(false, kindsFound, eventsFound, excluded, headCounts, days,
				new ReusePlanPreview.Totals(meals, daysWritten, daysLeftAlone, notCopied));
	}

	/**
	 * Writes what {@link #previewReuse} said it would.
	 *
	 * <p>Each meal goes through {@link #create}, never an insert, so every rule that governs a meal
	 * governs a copied one — the head count, the event's own fields, the audit entry. The occasion is
	 * the one thing deliberately not carried, for the reason a feast is not offered at all.
	 */
	@Transactional
	public ReusePlanResult reusePlan(AuthenticatedUser actor, ReusePlanRequest request) {
		ReusePlanPreview preview = previewReuse(request);
		if (preview.sourceWasEmpty()) {
			return new ReusePlanResult(0, 0, 0, 0, true);
		}
		Map<LocalDate, List<MealPlanView>> sourceByDay = list(
				request.sourceStart(), request.sourceStart().plusDays(request.days() - 1L), null, null)
				.stream()
				.filter(m -> m.status() != MealStatus.CANCELLED)
				.collect(Collectors.groupingBy(MealPlanView::planDate));

		int copied = 0;
		for (ReusePlanPreview.TargetDay day : preview.days()) {
			if (day.alreadyPlanned()) {
				continue;
			}
			List<MealPlanView> from = sourceByDay.getOrDefault(day.sourceDate(), List.of());
			for (ReusePlanPreview.PlannedMeal planned : day.meals()) {
				if (!planned.copied()) {
					continue;
				}
				from.stream()
						.filter(m -> m.mealKind().equals(planned.mealKind())
								&& Objects.equals(m.eventName(), planned.eventName())
								&& m.recipeName().equals(planned.recipeName()))
						.findFirst()
						.ifPresent(meal -> create(actor, copyOf(meal, day.targetDate())));
				copied++;
			}
		}
		ReusePlanPreview.Totals t = preview.totals();
		return new ReusePlanResult(copied, t.daysWritten(), t.daysLeftAlone(), t.notCopied(), false);
	}

	/** The name an event is grouped under, or the kind's own name where it has none. */
	private static String nameOf(MealPlanView meal) {
		return meal.eventName() == null || meal.eventName().isBlank()
				? meal.mealKind() : meal.eventName();
	}

	private static boolean wanted(
			MealPlanView meal, Map<String, MealKindView> kinds,
			Set<String> wantedKinds, Set<String> wantedEvents) {

		MealKindView kind = kinds.get(meal.mealKind());
		if (kind == null || kind.needsOccasion()) {
			return false;
		}
		return kind.isEvent() ? wantedEvents.contains(nameOf(meal)) : wantedKinds.contains(meal.mealKind());
	}

	/** Why a meal was left behind, in the words the screen prints. */
	private static String refusedBecause(MealPlanView meal, EkadashiCheck check) {
		return check.offendingIngredients().isEmpty()
				? meal.recipeName() + " does not suit the fast on this day."
				: meal.recipeName() + " contains "
						+ String.join(", ", check.offendingIngredients()) + ", which the fast forbids.";
	}

	@Transactional
	public SavedMealPlan create(AuthenticatedUser actor, CreateMealPlanRequest request) {
		MealKindView kind = mealKindService.require(request.mealKind());
		RecipeRef recipe = findRecipe(request.recipeId());

		LocalTime readyBy = resolveReadyBy(kind, request.readyBy());
		Event event = requireEventFields(kind, request);
		requireHeadCount(request.adults(), request.children(), request.seniors(), request.planDate(), kind);
		DayType dayType = deriveDayType(request.planDate());
		String occasionName = resolveOccasionName(kind, dayType, request.planDate(), request.occasionName());
		boolean recordAck = resolveEkadashiAck(request.planDate(), request.recipeId(), request.ekadashiAcknowledged());
		Located located = place(event, null, null, null, null);

		UUID id = UUID.randomUUID();
		jdbc.update(connection -> {
			var ps = connection.prepareStatement("""
					INSERT INTO meal_plans (
						id, tenant_id, plan_date, meal_kind, ready_by, recipe_id, target_yield,
						day_type, occasion_name, status, event_name, is_outside, handover,
						contact_name, contact_phone, delivery_address, delivery_sub_location,
						delivery_place_id, guests_eat_at, travel_minutes, travel_minutes_source,
						delivery_latitude, delivery_longitude, geocoded_at, purpose,
						adults, children, seniors, crew_required, kitchen_notes, server_notes,
						ekadashi_ack_by, ekadashi_ack_at, created_by)
					VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid,
						?, ?, ?, ?, ?, ?, ?, 'PLANNED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
						?, ?, ?, ?, ?, ?, ?, ?, ?)
					""");
			ps.setObject(1, id);
			ps.setObject(2, request.planDate());
			ps.setString(3, kind.name());
			ps.setObject(4, readyBy);
			ps.setObject(5, request.recipeId());
			ps.setBigDecimal(6, request.targetYield());
			ps.setString(7, dayType.name());
			ps.setString(8, occasionName);
			ps.setString(9, event.name());
			ps.setBoolean(10, event.outside());
			ps.setString(11, event.handover() == null ? null : event.handover().name());
			ps.setString(12, event.contactName());
			ps.setString(13, event.contactPhone());
			ps.setString(14, event.deliveryAddress());
			ps.setString(15, event.subLocation());
			ps.setString(16, event.placeId());
			ps.setObject(17, event.guestsEatAt());
			ps.setObject(18, event.travelMinutes(), java.sql.Types.INTEGER);
			ps.setString(19, event.travelSource());
			ps.setBigDecimal(20, located.latitude());
			ps.setBigDecimal(21, located.longitude());
			ps.setObject(22, located.at());
			ps.setString(23, trimToNull(request.purpose()));
			ps.setObject(24, request.adults(), java.sql.Types.INTEGER);
			ps.setObject(25, request.children(), java.sql.Types.INTEGER);
			ps.setObject(26, request.seniors(), java.sql.Types.INTEGER);
			ps.setObject(27, request.crewRequired(), java.sql.Types.INTEGER);
			ps.setString(28, trimToNull(request.kitchenNotes()));
			ps.setString(29, trimToNull(request.serverNotes()));
			ps.setObject(30, recordAck ? actor.getUserId() : null);
			ps.setObject(31, recordAck ? OffsetDateTime.now(java.time.ZoneOffset.UTC) : null);
			ps.setObject(32, actor.getUserId());
			return ps;
		});

		auditService.record(actor, AuditAction.MEAL_PLANNED, AuditEntityType.MEAL_PLAN, id,
				null, snapshot(request.planDate(), kind.name(), readyBy, recipe.name(), dayType), null);
		return new SavedMealPlan(id, located.warning());
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
	public SavedMealPlan update(AuthenticatedUser actor, UUID id, UpdateMealPlanRequest request) {
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
		Event event = requireEventFields(kind, request);
		requireHeadCount(request.adults(), request.children(), request.seniors(), request.planDate(), kind);
		DayType dayType = deriveDayType(request.planDate());
		String occasionName = resolveOccasionName(kind, dayType, request.planDate(), request.occasionName());
		boolean recordAck = resolveEkadashiAck(request.planDate(), request.recipeId(), request.ekadashiAcknowledged());
		Located located = place(event, before.deliveryAddress(),
				before.deliveryLatitude(), before.deliveryLongitude(), before.geocodedAt());

		jdbc.update("""
				UPDATE meal_plans
				SET plan_date = ?, meal_kind = ?, ready_by = ?, recipe_id = ?, target_yield = ?,
					day_type = ?, occasion_name = ?, event_name = ?, is_outside = ?, handover = ?,
					contact_name = ?, contact_phone = ?, delivery_address = ?,
					delivery_sub_location = ?, delivery_place_id = ?, guests_eat_at = ?,
					travel_minutes = ?, travel_minutes_source = ?,
					delivery_latitude = ?, delivery_longitude = ?, geocoded_at = ?,
					purpose = ?, adults = ?, children = ?, seniors = ?, crew_required = ?,
					kitchen_notes = ?, server_notes = ?,
					ekadashi_ack_by = ?, ekadashi_ack_at = ?, updated_at = now()
				WHERE id = ?
				""",
				request.planDate(), kind.name(), readyBy, request.recipeId(), request.targetYield(),
				dayType.name(), occasionName, event.name(), event.outside(),
				event.handover() == null ? null : event.handover().name(),
				event.contactName(), event.contactPhone(), event.deliveryAddress(),
				event.subLocation(), event.placeId(), event.guestsEatAt(),
				event.travelMinutes(), event.travelSource(),
				located.latitude(), located.longitude(), located.at(),
				trimToNull(request.purpose()),
				request.adults(), request.children(), request.seniors(), request.crewRequired(),
				trimToNull(request.kitchenNotes()), trimToNull(request.serverNotes()),
				recordAck ? actor.getUserId() : null,
				recordAck ? OffsetDateTime.now(java.time.ZoneOffset.UTC) : null,
				id);

		auditService.record(actor, AuditAction.MEAL_PLAN_UPDATED, AuditEntityType.MEAL_PLAN, id,
				snapshot(before.planDate(), before.mealKind(), before.readyBy(), recipe.name(), before.dayType()),
				snapshot(request.planDate(), kind.name(), readyBy, recipe.name(), dayType), null);
		return new SavedMealPlan(id, located.warning());
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
		// The event's name is part of which meal this is (V89): without it, recording the morning
		// children's reading would lock the evening Bhajan Prasadam out of being edited, because
		// both are an Event on that Saturday. Compared the way the unique index folds it, so the
		// answer here and the row ServedMealService finds are always the same row.
		Integer recorded = jdbc.queryForObject("""
				SELECT count(*) FROM meal_services
				WHERE plan_date = ? AND meal_kind = ? AND recorded_at IS NOT NULL
				  AND lower(COALESCE(event_name, '')) = lower(COALESCE(?::text, ''))
				""", Integer.class, row.planDate(), row.mealKind(), row.eventName());
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
	 * What an event needs beyond a recipe, asked in a chain and never all at once (E4-S15 D6).
	 *
	 * <p>An event has a <strong>name</strong>. That is the whole point of splitting events out of the
	 * main meals: it is what makes the Saturday reading a thing the kitchen can see, cost and record
	 * a year later, rather than a rounding error inside breakfast.
	 *
	 * <p>An event that is <strong>going outside</strong> has somebody to contact, name and phone
	 * both — a contact you cannot ring is not a contact. A <strong>delivered</strong> one also has an
	 * address and the time the guests eat, because those are what E4-S16 works backwards from.
	 *
	 * <p>And an <strong>in-house</strong> event stops at its name. Nothing else is asked and nothing
	 * else is kept: a Bhajan Prasadam in the temple hall has no client, no venue and no handover, and
	 * a form asking for one would be asking a question with no answer — which gets either a made-up
	 * answer or a blocked save. That is why the three old kind flags collapsed into one (D5) rather
	 * than {@code needsClient} being set true for Event.
	 *
	 * <p>Breakfast, Lunch and Dinner reach none of this. A kind that is not an event has every one of
	 * these fields dropped on the way in, so a caller sending an address on a Lunch stores nothing —
	 * the alternative, refusing it, would make the three main meals answerable for a shape that has
	 * nothing to do with them.
	 */
	private Event requireEventFields(MealKindView kind, CreateMealPlanRequest r) {
		return requireEventFields(kind, r.eventName(), r.isOutside(), r.handover(),
				r.contactName(), r.contactPhone(), r.deliveryAddress(), r.deliverySubLocation(),
				r.deliveryPlaceId(), r.deliveryLatitude(), r.deliveryLongitude(),
				r.guestsEatAt(), r.travelMinutes(), r.travelMinutesManual(), r.readyBy());
	}

	private Event requireEventFields(MealKindView kind, UpdateMealPlanRequest r) {
		return requireEventFields(kind, r.eventName(), r.isOutside(), r.handover(),
				r.contactName(), r.contactPhone(), r.deliveryAddress(), r.deliverySubLocation(),
				r.deliveryPlaceId(), r.deliveryLatitude(), r.deliveryLongitude(),
				r.guestsEatAt(), r.travelMinutes(), r.travelMinutesManual(), r.readyBy());
	}

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
			// Pickup, or an outside plan that predates the question — V88 carried the old catering and
			// outside-event rows across with no handover, because nobody was ever asked. Neither needs
			// an address: somebody is coming to collect it, or somebody already did.
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
	 * How many people this meal is for. A preparation is never planned without one.
	 *
	 * <p>Every figure the plan is worth is derived from this number: how much of each preparation to
	 * make, what the day's food costs, what a serving of it costs, how many plates the job card says.
	 * The composer used to open on 100 adults, so a meal nobody had counted was still costed, scaled
	 * and rostered against a number the application had invented. The planner picks the number; the
	 * application does not guess it, and the endpoint is where that is true rather than the screen.
	 *
	 * <p>Absent and zero are refused alike. A request that simply omits the three counters is the
	 * same meal with the same hole in it, and a guard a caller escapes by leaving a field out is not
	 * a guard. What this does <em>not</em> do is reach backwards: rows already carrying no head count
	 * stay exactly as they are, and the cost-per-serving report still totals them without dividing by
	 * them and says how many it left out.
	 *
	 * <p>Checked as three counters rather than as the weighted total. Children count 0.6 of a portion
	 * and seniors 0.8, so a hall of one child weighs 0.6 — a real head count that no arithmetic here
	 * is entitled to round away to nothing.
	 */
	private static void requireHeadCount(
			Integer adults, Integer children, Integer seniors, LocalDate date, MealKindView kind) {

		if (kind.isEvent()) {
			// An event is quantified by how much to make, and its head count is context (E4-S15 D2).
			// Thirty laddus and some chiwda is a real thing a temple cooks, and the temple's own
			// FHC Sabjis sheet — its crib for bulk distribution — is kept in gross kilograms per dish
			// with no head count anywhere on it. Refusing an event for want of one would refuse a
			// practice the temple already has. The exemption is exactly this: it does not loosen for
			// the three main meals, and it does not let anything invent a number nobody typed.
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

	/**
	 * What kind of day this meal is cooked on — derived, never asked (E4-S7). The calendar decides,
	 * and a festival outranks a weekend because it is what explains the quantity.
	 *
	 * <p>It no longer takes the kind. Food cooked for an outside client used to be stamped CATERING
	 * whatever the date, which made a fact about the meal masquerade as a fact about the day: a
	 * temple catering on Janmashtami lost the festival. E4-S15 removed the value, and an event going
	 * outside now says so on the meal, where it belongs.
	 */
	private DayType deriveDayType(LocalDate date) {
		return dayContext(date).suggestedDayType();
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

	// ---- Going outside ---------------------------------------------------

	/**
	 * Everything this temple has undertaken to send out of the building, soonest first (E4-S15 D4).
	 *
	 * <p>Future only, cancelled ones dropped, one row per event rather than one per dish. The idea
	 * behind the *Upcoming catering* table that was designed and never built was right — nobody
	 * should discover a booking on the morning — and this is that idea keyed off <em>is this going
	 * outside</em> instead of <em>is this catering</em>, so the school delivery and the community
	 * programme are on it too.
	 *
	 * <p>Today is today <em>at the temple</em>. A list of what is coming up, read in Bengaluru at
	 * half past six in the morning, must not have dropped this morning's delivery because the server
	 * is still on yesterday in UTC.
	 */
	@Transactional(readOnly = true)
	public List<OutsideCommitment> outsideCommitments() {
		return jdbc.query("""
				SELECT mp.plan_date, mp.event_name, mp.meal_kind, mp.handover, mp.contact_name,
					   mp.contact_phone, mp.delivery_address, mp.guests_eat_at,
					   min(mp.ready_by) AS ready_by, count(*) AS preparations
				FROM meal_plans mp
				WHERE mp.is_outside
				  AND mp.status <> 'CANCELLED'
				  AND mp.plan_date >= ?
				GROUP BY mp.plan_date, mp.event_name, mp.meal_kind, mp.handover, mp.contact_name,
						 mp.contact_phone, mp.delivery_address, mp.guests_eat_at
				ORDER BY mp.plan_date, min(mp.ready_by), mp.event_name
				""",
				(rs, n) -> new OutsideCommitment(
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
				LocalDate.now(templeZone()));
	}

	/**
	 * The event names this temple has used before, newest first, with what each was last time
	 * (E4-S15 D9).
	 *
	 * <p>Ten at most, because this is a list somebody reads while typing and not a report. Distinct
	 * by name ignoring case — a temple that typed "Bhajan Prasadam" once and "bhajan prasadam" once
	 * has used one name twice, and offering both back would teach it to keep doing that. The newest
	 * spelling wins, along with the newest of everything else on the row, so choosing a suggestion
	 * carries the previous event's contact forward rather than the first one ever entered.
	 *
	 * <p>A cancelled plan is not a name the temple uses. It is a plan somebody called off, and
	 * suggesting it back would put a cancelled booking's contact into a new one.
	 *
	 * <p><strong>Newest is by plan date, and a future date is newer than today.</strong> An event
	 * already planned for next month is the freshest thing the temple has said about that event, and
	 * its contact is the one somebody would ring. Ordering by when the row was typed instead would
	 * put a booking entered in January below one entered yesterday for a party that happened last
	 * year.
	 *
	 * <p>Matched with {@code starts_with} rather than {@code LIKE}: a prefix typed into a search box
	 * may contain {@code %} or {@code _}, and those are ordinary characters in an event's name.
	 * Nothing here has to escape anything, and there is no pattern for a caller to smuggle in.
	 */
	@Transactional(readOnly = true)
	public List<EventSuggestion> eventNames(String prefix) {
		String q = prefix == null ? "" : prefix.trim();
		return jdbc.query("""
				SELECT DISTINCT ON (lower(mp.event_name))
					   mp.event_name, mp.is_outside, mp.handover, mp.contact_name,
					   mp.contact_phone, mp.delivery_address, mp.plan_date, mp.created_at
				FROM meal_plans mp
				WHERE mp.event_name IS NOT NULL
				  AND mp.status <> 'CANCELLED'
				  AND starts_with(lower(mp.event_name), lower(?))
				ORDER BY lower(mp.event_name), mp.plan_date DESC, mp.created_at DESC
				""", SUGGESTION_MAPPER, q).stream()
				// DISTINCT ON has to sort by the name it is distinct on, so the ordering the caller
				// actually wants — most recently used first — is applied to the result of that.
				// Postgres would need a second SELECT wrapped round this one to do it; ten rows do
				// not earn one.
				.sorted(Comparator
						.comparing(Suggestion::planDate, Comparator.reverseOrder())
						.thenComparing(Suggestion::createdAt, Comparator.reverseOrder())
						.thenComparing(s -> s.suggestion().eventName(), String.CASE_INSENSITIVE_ORDER))
				.limit(MAX_EVENT_SUGGESTIONS)
				.map(Suggestion::suggestion)
				.toList();
	}

	/**
	 * Repeats an event forward for a number of weeks (E4-S15 D8).
	 *
	 * <p><strong>Copies, not a series.</strong> Each one is a plan in its own right: editing the
	 * third does not touch the first, cancelling the fifth does not offer *this one or all of them?*,
	 * and there is no rule anywhere that has to be reasoned about later. A true recurrence with
	 * per-occurrence exceptions was considered and deferred — it is a feature that grows teeth, and
	 * the problem in front of us is somebody not wanting to type the same Saturday reading fifty-two
	 * times.
	 *
	 * <p>Every copy goes through {@link #create}, so every rule that governs an event still governs a
	 * copied one — which matters most for the rule that depends on the date rather than the meal: a
	 * week whose recipe does not suit an Ekadashi falling there is skipped whole and counted, never
	 * acknowledged on the planner's behalf. Nobody is looking at that meal to say it is all right.
	 */
	@Transactional
	public RepeatEventResult repeatForward(AuthenticatedUser actor, UUID id, int weeks) {
		if (weeks < 1 || weeks > 52) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
					Map.of("field", "weeks", "weeks", weeks));
		}
		MealPlanView source = get(id);
		// Every preparation of that event on that day, not just the dish that was clicked. Six copies
		// of a two-dish event is two dishes on each of six days; anything else is a copy of half a
		// meal.
		List<MealPlanView> dishes = list(source.planDate(), source.planDate(), null, null).stream()
				.filter(m -> m.status() != MealStatus.CANCELLED)
				.filter(m -> m.mealKind().equalsIgnoreCase(source.mealKind()))
				.filter(m -> sameEvent(m.eventName(), source.eventName()))
				.toList();

		int copied = 0;
		int weeksCopied = 0;
		int refusedOnFast = 0;
		for (int week = 1; week <= weeks; week++) {
			LocalDate target = source.planDate().plusWeeks(week);
			boolean fasts = dishes.stream().anyMatch(d -> {
				EkadashiCheck check = ekadashiCheck(target, d.recipeId());
				return check.isEkadashi() && !check.compatible();
			});
			if (fasts) {
				refusedOnFast++;
				continue;
			}
			for (MealPlanView dish : dishes) {
				create(actor, copyOf(dish, target));
				copied++;
			}
			weeksCopied++;
		}
		return new RepeatEventResult(copied, weeksCopied, refusedOnFast);
	}

	private static boolean sameEvent(String a, String b) {
		return a == null ? b == null : a.equalsIgnoreCase(b);
	}

	/**
	 * One planned dish, ready to be planned again on another date.
	 *
	 * <p>The occasion is deliberately not carried across: a feast copied onto an ordinary Wednesday
	 * is not last week's festival, and the derivation on the target date is the only thing that can
	 * say what it is. Everything else carries, the event's own fields included — a repeated Saturday
	 * reading is the same reading, for the same people, at the same place.
	 */
	private static CreateMealPlanRequest copyOf(MealPlanView meal, LocalDate target) {
		return new CreateMealPlanRequest(
				target, meal.mealKind(), meal.recipeId(), meal.targetYield(), meal.readyBy(),
				meal.eventName(), meal.isOutside(), meal.handover(), meal.contactName(),
				meal.contactPhone(), meal.deliveryAddress(), meal.deliverySubLocation(),
				meal.deliveryPlaceId(),
				// No coordinates on a copy: MealPlanView does not carry them. The place id does, and
				// the save resolves the pin from that rather than geocoding the address text again.
				null, null,
				// The travel figure carries with its source intact. A copy of the same drive to the
				// same gate takes about as long, and somebody's manual correction must survive the
				// copy or they would have to make it again every week.
				meal.travelMinutes(), "MANUAL".equals(meal.travelMinutesSource()),
				meal.guestsEatAt(),
				meal.purpose(), null,
				meal.adults(), meal.children(), meal.seniors(), meal.crewRequired(),
				meal.kitchenNotes(), meal.serverNotes(), false);
	}

	// ---- Getting there (E4-S16) ------------------------------------------

	/**
	 * When to leave the temple for this delivery, and how long the drive is expected to take.
	 *
	 * <p>Computed backwards from the time the guests eat, and from the <em>pessimistic</em> end of
	 * the range: arriving early with the food is an inconvenience, arriving after the guests have sat
	 * down is the thing this exists to prevent.
	 *
	 * <p>Every way this can fail to produce a number is an answer rather than an error. Not a
	 * delivery, no serving time, no map service, an address nobody could place, no route — each is a
	 * quiet line on the screen. An estimate is never a reason a plan is refused (E4-S16 D7): a temple
	 * that wants to send food two hours away may.
	 *
	 * <p><strong>Nothing about the drive is stored.</strong> It is recomputed every time it is asked
	 * for, which is both the licence (D4) and the truth — Friday's traffic is not Tuesday's.
	 */
	@Transactional
	public TravelEstimate travelEstimate(UUID id) {
		MealPlanView plan = get(id);
		if (plan.handover() != Handover.DELIVERY) {
			return TravelEstimate.unavailable("NOT_A_DELIVERY");
		}
		if (plan.guestsEatAt() == null) {
			return TravelEstimate.unavailable("NO_SERVING_TIME");
		}
		if (!travelTimeProvider.configured()) {
			return TravelEstimate.unavailable("NO_MAP_SERVICE");
		}
		GeocodingProvider.Coordinates destination = deliveryCoordinates(id);
		GeocodingProvider.Coordinates origin = templeCoordinates();
		if (destination == null || origin == null) {
			return TravelEstimate.unavailable("ADDRESS_NOT_FOUND");
		}

		Instant sitDown = LocalDateTime.of(plan.planDate(), plan.guestsEatAt())
				.atZone(templeZone()).toInstant();
		Optional<TravelTimeProvider.TravelTime> drive;
		try {
			drive = travelTimeProvider.drive(
					origin, destination, sitDown.minus(ASSUMED_DEPARTURE_LEAD));
		} catch (RuntimeException e) {
			// The port's contract is that it never raises, and both implementations honour it. This
			// is the belt to that pair of braces: whatever a future provider does on its worst day,
			// the answer here is a quiet line on a screen and never a page that will not load.
			log.warn("The routing provider raised ({}); the planner shows no estimate", e.toString());
			drive = Optional.empty();
		}
		if (drive.isEmpty()) {
			return TravelEstimate.unavailable("NO_ROUTE");
		}

		int optimistic = minutes(drive.get().optimistic());
		int pessimistic = minutes(drive.get().pessimistic());
		return new TravelEstimate(
				true, plan.guestsEatAt().minusMinutes(pessimistic),
				optimistic, pessimistic, plan.guestsEatAt(), null);
	}

	/**
	 * Refuses a delivery whose van is still on the road when the guests sit down (KMS-4994).
	 *
	 * <p>Rajeev, 2026-09-05: <em>"People Sit to eat time MUST be = Ready by time + transit time at a
	 * minumum. That is impractical and impossible given the loading and unlaoding and setup time."</em>
	 * Both halves of that are honoured, and they are different rules. This one is the floor and it is
	 * enforced: ready-by plus the drive must land at or before the serving time. Everything above the
	 * floor — the loading, the unloading, the setting up — is time nobody here can measure, so the
	 * composer warns about it and neither of them refuses a plan over it.
	 *
	 * <p>The composer checks this too, so a planner is stopped before typing eight preparations. This
	 * is the check that matters: a screen is not a guard.
	 *
	 * <p>It only fires when the temple has supplied all three figures. A delivery with no travel
	 * allowance yet — the address was typed rather than picked, or the map service was quiet — has
	 * nothing to check, and inventing a drive in order to refuse a plan would be worse than silence.
	 */
	private static void requireItCanArriveInTime(
			LocalTime readyBy, LocalTime guestsEatAt, Integer travelMinutes) {

		if (readyBy == null || guestsEatAt == null || travelMinutes == null) {
			return;
		}
		// A serving time at or before the ready-by is a meal running past midnight, or a half-typed
		// form. Comparing across a wrap would refuse plans on arithmetic that does not understand the
		// clock, which is a bug wearing a validation's clothes.
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
	 * The same estimate, for a delivery nobody has saved yet.
	 *
	 * <p>What the meal composer asks while somebody is still typing. It takes a place — either the id
	 * of one they picked, or the coordinates behind it — rather than a plan id, because in a form
	 * there is no plan to have an id.
	 *
	 * <p>Every unavailable answer the saved version can give, this can give too, with one addition:
	 * an address that was typed rather than picked has no coordinates and no place id, and comes back
	 * {@code ADDRESS_NOT_FOUND}. That is the honest answer — nobody looked, because there was nothing
	 * to look up.
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

		Instant sitDown = LocalDateTime.of(planDate, eatAt).atZone(templeZone()).toInstant();
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
	 * Google's estimate for this delivery right now, in minutes, stored onto the plan.
	 *
	 * <p>Called when a job card is printed, and only for a plan whose figure nobody has edited — the
	 * job card decides that, because it is the one that knows a person's correction must not be
	 * overwritten on the sheet a driver is about to act on (V93).
	 *
	 * <p>Returns null and changes nothing whenever an estimate cannot be had: no map service, an
	 * address nobody could place, a service having a bad minute. The card then prints whatever figure
	 * was already there, which is the last one anybody had, and that is a better answer on paper than
	 * a blank.
	 */
	@Transactional
	public Integer refreshTravelEstimate(UUID id) {
		TravelEstimate estimate = travelEstimate(id);
		if (!estimate.available() || estimate.pessimisticMinutes() == null) {
			return null;
		}
		// The pessimistic end, for the reason the leave-by has always used it: arriving early with
		// the food is an inconvenience, arriving after the guests have sat down is the failure.
		int minutes = estimate.pessimisticMinutes();
		jdbc.update("""
				UPDATE meal_plans
				SET travel_minutes = ?, travel_minutes_source = 'ESTIMATED', updated_at = now()
				WHERE id = ? AND (travel_minutes_source IS NULL OR travel_minutes_source = 'ESTIMATED')
				""", minutes, id);
		return minutes;
	}

	/**
	 * Where this delivery is going, geocoding it if the coordinates are missing or out of licence.
	 *
	 * <p>Public because the job card needs it for the map on the delivery sheet, and re-deriving it
	 * there would be a second answer to a question this class already answers.
	 */
	@Transactional
	public GeocodingProvider.Coordinates deliveryCoordinatesFor(UUID id) {
		return deliveryCoordinates(id);
	}

	/** Rounded up. Half a minute of slack is worth having and no driver counts seconds. */
	private static int minutes(Duration duration) {
		return (int) ((duration.getSeconds() + 59) / 60);
	}

	/**
	 * Where this delivery is going, looking the address up again if what we hold has expired.
	 *
	 * <p>The thirty-day life is the licence (E4-S16 D4) and it is enforced here as well as on save,
	 * because a plan made in March and opened in June has coordinates nobody is entitled to reuse —
	 * and a street may have been renamed in between.
	 */
	private GeocodingProvider.Coordinates deliveryCoordinates(UUID id) {
		MealPlanRow row = findRow(id).orElseThrow(() -> notFound(id));
		if (fresh(row.deliveryLatitude(), row.deliveryLongitude(), row.geocodedAt())) {
			return new GeocodingProvider.Coordinates(
					row.deliveryLatitude().doubleValue(), row.deliveryLongitude().doubleValue());
		}
		Located located = geocode(row.deliveryAddress());
		jdbc.update("""
				UPDATE meal_plans
				SET delivery_latitude = ?, delivery_longitude = ?, geocoded_at = ?
				WHERE id = ?
				""", located.latitude(), located.longitude(), located.at(), id);
		if (located.latitude() == null) {
			return null;
		}
		return new GeocodingProvider.Coordinates(
				located.latitude().doubleValue(), located.longitude().doubleValue());
	}

	/**
	 * Where a delivery address is, on the way into the database.
	 *
	 * <p>Looked up only when it has to be: an address that has not changed and was placed inside the
	 * last thirty days keeps the coordinates it has. Editing the head count on a delivery is not a
	 * reason to spend a geocoding request, and the daily quota is fifty.
	 */
	/**
	 * Where this event is going, preferring the pin somebody actually chose.
	 *
	 * <p><strong>A picked place is never geocoded.</strong> Found by driving the live app on
	 * 2026-09-05: the composer offered "Mantri Serenity", the planner chose it, the form showed a
	 * fourteen-minute drive from its coordinates — and then the save discarded them and asked
	 * OpenStreetMap to find the address text from scratch, which failed, so the meal came back
	 * warning KMS-4993 and carrying no pin at all. Two answers to one question, a second apart, on
	 * one screen. The coordinates come with the place id and are used as given.
	 */
	private Located place(
			Event event, String previousAddress, BigDecimal latitude, BigDecimal longitude,
			OffsetDateTime geocodedAt) {

		if (event.isPlaced()) {
			return new Located(event.latitude(), event.longitude(), OffsetDateTime.now(), null);
		}
		// A place id with no coordinates beside it — a repeated event, or a client that sent only the
		// id. Ask Places rather than the geocoder: the id is exactly the question Places can answer,
		// and the address text is the question that just failed.
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
			// Same belt. A map service having a bad day must never cost somebody the meal plan they
			// have just typed — the save goes through and the estimate is simply absent.
			log.warn("The geocoder raised ({}); the address is stored unplaced", e.toString());
			return Located.NOWHERE;
		}
		if (found.isEmpty()) {
			// Reported only when somebody actually looked. With no map service configured this is
			// silence, because the planner has done nothing wrong and there is nothing they could do
			// about it (E4-S16, UAT-086 step 50).
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
		return latitude != null && longitude != null && at != null
				&& at.toInstant().isAfter(Instant.now().minus(Duration.ofDays(GEOCODE_LIFE_DAYS)));
	}

	/**
	 * The temple's own coordinates — the origin of every delivery. They cost nothing to have: every
	 * tenant already carries them, because the Vaishnava calendar cannot compute a tithi without
	 * knowing where the temple is.
	 */
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

	private ZoneId templeZone() {
		String zone = jdbc.query("""
				SELECT timezone FROM tenants
				WHERE id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""", (rs, n) -> rs.getString("timezone")).stream().findFirst().orElse(null);
		try {
			return zone == null ? ZoneId.of("Asia/Kolkata") : ZoneId.of(zone);
		} catch (RuntimeException e) {
			return ZoneId.of("Asia/Kolkata");
		}
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
				SELECT id, plan_date, meal_kind, event_name, ready_by, recipe_id, target_yield,
					   day_type, status, delivery_address, delivery_latitude, delivery_longitude,
					   geocoded_at
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

	private record MealPlanRow(
			UUID id, LocalDate planDate, String mealKind, String eventName, LocalTime readyBy,
			UUID recipeId, BigDecimal targetYield, DayType dayType, MealStatus status,
			String deliveryAddress, BigDecimal deliveryLatitude, BigDecimal deliveryLongitude,
			OffsetDateTime geocodedAt) {
	}

	/**
	 * The event fields as they will actually be stored, after the chain of D6 has been walked.
	 *
	 * <p>Everything below the first {@code null} is null: an in-house event keeps no contact, and a
	 * pickup keeps no address. That is not tidiness — it is what stops the day a pickup is switched
	 * from a delivery leaving a stale address behind for somebody to drive to.
	 */
	private record Event(
			String name, boolean outside, Handover handover, String contactName, String contactPhone,
			String deliveryAddress, String subLocation, String placeId, BigDecimal latitude,
			BigDecimal longitude, LocalTime guestsEatAt, Integer travelMinutes, String travelSource) {

		static Event none() {
			return new Event(null, false, null, null, null, null, null, null, null, null, null, null, null);
		}

		/** A place somebody chose from the list, so there is nothing left to look up. */
		boolean isPlaced() {
			return latitude != null && longitude != null;
		}
	}

	/**
	 * Where a delivery address is, and whether anybody could say.
	 *
	 * @param warning {@code DELIVERY_ADDRESS_NOT_FOUND} where a map service looked and found nothing,
	 *                and null where there was no map service to look — telling somebody an address
	 *                could not be found when nobody looked would be a lie, and one they would waste
	 *                an afternoon on.
	 */
	private record Located(
			BigDecimal latitude, BigDecimal longitude, OffsetDateTime at, ErrorCode warning) {

		static final Located NOWHERE = new Located(null, null, null, null);
	}

	private static final String SELECT = """
			SELECT mp.id, mp.plan_date, mp.meal_kind, mp.ready_by, mp.recipe_id, r.name AS recipe_name,
			       r.base_yield_unit AS target_yield_unit,
				   mp.target_yield, mp.day_type, mp.occasion_name, mp.status, mp.event_name,
				   mp.is_outside, mp.handover, mp.contact_name, mp.contact_phone,
				   mp.delivery_address, mp.delivery_sub_location, mp.delivery_place_id,
				   mp.guests_eat_at, mp.travel_minutes, mp.travel_minutes_source,
				   mp.purpose, mp.adults, mp.children, mp.seniors,
				   mp.crew_required, mp.kitchen_notes, mp.server_notes,
				   mp.actual_servings, mp.consumed_quantity,
				   mp.not_made,
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
			rs.getString("target_yield_unit"),
			DayType.valueOf(rs.getString("day_type")),
			rs.getString("occasion_name"),
			MealStatus.valueOf(rs.getString("status")),
			rs.getString("event_name"),
			rs.getBoolean("is_outside"),
			rs.getString("handover") == null ? null : Handover.valueOf(rs.getString("handover")),
			rs.getString("contact_name"),
			rs.getString("contact_phone"),
			rs.getString("delivery_address"),
			rs.getString("delivery_sub_location"),
			rs.getString("delivery_place_id"),
			rs.getObject("guests_eat_at", LocalTime.class),
			(Integer) rs.getObject("travel_minutes"),
			rs.getString("travel_minutes_source"),
			rs.getString("purpose"),
			(Integer) rs.getObject("adults"),
			(Integer) rs.getObject("children"),
			(Integer) rs.getObject("seniors"),
			(Integer) rs.getObject("crew_required"),
			rs.getString("kitchen_notes"),
			rs.getString("server_notes"),
			rs.getBigDecimal("actual_servings"),
			rs.getBigDecimal("consumed_quantity"),
			rs.getBoolean("not_made"),
			instant(rs, "cooked_at"),
			instant(rs, "ekadashi_ack_at") != null,
			instant(rs, "created_at"));

	private static final RowMapper<MealPlanRow> ROW_MAPPER = (rs, n) -> new MealPlanRow(
			rs.getObject("id", UUID.class),
			rs.getObject("plan_date", LocalDate.class),
			rs.getString("meal_kind"),
			rs.getString("event_name"),
			rs.getObject("ready_by", LocalTime.class),
			rs.getObject("recipe_id", UUID.class),
			rs.getBigDecimal("target_yield"),
			DayType.valueOf(rs.getString("day_type")),
			MealStatus.valueOf(rs.getString("status")),
			rs.getString("delivery_address"),
			rs.getBigDecimal("delivery_latitude"),
			rs.getBigDecimal("delivery_longitude"),
			rs.getObject("geocoded_at", OffsetDateTime.class));
}
