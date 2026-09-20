package org.iskcon.kms.meal;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.iskcon.kms.kitchen.KitchenOrder;
import org.iskcon.kms.staff.MealMoment;
import org.iskcon.kms.staff.WorkforceCount;
import org.iskcon.kms.staff.WorkforceService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * How many people it takes to cook the meal, and how many there are (item 24).
 *
 * <p>The planner carries the number of people needed to execute a meal. At execution time that can
 * be any mix of staff and volunteers — it is satisfied when staff + volunteers reaches the planned
 * number — so there is one counter and not two. The mix does not matter, and splitting it would
 * invent a constraint the temple does not have.
 *
 * <p>The rostered side is asked per meal rather than per day, through {@link WorkforceService}: a
 * person counts towards a meal if their working window covers the time that meal's food must be
 * ready. A volunteer counts the same way — a shift posted 11:00–14:00 still falls to lunch without
 * anybody linking it to one — unless the shift is for a meal, in which case it counts toward that
 * meal and no other (D-14), matched by the meal's id since D-27. The clock was getting this wrong in both directions:
 * a shift 06:00–10:00 to cut vegetables for lunch was landing on breakfast, so lunch was short of
 * hands that were coming and breakfast was credited with hands that were not.
 *
 * <p><strong>Per kitchen since Epic 12.</strong> Rajeev, 2026-09-19: <em>"'People needed' is answered
 * per kitchen; the rostered staff shown are that kitchen's staff."</em> A meal is cooked by one or more
 * kitchens ({@code meal_kitchens}, V150), each with its own People needed, and each is held against
 * the staff whose record is in that kitchen. The meal-level figures are the kitchens' added up, and the
 * meal is short when any kitchen is. The meal's volunteers are counted once, in the main kitchen's
 * section when it is on the meal, else in the first section this person sees: see
 * {@link KitchenCrewView}. People needed is read from {@code meal_kitchens}, never from
 * {@code meals.crew_required}, which V151 drops.
 *
 * <p>Nothing here refuses anything. A meal short of hands takes a quiet warning tone on the screen
 * and saves exactly as it would otherwise — a meal is planned weeks before anybody is rostered, and
 * a planner blocked in August by a roster nobody has written for September would simply stop using
 * the field.
 */
@Service
public class MealCrewService {

	private final JdbcTemplate jdbc;
	private final MealKindService mealKindService;
	private final ServedMealService servedMealService;
	private final WorkforceService workforceService;
	private final KitchenOrder kitchenOrder;

	public MealCrewService(
			JdbcTemplate jdbc, MealKindService mealKindService, ServedMealService servedMealService,
			WorkforceService workforceService, KitchenOrder kitchenOrder) {
		this.jdbc = jdbc;
		this.mealKindService = mealKindService;
		this.servedMealService = servedMealService;
		this.workforceService = workforceService;
		this.kitchenOrder = kitchenOrder;
	}

	/**
	 * One kitchen on one meal, as much as the crew figures need: which kitchen, what it is called,
	 * whether it is the temple's main kitchen, and its People needed.
	 */
	private record Section(UUID kitchenId, String kitchenName, boolean isMain, Integer crewRequired) {
	}

	// ---- The readout ----------------------------------------------------

	/** {@link #crewFor(LocalDate, LocalDate, UUID)} for nobody in particular: the main kitchen first. */
	@Transactional(readOnly = true)
	public List<MealCrewView> crewFor(LocalDate from, LocalDate to) {
		return crewFor(from, to, null);
	}

