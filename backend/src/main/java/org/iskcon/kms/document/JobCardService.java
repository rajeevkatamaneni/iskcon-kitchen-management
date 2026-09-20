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
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.ingredient.Quantities;
import java.time.LocalTime;
import org.iskcon.kms.geo.GeocodingProvider;
import org.iskcon.kms.geo.StaticMapProvider;
import org.iskcon.kms.meal.EkadashiPolicy;
import org.iskcon.kms.meal.Handover;
import org.iskcon.kms.meal.MealPlanService;
import org.iskcon.kms.meal.MealDishView;
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
import org.iskcon.kms.staff.MealMoment;
import org.iskcon.kms.staff.WorkforceService;
import org.iskcon.kms.translation.Languages;
import org.iskcon.kms.translation.RecipeTranslationService;
import org.iskcon.kms.translation.TranslatedRecipe;
import org.iskcon.kms.translation.TranslationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.iskcon.kms.tenancy.TempleClock;
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
 * <p><strong>The appendix is translated on demand.</strong> {@link #appendixLanguages} offers
 * English and all 22 scheduled languages, every time, and {@code translateAll} produces whatever the
 * person at the printer asked for, cached per recipe and version so only the first card in a given
 * language costs a round trip. A preparation whose translation cannot be produced prints in English
 * under one line saying so, per preparation rather than for the whole card, because three recipes of
 * four in Kannada beats none.
 *
 * <p><em>Corrected 2026-09-06.</em> This paragraph used to say the opposite — that the appendix used
 * only translations that already existed and never reached the provider — and it had been wrong since
 * {@code d192168} on 2026-08-22, which made the offer unconditional. The old rule narrowed the picker
 * to whatever happened to be cached, which on a fresh temple is nothing, so the control looked broken;
 * and its premise, that a temple's cooks read the language of the state it stands in, is not true of
 * any kitchen this is for. A temple in Bengaluru may have Bengali and Bihari cooks (Rajeev,
 * 2026-09-06). The comment outliving the change cost a day: it was read as the design and reported
 * as a gap that did not exist.
 */
@Service
public class JobCardService {

	private final TempleClock clock;

	private static final Logger log = LoggerFactory.getLogger(JobCardService.class);

	private final MealPlanService mealPlanService;
	private final StaticMapProvider staticMapProvider;

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

	/**
	 * The card's dates come from {@link DisplayDates} (T-312), shared with every other document and
	 * the screens' en-GB: with no locale the stamp's month took the JVM's US English and wrote "Sep".
	 */
	private static final DateTimeFormatter DATE_LONG = DisplayDates.LONG_DAY;
	/**
	 * Left without a zone on purpose. It carried {@code .withZone(Asia/Kolkata)}, which is a static
	 * decision about a fact that belongs to whichever temple is printing — so the zone is supplied
	 * at the moment of formatting instead.
	 */
	private static final DateTimeFormatter GENERATED = DisplayDates.DAY_AND_TIME;
	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

	private final JdbcTemplate jdbc;
	private final ServedMealService servedMealService;
	private final RecipeService recipeService;
	private final RecipeTranslationService recipeTranslationService;
	private final CalendarService calendarService;
	private final EkadashiPolicy ekadashiPolicy;
	/** The per-kitchen roster, as the planner's own crew figures read it (T-358). */
	private final WorkforceService workforceService;
	private final ShiftService shiftService;
	private final TranslationProvider translationProvider;
	private final DocumentLabelTranslator labelTranslator;

	public JobCardService(
			JdbcTemplate jdbc, ServedMealService servedMealService, RecipeService recipeService,
			RecipeTranslationService recipeTranslationService, CalendarService calendarService,
			EkadashiPolicy ekadashiPolicy, WorkforceService workforceService,
			ShiftService shiftService, TranslationProvider translationProvider,
			DocumentLabelTranslator labelTranslator, MealPlanService mealPlanService,
			StaticMapProvider staticMapProvider, TempleClock clock) {
		this.clock = clock;
		this.mealPlanService = mealPlanService;
		this.staticMapProvider = staticMapProvider;
		this.jdbc = jdbc;
		this.servedMealService = servedMealService;
		this.recipeService = recipeService;
		this.recipeTranslationService = recipeTranslationService;
		this.calendarService = calendarService;
		this.ekadashiPolicy = ekadashiPolicy;
		this.workforceService = workforceService;
		this.shiftService = shiftService;
		this.translationProvider = translationProvider;
		this.labelTranslator = labelTranslator;
	}

	/**
	 * The card for a meal, rendered to HTML for the browser print view.
	 *
	 * <p>Its footer is drawn by the document itself — see {@code CardModel.footerInDocument}. The PDF
	 * takes the other path, {@link #renderForPdf}, because a page number can only come from the
	 * renderer.
	 */
	@Transactional
	public String render(UUID mealId, UUID kitchenId, String language) {
		return JobCardTemplate.render(build(mealId, kitchenId, language, true));
	}

	/** The card and the running footer its renderer has to draw, for the PDF path. */
	public record RenderedCard(String html, PdfRenderer.Footer footer) {
	}

	/**
	 * The card for the PDF, with its footer handed out separately.
	 *
	 * <p>Blink can produce a page number only from the renderer's own footer template, so the
	 * document leaves its footer out and {@link PlaywrightPdfRenderer} draws it on every page with
	 * "Page 3 of 7" in the middle. The words come from the template either way, so the print view and
	 * the PDF cannot drift apart.
	 */
	@Transactional
	public RenderedCard renderForPdf(UUID mealId, UUID kitchenId, String language) {
		JobCardTemplate.CardModel model = build(mealId, kitchenId, language, false);
		return new RenderedCard(
				JobCardTemplate.render(model),
				new PdfRenderer.Footer(
						JobCardTemplate.footerLeft(model), model.cardNumber()));
	}

	/**
	 * One kitchen's section of a meal, as its card needs it: who, and how many people it needs.
	 *
	 * @param crewRequired that kitchen's People needed ({@code meal_kitchens.crew_required}), null
	 *                     where nobody has said
	 */
	public record CardKitchen(UUID id, String name, Integer crewRequired) {
	}

	/**
	 * Whose card this is (Epic 12): the kitchen asked for, or the meal's only kitchen.
	 *
	 * <p>Rajeev, 2026-09-19: a meal cooked by two kitchens shows one meal card with a section per
	 * kitchen, and each kitchen gets its own job card, downloaded from its own section. So a card is
	 * always one kitchen's, and the question is only how that kitchen is named.
	 *
	 * <ul>
	 *   <li><strong>Named.</strong> It has to be one of this meal's kitchens. A kitchen of this temple
	 *       that is not cooking the meal is refused ({@code KITCHEN_NOT_ON_THIS_MEAL}) rather than
	 *       printed as an empty sheet, because an empty sheet with a real kitchen's name on it reads as
	 *       "nothing to cook" to the person who picks it up. Another temple's kitchen reads as the same
	 *       refusal: row-level security hides its row, and absent is absent.</li>
	 *   <li><strong>Not named, one kitchen.</strong> That kitchen. This is every meal before Epic 12
	 *       and most meals after it, and it keeps every caller that never learnt about kitchens — an
	 *       old screen, a bookmarked print link — working exactly as it did.</li>
	 *   <li><strong>Not named, two or more.</strong> Refused ({@code JOB_CARD_NEEDS_A_KITCHEN}). There
	 *       is no right default: printing the main kitchen's card to somebody who asked from the
	 *       sweets kitchen's section is the one mistake this whole change exists to prevent, and
	 *       printing every kitchen's dishes on one sheet is the sheet Rajeev ruled out.</li>
	 * </ul>
	 *
	 * <p>The meal is required first, so a meal that is not this temple's is the same 404 it always
	 * was, before any question about kitchens is asked. Callers run this before the card number is
	 * issued, so a refused request spends no number.
	 */
	@Transactional(readOnly = true)
	public CardKitchen kitchenFor(UUID mealId, UUID requested) {
		servedMealService.require(mealId);
		// Settings order (is_main DESC, lower(name)), so that "the only one" and anything that ever
		// lists them read in the order a temple already sees its kitchens in.
		List<CardKitchen> sections = jdbc.query("""
				SELECT mk.kitchen_id, k.name, mk.crew_required
				FROM meal_kitchens mk JOIN kitchens k ON k.id = mk.kitchen_id
				WHERE mk.meal_id = ?
				ORDER BY k.is_main DESC, lower(k.name), k.id
				""", (rs, n) -> new CardKitchen(
						rs.getObject("kitchen_id", UUID.class),
						rs.getString("name"),
						(Integer) rs.getObject("crew_required")),
				mealId);

		if (requested != null) {
			return sections.stream().filter(k -> k.id().equals(requested)).findFirst()
					.orElseThrow(() -> new ApplicationException(ErrorCode.KITCHEN_NOT_ON_THIS_MEAL,
							Map.of("mealId", String.valueOf(mealId), "kitchenId", String.valueOf(requested))));
		}
		if (sections.size() == 1) {
			return sections.get(0);
		}
		if (sections.isEmpty()) {
			// Not reachable: V150 gave every meal a section and the meal save always writes one. A meal
			// without one is a fault in the data, not something the person printing can act on, so it
			// goes to the logs with an incident id rather than borrowing a refusal that would tell them
			// something untrue.
			throw new IllegalStateException("Meal " + mealId + " has no kitchen");
		}
		throw new ApplicationException(ErrorCode.JOB_CARD_NEEDS_A_KITCHEN,
				Map.of("mealId", String.valueOf(mealId), "kitchens", String.valueOf(sections.size())));
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
	public AppendixLanguages appendixLanguages(UUID mealId) {
		// Through require, so a meal that is not this temple's is refused rather than offered a list.
		// The meal's own id is what the planner holds (D-27); there used to be a second overload taking
		// a date, a kind's name and an event name, which is the identification Rajeev ruled out.
		return appendixLanguages(servedMealService.require(mealId));
	}

	private AppendixLanguages appendixLanguages(ServedMeal meal) {
		String temple = templeLanguage();
		String preselected = Languages.ALL.contains(temple) ? temple : Languages.ENGLISH;
		return new AppendixLanguages(Languages.ALL, preselected);
	}

	/** The languages a meal's recipes can print in, and the one the picker opens on. */
	public record AppendixLanguages(List<String> languages, String defaultLanguage) {
	}

	/**
	 * What the card calls this meal: the event's own name where it is an event, else its kind.
	 *
	 * <p>Not both. "Event · Children's Bhagavad-gita Reading" is the kind said twice — every event
	 * of every temple is called Event, so the word carries no information a reader does not already
	 * have from the name.
	 */
	private static String kindLabelFor(ServedMeal meal) {
		// Going outside is a fact about the meal now (D-27), held once on its row, rather than something
		// read off whichever dish row happened to carry it.
		return meal.isOutside() ? "Outside " + meal.mealKind() : meal.mealKind();
	}

	// ---------------------------------------------------------------------

	JobCardTemplate.CardModel build(UUID mealId, UUID kitchenId, String language, boolean footerInDocument) {
		ServedMeal meal = servedMealService.require(mealId);
		CardKitchen kitchen = kitchenFor(mealId, kitchenId);

		// Only this kitchen's dishes (Epic 12). A sheet for the sweets kitchen listing the main
		// kitchen's rice is worse than no sheet — somebody cooks it twice, or trusts that somebody
		// else has. Read here from meal_dishes rather than off the dish view, because which kitchen a
		// dish is in is a fact of the row, and one query answers it for the whole meal. Everything
		// below — the food items, the recipes appendix and its translations, the serving sheet —
		// is built from this list, so filtering it once is filtering all of them.
		Map<UUID, UUID> kitchenOfDish = kitchensOfDishes(mealId);

		// A preparation that was called off is not work; printing it would put a pot on the card that
		// nobody is meant to fill, and a ruled box beside it that nobody is meant to write in.
		List<MealDishView> live = meal.dishes().stream()
				.filter(dish -> dish.status() != MealStatus.CANCELLED)
				.filter(dish -> kitchen.id().equals(kitchenOfDish.get(dish.id())))
				.toList();

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

		List<UUID> recipeIds = live.stream().map(MealDishView::recipeId).distinct().toList();
		// Produced now if it does not exist yet, not looked up among what was translated in advance.
		Map<UUID, TranslatedRecipe> translated = translating
				? translateAll(recipeIds, appendixLanguage) : Map.of();

		CalendarDayView day = calendarService.day(meal.planDate()).orElse(null);

		List<JobCardTemplate.Preparation> preparations = new ArrayList<>();
		List<JobCardTemplate.RecipePage> recipes = new ArrayList<>();
		for (MealDishView dish : live) {
			TranslatedRecipe local = translated.get(dish.recipeId());
			preparations.add(new JobCardTemplate.Preparation(
					dish.recipeName(), local == null ? null : local.name(), planned(dish)));
			if (wantsAppendix) {
				recipes.add(recipePage(dish, day, appendixLanguage, local, translating));
			}
		}

		String cardNumber = meal.cardNumber() == null
				? servedMealService.issueCardNumber(meal.mealId())
				: meal.cardNumber();

		JobCardTemplate.Delivery delivery = delivery(meal);

		JobCardTemplate.CardModel model = new JobCardTemplate.CardModel(
				templeName(),
				cardNumber,
				// Filled in below, once there is a model to fingerprint. Zero would print on a card
				// that failed between here and there, and a v0 sheet is a sheet nobody can trust.
				0,
				kindLabelFor(meal),
				// The event's own name, beside its kind rather than instead of it. Rajeev asked for
				// "Outside Event : Bhagavad Gita Parayanam" on 2026-09-05 — the kind says what shape
				// of thing this is, the name says which one, and a folder of Saturdays needs both.
				meal.eventName(),
				kitchen.name(),
				DATE_LONG.format(meal.planDate()),
				meal.readyBy() == null ? null : CLOCK.format(meal.readyBy()),
				String.valueOf(meal.plates()),
				headCountText(meal),
				warnings(day, live),
				meal.kitchenNotes(),
				meal.serverNotes(),
				preparations,
				peopleNeededText(kitchen),
				staffOn(meal, kitchen.id()),
				volunteersOn(meal.planDate()),
				delivery,
				recipes,
				translating ? languageLabel(appendixLanguage) : null,
				GENERATED.format(Instant.now().atZone(clock.zone())),
				footerInDocument,
				labels);

		return withVersion(meal.mealId(), kitchen.id(), model);
	}

	/** Which of its meal's kitchens each dish is in (V150's meal_dishes.kitchen_id). */
	private Map<UUID, UUID> kitchensOfDishes(UUID mealId) {
		Map<UUID, UUID> byDish = new HashMap<>();
		jdbc.query("SELECT id, kitchen_id FROM meal_dishes WHERE meal_id = ?", rs -> {
			byDish.put(rs.getObject("id", UUID.class), rs.getObject("kitchen_id", UUID.class));
		}, mealId);
		return byDish;
	}

	// ---- The version in the footer --------------------------------------

	/**
	 * Stamps the card with its version, moving it on only if the meal has actually changed.
	 *
	 * <p>Rajeev, 2026-09-05: <em>"different people have differnt version od the job card in the
	 * kitchen and how do we tell which one id correct? The lastest version number must be the correct
	 * one."</em> The rule on the sheet is simply that the higher number wins.
	 *
	 * <p><strong>Derived, not maintained.</strong> The obvious build is a counter bumped wherever the
	 * application edits a meal, and it was rejected: a bump forgotten at one of those sites prints a
	 * changed plan under an unchanged number, which is worse than no version at all. Hashing the
	 * model the card is about to render makes "no field can be missed" true by construction — if it
	 * is on the sheet it is in the hash, because the hash is taken from the sheet.
	 *
	 * <p>What is deliberately left out is in {@link #fingerprint}.
	 *
	 * <p><strong>Per kitchen (Epic 12, V152).</strong> Each kitchen's card is its own sheet, so each
	 * has its own version, on its own {@code meal_kitchens} row: changing the halva moves the sweets
	 * kitchen's card on and leaves the main kitchen's where it was, because nothing on the main
	 * kitchen's sheet changed and a cook there must not go hunting for a difference. V150 copied the
	 * meal's version and fingerprint onto every section, so a card printed before this change carries
	 * on from the number already on the kitchen wall.
	 */
	private JobCardTemplate.CardModel withVersion(UUID mealId, UUID kitchenId, JobCardTemplate.CardModel model) {
		String fingerprint = fingerprint(model);
		// Every meal has a row of its own (D-27) and every kitchen on it a section (V150), so there is
		// always somewhere to remember the version against. Locked, so two prints of a changed card at
		// the same moment move the version once rather than both to the same number.
		Map<String, Object> row = jdbc.queryForMap("""
				SELECT card_version, card_fingerprint FROM meal_kitchens
				WHERE meal_id = ? AND kitchen_id = ? FOR UPDATE
				""", mealId, kitchenId);
		int current = row.get("card_version") == null ? 0 : (Integer) row.get("card_version");
		String stored = (String) row.get("card_fingerprint");

		if (fingerprint.equals(stored) && current > 0) {
			// The same meal, printed again. Two sheets reading v2 are the same sheet, so nobody hunts
			// for a difference that is not there — the printed timestamp beside it says which came off
			// the printer later.
			return version(model, current);
		}
		if (stored != null && current > 0 && printedTheOldWay(stored, model)) {
			// The same card, fingerprinted an older way. Two older ways, both recognised:
			//
			//  - before T-356 (2026-09-19) the hash had no kitchen in it, because a card had no
			//    kitchen. Putting the kitchen's name on the sheet changed every stored value at once.
			//  - before 2026-09-14 it also covered the temple's equipment list, and taking equipment
			//    off the card changed every stored value at once too.
			//
			// Without this, every card in every kitchen would have moved to a new version on its next
			// print while its cooking instructions stayed word for word the same, and the rule on the
			// sheet — the higher number wins — would have sent people hunting for a change that is not
			// there. So the old value is recognised, and quietly replaced with the new one, so this
			// card never takes this path again.
			//
			// updated_at is left alone on purpose: nothing about the meal changed, only how its
			// fingerprint is written down.
			jdbc.update("UPDATE meal_kitchens SET card_fingerprint = ? WHERE meal_id = ? AND kitchen_id = ?",
					fingerprint, mealId, kitchenId);
			return version(model, current);
		}
		int next = current + 1;
		jdbc.update("""
				UPDATE meal_kitchens SET card_version = ?, card_fingerprint = ?, updated_at = now()
				WHERE meal_id = ? AND kitchen_id = ?
				""", next, fingerprint, mealId, kitchenId);
		return version(model, next);
	}

	/**
	 * Whether a stored fingerprint is this very card in one of the older forms. The kitchen-less form
	 * is tried first because it is the one every card printed before Epic 12 carries, and it costs no
	 * query; the equipment list is read only when that fails.
	 */
	private boolean printedTheOldWay(String stored, JobCardTemplate.CardModel model) {
		return stored.equals(fingerprint(model, null, false))
				|| stored.equals(fingerprint(model, legacyEquipment(), false));
	}

	private static JobCardTemplate.CardModel version(JobCardTemplate.CardModel m, int version) {
		return new JobCardTemplate.CardModel(
				m.templeName(), m.cardNumber(), version, m.mealKindLabel(), m.eventName(),
				m.kitchenName(), m.dateText(), m.readyByText(), m.headCountText(), m.headCountDetail(),
				m.warnings(), m.kitchenNotes(),
				m.serverNotes(), m.preparations(), m.peopleNeededText(), m.staff(),
				m.volunteers(), m.delivery(), m.recipes(), m.recipeLanguageLabel(), m.generatedOn(),
				m.footerInDocument(), m.labels());
	}

	/**
	 * A hash of everything on the card that is a fact about the meal.
	 *
	 * <p>Three things are deliberately excluded, and each would make the version worse:
	 *
	 * <ul>
	 *   <li><strong>Who is rostered.</strong> The roster moves daily from the staff schedule, and a
	 *       card whose cooking instructions are identical must not climb to v9 because three
	 *       volunteers swapped shifts.</li>
	 *   <li><strong>The recipes and their language.</strong> Printing the same lunch in Kannada is a
	 *       choice made at the printer, not a new version of the meal.</li>
	 *   <li><strong>The print timestamp.</strong> Including it would bump the version on every
	 *       press of the button, which is the behaviour this design exists to avoid.</li>
	 * </ul>
	 *
	 * <p>So what it covers is: the meal's kind and event name, the kitchen the card is for, its date,
	 * ready-by time and head count, both sets of notes, that kitchen's planned crew, the day's
	 * warnings, each of that kitchen's preparations with its planned quantity, and the delivery
	 * details where food leaves by van. Every one of those is printed on the card, which is what makes
	 * the version trustworthy.
	 *
	 * <p><strong>The kitchen's name went in on 2026-09-19 (T-356)</strong>, when it went on the sheet.
	 * {@code includeKitchen = false} rebuilds the form every card printed before then was stored in, so
	 * {@link #withVersion} can recognise one and keep its version rather than moving every card in
	 * every kitchen on at once.
	 *
	 * <p><strong>Equipment used to be in here too, and the legacy form still exists.</strong> Until
	 * 2026-09-14 the card listed the temple's equipment, so the hash covered it. When the list came off
	 * the card it had to come out of the hash, or a change to the register would move the version of a
	 * sheet that no longer mentions it; and that changed every value already stored in
	 * {@code meals.card_fingerprint}. Passing {@code legacyEquipment} rebuilds the old material exactly,
	 * with the equipment segment where it used to sit, so {@link #withVersion} can recognise a card
	 * printed before the change and keep its version. Null means the current form, which leaves the
	 * segment out altogether (not an empty one: an empty list still wrote a separator).
	 *
	 * <p>The legacy form can be deleted once every meal printed before 2026-09-14 has been reprinted or
	 * is in the past. Until then it costs one small query, and only when the current fingerprint does
	 * not already match.
	 */
	private static String fingerprint(JobCardTemplate.CardModel m) {
		return fingerprint(m, null, true);
	}

	/** See {@link #fingerprint(JobCardTemplate.CardModel)}. Package-private for the reprint tests. */
	static String fingerprint(
			JobCardTemplate.CardModel m, List<String> legacyEquipment, boolean includeKitchen) {
		StringBuilder material = new StringBuilder()
				.append(m.mealKindLabel()).append('\u001f')
				.append(nullSafe(m.eventName())).append('\u001f');
		if (includeKitchen) {
			// Only in the current form, so that the kitchen-less form is byte for byte what the code
			// before T-356 hashed.
			material.append("kitchen=").append(nullSafe(m.kitchenName())).append('\u001f');
		}
		material
				.append(nullSafe(m.dateText())).append('\u001f')
				.append(nullSafe(m.readyByText())).append('\u001f')
				.append(nullSafe(m.headCountText())).append('\u001f')
				.append(nullSafe(m.headCountDetail())).append('\u001f')
				.append(nullSafe(m.kitchenNotes())).append('\u001f')
				.append(nullSafe(m.serverNotes())).append('\u001f')
				.append(nullSafe(m.peopleNeededText())).append('\u001f')
				.append(String.join(",", m.warnings())).append('\u001f');
		if (legacyEquipment != null) {
			material.append(String.join(",", legacyEquipment)).append('\u001f');
		}
		for (JobCardTemplate.Preparation p : m.preparations()) {
			material.append(p.name()).append('=').append(nullSafe(p.plannedText())).append(';');
		}
		material.append('\u001f');
		JobCardTemplate.Delivery d = m.delivery();
		if (d != null) {
			material.append(nullSafe(d.contactName())).append('\u001f')
					.append(nullSafe(d.contactPhone())).append('\u001f')
					.append(nullSafe(d.address())).append('\u001f')
					.append(nullSafe(d.subLocation())).append('\u001f')
					.append(nullSafe(d.deliverByText())).append('\u001f')
					.append(nullSafe(d.allowanceText()));
		}
		try {
			byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
					.digest(material.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
			return java.util.HexFormat.of().formatHex(digest);
		} catch (java.security.NoSuchAlgorithmException e) {
			// SHA-256 is required of every JVM. Unreachable, and not worth a checked exception in
			// every caller above.
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
	}

	private static String nullSafe(String s) {
		return s == null ? "" : s;
	}

	// ---- The delivery sheet ---------------------------------------------

	/**
	 * Everything on the sheet the driver takes, or null when nothing is leaving by van.
	 *
	 * <p>The travel figure printed here is the temple's own — {@code meals.travel_minutes},
	 * prefilled from Google and editable by anybody who knows the road. Where nobody has touched it,
	 * printing the card is a good moment to refresh it, because a card printed on Friday for a
	 * Saturday delivery should carry Saturday's traffic. Where somebody has, their figure stands:
	 * a recalculation that silently overruled the one person who knew better, on the sheet the driver
	 * is about to act on, is the worst possible moment to be clever.
	 */
	private JobCardTemplate.Delivery delivery(ServedMeal meal) {
		// Whether the food leaves by van, and every fact about the drive, are the meal's own (D-27).
		// Before D-27 they were read off the first dish row that carried them, which is a rule nobody
		// should have to write a second time.
		if (!meal.isOutside() || meal.handover() != Handover.DELIVERY) {
			return null;
		}

		Integer minutes = meal.travelMinutes();
		boolean manual = "MANUAL".equals(meal.travelMinutesSource());
		String note = null;
		if (!manual) {
			Integer refreshed = mealPlanService.refreshTravelEstimate(meal.mealId());
			if (refreshed != null) {
				minutes = refreshed;
			}
			note = minutes == null
					? null
					: "Google's estimate, taken when this card was printed.";
		} else {
			note = "Set by hand at the temple, not by the map service.";
		}

		LocalTime eatAt = meal.guestsEatAt();
		String leaveBy = eatAt != null && minutes != null
				? CLOCK.format(eatAt.minusMinutes(minutes)) : null;

		return new JobCardTemplate.Delivery(
				meal.contactName(),
				meal.contactPhone(),
				meal.deliveryAddress(),
				meal.deliverySubLocation(),
				minutes == null ? null : minutes + (minutes == 1 ? " minute" : " minutes"),
				note,
				leaveBy,
				eatAt == null ? null : CLOCK.format(eatAt),
				mapFor(meal.mealId()));
	}

	/** The map, already base64 — the renderer has no network, so a URL would be a broken box. */
	private String mapFor(UUID mealId) {
		if (!staticMapProvider.configured()) {
			return null;
		}
		GeocodingProvider.Coordinates at = mealPlanService.deliveryCoordinatesFor(mealId);
		if (at == null) {
			return null;
		}
		return staticMapProvider.map(at, 640, 360)
				.map(png -> "data:image/png;base64,"
						+ java.util.Base64.getEncoder().encodeToString(png))
				.orElse(null);
	}

	/**
	 * One preparation's page in the appendix: scaled to what it is actually cooking, and in the
	 * chosen language where a translation of the current version exists.
	 */
	private JobCardTemplate.RecipePage recipePage(
			MealDishView dish, CalendarDayView day, String language, TranslatedRecipe translated,
			boolean translating) {
		ScaledRecipeView scaled = recipeService.scale(dish.recipeId(), dish.targetYield());
		RecipeView recipe = recipeService.get(dish.recipeId());

		// The stored translation lists ingredient names in the recipe's own line order, so it is
		// turned into a lookup by English name — merging folds repeated lines and destroys the
		// positions. Anything not in it keeps its English name rather than being sent for
		// translation: a print is not the moment to discover the translation provider is down.
		Map<String, String> ingredientNames = new HashMap<>();
		// The notes the same way, by English note: "slit" is "slit" on whichever line it appears.
		Map<String, String> preparationNotes = new HashMap<>();
		if (translated != null) {
			List<RecipeIngredientView> base = recipe.ingredients();
			for (int i = 0; i < base.size() && i < translated.ingredientNames().size(); i++) {
				ingredientNames.put(base.get(i).ingredientName(), translated.ingredientNames().get(i));
			}
			for (int i = 0; i < base.size(); i++) {
				String note = base.get(i).preparationNote();
				if (note != null && !note.isBlank()) {
					preparationNotes.put(note.strip(), translated.preparationNote(i, note.strip()));
				}
			}
		}

		List<JobCardTemplate.Ingredient> ingredients = new ArrayList<>();
		for (MergedLine line : merge(scaled.ingredients())) {
			// The cook's form: this is the figure somebody stands at a scale with. It also settles a
			// disagreement — the merged line carries the stored unit name, so this card used to ask
			// for "2 KG" while the recipe card for the very same line said "2 Kg".
			// The preparation note follows the name, "Green chilli · slit" (R-DUP-1): the cook
			// weighing it out is the person who has to know it is to be slit.
			String note = line.preparationNote() == null
					? null : preparationNotes.getOrDefault(line.preparationNote(), line.preparationNote());
			ingredients.add(new JobCardTemplate.Ingredient(
					RecipeIngredientView.withPreparation(
							ingredientNames.getOrDefault(line.name(), line.name()), note),
					Quantities.cooks(line.quantity(), line.unit())));
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
	 * misses that line has cooked the wrong food for a hall of people.
	 *
	 * <p>A second warning stood here, naming any dish whose sattvic block a Temple Admin had
	 * overridden. D-18 deleted that block on 2026-09-08, so no recipe can carry an override and the
	 * line could never have printed again.
	 */
	private List<String> warnings(CalendarDayView day, List<MealDishView> live) {
		List<String> warnings = new ArrayList<>();
		if (day != null && day.isEkadashi()) {
			String name = day.ekadashiName() == null || day.ekadashiName().isBlank()
					? "Ekadashi" : day.ekadashiName();
			warnings.add(name + " — a fasting day. No grains, dal or beans.");
		}
		if (day != null && day.fastType() != null && !day.fastType().isBlank()) {
			warnings.add("Fast: " + day.fastType());
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
	 * How many people this kitchen needs (item 24, per kitchen since Epic 12).
	 *
	 * <p>That kitchen's People needed, from its own section of the meal ({@code
	 * meal_kitchens.crew_required}) — not the meal's figure, which is the sum over its kitchens and
	 * would tell the sweets kitchen it has the main kitchen's people. Not read off {@link ServedMeal}
	 * either: its figure is {@code meals.crew_required}, which V151 drops. A section planned weeks
	 * before anybody was rostered has none, and the line simply does not appear — the card is not the
	 * place to print a blank where a decision has not been taken yet.
	 *
	 * <p><strong>"People needed", not "Planned crew" (T-361.)</strong> The planner screen calls this
	 * figure People needed, and so does the composer's field, and so does the crew readout that says
	 * "Lunch at 4 of 6". A sheet that called the same number something else would be one more thing
	 * for a head cook to work out; Rajeev's standing rule is the same label in every view.
	 */
	private static String peopleNeededText(CardKitchen kitchen) {
		Integer crew = kitchen.crewRequired();
		if (crew == null || crew <= 0) {
			return null;
		}
		return "People needed · " + crew + (crew == 1 ? " person" : " people");
	}


	/**
	 * The temple's equipment list, worded exactly as the card used to print it — used only to
	 * recognise a fingerprint stored before equipment came off the card on 2026-09-14.
	 *
	 * <p>Nothing prints this any more. It is the old query and the old wording, unchanged, because the
	 * legacy fingerprint only matches if the material is byte for byte what the old code hashed:
	 * everything not scrapped, broken first, then by name, with any condition other than good in
	 * brackets. Read at the moment of the print rather than remembered, so a register that changed
	 * since the last print simply fails to match and the card moves on once, which is what the old
	 * code would have done too. See {@link #fingerprint(JobCardTemplate.CardModel)} for when it can go.
	 */
	private List<String> legacyEquipment() {
		return jdbc.query("""
				SELECT name, condition FROM equipment_items
				WHERE condition <> 'SCRAPPED'
				ORDER BY (condition = 'GOOD'), name
				""", (rs, n) -> {
			String condition = rs.getString("condition");
			return "GOOD".equals(condition)
					? rs.getString("name")
					: rs.getString("name") + " (" + condition.toLowerCase().replace('_', ' ') + ")";
		});
	}

	/**
	 * The staff this kitchen has for this meal: the people the planner counts in that kitchen's
	 * section, with a number to ring (Epic 12, T-361).
	 *
	 * <p><strong>What changed and why.</strong> Until now the card printed the whole temple's roster
	 * for the day — everybody working, whichever kitchen they work in and whatever hours they keep. A
	 * meal is now cooked by several kitchens and each of them prints its own card, so the sweets
	 * kitchen's sheet was listing the main kitchen's twelve cooks under a figure of two. It now prints
	 * the people that kitchen's own section is counting, which is the set of names the planner screen
	 * shows beside "4 of 6" for the same meal and the same kitchen.
	 *
	 * <p><strong>Whose answer it is.</strong> {@link WorkforceService#rosterAt} — the per-kitchen
	 * roster T-358 built for the crew readouts — is asked, not asked again here in different words.
	 * That matters twice over: who is in for a meal is a rule with edges (an active record, a working
	 * day that is not half-day leave, and a window covering the ready-by, both ends inclusive), and a
	 * card that answered it its own way would sooner or later print a name the screen does not count,
	 * or count a name it does not print.
	 *
	 * <p>It does mean a narrower list than before: somebody working that day whose hours end before the
	 * food is due is no longer on the sheet. That is the same person the planner already leaves out of
	 * "4 of 6", and a card is asked for at 05:40 to find out who should be standing in this kitchen
	 * now, not who is in the building at some point today.
	 *
	 * <p>A volunteer has no kitchen — a shift is posted for a meal or a stretch of the day, never for a
	 * kitchen — so {@link #volunteersOn} is unchanged and every kitchen's card lists the day's
	 * volunteers.
	 */
	private List<JobCardTemplate.Person> staffOn(ServedMeal meal, UUID kitchenId) {
		MealMoment moment = new MealMoment(
				meal.mealId(), meal.planDate(), meal.readyBy(), meal.mealKind(), meal.eventName());
		WorkforceService.RosterAt roster = workforceService.rosterAt(List.of(moment), null).get(moment);
		if (roster == null) {
			return List.of();
		}
		List<WorkforceService.RosteredPerson> here = roster.staff().stream()
				.filter(person -> kitchenId.equals(person.kitchenId()))
				.toList();
		Map<UUID, String> phones = staffPhones(here.stream()
				.map(WorkforceService.RosteredPerson::staffProfileId).toList());

		List<JobCardTemplate.Person> people = new ArrayList<>();
		for (WorkforceService.RosteredPerson person : here) {
			// The third field is the job title, and the card prints it for volunteers only (it says
			// which shift they signed up for). Staff are listed by name and number, so there is nothing
			// to fetch a title for.
			people.add(new JobCardTemplate.Person(
					person.name(), phones.get(person.staffProfileId()), null));
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
	private String resolveAppendixLanguage(String requested, List<MealDishView> live) {
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
				.map(MealDishView::recipeId)
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
	 *
	 * <p>Two lines prepared differently also stay two lines (R-DUP-1). Since the library import
	 * stopped making "Coconut, grated" and "Coconut, fresh grated" two ingredients, a recipe can hold
	 * Coconut twice with different notes, and "Coconut · grated 2 Kg" plus "Coconut · fresh grated
	 * 1 Kg" is two jobs at the grating station; one "Coconut 3 Kg" would lose both instructions. Lines
	 * with the same note, or none, still fold as they always did.
	 */
	private static List<MergedLine> merge(List<ScaledLine> lines) {
		Map<String, MergedLine> byKey = new LinkedHashMap<>();
		for (ScaledLine line : lines) {
			String note = line.preparationNote() == null || line.preparationNote().isBlank()
					? null : line.preparationNote().strip();
			String key = line.ingredientId() + "|" + line.rawUnit() + "|" + (note == null ? "" : note);
			MergedLine existing = byKey.get(key);
			if (existing == null) {
				byKey.put(key, new MergedLine(line.ingredientName(), note, line.rawQuantity(),
						line.rawUnit()));
			} else {
				byKey.put(key, new MergedLine(existing.name(), existing.preparationNote(),
						existing.quantity().add(line.rawQuantity()), existing.unit()));
			}
		}
		return List.copyOf(byKey.values());
	}

	private record MergedLine(String name, String preparationNote, BigDecimal quantity, String unit) {
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

	/**
	 * How much of this preparation to make, in the unit it is measured in.
	 *
	 * <p>It printed as a bare number until 2026-09-05, which the first real card off staging made
	 * plain: "Kesari Bath 35.6" beside "Puran Poli 500" is two figures in two different units with
	 * nothing saying so, on the one table the sheet exists for. The recipe's own yield unit is
	 * already carried on the dish row, and it is rendered the way every other quantity in the
	 * application is — the cook's form, not the ledger's.
	 *
	 * <p>Falls back to the bare number where the unit is missing or unrecognised, because a figure
	 * with no unit still beats an empty cell in a pot's worth of instructions.
	 */
	private static String planned(MealDishView dish) {
		String rendered = Quantities.cooks(dish.targetYield(), dish.targetYieldUnit());
		return rendered == null || rendered.isBlank() || "—".equals(rendered)
				? plain(dish.targetYield()) : rendered;
	}

	private static String plain(BigDecimal value) {
		return value == null ? "" : value.stripTrailingZeros().toPlainString();
	}
}
