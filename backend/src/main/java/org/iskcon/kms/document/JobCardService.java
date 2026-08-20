package org.iskcon.kms.document;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.calendar.CalendarDayView;
import org.iskcon.kms.calendar.CalendarService;
import org.iskcon.kms.meal.EkadashiPolicy;
import org.iskcon.kms.meal.MealPlanView;
import org.iskcon.kms.meal.MealStatus;
import org.iskcon.kms.meal.ServedMeal;
import org.iskcon.kms.meal.ServedMealService;
import org.iskcon.kms.recipe.RecipeService;
import org.iskcon.kms.recipe.RecipeView;
import org.iskcon.kms.recipe.ScaledLine;
import org.iskcon.kms.recipe.ScaledRecipeView;
import org.iskcon.kms.shift.RosterView;
import org.iskcon.kms.shift.ShiftService;
import org.iskcon.kms.shift.ShiftView;
import org.iskcon.kms.staff.StaffScheduleService;
import org.iskcon.kms.staff.WeekScheduleView;
import org.iskcon.kms.translation.GlossaryService;
import org.iskcon.kms.translation.TranslatedRecipe;
import org.iskcon.kms.translation.RecipeTranslationService;
import org.iskcon.kms.translation.TranslationProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the job card for one meal (B5) — everything a kitchen needs on one sheet of A4.
 *
 * <p>It gathers rather than computes: each figure comes from the service that owns it, so the card
 * cannot disagree with the screens it is printed from. The scaled quantities come from
 * {@link RecipeService#scale}, the fast from the calendar and the Ekadashi rule, the roster from the
 * staff schedule and the volunteers from their shifts.
 *
 * <p><strong>Language.</strong> The card defaults to the temple's own, because it goes to the
 * kitchen; anybody may override it at print time, and print it twice if the head cook wants English
 * and the line cooks do not. Words are translated — dish names, ingredients, method, notes, the
 * fixed labels. Numbers, times, units and the card number never are.
 */
@Service
public class JobCardService {

	/** The label set this card's fixed wording is cached under. */
	static final String LABEL_SET = "JOB_CARD";

	private static final ZoneId TEMPLE_ZONE = ZoneId.of("Asia/Kolkata");
	private static final DateTimeFormatter DATE_LONG = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy");
	private static final DateTimeFormatter GENERATED =
			DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(TEMPLE_ZONE);
	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

	private final JdbcTemplate jdbc;
	private final ServedMealService servedMealService;
	private final RecipeService recipeService;
	private final RecipeTranslationService recipeTranslationService;
	private final CalendarService calendarService;
	private final EkadashiPolicy ekadashiPolicy;
	private final StaffScheduleService staffScheduleService;
	private final ShiftService shiftService;
	private final GlossaryService glossaryService;
	private final TranslationProvider translationProvider;
	private final DocumentLabelTranslator labelTranslator;

	public JobCardService(
			JdbcTemplate jdbc, ServedMealService servedMealService, RecipeService recipeService,
			RecipeTranslationService recipeTranslationService, CalendarService calendarService,
			EkadashiPolicy ekadashiPolicy, StaffScheduleService staffScheduleService,
			ShiftService shiftService, GlossaryService glossaryService,
			TranslationProvider translationProvider, DocumentLabelTranslator labelTranslator) {
		this.jdbc = jdbc;
		this.servedMealService = servedMealService;
		this.recipeService = recipeService;
		this.recipeTranslationService = recipeTranslationService;
		this.calendarService = calendarService;
		this.ekadashiPolicy = ekadashiPolicy;
		this.staffScheduleService = staffScheduleService;
		this.shiftService = shiftService;
		this.glossaryService = glossaryService;
		this.translationProvider = translationProvider;
		this.labelTranslator = labelTranslator;
	}

	/** The card for a meal, rendered to HTML — the same document the PDF is made from. */
	@Transactional
	public String render(UUID mealServiceId, String language) {
		return JobCardTemplate.render(build(mealServiceId, language));
	}

	/**
	 * The language a card prints in when nobody chose one: the temple's own.
	 *
	 * <p>Read off {@code tenants.locale}, which is the only statement of language a temple makes
	 * anywhere in the schema. It is a BCP-47 tag — {@code en-IN}, {@code kn-IN} — so the language
	 * subtag in front of the dash is what the translation provider wants. Adding a second "kitchen
	 * language" setting beside it was the alternative and was rejected: two places to say the same
	 * thing is two places to keep in step, and the one that already exists is the one temples have.
	 */
	@Transactional(readOnly = true)
	public String templeLanguage() {
		String locale = jdbc.query("""
				SELECT locale FROM tenants
				WHERE id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""", (rs, n) -> rs.getString("locale")).stream().findFirst().orElse(null);
		if (locale == null || locale.isBlank()) {
			return "en";
		}
		int dash = locale.indexOf('-');
		return dash > 0 ? locale.substring(0, dash) : locale;
	}

	// ---------------------------------------------------------------------

	JobCardTemplate.CardModel build(UUID mealServiceId, String language) {
		ServedMeal meal = servedMealService.requireByServiceId(mealServiceId);
		String lang = (language == null || language.isBlank()) ? templeLanguage() : language;

		CalendarDayView day = calendarService.day(meal.planDate()).orElse(null);
		List<String> warnings = warnings(day, meal);

		List<String> labels = labelTranslator.labels(
				LABEL_SET, JobCardTemplate.Labels.VERSION, JobCardTemplate.Labels.englishList(), lang);
		// The last label is the word for "servings", which belongs beside each dish's figure rather
		// than in a column heading — so it is read here and handed down.
		String servingsWord = labels.get(labels.size() - 1);

		List<JobCardTemplate.Dish> dishes = new ArrayList<>();
		for (MealPlanView dish : meal.dishes()) {
			// A dish that was called off is not work; printing it would put a pot on the card that
			// nobody is meant to fill.
			if (dish.status() == MealStatus.CANCELLED) {
				continue;
			}
			dishes.add(dish(dish, day, lang, servingsWord));
		}

		String cardNumber = meal.cardNumber() == null
				? servedMealService.issueCardNumber(meal.planDate(), meal.mealKind())
				: meal.cardNumber();

		return new JobCardTemplate.CardModel(
				templeName(),
				cardNumber,
				translate(meal.mealKind(), lang),
				DATE_LONG.format(meal.planDate()),
				meal.readyBy() == null ? null : CLOCK.format(meal.readyBy()),
				translate(meal.occasionName(), lang),
				headCountText(meal),
				String.valueOf(meal.plates()),
				warnings,
				meal.clientName(),
				translate(meal.venue(), lang),
				translate(meal.purpose(), lang),
				translate(meal.kitchenNotes(), lang),
				dishes,
				equipment(),
				staffOn(meal.planDate()),
				volunteersOn(meal.planDate()),
				GENERATED.format(Instant.now()),
				labels);
	}

	/** One dish: scaled to what it is actually cooking, in the language the card is printing in. */
	private JobCardTemplate.Dish dish(
			MealPlanView dish, CalendarDayView day, String language, String servingsWord) {
		ScaledRecipeView scaled = recipeService.scale(dish.recipeId(), dish.targetServings());
		RecipeView recipe = recipeService.get(dish.recipeId());

		boolean translating = !DocumentLabelTranslator.isEnglish(language);
		TranslatedRecipe translated = translating
				? recipeTranslationService.translate(dish.recipeId(), language) : null;

		List<MergedLine> merged = merge(scaled.ingredients());
		List<String> names = translateAll(merged.stream().map(MergedLine::name).toList(), language);

		List<JobCardTemplate.Ingredient> ingredients = new ArrayList<>();
		for (int i = 0; i < merged.size(); i++) {
			MergedLine line = merged.get(i);
			ingredients.add(new JobCardTemplate.Ingredient(
					names.get(i), plain(line.quantity()) + " " + line.unit(), line.prohibited()));
		}

		List<String> method = translated != null ? translated.method() : splitMethod(recipe.method());
		String name = translated != null ? translated.name() : dish.recipeName();

		// Named, per dish, because the warning at the top of the card says the day is a fast and this
		// says which pot the problem is in. The planner already acknowledged it to get here.
		List<String> conflicts = day != null && day.isEkadashi()
				? ekadashiPolicy.of(dish.recipeId()).offendingIngredients() : List.of();

		return new JobCardTemplate.Dish(
				name,
				plain(dish.targetServings()) + " " + servingsWord,
				ingredients,
				method,
				translateAll(conflicts, language));
	}

	/**
	 * What the day asks of the kitchen, in the order it should be read.
	 *
	 * <p>Not decoration: on an Ekadashi grains, dal and beans come off every menu, and a cook who
	 * misses that line has cooked the wrong food for a hall of people. The temple's own sattvic rule
	 * gets the same treatment where a recipe has been overridden past it.
	 */
	private List<String> warnings(CalendarDayView day, ServedMeal meal) {
		List<String> warnings = new ArrayList<>();
		if (day != null && day.isEkadashi()) {
			String name = day.ekadashiName() == null || day.ekadashiName().isBlank()
					? "Ekadashi" : day.ekadashiName();
			warnings.add(name + " — a fasting day. No grains, dal or beans.");
		}
		if (day != null && day.fastType() != null && !day.fastType().isBlank()) {
			warnings.add("Fast: " + day.fastType());
		}
		for (MealPlanView dish : meal.dishes()) {
			if (dish.status() == MealStatus.CANCELLED) {
				continue;
			}
			RecipeView recipe = recipeService.get(dish.recipeId());
			if (recipe.sattvicOverrideReason() != null && !recipe.sattvicOverrideReason().isBlank()) {
				warnings.add(dish.recipeName() + " — sattvic rule overridden: "
						+ recipe.sattvicOverrideReason());
			}
		}
		return warnings;
	}

	/** "200 adults · 40 children · 30 seniors" — the count the servings were worked out from. */
	private String headCountText(ServedMeal meal) {
		List<String> parts = new ArrayList<>();
		if (meal.adults() != null && meal.adults() > 0) {
			parts.add(meal.adults() + " adults");
		}
		if (meal.children() != null && meal.children() > 0) {
			parts.add(meal.children() + " children");
		}
		if (meal.seniors() != null && meal.seniors() > 0) {
			parts.add(meal.seniors() + " seniors");
		}
		return parts.isEmpty() ? null : String.join(" · ", parts);
	}

	/**
	 * The temple's machines and tools, with anything that is not in good order marked.
	 *
	 * <p>Nothing in the schema links a recipe to the equipment it needs, so the card cannot say "you
	 * will need the wet grinder for this one". What it can truthfully say is what the temple has and
	 * which of it is out of action — which is the thing a head cook checks before starting, and the
	 * reason the section is worth its space at all. Broken first, because that is the news.
	 * Furniture is left off: nobody plans a meal around a trestle table.
	 */
	private List<String> equipment() {
		return jdbc.query("""
				SELECT name, condition FROM equipment_items
				WHERE category IN ('MACHINE', 'TOOL') AND condition <> 'SCRAPPED'
				ORDER BY (condition = 'GOOD'), name
				""", (rs, n) -> {
			String condition = rs.getString("condition");
			return "GOOD".equals(condition)
					? rs.getString("name")
					: rs.getString("name") + " (" + condition.toLowerCase().replace('_', ' ') + ")";
		});
	}

	/**
	 * The staff rostered on this date, read the way the week grid reads them: the weekly template
	 * adjusted by any per-date override, and minus approved leave.
	 *
	 * <p>Asked for as a one-day week — {@code weekView(date)} returns seven days beginning at the date
	 * given, so the first of them is this date — rather than by working out which Monday the date
	 * belongs to. That is one assumption fewer to get wrong, and it uses the narrowest public method
	 * the schedule offers instead of a second query with its own opinion of the roster.
	 */
	private List<JobCardTemplate.Person> staffOn(LocalDate date) {
		WeekScheduleView week = staffScheduleService.weekView(date);
		List<JobCardTemplate.Person> people = new ArrayList<>();
		for (WeekScheduleView.StaffWeek person : week.staff()) {
			WeekScheduleView.ResolvedDay today = person.days().stream()
					.filter(d -> d.date().equals(date)).findFirst().orElse(null);
			if (today == null || !today.working()) {
				continue;
			}
			people.add(new JobCardTemplate.Person(person.fullName(), hours(today, person.jobTitleLabel())));
		}
		return people;
	}

	private static String hours(WeekScheduleView.ResolvedDay day, String jobTitle) {
		List<String> parts = new ArrayList<>();
		if (day.startTime() != null && day.endTime() != null) {
			parts.add(CLOCK.format(day.startTime()) + "–" + CLOCK.format(day.endTime()));
		}
		if (jobTitle != null && !jobTitle.isBlank()) {
			parts.add(jobTitle);
		}
		return parts.isEmpty() ? null : String.join(", ", parts);
	}

	/** The volunteers signed up for a shift falling on this date, with the shift they said yes to. */
	private List<JobCardTemplate.Person> volunteersOn(LocalDate date) {
		List<JobCardTemplate.Person> people = new ArrayList<>();
		for (ShiftView shift : shiftService.list(date, date, false)) {
			RosterView roster = shiftService.roster(shift.id());
			for (RosterView.Signup signup : roster.signups()) {
				// Released spots are on the roster so that a poster can see somebody dropped out. On a
				// card they would be a name in a kitchen that is not there.
				if (signup.releasedAt() != null) {
					continue;
				}
				people.add(new JobCardTemplate.Person(
						signup.fullName(), shift.title() + ", " + CLOCK.format(shift.startTime())));
			}
		}
		return people;
	}

	// ---- Translation ----------------------------------------------------

	/**
	 * Ingredient and other short names: the temple's glossary first, so a term it has pinned stays
	 * pinned, then one machine-translation batch for whatever is left.
	 */
	private List<String> translateAll(List<String> source, String language) {
		if (DocumentLabelTranslator.isEnglish(language) || source.isEmpty()) {
			return source;
		}
		Map<String, String> glossary = glossaryService.lookup(language);
		String[] out = new String[source.size()];
		List<String> mt = new ArrayList<>();
		int[] mtIndex = new int[source.size()];
		for (int i = 0; i < source.size(); i++) {
			String override = glossary.get(source.get(i).toLowerCase());
			if (override != null) {
				out[i] = override;
				mtIndex[i] = -1;
			} else {
				mtIndex[i] = mt.size();
				mt.add(source.get(i));
			}
		}
		if (!mt.isEmpty()) {
			List<String> translated = translationProvider.translate(mt, "en", language);
			for (int i = 0; i < source.size(); i++) {
				if (mtIndex[i] >= 0) {
					out[i] = translated.get(mtIndex[i]);
				}
			}
		}
		return List.of(out);
	}

	private String translate(String text, String language) {
		if (DocumentLabelTranslator.isEnglish(language) || text == null || text.isBlank()) {
			return text;
		}
		return translationProvider.translate(List.of(text), "en", language).get(0);
	}

	// ---------------------------------------------------------------------

	/**
	 * Folds a recipe's repeated ingredient lines together — a recipe may list ghee twice, once for the
	 * tempering and once for the finish, and a cook weighing it out wants one figure.
	 *
	 * <p>Merged on the raw quantity and unit, never the display ones: display quantities are chosen
	 * per line for a person to read (2.5 kg rather than 2500 g) and two lines of the same ingredient
	 * can be shown in different units, so adding them would produce a number that is simply wrong.
	 * Two lines whose raw units differ stay two lines for the same reason — converting between them is
	 * the inventory module's job and the card must not invent a conversion of its own.
	 */
	private static List<MergedLine> merge(List<ScaledLine> lines) {
		Map<String, MergedLine> byKey = new LinkedHashMap<>();
		for (ScaledLine line : lines) {
			String key = line.ingredientId() + "|" + line.rawUnit();
			MergedLine existing = byKey.get(key);
			if (existing == null) {
				byKey.put(key, new MergedLine(line.ingredientName(), line.rawQuantity(),
						line.rawUnit(), line.sattvicProhibited()));
			} else {
				byKey.put(key, new MergedLine(existing.name(),
						existing.quantity().add(line.rawQuantity()), existing.unit(),
						existing.prohibited() || line.sattvicProhibited()));
			}
		}
		return List.copyOf(byKey.values());
	}

	private record MergedLine(String name, BigDecimal quantity, String unit, boolean prohibited) {
	}

	private String templeName() {
		try {
			return jdbc.queryForObject("""
					SELECT name FROM tenants WHERE id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
					""", String.class);
		} catch (RuntimeException e) {
			return "Temple";
		}
	}

	private static List<String> splitMethod(String method) {
		if (method == null || method.isBlank()) {
			return List.of();
		}
		List<String> steps = new ArrayList<>();
		for (String line : method.split("\\R")) {
			if (!line.isBlank()) {
				steps.add(line.trim());
			}
		}
		return steps;
	}

	private static String plain(BigDecimal value) {
		return value == null ? "" : value.stripTrailingZeros().toPlainString();
	}
}