	/**
	 * Every meal in the range with the hands it needs and the hands it has, in the order the kitchen
	 * works, each with its kitchens in the order this person sees them.
	 *
	 * <p>A meal every dish of which was called off is left out. It is not work the kitchen has to do,
	 * so it is not a crew it has to find either, and a cancelled lunch drawn in warning colours would
	 * be the screen worrying on the temple's behalf about nothing.
	 *
	 * @param viewerUserId the signed-in person, whose own kitchen reads first; null for nobody
	 */
	@Transactional(readOnly = true)
	public List<MealCrewView> crewFor(LocalDate from, LocalDate to, UUID viewerUserId) {
		List<ServedMeal> meals = mealsIn(from, to);
		if (meals.isEmpty()) {
			return List.of();
		}
		Map<UUID, List<Section>> sections = sectionsFor(meals, viewerUserId);
		Map<MealMoment, WorkforceService.RosterAt> rosters = workforceService.rosterAt(momentsOf(meals), null);
		List<MealCrewView> readouts = new ArrayList<>();
		for (ServedMeal meal : meals) {
			List<KitchenCrewView> kitchens = kitchensOf(
					sections.getOrDefault(meal.mealId(), List.of()), rosters.get(momentOf(meal)));
			// Every section of the meal is in this readout, so the two counts are the same here.
			readouts.add(readout(meal, kitchens.size(), kitchens));
		}
		return readouts;
	}

	/**
	 * What approving one person's leave would cost each meal it covers — <em>"Approving this leaves
	 * Lunch on 24 Aug at 4 of 8."</em>
	 *
	 * <p>Only the meals the person is actually standing in for come back. A cook rostered 06:00–14:00
	 * costs breakfast and lunch nothing that dinner also loses, and listing dinner at the figure it
	 * already had would be three lines of noise around the one that matters.
	 *
	 * <p><strong>Only their own kitchen's section (Epic 12).</strong> A person on leave takes a pair of
	 * hands out of the kitchen their staff record is in, and nowhere else. So each line is that one
	 * section — its People needed against its rostered with them away — and its {@code kitchens} holds
	 * that section alone. The meal-level figures are therefore that section's, which keeps the record's
	 * rule (the meal-level figures are its kitchens' added up) and says the true thing: <em>Lunch (Main
	 * kitchen) at 4 of 6</em>, not the whole lunch at 7 of 8, which would hide a Main kitchen four short
	 * behind a Sweets kitchen with hands to spare.
	 *
	 * <p>The counts are the ones the approver would be left with, not the ones they have now. Told,
	 * never enforced: the decision is the approver's and this is only what it is going to cost.
	 */
	@Transactional(readOnly = true)
	public List<MealCrewView> crewIfAway(UUID staffProfileId, LocalDate from, LocalDate to) {
		List<ServedMeal> meals = mealsIn(from, to);
		if (meals.isEmpty()) {
			return List.of();
		}
		List<MealMoment> moments = momentsOf(meals);
		Map<UUID, List<Section>> sections = sectionsFor(meals, null);
		Map<MealMoment, WorkforceService.RosterAt> asItStands = workforceService.rosterAt(moments, null);
		Map<MealMoment, WorkforceService.RosterAt> withoutThem = workforceService.rosterAt(moments, staffProfileId);

		List<MealCrewView> affected = new ArrayList<>();
		for (ServedMeal meal : meals) {
			MealMoment moment = momentOf(meal);
			List<Section> onMeal = sections.getOrDefault(meal.mealId(), List.of());
			List<KitchenCrewView> before = kitchensOf(onMeal, asItStands.get(moment));
			List<KitchenCrewView> after = kitchensOf(onMeal, withoutThem.get(moment));
			for (int i = 0; i < after.size(); i++) {
				if (before.get(i).staffIn() != after.get(i).staffIn()) {
					// One section, but the whole meal's count beside it: the line names the kitchen
					// only where naming it distinguishes this section from another on the same meal.
					affected.add(readout(meal, onMeal.size(), List.of(after.get(i))));
				}
			}
		}
		return affected;
	}

	// ---- A meal not saved yet (T-215) -----------------------------------

