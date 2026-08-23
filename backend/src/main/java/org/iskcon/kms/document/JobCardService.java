package org.iskcon.kms.document;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.iskcon.kms.calendar.CalendarDayView;
import org.iskcon.kms.calendar.CalendarService;
import org.iskcon.kms.meal.EkadashiPolicy;
import org.iskcon.kms.meal.MealPlanView;
import org.iskcon.kms.meal.MealStatus;
import org.iskcon.kms.meal.ServedMeal;
import org.iskcon.kms.meal.ServedMealService;
import org.iskcon.kms.recipe.RecipeIngredientView;
import org.iskcon.kms.recipe.RecipeService;
import org.iskcon.kms.recipe.RecipeView;
import org.iskcon.kms.recipe.ScaledLine;
import org.iskcon.kms.recipe.ScaledRecipeView;
import org.iskcon.kms.shift.RosterView;
import org.iskcon.kms.shift.ShiftService;
import org.iskcon.kms.shift.ShiftView;
import org.iskcon.kms.staff.StaffScheduleService;
import org.iskcon.kms.staff.WeekScheduleView;
import org.iskcon.kms.translation.Languages;
import org.iskcon.kms.translation.RecipeTranslationService;
import org.iskcon.kms.translation.TranslatedRecipe;
import org.iskcon.kms.translation.TranslationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the job card for one meal (B5, rebuilt by build brief 2026-08-21 item 17).
 *
 * <p>It gathers rather than computes: each figure comes from the service that owns it, so the card
 * cannot disagree with the screens it is printed from. The scaled quantities come from
 * {@link RecipeService#scale}, the fast from the calendar and the Ekadashi rule, the roster from the
 * staff schedule and the volunteers from their shifts.
 *
 * <p><strong>Two halves, two languages.</strong> The worksheet is always English — the app's Phase 1
 * UI is English-only and the office reads the worksheet. The recipes appendix prints in whichever
 * language the person at the printer chose, defaulting to the temple's own (Q3).
 *
 * <p><strong>The appendix uses translations that already exist, and never makes one.</strong>
 * {@link #appendixLanguages} offers English plus every language {@code recipe_translations} holds
 * for this meal's preparations at their current versions; anything else would print an English
 * appendix under a Kannada heading. A preparation whose translation is missing or stale prints in
 * English under one line saying so, because three recipes of four in Kannada beats none. Nothing
 * here reaches the translation provider, so a card prints at the same speed with the network down.
 */
@Service
public class JobCardService {

	private static final Logger log = LoggerFactory.getLogger(JobCardService.class);

	/** The label set the appendix's fixed wording is cached under. */
	static final String LABEL_SET = "JOB_CARD";

	/**
	 * The language value that means "the worksheet on its own".
	 *
	 * <p>The appendix is optional and somebody has to say so, and the choice has to survive into the
	 * queued PDF as well as the browser print view — a card downloaded and a card printed must be the
	 * same sheet. {@code documents.language} is the one column that already carries a print-time
	 * choice from the request to the worker, so the choice rides on it rather than on a new column
	 * and a migration. It is not a language, which is exactly why no language code can collide
	 * with it.
	 */
	public static final String WORKSHEET_ONLY = "none";

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
	private final TranslationProvider translationProvider;
	private final DocumentLabelTranslator labelTranslator;

	public JobCardService(
			JdbcTemplate jdbc, ServedMealService servedMealService, RecipeService recipeService,
			RecipeTranslationService recipeTranslationService, CalendarService calendarService,
			EkadashiPolicy ekadashiPolicy, StaffScheduleService staffScheduleService,
			ShiftService shiftService, TranslationProvider translationProvider,
			DocumentLabelTranslator labelTranslator) {
		this.jdbc = jdbc;
		this.servedMealService = servedMealService;
		this.recipeService = recipeService;
		this.recipeTranslationService = recipeTranslationService;
		this.calendarService = calendarService;
		this.ekadashiPolicy = ekadashiPolicy;
		this.staffScheduleService = staffScheduleService;
		this.shiftService = shiftService;
		this.translationProvider = translationProvider;
		this.labelTranslator = labelTranslator;
	}

	/** The card for a meal, rendered to HTML — the same document the PDF is made from. */
	@Transactional
	public String render(UUID mealServiceId, String language) {
		return JobCardTemplate.render(build(mealServiceId, language));
	}

	/**
	 * The language a card's recipes print in when nobody chose one: the temple's own.
	 *
	 * <p>Read off {@code tenants.locale}, which is the only statement of language a temple makes
	 * anywhere in the schema. It is a BCP-47 tag — {@code en-IN}, {@code kn-IN} — so the language
	 * subtag in front of the dash is what the rest of the system wants. Adding a second "kitchen
	 * language" setting beside it was the alternative and was rejected: two places to say the same
	 * thing is two places to keep in step, and the one that already exists is the one temples have.
	 *
	 * <p>It is the default and not the rule (Q3). Whoever prints picks — a cook printing for a
	 * Kannada kitchen and an admin printing a copy for a Hindi-speaking guest cook each get what they
	 * need, off the same meal.
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

	/**
	 * What languages this meal's card can be printed in, and which is preselected.
	 *
	 * <p>All of them: English and the 22 scheduled languages, every time. This list used to be
	 * narrowed to the languages {@code recipe_translations} already held, which sounds careful and
	 * is wrong twice over. It made the offer depend on what somebody happened to have translated
	 * before, so the picker on a fresh temple held one entry and looked broken; and the premise
	 * underneath it — that a temple's cooks read the language of the state it stands in — is not
	 * true of any kitchen this is for. Translations are produced when a card is asked for, and
	 * cached; the picker offers the choice, and {@link RecipeTranslationService} makes it real.
	 *
	 * <p>The default is the temple's own language, which is now always on the list.
	 */
	@Transactional(readOnly = true)
	public AppendixLanguages appendixLanguages(UUID mealServiceId) {
		return appendixLanguages(servedMealService.requireByServiceId(mealServiceId));
	}

	/**
	 * The same list, for a meal named the way a screen names it.
	 *
	 * <p>Deliberately not routed through {@code serviceFor}: that creates the meal's own row on
	 * demand, and asking what languages are on offer is a read. The planner asks this for every meal
	 * of the day as it loads, and a page view must not leave rows behind it.
	 */
	@Transactional(readOnly = true)
	public AppendixLanguages appendixLanguages(LocalDate date, String mealKind) {
		return appendixLanguages(servedMealService.require(date, mealKind));
	}

	private AppendixLanguages appendixLanguages(ServedMeal meal) {
		String temple = templeLanguage();
		String preselected = Languages.ALL.contains(temple) ? temple : Languages.ENGLISH;
		return new AppendixLanguages(Languages.ALL, preselected);
	}

	/** The languages a meal's recipes can print in, and the one the picker opens on. */
	public record AppendixLanguages(List<String> languages, String defaultLanguage) {
	}

	// ---------------------------------------------------------------------

	JobCardTemplate.CardModel build(UUID mealServiceId, String language) {
		ServedMeal meal = servedMealService.requireByServiceId(mealServiceId);

		// A preparation that was called off is not work; printing it would put a pot on the card that
		// nobody is meant to fill, and a ruled box beside it that nobody is meant to write in.
		List<MealPlanView> live = meal.dishes().stream()
				.filter(dish -> dish.status() != MealStatus.CANCELLED).toList();

		boolean wantsAppendix = !WORKSHEET_ONLY.equalsIgnoreCase(trimmed(language));
		String appendixLanguage = wantsAppendix ? resolveAppendixLanguage(language, live) : null;
		boolean translating = appendixLanguage != null
				&& !DocumentLabelTranslator.isEnglish(appendixLanguage);

		// Only the appendix's fixed wording is ever translated; the worksheet is English literals in
		// the template, so an English appendix costs nothing at all here.
		List<String> labels = translating
				? labelTranslator.labels(LABEL_SET, JobCardTemplate.Labels.VERSION,
						JobCardTemplate.Labels.englishList(), appendixLanguage)
				: JobCardTemplate.Labels.englishList();

		List<UUID> recipeIds = live.stream().map(MealPlanView::recipeId).distinct().toList();
		// Produced now if it does not exist yet, not looked up among what was translated in advance.
		Map<UUID, TranslatedRecipe> translated = translating
				? translateAll(recipeIds, appendixLanguage) : Map.of();

		CalendarDayView day = calendarService.day(meal.planDate()).orElse(null);

		List<JobCardTemplate.Preparation> preparations = new ArrayList<>();
		List<JobCardTemplate.RecipePage> recipes = new ArrayList<>();
		for (MealPlanView dish : live) {
			TranslatedRecipe local = translated.get(dish.recipeId());
			preparations.add(new JobCardTemplate.Preparation(
					dish.recipeName(), local == null ? null : local.name(), plain(dish.targetYield())));
			if (wantsAppendix) {
				recipes.add(recipePage(dish, day, appendixLanguage, local, translating));
			}
		}

		String cardNumber = meal.cardNumber() == null
				? servedMealService.issueCardNumber(meal.planDate(), meal.mealKind())
				: meal.cardNumber();

		return new JobCardTemplate.CardModel(
				templeName(),
				cardNumber,
				meal.mealKind(),
				DATE_LONG.format(meal.planDate()),
				meal.readyBy() == null ? null : CLOCK.format(meal.readyBy()),
				meal.occasionName(),
				headCountText(meal),
				String.valueOf(meal.plates()),
				warnings(day, live),
				meal.clientName(),
				meal.venue(),
				meal.purpose(),
				meal.kitchenNotes(),
				preparations,
				equipment(),
				plannedCrewText(meal),
				staffOn(meal.planDate()),
				volunteersOn(meal.planDate()),
				recipes,
				translating ? languageLabel(appendixLanguage) : null,
				GENERATED.format(Instant.now()),
				labels);
	}

	/**
	 * One preparation's page in the appendix: scaled to what it is actually cooking, and in the
	 * chosen language where a translation of the current version exists.
	 */
	private JobCardTemplate.RecipePage recipePage(
			MealPlanView dish, CalendarDayView day, String language, TranslatedRecipe translated,
			boolean translating) {
		ScaledRecipeView scaled = recipeService.scale(dish.recipeId(), dish.targetYield());
		RecipeView recipe = recipeService.get(dish.recipeId());

		// The stored translation lists ingredient names in the recipe's own line order, so it is
		// turned into a lookup by English name — merging folds repeated lines and destroys the
		// positions. Anything not in it keeps its English name rather than being sent for
		// translation: a print is not the moment to discover the translation provider is down.
		Map<String, String> ingredientNames = new HashMap<>();
		if (translated != null) {
			List<RecipeIngredientView> base = recipe.ingredients();
			for (int i = 0; i < base.size() && i < translated.ingredientNames().size(); i++) {
				ingredientNames.put(base.get(i).ingredientName(), translated.ingredientNames().get(i));
			}
		}

		List<JobCardTemplate.Ingredient> ingredients = new ArrayList<>();
		for (MergedLine line : merge(scaled.ingredients())) {
			ingredients.add(new JobCardTemplate.Ingredient(
					ingredientNames.getOrDefault(line.name(), line.name()),
					plain(line.quantity()) + " " + line.unit(),
					line.prohibited()));
		}

		// Named, per preparation, because the warning at the top of the card says the day is a fast
		// and this says which pot the problem is in. The planner already acknowledged it to get here.
		List<String> conflicts = day != null && day.isEkadashi()
				? ekadashiPolicy.of(dish.recipeId()).offendingIngredients() : List.of();
		List<String> namedConflicts = conflicts.stream()
				.map(name -> ingredientNames.getOrDefault(name, name)).toList();

		return new JobCardTemplate.RecipePage(
				dish.recipeName(),
				translated == null ? null : translated.name(),
				ingredients,
				translated != null ? translated.method() : splitMethod(recipe.method()),
				namedConflicts,
				translating && translated == null);
	}

	/**
	 * What the day asks of the kitchen, in the order it should be read.
	 *
	 * <p>Not decoration: on an Ekadashi grains, dal and beans come off every menu, and a cook who
	 * misses that line has cooked the wrong food for a hall of people. The temple's own sattvic rule
	 * gets the same treatment where a recipe has been overridden past it.
	 */
	private List<String> warnings(CalendarDayView day, List<MealPlanView> live) {
		List<String> warnings = new ArrayList<>();
		if (day != null && day.isEkadashi()) {
			String name = day.ekadashiName() == null || day.ekadashiName().isBlank()
					? "Ekadashi" : day.ekadashiName();
			warnings.add(name + " — a fasting day. No grains, dal or beans.");
		}
		if (day != null && day.fastType() != null && !day.fastType().isBlank()) {
			warnings.add("Fast: " + day.fastType());
		}
		for (MealPlanView dish : live) {
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
	 * How many people the meal was planned to take (item 24).
	 *
	 * <p>The figure is a whole-meal fact carried on every one of the meal's rows, exactly as
	 * {@code adults}, {@code ready_by} and the rest are, so {@link ServedMeal} has already collapsed
	 * them into one. A meal planned weeks before anybody was rostered has none, and the line simply
	 * does not appear — the card is not the place to print a blank where a decision has not been
	 * taken yet.
	 */
	private String plannedCrewText(ServedMeal meal) {
		Integer crew = meal.crewRequired();
		if (crew == null || crew <= 0) {
			return null;
		}
		return "Planned crew · " + crew + (crew == 1 ? " person" : " people");
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
		List<WeekScheduleView.StaffWeek> working = new ArrayList<>();
		for (WeekScheduleView.StaffWeek person : week.staff()) {
			WeekScheduleView.ResolvedDay today = person.days().stream()
					.filter(d -> d.date().equals(date)).findFirst().orElse(null);
			if (today != null && today.working()) {
				working.add(person);
			}
		}
		Map<UUID, String> phones = staffPhones(working.stream()
				.map(WeekScheduleView.StaffWeek::staffProfileId).toList());

		List<JobCardTemplate.Person> people = new ArrayList<>();
		for (WeekScheduleView.StaffWeek person : working) {
			people.add(new JobCardTemplate.Person(
					person.fullName(), phones.get(person.staffProfileId()), person.jobTitleLabel()));
		}
		return people;
	}

	/** The volunteers signed up for a shift falling on this date, with the shift they said yes to. */
	private List<JobCardTemplate.Person> volunteersOn(LocalDate date) {
		List<RosterView.Signup> signups = new ArrayList<>();
		List<String> shiftTitles = new ArrayList<>();
		for (ShiftView shift : shiftService.list(date, date, false)) {
			RosterView roster = shiftService.roster(shift.id());
			for (RosterView.Signup signup : roster.signups()) {
				// Released spots are on the roster so that a poster can see somebody dropped out. On a
				// card they would be a name in a kitchen that is not there.
				if (signup.releasedAt() != null) {
					continue;
				}
				signups.add(signup);
				shiftTitles.add(shift.title() + ", " + CLOCK.format(shift.startTime()));
			}
		}
		Map<UUID, String> phones = userPhones(signups.stream().map(RosterView.Signup::userId).toList());

		List<JobCardTemplate.Person> people = new ArrayList<>();
		for (int i = 0; i < signups.size(); i++) {
			people.add(new JobCardTemplate.Person(
					signups.get(i).fullName(), phones.get(signups.get(i).userId()), shiftTitles.get(i)));
		}
		return people;
	}

	/**
	 * A number for each rostered staff member, in one query.
	 *
	 * <p>Neither the week grid nor a shift roster carries a phone number — they are read on screens
	 * where a name is enough and a page of numbers would be a small privacy leak. The card is the one
	 * place they earn their space: the thing this sheet is asked for at 05:40 is a way to ring
	 * whoever has not arrived. Read here rather than added to those two views, so no screen gains a
	 * column it did not ask for.
	 *
	 * <p>The employment record's own number first, and the login's only if it has none. Staff without
	 * a login are ordinary in a temple kitchen — {@code staff_profiles.user_id} is nullable precisely
	 * for them — and they are exactly the people whose number a head cook does not already have.
	 */
	private Map<UUID, String> staffPhones(Collection<UUID> staffProfileIds) {
		return phones(staffProfileIds, """
				SELECT sp.id AS key, COALESCE(sp.phone, u.phone) AS phone
				FROM staff_profiles sp LEFT JOIN users u ON u.id = sp.user_id
				WHERE sp.id IN (%s)
				""");
	}

	/** A number for each volunteer. A volunteer always has a login; that is how they signed up. */
	private Map<UUID, String> userPhones(Collection<UUID> userIds) {
		return phones(userIds, "SELECT id AS key, phone FROM users WHERE id IN (%s)");
	}

	private Map<UUID, String> phones(Collection<UUID> ids, String sqlWithPlaceholders) {
		Set<UUID> wanted = new HashSet<>(ids);
		wanted.remove(null);
		if (wanted.isEmpty()) {
			return Map.of();
		}
		String placeholders = String.join(",", Collections.nCopies(wanted.size(), "?"));
		Map<UUID, String> phones = new HashMap<>();
		for (Map.Entry<UUID, String> row : jdbc.query(
				sqlWithPlaceholders.formatted(placeholders),
				(rs, n) -> Map.entry(rs.getObject("key", UUID.class),
						rs.getString("phone") == null ? "" : rs.getString("phone")),
				wanted.toArray())) {
			phones.put(row.getKey(), row.getValue());
		}
		return phones;
	}

	// ---- The appendix's language ----------------------------------------

	/**
	 * Which language the appendix prints in: what was asked for, or the temple's own, or English.
	 *
	 * <p>An explicit choice is honoured as given even when nothing on this meal is translated into
	 * it — the per-preparation line then says so on the sheet, which is a truthful answer rather than
	 * a silent substitution. Only the unasked-for default is narrowed to what the meal can actually
	 * deliver.
	 */
	private String resolveAppendixLanguage(String requested, List<MealPlanView> live) {
		String asked = trimmed(requested);
		return asked != null ? asked : templeLanguage();
	}


	/**
	 * Every one of these recipes in the chosen language, translated now if it has not been before.
	 *
	 * <p>A recipe that cannot be produced is left out rather than failing the print: the card comes
	 * back with that preparation in English and the sheet says so per preparation, which is a
	 * truthful answer. A print is not the moment to discover the translation provider is down — but
	 * it is also not the moment to silently hand a cook a language nobody asked for, so the failure
	 * is per recipe and visible on the page rather than swallowed for the whole card.
	 */
	private Map<UUID, TranslatedRecipe> translateAll(List<UUID> recipeIds, String language) {
		Map<UUID, TranslatedRecipe> translated = new LinkedHashMap<>();
		for (UUID recipeId : recipeIds) {
			try {
				translated.put(recipeId, recipeTranslationService.translate(recipeId, language));
			} catch (RuntimeException e) {
				log.warn("Job card appendix falling back to English for recipe {} in {}: {}",
						recipeId, language, e.toString());
			}
		}
		return translated;
	}


	private static List<UUID> recipeIds(ServedMeal meal) {
		return meal.dishes().stream()
				.filter(dish -> dish.status() != MealStatus.CANCELLED)
				.map(MealPlanView::recipeId)
				.distinct()
				.toList();
	}

	/**
	 * What to call the appendix's language on the sheet, in that language's own script where the JDK
	 * knows it — a cook who does not read English should not have to read "Kannada" to find out that
	 * this is the Kannada copy.
	 */
	private static String languageLabel(String language) {
		Locale locale = Locale.forLanguageTag(language);
		String own = locale.getDisplayLanguage(locale);
		return own == null || own.isBlank() ? language : own;
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

	private static String trimmed(String s) {
		return s == null || s.isBlank() ? null : s.trim();
	}

	private static String plain(BigDecimal value) {
		return value == null ? "" : value.stripTrailingZeros().toPlainString();
	}
}
