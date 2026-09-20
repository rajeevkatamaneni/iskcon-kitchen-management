package org.iskcon.kms.library;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.error.ErrorResponse.FieldError;
import org.iskcon.kms.ingredient.IngredientNameMatcher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Taking a temple's own copy of a library recipe (E2-S12).
 *
 * <p>The copy is a full, independent row set in {@code recipes} and {@code recipe_ingredients} —
 * not a pointer, not a view, not a subscription. Everything downstream already reads those two
 * tables: scaling, meal planning, sufficiency, order generation, the printed card, translation. A
 * second kind of recipe that only half of them understood is how a bug reaches the shopping list six
 * weeks later.
 *
 * <p>{@code master_recipe_id} records where the copy came from and constrains nothing. The temple
 * may rename it, rewrite its method, change its per-head portion; a later correction in the library
 * never reaches it, and an operator deleting the library row leaves it standing.
 *
 * <h2>Tenant isolation</h2>
 *
 * <p>Both target tables call {@code enable_tenant_rls()}. {@code tenant_id} is taken from the
 * verified token by way of {@code app.tenant_id} and never from anything in the request — the
 * master row does not carry one to borrow. The application connects as a role holding neither DDL
 * nor {@code BYPASSRLS}, so a temple cannot see, read, edit or count another temple's copies for
 * the same reason it cannot see their donations: the database refuses, not the code.
 *
 * <h2>What it does in one transaction, and why that matters</h2>
 *
 * <ol>
 *   <li>Plans every ingredient line against the temple's catalogue and refuses, having written
 *       nothing, if a line is a close match nobody has answered for (Q-11, below).</li>
 *   <li>Resolves the category, creating it on first use.</li>
 *   <li>Resolves every ingredient by name, creating what is missing. A preparation the line states
 *       in its own {@code prep} field ("Green chilli" + "Slit") goes on the recipe line as its note;
 *       so does one the older books left inside the name ("Cashew, halved"), recovered by splitting
 *       it. Neither ever becomes an ingredient. So does the rest of a name the person has matched to
 *       an existing ingredient ("Ginger, peeled", "Use Ginger": Ginger · peeled; A-N5).</li>
 *   <li>Marks what the book says the temple never buys, on the ingredients this import
 *       <em>creates</em> and on no others (T-403). Water arrives as an ingredient that will never
 *       reach a shopping list; a Water the temple already holds is left exactly as the temple has
 *       it, and the disagreement is written into the import's audit entry. See
 *       {@link #resolveIngredients} for the argument.</li>
 *   <li>Writes the recipe and its lines.</li>
 * </ol>
 *
 * <p>A third step stood between those two until 2026-09-08: it refused any recipe naming an
 * ingredient this temple had flagged sattvic-prohibited. D-18 deleted that flag, so nothing can be
 * flagged and the step could only ever pass. A recipe naming garlic now imports like any other —
 * accepted deliberately, on the reasoning that the library is the temple's own and does not carry
 * such ingredients. Everything still happens in one transaction, so any failure leaves nothing
 * behind — no half-created category, no orphan ingredients from a recipe the temple never got.
 */
@Service
public class RecipeImportService {

	private final JdbcTemplate jdbc;
	private final MasterRecipeService library;
	private final AuditService audit;

	public RecipeImportService(JdbcTemplate jdbc, MasterRecipeService library, AuditService audit) {
		this.jdbc = jdbc;
		this.library = library;
		this.audit = audit;
	}

	/** What an import created, so the response can say more than "done". */
	public record Imported(UUID recipeId, String name, int ingredientsCreated, boolean categoryCreated) {
	}

	/**
	 * Every ingredient name in the library recipe that is a close — not exact — match for one the
	 * temple has: what the copy screen lists before it copies anything (Q-11, T-287). Empty when
	 * there is none, which is the common case and lets the screen copy at once.
	 *
	 * <p>Worked out by the same {@link #plan} the copy itself runs, so the list the person answered
	 * is the list the copy checks their answers against. It writes nothing.
	 */
	@Transactional(readOnly = true)
	public List<ImportCloseMatchView> closeMatches(UUID masterRecipeId) {
		MasterRecipeView master = library.get(masterRecipeId);
		return closeMatchesOf(plan(master, catalogue())).values().stream().map(CloseMatch::view).toList();
	}

	/** The copy with no answers, as every caller before T-287 made it. */
	@Transactional
	public Imported importRecipe(AuthenticatedUser actor, UUID masterRecipeId) {
		return importRecipe(actor, masterRecipeId, List.of());
	}

	/**
	 * The copy, with the person's answer to each close match {@link #closeMatches} listed.
	 *
	 * <p>Refused, having written nothing, when an answer is malformed or names something that was
	 * not listed ({@code VALIDATION_FAILED}, see {@link #answersFor}), or when any listed close match
	 * has no answer ({@code INGREDIENT_LOOKS_LIKE_EXISTING}, with every close match in its details
	 * so a screen that did not ask first can still ask now).
	 */
	@Transactional
	public Imported importRecipe(
			AuthenticatedUser actor, UUID masterRecipeId, List<ImportCloseMatchDecision> decisions) {
		MasterRecipeView master = library.get(masterRecipeId);

		// Already taken, by this exact library recipe.
		Integer existing = jdbc.queryForObject(
				"SELECT count(*) FROM recipes WHERE master_recipe_id = ? AND status = 'ACTIVE'",
				Integer.class, masterRecipeId);
		if (existing != null && existing > 0) {
			throw new ApplicationException(ErrorCode.RECIPE_ALREADY_ADDED,
					Map.of("name", master.displayName()));
		}

		// Or a recipe of the same name arrived some other way — typed by hand last year, or taken
		// from a different state's book. Refused here rather than at the unique index, so the
		// message names the recipe instead of naming a constraint.
		Integer sameName = jdbc.queryForObject(
				"SELECT count(*) FROM recipes WHERE lower(name) = lower(?) AND status = 'ACTIVE'",
				Integer.class, master.displayName());
		if (sameName != null && sameName > 0) {
			throw new ApplicationException(ErrorCode.RECIPE_ALREADY_EXISTS,
					Map.of("name", master.displayName()));
		}

		// Decided before anything is written, so a refusal leaves no category or ingredient behind even
		// before the transaction's rollback is counted on.
		List<LinePlan> plans = plan(master, catalogue());
		Map<String, CloseMatch> listed = closeMatchesOf(plans);
		Map<String, ImportCloseMatchDecision> answers = answersFor(decisions, listed);

		CategoryResolution category = resolveCategory(master);
		Resolution resolution = resolveIngredients(actor, plans, listed, answers);
		List<ResolvedIngredient> ingredients = resolution.lines();

		UUID recipeId = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO recipes (
					id, tenant_id, name, category_id, base_yield_qty, base_yield_unit, yield_note,
					per_head_qty, per_head_unit, method, notes, region_tag, sub_region,
					subtitle, badge, indicative_cost, why, catering_note,
					note_start, note_vessel, note_season,
					tags, serve_with, master_recipe_id, status, version)
				VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid,
					?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
					CAST(? AS text[]), CAST(? AS text[]), ?, 'ACTIVE', 1)
				""",
				recipeId, master.displayName(), category.id(),
				master.yieldQty(), master.yieldUnit(), master.yieldText(),
				master.perHeadQty(), master.perHeadUnit(),
				String.join("\n", master.method()),
				master.state(), master.region(),
				master.subtitle(), master.badge(), master.indicativeCost(),
				master.why(), master.cateringNote(),
				master.noteStart(), master.noteVessel(), master.noteSeason(),
				pgArray(master.tags()), pgArray(master.serveWith()), masterRecipeId);

		int order = 1;
		for (ResolvedIngredient ingredient : ingredients) {
			jdbc.update("""
					INSERT INTO recipe_ingredients (
						tenant_id, recipe_id, ingredient_id, quantity, unit, line_order, preparation_note)
					VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?, ?)
					""", recipeId, ingredient.id(), ingredient.quantity(), ingredient.unit(), order++,
					ingredient.preparationNote());
		}

		int created = (int) ingredients.stream().filter(ResolvedIngredient::created).count();

		Map<String, Object> after = new LinkedHashMap<>();
		after.put("name", master.displayName());
		after.put("from", master.state());
		after.put("masterRecipeId", masterRecipeId.toString());
		after.put("ingredientsCreated", created);
		// What the person answered for each close match, so the import's own entry says which
		// ingredients a line was put on by choice rather than by the matcher. Left out when there was
		// nothing to answer, so an ordinary import's entry reads exactly as it did before T-287.
		if (!listed.isEmpty()) {
			List<Map<String, Object>> answered = new ArrayList<>();
			listed.forEach((key, match) -> {
				ImportCloseMatchDecision answer = answers.get(key);
				Map<String, Object> entry = new LinkedHashMap<>();
				entry.put("libraryName", match.view().libraryName());
				entry.put("lookedLike", match.view().existingIngredientName());
				entry.put("answer", answer.useIngredientId() != null ? "USED_EXISTING" : "DIFFERENT_INGREDIENT");
				answered.add(entry);
			});
			after.put("closeMatches", answered);
		}
		// The book said the temple never buys these and the temple's own rows say it does. The
		// import does not overrule that (see resolveIngredients), so the entry says so instead of
		// the disagreement vanishing: this is the one thing about the copy that did not come out the
		// way the library wrote it, and a Temple Admin asking "why is water still on our order after
		// we copied Rajeev's recipes" has one entry to find. Left out when there is nothing to say,
		// like closeMatches above, so an ordinary import's entry reads exactly as it did before.
		if (!resolution.notBoughtNotApplied().isEmpty()) {
			after.put("notBoughtNotApplied", resolution.notBoughtNotApplied());
		}
		audit.record(actor, AuditAction.RECIPE_IMPORTED, AuditEntityType.RECIPE, recipeId,
				null, after, null);

		return new Imported(recipeId, master.displayName(), created, category.created());
	}

	// ------------------------------------------------------------------ resolution

	private record CategoryResolution(UUID id, boolean created) {
	}

	private CategoryResolution resolveCategory(MasterRecipeView master) {
		String name = CategoryMapping.nameFor(master.categoryKey(), master.categoryName());

		List<UUID> found = jdbc.queryForList(
				"SELECT id FROM recipe_categories WHERE lower(name) = lower(?)", UUID.class, name);
		if (!found.isEmpty()) {
			return new CategoryResolution(found.get(0), false);
		}

		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO recipe_categories (id, tenant_id, name, fasting_compatible)
				VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?)
				""", id, name, CategoryMapping.fastingCompatible(master.categoryKey()));
		return new CategoryResolution(id, true);
	}

	/** A library line resolved to this temple's ingredient, with the preparation that goes on the line. */
	private record ResolvedIngredient(
			UUID id, String name, String preparationNote, BigDecimal quantity, String unit, boolean created) {
	}

	/**
	 * One library line, split and matched against the catalogue as it stood before this import:
	 * {@code exact} when the temple has it, {@code close} when it has something only close to it,
	 * neither when it has nothing like it. {@code key} is the matcher's normalised form of the base,
	 * which is what an answer is filed under.
	 */
	private record LinePlan(
			MasterRecipeView.MasterRecipeIngredient line, String base, String note, String key,
			IngredientNameMatcher.Entry exact, IngredientNameMatcher.Match close, String useNote) {
	}

	/** A close match as the screen is shown it, with the match the audit needs if it is overridden. */
	private record CloseMatch(ImportCloseMatchView view, IngredientNameMatcher.Match match) {
	}

	/**
	 * Every line of the recipe, split and matched.
	 *
	 * <p><strong>The ingredient is separate from its preparation (R-DUP-1).</strong> The library names
	 * a line the way a reference book does — "Cashew, halved", "Green chilli, slit" — and until
	 * 2026-09-19 the import created each of those as an ingredient of its own. That is how one temple
	 * came to hold curd as "Curd", "Curd, fresh", "Curd, sour" and "Curd, whisked": four stock
	 * figures, four prices, four lines on the shopping list for one pot of curd. Now each name is
	 * split by {@link IngredientNameMatcher#split} into the ingredient (Cashew) and the preparation
	 * (halved); the preparation goes on the recipe line as its note, and only the ingredient is
	 * looked for or created.
	 *
	 * <p><strong>Unless the line says its preparation outright, in which case that is the answer
	 * (T-401).</strong> Splitting the name is a reconstruction, and it only works because the
	 * vendored books file a name noun-first with a comma. Rajeev's curated recipes (2026-09-19) took
	 * the commas out and put the preparation in {@code prep}: "Green chilli, slit" became "Green
	 * chilli" with prep "Slit". Split, such a name yields nothing, and all 84 of his notes would
	 * import empty. So a line carrying a {@code prep} is not split at all — the whole name is the
	 * ingredient, and {@code prep} is the note, kept in the words he wrote. The split is the
	 * fallback, for the books that still say it inside the name.
	 *
	 * <p><strong>{@link #leftOver} still runs on a curated line, and that is deliberate.</strong> It
	 * asks a different question from the split: not "which of these words is a preparation" but
	 * "which of these words is not the ingredient the person just chose". On most curated lines the
	 * name is the bare ingredient, so it finds nothing and the note is simply the {@code prep} —
	 * which is the expected answer, not a skipped step. Where a curated name still carries a comma
	 * because the comma names what the temple buys ("Rice, basmati"), and the person answers "Use
	 * Rice", "basmati" is as much part of the note as it ever was and is joined to the prep by
	 * {@link #combineNotes}. Suppressing it would lose a word the library wrote, which is the fault
	 * A-N5 was raised to fix.
	 *
	 * <p>What is looked for is decided by {@link IngredientNameMatcher#findMatch}, the same rule the
	 * create-and-rename guard uses (R-DUP-2), against every ingredient's name and every alias in
	 * {@code ingredient_aliases} — so a name merged away ("Curd, sour", now an alias of Curd) finds
	 * Curd, and so do "Green chillies" and "green chilli". The catalogue is loaded once rather than
	 * queried per line: the matcher's closeness rule is not something SQL can say, and a temple's
	 * catalogue is hundreds of rows.
	 *
	 * <p><strong>Only the catalogue as it was before this import is matched here</strong>, and that is
	 * what makes the list of close matches a thing the screen can ask about in advance. What this
	 * import itself creates depends on the answers, so a close match against it could not be listed
	 * before they were given — and "Use Jaggery" could not name an ingredient that does not exist yet.
	 * Ingredients created by the import are found again by {@link #resolveIngredients}, for exact
	 * matches only, which is what they always needed: the recipe's second coconut line finding the
	 * first one's Coconut.
	 */
	private List<LinePlan> plan(MasterRecipeView master, List<IngredientNameMatcher.Entry> catalogue) {
		List<LinePlan> plans = new ArrayList<>();
		for (MasterRecipeView.MasterRecipeIngredient line : master.ingredients()) {
			String written = line.name() == null ? "" : line.name().trim().replaceAll("\\s+", " ");
			String stated = line.prep() == null || line.prep().isBlank() ? null : line.prep().trim();

			String base;
			String note;
			if (stated != null) {
				base = written;
				note = stated;
			} else {
				IngredientNameMatcher.Split split = IngredientNameMatcher.split(written);
				base = split.base();
				note = split.preparation();
			}

			Optional<IngredientNameMatcher.Match> match = IngredientNameMatcher.findMatch(base, catalogue);
			boolean exact = match.isPresent() && match.get().kind() == IngredientNameMatcher.MatchKind.EXACT;
			IngredientNameMatcher.Match close = match.isPresent() && !exact ? match.get() : null;
			String useNote = close == null ? null
					: combineNotes(written, note, leftOver(base, close));
			plans.add(new LinePlan(line, base, note, IngredientNameMatcher.normalise(base),
					exact ? match.get().entry() : null, close, useNote));
		}
		return plans;
	}

	/**
	 * The close matches, one per distinct base name in recipe order. Two lines naming the same thing
	 * ("Tomatos, chopped" and "Tomatos") match the same ingredient by the same rule and get one
	 * question: asking twice would let the recipe put one tomato on two ingredients, which is the
	 * split this whole feature exists to stop.
	 */
	private static Map<String, CloseMatch> closeMatchesOf(List<LinePlan> plans) {
		Map<String, CloseMatch> out = new LinkedHashMap<>();
		for (LinePlan plan : plans) {
			if (plan.close() == null || out.containsKey(plan.key())) {
				continue;
			}
			IngredientNameMatcher.Entry existing = plan.close().entry();
			out.put(plan.key(), new CloseMatch(
					new ImportCloseMatchView(plan.base(), plan.useNote(), existing.id(), existing.name()),
					plan.close()));
		}
		return out;
	}

	/**
	 * The person's answers, filed by the close match they answer, after checking every one.
	 *
	 * <p><strong>A wrong answer is a {@code VALIDATION_FAILED} field error</strong>, named by its place
	 * in the list the way other list bodies here are ({@code decisions[1].useIngredientId}):
	 * <ul>
	 *   <li>{@code decisions[i]} — the entry is null.</li>
	 *   <li>{@code decisions[i].libraryName} — blank; or no close match in this recipe by that name
	 *       (compared the way the matcher compares, so "tomatos" answers "Tomatos"); or a second
	 *       answer to a name already answered.</li>
	 *   <li>{@code decisions[i].useIngredientId} — an ingredient that is not the one listed for that
	 *       name. "Use" means the ingredient the person was shown, never another one slipped in: the
	 *       recipe form is where a line is put on an arbitrary ingredient.</li>
	 *   <li>{@code decisions[i].confirmDifferent} — both "use" and "different" at once.</li>
	 * </ul>
	 * The first problem found is the one refused, as elsewhere in this codebase.
	 *
	 * <p><strong>A missing answer is {@code INGREDIENT_LOOKS_LIKE_EXISTING}</strong> (KMS-400156), the
	 * code the ingredient form's prompt is driven by. An entry with no ingredient and
	 * {@code confirmDifferent: false} counts as missing, exactly as {@code confirmDifferent: false} does
	 * on the ingredient form. The refusal's details list every close match, not only the unanswered
	 * ones, so that a screen that copied without asking first can ask about the whole recipe at once.
	 */
	private static Map<String, ImportCloseMatchDecision> answersFor(
			List<ImportCloseMatchDecision> decisions, Map<String, CloseMatch> listed) {
		Map<String, ImportCloseMatchDecision> answers = new LinkedHashMap<>();
		Set<String> seen = new HashSet<>();
		List<ImportCloseMatchDecision> given = decisions == null ? List.of() : decisions;
		for (int i = 0; i < given.size(); i++) {
			ImportCloseMatchDecision decision = given.get(i);
			String at = "decisions[" + i + "]";
			if (decision == null) {
				throw fieldError(at, "Answer each ingredient in the list.");
			}
			if (decision.libraryName() == null || decision.libraryName().isBlank()) {
				throw fieldError(at + ".libraryName", "Say which ingredient in the recipe this answers.");
			}
			String key = IngredientNameMatcher.normalise(decision.libraryName().trim());
			CloseMatch match = listed.get(key);
			if (match == null) {
				throw fieldError(at + ".libraryName",
						"This recipe has no ingredient called “" + decision.libraryName().trim()
								+ "” that needs an answer.");
			}
			if (!seen.add(key)) {
				throw fieldError(at + ".libraryName",
						"“" + match.view().libraryName() + "” has already been answered.");
			}
			if (decision.useIngredientId() != null && decision.confirmDifferent()) {
				throw fieldError(at + ".confirmDifferent",
						"Choose either " + match.view().existingIngredientName()
								+ " or a different ingredient, not both.");
			}
			if (decision.useIngredientId() != null
					&& !decision.useIngredientId().equals(match.view().existingIngredientId())) {
				throw fieldError(at + ".useIngredientId",
						"That isn’t the ingredient “" + match.view().libraryName()
								+ "” was matched with. Choose " + match.view().existingIngredientName()
								+ " or a different ingredient.");
			}
			if (decision.useIngredientId() != null || decision.confirmDifferent()) {
				answers.put(key, decision);
			}
		}

		if (answers.size() < listed.size()) {
			List<FieldError> details = new ArrayList<>();
			int i = 0;
			for (CloseMatch match : listed.values()) {
				// ErrorResponse carries details as field/message pairs only, so each close match is
				// flattened into its own four entries, indexed like a list body's field errors. The
				// screen rebuilds ImportCloseMatchView[] from them; note is left out when there is none.
				String at = "closeMatches[" + i++ + "]";
				ImportCloseMatchView view = match.view();
				details.add(new FieldError(at + ".libraryName", view.libraryName()));
				if (view.note() != null) {
					details.add(new FieldError(at + ".note", view.note()));
				}
				details.add(new FieldError(at + ".existingIngredientId", view.existingIngredientId().toString()));
				details.add(new FieldError(at + ".existingIngredientName", view.existingIngredientName()));
			}
			List<String> unanswered = listed.keySet().stream().filter(k -> !answers.containsKey(k)).toList();
			throw new ApplicationException(ErrorCode.INGREDIENT_LOOKS_LIKE_EXISTING,
					Map.of("unanswered", unanswered), details, null);
		}
		return answers;
	}

	private static ApplicationException fieldError(String field, String message) {
		return new ApplicationException(ErrorCode.VALIDATION_FAILED, Map.of("field", field),
				List.of(new FieldError(field, message)), null);
	}

	/**
	 * Every ingredient the recipe needs, as a row in this temple's own catalogue.
	 *
	 * <ul>
	 *   <li><strong>An exact match</strong> is used, and the note goes on the line.</li>
	 *   <li><strong>A close match</strong> — a spelling one letter off, or "Rice" against "Rice,
	 *       basmati" — is decided by the person, in {@link #answeredCloseMatch} (Q-11).</li>
	 *   <li><strong>No match</strong> creates the base ingredient only ("Cashew", never "Cashew,
	 *       halved") — silently, and marked {@code library_derived} so a catalogue can be tidied
	 *       later. A review step in front of every import was the alternative, and it is the kind of
	 *       friction that stops a feature being used at all; the close matches are the only lines
	 *       worth a question, because only they can be a duplicate.</li>
	 * </ul>
	 *
	 * <p>Whatever a line created is found again by the recipe's later lines, by an exact match only
	 * (see {@link #plan}): "Coconut, grated" then "Fresh grated coconut" make one Coconut, and a
	 * second "Tomatos" line after "It's a different ingredient" goes on the Tomatos the first made.
	 *
	 * <p>The unit comes from the book's own quantity, which is why it can: all 46,337 ingredient
	 * lines in the library parse, into five units the catalogue already knows.
	 *
	 * <h2>What the book's {@code not_bought} mark does, and what it deliberately does not (T-403)</h2>
	 *
	 * <p><strong>It marks an ingredient this import creates. It never changes one the temple already
	 * has.</strong> Rajeev's curated set marks fifteen lines, every one of them water, and the point
	 * of the mark is that water never reaches a shopping list again (T-402, V153). Creating Water
	 * already marked is how a temple loading the catalogue gets that for nothing — and the temple that
	 * already holds a Water it buys keeps buying it until somebody says otherwise.
	 *
	 * <p>The reason for the second half is the permission. Copying a library recipe is
	 * {@code MANAGE_RECIPES}, which all three kitchen roles hold; marking an ingredient the temple
	 * never buys is {@code MANAGE_BUYING_POLICY}, which T-402 gave to the Temple Admin alone, behind
	 * an endpoint of its own that is audited on every move. If an import could flip the flag on a row
	 * the temple owns, a Kitchen Manager copying a recipe would set a flag they are not allowed to
	 * set, and it would happen with nothing on the screen to say so. The same reasoning
	 * {@link #create} already gives for the Ekadashi flag applies with more force here, because this
	 * one silently removes something from what the temple orders.
	 *
	 * <p>Creating a <em>new</em> row marked is a different act and not the same objection: the temple
	 * held no opinion about an ingredient that did not exist a moment ago, the authority behind the
	 * mark is the platform operator who curated the book (writing the library is
	 * {@code MANAGE_RECIPE_LIBRARY}), and the mistake it can make is reversible in the safe
	 * direction — an ingredient wrongly marked shows up as something missing from an order, which is
	 * the thing a store keeper notices, and one click on the ingredient clears it. Each such creation
	 * writes {@code INGREDIENT_NOT_BOUGHT_CHANGED} exactly as the ingredient form does, so "who said
	 * we never buy this" has one action to grep for whichever route set it.
	 *
	 * <p><strong>The mark is per ingredient, not per line.</strong> The keys are collected across the
	 * whole recipe before anything is resolved, so two lines naming the same thing cannot give
	 * different answers depending on which came first — a recipe whose water is marked on one line and
	 * not on another marks it, because a mark is a statement about water and not about a line.
	 *
	 * <p>Where the book marks a line and the temple's own row disagrees, the names come back in
	 * {@link Resolution#notBoughtNotApplied} and are written into the import's audit entry. Silence
	 * would be the one outcome nobody could act on.
	 */
	private Resolution resolveIngredients(AuthenticatedUser actor, List<LinePlan> plans,
			Map<String, CloseMatch> listed, Map<String, ImportCloseMatchDecision> answers) {
		Set<String> neverBought = neverBoughtKeys(plans);
		List<IngredientNameMatcher.Entry> createdHere = new ArrayList<>();
		List<ResolvedIngredient> resolved = new ArrayList<>();
		// The temple's own rows that the book says it never buys, by id so that two lines naming the
		// same one are asked about once. Whether each really disagrees is read from the database
		// afterwards, in one statement — the catalogue this import matched against carries names and
		// aliases, not flags.
		Map<UUID, String> alreadyTheirs = new LinkedHashMap<>();

		for (LinePlan plan : plans) {
			MasterRecipeView.MasterRecipeIngredient line = plan.line();
			boolean neverBuys = neverBought.contains(plan.key());

			if (plan.exact() != null) {
				if (neverBuys) {
					alreadyTheirs.put(plan.exact().id(), plan.exact().name());
				}
				resolved.add(new ResolvedIngredient(plan.exact().id(), plan.exact().name(), plan.note(),
						line.qtyValue(), line.qtyUnit(), false));
				continue;
			}

			if (plan.close() != null
					&& answers.get(plan.key()).useIngredientId() != null) {
				ResolvedIngredient used = answeredCloseMatch(plan);
				if (neverBuys) {
					alreadyTheirs.put(used.id(), used.name());
				}
				resolved.add(used);
				continue;
			}

			Optional<IngredientNameMatcher.Match> again = IngredientNameMatcher.findMatch(plan.base(), createdHere);
			if (again.isPresent() && again.get().kind() == IngredientNameMatcher.MatchKind.EXACT) {
				// A row this very import created, so it already carries whatever the book said: the
				// keys were collected across the whole recipe before the first one was written.
				// Nothing to mark and nothing to disagree with.
				IngredientNameMatcher.Entry found = again.get().entry();
				resolved.add(new ResolvedIngredient(found.id(), found.name(), plan.note(),
						line.qtyValue(), line.qtyUnit(), false));
				continue;
			}

			ResolvedIngredient created = create(actor, plan.base(), plan.note(), line, neverBuys);
			if (plan.close() != null) {
				auditConfirmedDifferent(actor, created, line, listed.get(plan.key()).match(), neverBuys);
			}
			createdHere.add(new IngredientNameMatcher.Entry(created.id(), created.name()));
			resolved.add(created);
		}
		return new Resolution(resolved, stillBought(alreadyTheirs));
	}

	/**
	 * What the resolution produced: every line's ingredient, and the names of the temple's own
	 * ingredients the book says it never buys and that the import left alone.
	 */
	private record Resolution(List<ResolvedIngredient> lines, List<String> notBoughtNotApplied) {
	}

	/**
	 * The normalised base names any line of this recipe marks {@code not_bought}, collected before
	 * anything is resolved. Read {@link #resolveIngredients} for why it is a set of ingredients rather
	 * than a property of each line.
	 */
	private static Set<String> neverBoughtKeys(List<LinePlan> plans) {
		Set<String> keys = new LinkedHashSet<>();
		for (LinePlan plan : plans) {
			if (plan.line().notBought()) {
				keys.add(plan.key());
			}
		}
		return keys;
	}

	/**
	 * Of the temple's own ingredients the book marks, the ones the temple still buys — the
	 * disagreements worth recording. Empty in the ordinary case, including the one that matters most:
	 * a temple loading the curated catalogue creates Water marked on the first recipe that names it,
	 * and every recipe after that agrees with the row it finds.
	 *
	 * <p>One statement for all of them, and the names come from the row rather than from the match,
	 * because it is the temple's name for the thing that a person reading the audit will recognise.
	 */
	private List<String> stillBought(Map<UUID, String> candidates) {
		if (candidates.isEmpty()) {
			return List.of();
		}
		String placeholders = String.join(",", java.util.Collections.nCopies(candidates.size(), "?"));
		return jdbc.queryForList(
				"SELECT name FROM ingredients WHERE id IN (" + placeholders + ") AND NOT is_not_bought"
						+ " ORDER BY name",
				String.class, candidates.keySet().toArray());
	}

	/**
	 * A close match the person answered "Use Tomato, ripe" (Q-11, Rajeev 2026-09-19). Until his
	 * answer this method held the line and created an ingredient, because an import with nobody there
	 * to ask could not tell a slip from a second ingredient. Now somebody is asked, on the copy
	 * screen, before anything is written.
	 *
	 * <p>The line goes on the existing ingredient and keeps its note (R-DUP-1): "Tomatos, chopped"
	 * becomes Tomato, ripe · chopped. Whatever else the library wrote that is not the chosen
	 * ingredient's name goes on the note too (A-N5, T-297): "Ginger, peeled" becomes Ginger · peeled.
	 * That is {@link LinePlan#useNote}, worked out in {@link #plan}. Nothing is created. {@link #answersFor} has already checked that
	 * the id answered is the one listed. The other answer, "It's a different ingredient", takes the
	 * unmatched path in {@link #resolveIngredients} and is audited there.
	 */
	private static ResolvedIngredient answeredCloseMatch(LinePlan plan) {
		IngredientNameMatcher.Entry existing = plan.close().entry();
		return new ResolvedIngredient(existing.id(), existing.name(), plan.useNote(),
				plan.line().qtyValue(), plan.line().qtyUnit(), false);
	}

	// ------------------------------------------------------------------ the rest of the typed name (A-N5)

	/**
	 * What is left of {@code base} once the chosen ingredient's name is taken out of it, or null when
	 * nothing is — the words that become the line's note when the person answers "Use Ginger" (A-N5,
	 * conductor's ruling of 2026-09-19, T-297).
	 *
	 * <p><strong>Why this is not the preparation-word list.</strong> {@link IngredientNameMatcher#split}
	 * only moves a qualifier onto the note when it is a word somebody has ruled is a preparation, and
	 * that list is held on Q-12. So "Ginger, peeled" stays whole, is only a close match for Ginger, and
	 * until this method existed "Use Ginger" put the line on Ginger with no note at all: the recipe
	 * lost "peeled", and "Mustard, split" lost "split", which changes what is bought. Here the person
	 * has already said the line <em>is</em> Ginger, so the question the list answers — is "peeled"
	 * part of what the temple buys? — has been answered by them for this line, and whatever is not
	 * "Ginger" is simply the rest of what the library wrote. R-DUP-2's prompt says the same thing to
	 * the ingredient form: "Use Curd, or add a preparation note instead."
	 *
	 * <p>The rules, in order, tried against the name the match was made on (an alias, if it was one)
	 * and then the ingredient's own name, the first that leaves something winning:
	 * <ol>
	 *   <li><strong>With a comma</strong>, the part before the first comma is compared with the chosen
	 *       name the way the matcher compares (plural, case, a one-letter slip), and when it is the
	 *       same, every segment after it is the note, as written, joined with ", ": "Ginger, peeled" →
	 *       "peeled"; "Tomatos, ripe" + Tomato → "ripe"; "Rice, basmati, aged" + Rice → "basmati,
	 *       aged". As written, not lower-cased, because {@code split} keeps a comma tail as written
	 *       too.</li>
	 *   <li><strong>Without a comma</strong>, the chosen name must be the first or the last words of
	 *       the typed name, and the words on the other side are the note, lower case: "Thick curd" +
	 *       Curd → "thick"; "Curd thick" + Curd → "thick". Lower case because that is what
	 *       {@code split} does with a word it takes off the front or the end ("Sour curd" → "sour").
	 *       Only the ends: "Hot curd rice" + Curd is not taken apart, because "hot rice" would be a
	 *       note nobody wrote. (The matcher does not call a bare two-word name close to a one-word one
	 *       today, so this rule only matters if that ever changes; it is here so that the answer does
	 *       not silently depend on it.)</li>
	 *   <li><strong>Nothing left</strong> — the typed name is a spelling of the chosen one ("Tomatos"
	 *       + Tomato, "Greenchilli" + Green chilli), or it is the bare side of a bare-and-qualified
	 *       pair ("Tomatos" + "Tomato, ripe") — gives null, and the line has only whatever note
	 *       {@code split} gave it, exactly as before.</li>
	 * </ol>
	 */
	static String leftOver(String base, IngredientNameMatcher.Match match) {
		String left = leftOver(base, match.matchedName());
		return left != null ? left : leftOver(base, match.entry().name());
	}

	private static String leftOver(String base, String chosen) {
		if (base == null || chosen == null || base.isBlank()) {
			return null;
		}
		String typed = base.trim().replaceAll("\\s+", " ");
		String key = IngredientNameMatcher.normalise(chosen);
		if (key.isEmpty() || IngredientNameMatcher.normalise(typed).equals(key)) {
			return null;
		}
		int comma = typed.indexOf(',');
		if (comma >= 0) {
			String head = typed.substring(0, comma).trim();
			if (!sameName(head, chosen)) {
				return null;
			}
			List<String> tails = new ArrayList<>();
			for (String tail : typed.substring(comma + 1).split(",")) {
				if (!tail.isBlank()) {
					tails.add(tail.trim());
				}
			}
			return tails.isEmpty() ? null : String.join(", ", tails);
		}
		if (chosen.indexOf(',') >= 0) {
			return null;
		}
		String[] words = typed.split(" ");
		int size = IngredientNameMatcher.normalise(chosen).split(" ").length;
		if (size >= words.length) {
			return null;
		}
		String front = String.join(" ", Arrays.copyOfRange(words, 0, size));
		String end = String.join(" ", Arrays.copyOfRange(words, words.length - size, words.length));
		if (sameName(end, chosen)) {
			return String.join(" ", Arrays.copyOfRange(words, 0, words.length - size))
					.toLowerCase(Locale.ROOT);
		}
		if (sameName(front, chosen)) {
			return String.join(" ", Arrays.copyOfRange(words, size, words.length))
					.toLowerCase(Locale.ROOT);
		}
		return null;
	}

	/** Whether {@code typed} is {@code chosen} by the matcher's own rule: the same, or a one-letter slip. */
	private static boolean sameName(String typed, String chosen) {
		return IngredientNameMatcher.findMatch(typed, List.of(new IngredientNameMatcher.Entry(null, chosen)))
				.filter(m -> m.kind() == IngredientNameMatcher.MatchKind.EXACT
						|| IngredientNameMatcher.normalise(typed).split(" ").length
								== IngredientNameMatcher.normalise(chosen).split(" ").length)
				.isPresent();
	}

	/**
	 * The preparation {@code split} found and what {@link #leftOver} found, as one note, in the order
	 * the library wrote them: "Rice, basmati, soaked" + Rice gives "basmati, soaked", not "soaked,
	 * basmati". Either alone is returned as it is — so a line with no left-over keeps exactly the note
	 * it had before T-297.
	 */
	static String combineNotes(String raw, String prepared, String left) {
		if (left == null) {
			return prepared;
		}
		if (prepared == null || prepared.equalsIgnoreCase(left)) {
			return left;
		}
		String written = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
		int p = written.indexOf(prepared.split(", ")[0].toLowerCase(Locale.ROOT));
		int l = written.indexOf(left.split(", ")[0].toLowerCase(Locale.ROOT));
		// Not found (the split normalised spacing) sorts last, so the rarer case still reads sensibly.
		p = p < 0 ? Integer.MAX_VALUE : p;
		l = l < 0 ? Integer.MAX_VALUE : l;
		return l < p ? left + ", " + prepared : prepared + ", " + left;
	}

	/**
	 * "It's a different ingredient", confirmed, recorded the way the ingredient form records the same
	 * override (R-DUP-2, {@code IngredientService.create}): an {@code INGREDIENT_ADDED} entry on the new
	 * ingredient, whose reason names what it looked like and whose {@code after_state} carries
	 * {@code confirmedDifferentFrom}. The same shape, so that anyone reviewing overrides finds the
	 * import's among the form's with one query. The spec says the override "is audited", and it is the
	 * one decision here a reviewer later needs to be able to find and question.
	 *
	 * <p>Only this path writes an ingredient entry. An unmatched ingredient an import creates has never
	 * had one — the import's own {@code RECIPE_IMPORTED} entry counts them — and still does not.
	 */
	private void auditConfirmedDifferent(AuthenticatedUser actor, ResolvedIngredient created,
			MasterRecipeView.MasterRecipeIngredient line, IngredientNameMatcher.Match match,
			boolean neverBought) {
		Map<String, Object> lookalike = new LinkedHashMap<>();
		lookalike.put("id", match.entry().id().toString());
		lookalike.put("name", match.entry().name());
		lookalike.put("matchedName", match.matchedName());
		lookalike.put("kind", match.kind().name());

		Map<String, Object> after = new LinkedHashMap<>();
		after.put("name", created.name());
		after.put("category", IngredientCategories.forName(created.name()));
		after.put("unit", line.qtyUnit());
		after.put("ekadashiProhibited", false);
		after.put("supply", false);
		// Beside supply, in the order IngredientService.snapshot writes them, so the two routes'
		// entries still read as one shape (T-403). Unlike the two above it is not always false: an
		// ingredient created from a line the book marks arrives marked.
		after.put("notBought", neverBought);
		after.put("aliases", List.of());
		after.put("libraryDerived", true);
		after.put("confirmedDifferentFrom", lookalike);

		audit.record(actor, AuditAction.INGREDIENT_ADDED, AuditEntityType.INGREDIENT, created.id(),
				null, after,
				"Added although it looks like “" + match.entry().name()
						+ "”: confirmed as a different ingredient.");
	}

	/**
	 * Creates {@code base} as a library-derived ingredient of this temple, with the note on the line
	 * and — where the book says the temple never buys it — the mark that keeps it off every shopping
	 * list (T-403, V153).
	 */
	private ResolvedIngredient create(AuthenticatedUser actor, String base, String note,
			MasterRecipeView.MasterRecipeIngredient line, boolean neverBought) {
		// The catalogue unit is the one the recipe asked in: an ingredient first met as "200 gm"
		// is catalogued in grams, and every later recipe and stock movement speaks that unit.
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO ingredients (
					id, tenant_id, name, category, canonical_unit, library_derived, is_not_bought)
				VALUES (?, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, true, ?)
				""", id, base, IngredientCategories.forName(base), line.qtyUnit(), neverBought);

		if (neverBought) {
			// The same second entry the ingredient form writes when a row is created already marked,
			// and for the reason its comment gives: "who said we never buy this" should have one
			// action to grep for, whichever route set it. IngredientService keeps that write private
			// to itself, so this is the same two maps written here rather than a call across —
			// asserted against each other in RecipeImportNotBoughtIT so the shapes cannot drift.
			audit.record(actor, AuditAction.INGREDIENT_NOT_BOUGHT_CHANGED, AuditEntityType.INGREDIENT,
					id,
					Map.of("name", base, "notBought", false),
					Map.of("name", base, "notBought", true),
					null);
		}

		// Nothing the import creates arrives pre-flagged for Ekadashi either: the flag is a Temple
		// Admin's to set, and the import cannot tell a grain from a spice. That is the gap the
		// warning box on the Recipes page exists to make honest (D-18). The buying mark above is not
		// the same case and the difference is the whole of T-403's argument: nobody has to guess it,
		// because the book states it outright on the line.
		return new ResolvedIngredient(id, base, note, line.qtyValue(), line.qtyUnit(), true);
	}

	/**
	 * This temple's ingredients with their aliases, as the matcher reads them. RLS scopes both
	 * queries to the temple: another temple's Green chilli is never a match.
	 *
	 * <p>Read once per request and not added to: what the import creates is tracked apart from it
	 * (see {@link #plan}).
	 */
	private List<IngredientNameMatcher.Entry> catalogue() {
		Map<UUID, List<String>> aliases = new LinkedHashMap<>();
		jdbc.query("SELECT ingredient_id, alias FROM ingredient_aliases ORDER BY created_at, id", rs -> {
			aliases.computeIfAbsent(rs.getObject("ingredient_id", UUID.class), k -> new ArrayList<>())
					.add(rs.getString("alias"));
		});
		// Oldest first, so that where two ingredients tie as a match the one the temple has had
		// longest wins — the matcher breaks a tie by the order it was given. That is the same
		// preference V144's alias backfill applies, for the same reason.
		List<IngredientNameMatcher.Entry> out = new ArrayList<>();
		jdbc.query("SELECT id, name FROM ingredients ORDER BY created_at, id", rs -> {
			UUID id = rs.getObject("id", UUID.class);
			out.add(new IngredientNameMatcher.Entry(id, rs.getString("name"),
					aliases.getOrDefault(id, List.of())));
		});
		return out;
	}

	/** See {@code MasterRecipeService.pgArray} — same reason, same escaping. */
	private static String pgArray(List<String> values) {
		if (values == null || values.isEmpty()) {
			return "{}";
		}
		StringBuilder out = new StringBuilder("{");
		for (int i = 0; i < values.size(); i++) {
			if (i > 0) {
				out.append(',');
			}
			out.append('"')
					.append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\""))
					.append('"');
		}
		return out.append('}').toString();
	}
}