	/**
	 * Who is rostered at a date and ready-by that has no meal row yet: the figure the composer reads
	 * out, and prefills <em>Volunteers requested</em> from, before the meal's first save.
	 *
	 * <p><strong>Why this exists.</strong> Rajeev ruled (D-27 answer 1) that the planner's volunteer
	 * layer opens on People needed minus Rostered. The composer could only find Rostered on a meal the
	 * server had already seen, so a brand-new event, or a main meal with no meal of its kind that day,
	 * read "Not counted yet" and prefilled the whole of People needed. The browser test saw it: five
	 * asked for where two staff were rostered and three were short, and the same meal read "2 of 5"
	 * the moment it was saved.
	 *
	 * <p><strong>Counted the way a saved meal is counted, and nowhere else.</strong> The question goes
	 * through {@link WorkforceService#countAt} as a {@link MealMoment}, exactly as {@link #crewFor}
	 * asks it, so the figure before the first save and the figure after it are the same arithmetic on
	 * the same roster. A second count written here would be the first thing to drift.
	 *
	 * <p><strong>The moment has no meal id, and that decides the volunteers.</strong> A shift <em>for a
	 * meal</em> counts toward that meal and no other (D-14), matched by id; with no id,
	 * {@link MealMoment#isFor} is false for every one of them, so none counts here. A shift <em>not for
	 * a meal</em> counts if it is open on that date and its window covers the ready-by, both ends
	 * inclusive — the rule every meal already uses for those. That is also what the saved meal reads
	 * the moment it is saved: the only shift that could newly point at it is the one drafted with it,
	 * and nobody has signed up for that yet.
	 *
	 * <p>Read-only by construction, not by care: nothing here writes, and the transaction says so.
	 * Planning a meal saves nothing until Save this meal (D-27 answer 7), and asking who is rostered is
	 * not an exception to that.
	 */
	@Transactional(readOnly = true)
	public WorkforceCount crewAt(LocalDate date, LocalTime readyBy) {
		MealMoment moment = new MealMoment(null, date, readyBy, null, null);
		WorkforceCount count = workforceService.countAt(List.of(moment)).get(moment);
		return count != null ? count : new WorkforceCount(date, 0, 0);
	}

	/**
	 * {@link #crewAt(LocalDate, LocalTime)} for one kitchen's section of a meal not saved yet, and by
	 * name (Epic 12).
	 *
	 * <p>The composer asks it once per kitchen band. With a kitchen, only staff whose record is in that
	 * kitchen count, exactly as a saved meal's section counts them. Volunteers belong to no kitchen, so
	 * the composer says whether this band is the one the meal's volunteers fall to (the main kitchen's,
	 * else the first) and {@code countVolunteers} false leaves them out, so the bands add up to the
	 * meal the way a saved meal's sections do.
	 *
	 * <p>A kitchen id from another temple, or one that does not exist, is not refused: row-level
	 * security hides every staff record in it, so it answers 0 staff — which is true of it here.
	 *
	 * @param kitchenId       the section's kitchen; null counts every staff member, as before Epic 12,
	 *                        and then volunteers always count
	 * @param countVolunteers with a kitchen, whether this section carries the meal's volunteers
	 */
	@Transactional(readOnly = true)
	public CrewAtCount crewAt(LocalDate date, LocalTime readyBy, UUID kitchenId, boolean countVolunteers) {
		MealMoment moment = new MealMoment(null, date, readyBy, null, null);
		WorkforceService.RosterAt roster = workforceService.rosterAt(List.of(moment), null).get(moment);
		if (roster == null) {
			return new CrewAtCount(List.of(), 0);
		}
		if (kitchenId == null) {
			return new CrewAtCount(roster.names(), roster.volunteers());
		}
		return new CrewAtCount(roster.namesIn(kitchenId), countVolunteers ? roster.volunteers() : 0);
	}

	/**
	 * The staff by name and the volunteers as a count, for a moment with no meal behind it.
	 *
	 * @param staffNames in the roster's order; its size is the staff figure
	 */
	public record CrewAtCount(List<String> staffNames, int volunteers) {

		public int staffIn() {
			return staffNames.size();
		}

		/** Added through {@link WorkforceCount#rostered()}, the one place that sum is made. */
		public int rostered() {
			return new WorkforceCount(null, staffIn(), volunteers).rostered();
		}
	}

	// ---- Which kitchens, for Today ----------------------------------------

	/**
	 * The names of the kitchens cooking each of these meals, in the order this person sees them —
	 * their own kitchen first when it is on the meal, else the main kitchen, then Settings order
	 * ({@link KitchenOrder#forViewer}). Today names them beside each meal (Epic 12).
	 *
	 * <p>A meal always has at least one kitchen (V150 gave every meal one, and a save cannot leave it
	 * with none), so every id asked about comes back with a non-empty list.
	 */
	@Transactional(readOnly = true)
	public Map<UUID, List<String>> kitchenNamesOf(Collection<UUID> mealIds, UUID viewerUserId) {
		Map<UUID, List<String>> names = new LinkedHashMap<>();
		sectionsOf(mealIds, viewerUserId).forEach((mealId, sections) ->
				names.put(mealId, sections.stream().map(Section::kitchenName).toList()));
		return names;
	}

	// ---- The default the composer opens with ----------------------------

	/** {@link #suggestedCrew(String, UUID)} for nobody in particular: the temple's default kitchen. */
	@Transactional(readOnly = true)
	public Integer suggestedCrew(String mealKind) {
		return suggestedCrew(mealKind, null);
	}

	/**
	 * What to pre-fill for a new meal of this kind: the median of the last three ordinary meals of it
	 * (Q11).
	 *
	 * <p><strong>Per kitchen since Epic 12</strong>, and the kitchen is the one the composer opens its
	 * first band on: {@link KitchenOrder#defaultPlanningKitchen(UUID)} — the person's own kitchen if it
	 * plans meals, else the main kitchen, else the first planner kitchen. The composer puts this figure
	 * in that band, and People needed is answered per kitchen, so the history it learns from is that
	 * kitchen's People needed on the meals it cooked. The whole meal's sum would put a Sweets kitchen's
	 * two pastry cooks into the Main kitchen's default every time the two cooked together. A temple with
	 * one kitchen sees exactly what it saw before.
	 *
	 * <p>Nothing for the temple to maintain — no Settings field, no ratio to keep up to date. It
	 * learns the kitchen's real practice instead of asking for it. And deliberately not a formula off
	 * the servings: plates divided by a ratio is guesswork dressed as arithmetic, and it would be
	 * wrong in a way that looks authoritative.
	 *
	 * <p><strong>Ordinary</strong> means a stored {@code day_type} of REGULAR or WEEKEND. A festival
	 * lunch takes a crew no ordinary Tuesday will ever need, and letting one set the default would
	 * over-state every meal after it until somebody noticed.
	 *
	 * <p>That filter used to exclude CATERING too, and E4-S15 removed the value — the catering plans
	 * it excluded now read REGULAR or WEEKEND by their weekday, so they are inside this window where
	 * they were outside it. Checked rather than assumed: the median is taken per KIND, and those
	 * plans are Events now, so they can only ever move an Event's default. A Lunch still learns from
	 * lunches. And an Event learning what an event actually took is the answer we would want anyway.
	 *
	 * <p><strong>Three and not one</strong>, because the festival guard does not catch an unusual
	 * <em>ordinary</em> day. A visiting sannyasi, a wedding party: that meal is stored REGULAR, and
	 * as the last meal of its kind it would become the default for the next ordinary lunch. The
	 * middle of three throws it out.
	 *
	 * <p>The thin cases, in order: two meals give their mean rounded <em>up</em>, because being short
	 * is worse than being over; one meal gives itself; none gives null, and the field opens empty.
	 * Empty is honest. A made-up number would not be.
	 *
	 * @param viewerUserId the person composing; null (or a temple with no planner kitchen) falls back to
	 *                     the temple's default planning kitchen, and to nothing when there is none
	 */
	@Transactional(readOnly = true)
	public Integer suggestedCrew(String mealKind, UUID viewerUserId) {
		// Through the kind service so an unknown kind is refused by name (KMS-400071) rather than
		// quietly matching no meals and reading as "this temple has never cooked one".
		UUID kindId = mealKindService.require(mealKind).id();
		UUID kitchenId = kitchenOrder.defaultPlanningKitchen(viewerUserId).orElse(null);
		if (kitchenId == null) {
			return null;
		}
		// The day type is on the meal's day, and a meal is "called off" when every dish of it is — the
		// same test mealsIn() applies below. Matched on the kind's id, never its name: a kind renamed in
		// Settings keeps its history. People needed is this kitchen's, on meal_kitchens (Epic 12).
		List<Integer> recent = jdbc.queryForList("""
				SELECT mk.crew_required
				FROM meals m
				JOIN meal_plan_days d ON d.id = m.meal_plan_day_id
				JOIN meal_kitchens mk ON mk.meal_id = m.id AND mk.kitchen_id = ?
				WHERE m.meal_kind_id = ?
				  AND d.day_type IN ('REGULAR', 'WEEKEND')
				  AND mk.crew_required IS NOT NULL
				  AND EXISTS (SELECT 1 FROM meal_dishes md WHERE md.meal_id = m.id AND md.status <> 'CANCELLED')
				ORDER BY d.plan_date DESC, m.ready_by DESC
				LIMIT 3
				""", Integer.class, kitchenId, kindId);

		return median(recent);
	}

	/**
	 * The middle of three, the mean of two rounded up, the one of one, or null.
	 *
	 * <p>Package-private so the arithmetic can be tested for what it is, without a temple, a roster
	 * and three months of meals around it.
	 */
	static Integer median(List<Integer> values) {
		List<Integer> sorted = new ArrayList<>(values);
		sorted.sort(Integer::compareTo);
		return switch (sorted.size()) {
			case 0 -> null;
			case 1 -> sorted.get(0);
			// Rounded up, never down. Being short when you need more is the expensive mistake; an
			// extra pair of hands is not. The same asymmetry decides every close call in this feature.
			case 2 -> (sorted.get(0) + sorted.get(1) + 1) / 2;
			default -> sorted.get(sorted.size() / 2);
		};
	}

	// ---------------------------------------------------------------------

	private List<ServedMeal> mealsIn(LocalDate from, LocalDate to) {
		List<ServedMeal> meals = new ArrayList<>();
		for (ServedMeal meal : servedMealService.list(from, to)) {
			if (meal.dishes().stream().allMatch(d -> d.status() == MealStatus.CANCELLED)) {
				continue;
			}
			meals.add(meal);
		}
		return meals;
	}

	private Map<UUID, List<Section>> sectionsFor(List<ServedMeal> meals, UUID viewerUserId) {
		return sectionsOf(meals.stream().map(ServedMeal::mealId).toList(), viewerUserId);
	}

	/**
	 * Each meal's kitchens with their People needed, from {@code meal_kitchens}, in the order this
	 * person sees them. One query for the lot; row-level security keeps it to the temple.
	 */
	private Map<UUID, List<Section>> sectionsOf(Collection<UUID> mealIds, UUID viewerUserId) {
		if (mealIds.isEmpty()) {
			return Map.of();
		}
		List<UUID> ids = List.copyOf(new java.util.LinkedHashSet<>(mealIds));
		Map<UUID, List<Section>> byMeal = new LinkedHashMap<>();
		for (UUID id : ids) {
			byMeal.put(id, new ArrayList<>());
		}
		jdbc.query("""
				SELECT mk.meal_id, mk.kitchen_id, k.name, k.is_main, mk.crew_required
				FROM meal_kitchens mk
				JOIN kitchens k ON k.id = mk.kitchen_id
				WHERE mk.meal_id IN (%s)
				""".formatted(String.join(", ", Collections.nCopies(ids.size(), "?"))), rs -> {
			byMeal.get(rs.getObject("meal_id", UUID.class)).add(new Section(
					rs.getObject("kitchen_id", UUID.class),
					rs.getString("name"),
					rs.getBoolean("is_main"),
					(Integer) rs.getObject("crew_required")));
		}, ids.toArray());

		UUID viewerKitchen = viewerUserId == null ? null : kitchenOrder.kitchenOf(viewerUserId).orElse(null);
		List<KitchenOrder.KitchenRef> settingsOrder = kitchenOrder.settingsOrder();
		Map<UUID, List<Section>> ordered = new LinkedHashMap<>();
		byMeal.forEach((mealId, sections) -> ordered.put(mealId,
				KitchenOrder.forViewer(sections, Section::kitchenId, viewerKitchen, settingsOrder)));
		return ordered;
	}

	/**
	 * Each section's readout against one reading of the roster, in the order given.
	 *
	 * <p>The staff are that kitchen's; the meal's volunteers go to one section only — the main
	 * kitchen's where it is on the meal, else the first — and every other section reads 0, so the
	 * sections add up to the meal and no volunteer is counted twice.
	 */
	private static List<KitchenCrewView> kitchensOf(List<Section> sections, WorkforceService.RosterAt roster) {
		if (sections.isEmpty()) {
			return List.of();
		}
		WorkforceService.RosterAt in = roster != null ? roster : new WorkforceService.RosterAt(List.of(), 0);
		UUID volunteersIn = sections.stream().filter(Section::isMain).map(Section::kitchenId).findFirst()
				.orElse(sections.get(0).kitchenId());

		List<KitchenCrewView> kitchens = new ArrayList<>();
		for (Section section : sections) {
			List<String> names = in.namesIn(section.kitchenId());
			int volunteers = Objects.equals(section.kitchenId(), volunteersIn) ? in.volunteers() : 0;
			// Added through WorkforceCount.rostered(), the one place a cook and a volunteer are added.
			int rostered = new WorkforceCount(null, names.size(), volunteers).rostered();
			kitchens.add(new KitchenCrewView(
					section.kitchenId(),
					section.kitchenName(),
					section.crewRequired(),
					names.size(),
					names,
					volunteers,
					rostered,
					section.crewRequired() != null && rostered < section.crewRequired()));
		}
		return kitchens;
	}

	/**
	 * The moments to ask the roster about, without duplicates — the same meal asked about twice is one
	 * question, and the roster should be asked it once.
	 *
	 * <p>What counts as "the same" narrowed with D-14, and it had to. A moment used to be a date and
	 * a ready-by time, so two different meals due at the same minute collapsed into one question and
	 * one answer. That was harmless while the answer depended only on the clock; it is wrong now that
	 * a shift can be for one of them and not the other. A moment carries the meal's own id (D-27), so
	 * two meals sharing a minute are two questions with two answers.
	 */
	private static List<MealMoment> momentsOf(List<ServedMeal> meals) {
		Map<MealMoment, Boolean> seen = new LinkedHashMap<>();
		for (ServedMeal meal : meals) {
			seen.put(momentOf(meal), Boolean.TRUE);
		}
		return List.copyOf(seen.keySet());
	}

	/** The meal as the roster is asked about it, by its own id (D-27). */
	private static MealMoment momentOf(ServedMeal meal) {
		return new MealMoment(meal.mealId(), meal.planDate(), meal.readyBy(), meal.mealKind(), meal.eventName());
	}

	/**
	 * One meal beside its kitchens' readouts, the meal-level figures being theirs added up.
	 *
	 * <p>People needed is the sum of the kitchens that have a figure, and null only when none has —
	 * null is not zero. The meal is short when any kitchen is, never by comparing the sums: surplus
	 * hands in one kitchen are not hands in another.
	 *
	 * <p>{@code mealKitchenCount} is passed in rather than taken from {@code kitchens}, because the
	 * two are not the same number wherever a readout carries one section of a several-kitchen meal —
	 * which is exactly what {@link #crewIfAway} sends.
	 */
	private static MealCrewView readout(ServedMeal meal, int mealKitchenCount, List<KitchenCrewView> kitchens) {
		Integer crewRequired = null;
		int staffIn = 0;
		int volunteers = 0;
		int rostered = 0;
		boolean anyShort = false;
		for (KitchenCrewView kitchen : kitchens) {
			if (kitchen.crewRequired() != null) {
				crewRequired = (crewRequired == null ? 0 : crewRequired) + kitchen.crewRequired();
			}
			staffIn += kitchen.staffIn();
			volunteers += kitchen.volunteers();
			rostered += kitchen.rostered();
			anyShort |= kitchen.shortOfCrew();
		}
		return new MealCrewView(
				meal.mealId(),
				meal.planDate(),
				meal.mealKind(),
				meal.readyBy(),
				crewRequired,
				staffIn,
				volunteers,
				rostered,
				anyShort,
				mealKitchenCount,
				List.copyOf(kitchens));
	}
}
