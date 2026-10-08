package org.iskcon.kms.translation;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.ingredient.IngredientNames;
import org.iskcon.kms.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gives every stock item a name in the temple's glossary, translating by machine only the ones
 * nobody has named (E10-T8c).
 *
 * <h2>Why this exists</h2>
 *
 * <p>The Kitchen Assistant answers in Kannada. Left to name stock itself, the model spelt 12 of 28
 * items more than one way across five repeats of one question (docs/work/proof/E10-T8b.md). A cook
 * matching a word to a sack needs the same word every time, so Rajeev decided on 2026-10-04 that
 * names come from the app, never the model. Nothing in the app stored a machine name on its own —
 * recipes, purchase orders and work orders each translate afresh — so this makes the glossary the
 * store: one row per item, written once, read by everything after.
 *
 * <h2>Why a one-off action and not a lookup that fills as it goes</h2>
 *
 * <p>The assistant's tools are read-only, and should stay so: a translation inside a tool call
 * would be a paid network call and a database write on a path that today does neither, on behalf
 * of a model. So the fill is something a person with the glossary's own permission runs, and the
 * tools only ever read what it left.
 *
 * <h2>The rules it keeps</h2>
 *
 * <ul>
 *   <li><b>Never overwrites.</b> An item with any entry in the language — typed by a person or
 *       written by an earlier fill — is skipped, and the insert is {@code ON CONFLICT DO NOTHING}
 *       besides, so two fills racing cannot replace each other's rows either. A machine name is
 *       stable from the moment it is written until a person corrects it.
 *   <li><b>Safe to run twice.</b> The second run finds nothing missing and calls no translator.
 *   <li><b>Marked as the machine's.</b> Each row carries {@code machine_translated_at} and
 *       {@code machine_provider} (V161), so a later screen can list the names nobody has checked.
 *       That matters: Google rendered "mace" as the weapon when this was built.
 *   <li><b>The machine hears the name read aloud.</b> {@link Translatable#ingredientName} sends
 *       "Rice, broken" as "broken rice" while the glossary row is keyed on the name as filed, which
 *       is the key every reader looks it up by.
 *   <li><b>One temple.</b> Every statement runs on the caller's connection under the table's
 *       row-level security; nothing here names a tenant except the insert, which takes it from the
 *       session setting the verified token put there, as {@link GlossaryService#upsert} does.
 * </ul>
 *
 * <p>Not audited: no glossary change is today (adding, correcting and deleting a term write no
 * audit entry), and the rows themselves carry who-wrote-it in the machine mark.
 *
 * <p>The translation call happens inside the transaction. That holds one connection for the length
 * of one batched request, once, when a person presses a button, and in exchange a failed
 * translation leaves the glossary exactly as it was.
 *
 * <h2>Claude, for any language, by itself (E10-NAMES-A)</h2>
 *
 * <p>Google's names were often wrong for a kitchen (E10-T8c: rice as paddy, beaten rice as cooked
 * rice), and Claude's were the cook's own words (E10-T8d: 119 Kannada names, no drift after). Rajeev
 * decided on 2026-10-08 that item names must be in the conversation's language and stable, and that
 * when a temple has no stored names in a language the server asks Claude once, through the runner,
 * for every stock item, and stores them marked as the machine's without overwriting anything.
 * {@link #ensureNames} is that: cheap to call at the start of every conversation, it starts a fill
 * in the background only when items are missing, and says whether names are ready or on their way.
 * {@link #fillWithClaude} is the fill itself. The Google fill above is kept as it was; the row's
 * {@code machine_provider} ({@code 'claude'} or {@code 'google'}) tells them apart. Items added to
 * the store later are filled the same way, the next time {@link #ensureNames} finds them missing.
 *
 * <p>The rules above all still hold, and three more:
 *
 * <ul>
 *   <li><b>One fill per temple and language at a time, across every api instance.</b> A
 *       session-level PostgreSQL advisory lock keyed on the temple and the language, taken with
 *       {@code pg_try_advisory_lock} before anything is read: a second fill that cannot take it
 *       returns at once without calling the runner. After taking it the fill re-reads what is
 *       missing, so one that started just after another finished finds nothing to do. The lock is
 *       released in {@code finally}, and by PostgreSQL itself if the instance dies, so there is no
 *       "in progress" row to go stale and no migration. Its cost is one pooled connection held for
 *       the length of the runner call (about 80 seconds for 120 items), once per language.
 *   <li><b>Nothing the runner says is stored unchecked.</b> Each answer must carry its item's
 *       number, that item's English copied back (so an answer shifted by a line is refused rather
 *       than filed under the neighbouring item), and a name wholly in the language's script
 *       ({@link LanguageScripts}). An item that fails is left unnamed, and so are both answers to an
 *       item answered twice; the rest of the batch is stored in one transaction.
 *   <li><b>The temple comes from the caller's verified context, on whichever thread.</b> The tenant
 *       passed in must be the one already in {@link TenantContext}; the background thread is given
 *       that same tenant before it touches the database, and clears it afterwards, so the
 *       connection it checks out carries {@code app.tenant_id} and row-level security applies.
 * </ul>
 */
@Service
public class StockNameFillService {

	private static final Logger log = LoggerFactory.getLogger(StockNameFillService.class);

	/**
	 * Strings per translation request. Google's v3 limit is 1,024 strings and 30,000 code points per
	 * call; stock names are short, and a temple has about 120 of them, so this is one call in
	 * practice and a guard against a catalogue that grows.
	 */
	static final int BATCH = 100;

	/**
	 * Items per call to Claude. E10-T8d sent 119 in one call and got 119 back in 64 to 79 seconds;
	 * one call keeps related items side by side, so the model names them consistently. Past this a
	 * second call is made, which keeps a much larger store inside the runner's five minutes.
	 */
	static final int CLAUDE_BATCH = 150;

	static final String CLAUDE = "claude";

	/**
	 * How long after a fill that failed, or left items it could not name, before {@link #ensureNames}
	 * tries again. Without it, an item Claude will not name would cost a paid call on every message.
	 */
	static final Duration RETRY_AFTER = Duration.ofMinutes(10);

	private final JdbcTemplate jdbc;
	private final TranslationProvider provider;
	private final DataSource dataSource;
	private final NameFillRunnerClient runner;

	/** Fills started from this instance and not yet finished, by temple and language. */
	private final Set<String> filling = ConcurrentHashMap.newKeySet();

	/**
	 * Fills from this instance that ended short, by temple and language: when, and whether they
	 * got no answer at all (true) or only left some items unnamed (false). Held in memory because it
	 * is a guard on cost, not on correctness: an instance that restarts tries once more, and the
	 * advisory lock still stops two fills running at once.
	 */
	private final Map<String, ShortEnd> endedShort = new ConcurrentHashMap<>();

	private record ShortEnd(Instant at, boolean failed) {
	}

	/**
	 * Two threads: a fill is one slow network call, and two temples (or two languages) starting
	 * together should not wait on each other. Daemon threads, so a fill never holds the JVM open.
	 */
	private final ExecutorService background = Executors.newFixedThreadPool(2, new ThreadFactory() {
		private final AtomicInteger n = new AtomicInteger();

		@Override
		public Thread newThread(Runnable r) {
			Thread t = new Thread(r, "glossary-name-fill-" + n.incrementAndGet());
			t.setDaemon(true);
			return t;
		}
	});

	public StockNameFillService(JdbcTemplate jdbc, TranslationProvider provider, DataSource dataSource,
			NameFillRunnerClient runner) {
		this.jdbc = jdbc;
		this.provider = provider;
		this.dataSource = dataSource;
		this.runner = runner;
	}

	@PreDestroy
	void stopBackground() {
		background.shutdownNow();
	}

	/**
	 * Translates and stores the name of every stock item that has no glossary entry in
	 * {@code language}.
	 *
	 * <p>"Stock item" is every row of {@code inventory_items} — the list the stock screen and the
	 * assistant's {@code get_inventory} read — not every ingredient in the recipe catalogue, which is
	 * thousands of names nobody is asking about.
	 */
	@Transactional
	public FillResult fillStockNames(String language) {
		// The filed names with no entry, compared as the glossary's unique key compares them
		// (lower(source_term)). DISTINCT ON the same key, because two items filed "Rice" and
		// "rice" would be one glossary row and a second insert would just be dropped.
		List<String> missing = missingStockNames(jdbc, language);
		int total = counts(jdbc, language)[0];
		if (missing.isEmpty()) {
			return new FillResult(language, total, 0, provider.name());
		}

		Map<String, String> named = translate(missing, language);
		int inserted = 0;
		for (Map.Entry<String, String> name : named.entrySet()) {
			inserted += jdbc.update("""
					INSERT INTO translation_glossary (
						tenant_id, language, source_term, target_term, machine_translated_at, machine_provider)
					VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, now(), ?)
					ON CONFLICT (tenant_id, language, lower(source_term)) DO NOTHING
					""", language, name.getKey(), name.getValue(), provider.name());
		}
		return new FillResult(language, total, inserted, provider.name());
	}

	/** Filed name to translation, in batches, skipping any the engine hands back blank. */
	private Map<String, String> translate(List<String> filedNames, String language) {
		Map<String, String> out = new LinkedHashMap<>();
		for (int from = 0; from < filedNames.size(); from += BATCH) {
			List<String> batch = filedNames.subList(from, Math.min(from + BATCH, filedNames.size()));
			List<String> spoken = new ArrayList<>(batch.size());
			for (String filed : batch) {
				spoken.add(Translatable.ingredientName(filed).forMachine());
			}
			List<String> translated = provider.translate(spoken, Languages.ENGLISH, language);
			for (int i = 0; i < batch.size() && i < translated.size(); i++) {
				String target = translated.get(i) == null ? "" : translated.get(i).strip();
				// The table refuses an empty target, and one bad string should not lose the rest:
				// a blank is left unnamed and the next fill tries it again.
				if (!target.isEmpty()) {
					out.put(batch.get(i), target);
				}
			}
		}
		return out;
	}

	/**
	 * What one fill did. {@code stockItems} is how many distinct stock names the temple has;
	 * {@code added} how many this run named; {@code provider} which engine wrote them.
	 */
	public record FillResult(String language, int stockItems, int added, String provider) {
	}

	// ---------------------------------------------------------------------------------------------
	// Claude, through the runner (E10-NAMES-A)
	// ---------------------------------------------------------------------------------------------

	/** Whether a temple's stock names in a language can be used now. */
	public enum NamesStatus {
		/** Nothing is being filled: every item that could be named has a name. Use what is stored. */
		READY,
		/** A fill is running (here or on another instance). Names stored so far can be used. */
		FILLING,
		/**
		 * The last fill from this instance got no usable answer; another is tried after
		 * {@link #RETRY_AFTER}. Names stored so far can be used; the rest stay English.
		 */
		FAILED
	}

	/**
	 * {@code stockItems} distinct stock names the temple has; {@code named} how many of them have a
	 * glossary name in {@code language}, written by anyone.
	 */
	public record NamesState(String language, NamesStatus status, int stockItems, int named) {
	}

	/**
	 * Makes sure the temple's stock items have names in {@code language}, without waiting for it.
	 * Returns at once; when items are missing and no fill is running or recently ended short, starts
	 * one in the background. Cheap enough to call at the start of every conversation: one count
	 * query when nothing is missing, and no runner call.
	 *
	 * @param tenant the temple, which must be the one the verified token put in {@link TenantContext}
	 */
	public NamesState ensureNames(UUID tenant, String language) {
		requireSupported(language);
		requireCurrentTenant(tenant);
		int[] counts = counts(jdbc, language);
		int stockItems = counts[0];
		if (Languages.ENGLISH.equals(language)) {
			// English is the name as filed; there is nothing to fill.
			return new NamesState(language, NamesStatus.READY, stockItems, stockItems);
		}
		int named = stockItems - counts[1];
		String key = key(tenant, language);
		if (filling.contains(key)) {
			return new NamesState(language, NamesStatus.FILLING, stockItems, named);
		}
		if (counts[1] == 0) {
			return new NamesState(language, NamesStatus.READY, stockItems, named);
		}
		ShortEnd last = endedShort.get(key);
		if (last != null && last.at().plus(RETRY_AFTER).isAfter(Instant.now())) {
			return new NamesState(language, last.failed() ? NamesStatus.FAILED : NamesStatus.READY, stockItems, named);
		}
		// add() is the claim: of two requests arriving together, one adds and starts the fill, the
		// other sees it already there and reports FILLING.
		if (!filling.add(key)) {
			return new NamesState(language, NamesStatus.FILLING, stockItems, named);
		}
		try {
			background.execute(() -> fillInBackground(tenant, language, key));
		} catch (RuntimeException e) {
			filling.remove(key);
			throw e;
		}
		return new NamesState(language, NamesStatus.FILLING, stockItems, named);
	}

	private void fillInBackground(UUID tenant, String language, String key) {
		// The pool's thread has no tenant of its own. Give it the caller's before any connection is
		// checked out, so TenantAwareDataSource sets app.tenant_id on it; clear it afterwards so the
		// thread's next fill cannot inherit this temple.
		TenantContext.set(tenant);
		try {
			ClaudeFillResult result = fillWithClaude(tenant, language);
			boolean failed = result.outcome() == ClaudeFillOutcome.FAILED;
			if (failed || result.rejected() > 0) {
				endedShort.put(key, new ShortEnd(Instant.now(), failed));
			} else {
				endedShort.remove(key);
			}
		} catch (RuntimeException e) {
			log.warn("name fill for {} failed: {}", language, e.toString());
			endedShort.put(key, new ShortEnd(Instant.now(), true));
		} finally {
			TenantContext.clear();
			filling.remove(key);
		}
	}

	/** How a {@link #fillWithClaude} ended. */
	public enum ClaudeFillOutcome {
		/** Claude was asked; {@code added} names were stored. */
		FILLED,
		/** Every stock item already had a name in the language; nobody was asked. */
		NOTHING_MISSING,
		/** Another fill for the same temple and language holds the lock; nobody was asked. */
		ALREADY_FILLING,
		/** The runner gave no usable answer; anything stored before the failure stays. */
		FAILED
	}

	/**
	 * One fill. {@code asked} items were sent to Claude, {@code added} names stored, {@code rejected}
	 * answers refused by the checks or never given (those items stay unnamed for a later fill).
	 */
	public record ClaudeFillResult(String language, ClaudeFillOutcome outcome, int stockItems, int asked,
			int added, int rejected, long millis) {
	}

	/**
	 * Asks Claude, through the runner's {@code name-fill} profile, for the name of every stock item
	 * with no glossary entry in {@code language}, and stores the ones that pass the checks, marked
	 * {@code machine_provider = 'claude'}. Waits for the answer: {@link #ensureNames} is the way to
	 * start it without waiting.
	 *
	 * <p>Deliberately not {@code @Transactional}: the runner call takes a minute or more, and a
	 * transaction open across it would hold an idle-in-transaction connection for all of it. Each
	 * batch's inserts get their own short transaction instead, once its answer is in and checked.
	 *
	 * @param tenant the temple, which must be the one the verified token put in {@link TenantContext}
	 */
	public ClaudeFillResult fillWithClaude(UUID tenant, String language) {
		requireSupported(language);
		requireCurrentTenant(tenant);
		long started = System.nanoTime();
		if (Languages.ENGLISH.equals(language)) {
			return new ClaudeFillResult(language, ClaudeFillOutcome.NOTHING_MISSING, 0, 0, 0, 0, 0);
		}
		String lockKey = "glossary-name-fill:" + key(tenant, language);
		try (Connection connection = dataSource.getConnection()) {
			// One connection for the whole fill: a session advisory lock belongs to the connection
			// that took it, so the same one must release it before going back to the pool.
			JdbcTemplate on = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
			Boolean locked = on.queryForObject(
					"SELECT pg_try_advisory_lock(hashtextextended(?, 0))", Boolean.class, lockKey);
			if (!Boolean.TRUE.equals(locked)) {
				return new ClaudeFillResult(language, ClaudeFillOutcome.ALREADY_FILLING, 0, 0, 0, 0,
						millisSince(started));
			}
			try {
				return fillLocked(on, connection, language, started);
			} finally {
				if (!connection.getAutoCommit()) {
					connection.rollback();
					connection.setAutoCommit(true);
				}
				on.queryForObject("SELECT pg_advisory_unlock(hashtextextended(?, 0))", Boolean.class, lockKey);
			}
		} catch (SQLException e) {
			throw new IllegalStateException("name fill could not use its connection", e);
		}
	}

	private ClaudeFillResult fillLocked(JdbcTemplate on, Connection connection, String language, long started)
			throws SQLException {
		List<String> missing = missingStockNames(on, language);
		int stockItems = counts(on, language)[0];
		if (missing.isEmpty()) {
			return new ClaudeFillResult(language, ClaudeFillOutcome.NOTHING_MISSING, stockItems, 0, 0, 0,
					millisSince(started));
		}
		int added = 0;
		int rejected = 0;
		for (int from = 0; from < missing.size(); from += CLAUDE_BATCH) {
			List<String> batch = missing.subList(from, Math.min(from + CLAUDE_BATCH, missing.size()));
			List<String> spoken = batch.stream().map(IngredientNames::readable).toList();
			JsonNode answer;
			try {
				answer = runner.name(message(language, spoken));
			} catch (NameFillRunnerClient.NameFillFailed e) {
				log.warn("name fill for {}: no usable answer for {} items ({}); {} stored before it",
						language, batch.size(), e.getMessage(), added);
				return new ClaudeFillResult(language, ClaudeFillOutcome.FAILED, stockItems, missing.size(), added,
						rejected, millisSince(started));
			}
			Checked checked = check(language, batch, spoken, answer);
			rejected += checked.rejected();
			added += insert(on, connection, language, checked.names());
		}
		log.info("name fill for {}: asked {}, stored {}, refused {} in {} ms", language, missing.size(), added,
				rejected, millisSince(started));
		return new ClaudeFillResult(language, ClaudeFillOutcome.FILLED, stockItems, missing.size(), added, rejected,
				millisSince(started));
	}

	/**
	 * The message the profile receives: the language by name and code, its script, and the items
	 * read aloud, numbered from 1. The rules are the profile's system prompt, not ours to send.
	 */
	static String message(String language, List<String> spoken) {
		StringBuilder m = new StringBuilder()
				.append("Language: ").append(Languages.englishName(language)).append(" (").append(language)
				.append("), written in ").append(LanguageScripts.scriptName(language)).append(" script.\n\nItems:\n");
		for (int i = 0; i < spoken.size(); i++) {
			m.append(i + 1).append(". ").append(spoken.get(i)).append('\n');
		}
		return m.toString();
	}

	private record Checked(Map<String, String> names, int rejected) {
	}

	/**
	 * The answers that pass, filed name to name. An answer passes when its number is one that was
	 * asked, the English it copied back is that item's (ignoring case and spacing), it is the only
	 * answer for that number, and its name is wholly in the language's script. Everything else is
	 * counted and logged, never stored; an item with no passing answer stays unnamed.
	 */
	private static Checked check(String language, List<String> filed, List<String> spoken, JsonNode answer) {
		Map<Integer, String> good = new HashMap<>();
		Set<Integer> seen = new HashSet<>();
		Set<Integer> twice = new HashSet<>();
		List<String> problems = new ArrayList<>();
		JsonNode names = answer.path("names");
		if (names.isArray()) {
			for (JsonNode entry : names) {
				int n = entry.path("n").isIntegralNumber() ? entry.path("n").asInt() : -1;
				if (n < 1 || n > filed.size()) {
					problems.add("unknown number " + entry.path("n"));
					continue;
				}
				if (!seen.add(n)) {
					twice.add(n);
					continue;
				}
				String english = entry.path("english").asText("");
				if (!sameWords(english, spoken.get(n - 1))) {
					problems.add(n + " copied back as \"" + english + "\", sent as \"" + spoken.get(n - 1) + "\"");
					continue;
				}
				String name = LanguageScripts.tidy(entry.path("name").asText(""));
				if (!LanguageScripts.isWrittenIn(language, name)) {
					problems.add(n + " \"" + spoken.get(n - 1) + "\": not a plain name in the script: \"" + name + "\"");
					continue;
				}
				good.put(n, name);
			}
		}
		for (int n : twice) {
			good.remove(n);
			problems.add(n + " \"" + spoken.get(n - 1) + "\" answered more than once");
		}
		Map<String, String> out = new LinkedHashMap<>();
		for (int i = 0; i < filed.size(); i++) {
			String name = good.get(i + 1);
			if (name != null) {
				out.put(filed.get(i), name);
			}
		}
		int rejected = filed.size() - out.size();
		if (rejected > 0) {
			log.warn("name fill for {}: {} of {} items not stored: {}", language, rejected, filed.size(), problems);
		}
		return new Checked(out, rejected);
	}

	private static boolean sameWords(String a, String b) {
		return a.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT)
				.equals(b.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT));
	}

	/** One transaction per batch: all of its checked names or, if anything fails, none. */
	private static int insert(JdbcTemplate on, Connection connection, String language, Map<String, String> names)
			throws SQLException {
		if (names.isEmpty()) {
			return 0;
		}
		connection.setAutoCommit(false);
		int inserted = 0;
		for (Map.Entry<String, String> name : names.entrySet()) {
			inserted += on.update("""
					INSERT INTO translation_glossary (
						tenant_id, language, source_term, target_term, machine_translated_at, machine_provider)
					VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, now(), ?)
					ON CONFLICT (tenant_id, language, lower(source_term)) DO NOTHING
					""", language, name.getKey(), name.getValue(), CLAUDE);
		}
		connection.commit();
		connection.setAutoCommit(true);
		return inserted;
	}

	/**
	 * The filed stock names with no entry in the language, compared as the glossary's unique key
	 * compares them (lower(source_term)). DISTINCT ON the same key, because two items filed "Rice"
	 * and "rice" would be one glossary row and a second insert would just be dropped.
	 */
	private static List<String> missingStockNames(JdbcTemplate on, String language) {
		return on.queryForList("""
				SELECT DISTINCT ON (lower(i.name)) i.name
				FROM inventory_items ii
				JOIN ingredients i ON i.id = ii.ingredient_id
				WHERE NOT EXISTS (
					SELECT 1 FROM translation_glossary g
					WHERE g.language = ? AND lower(g.source_term) = lower(i.name))
				ORDER BY lower(i.name), i.name
				""", String.class, language);
	}

	/** {distinct stock names, how many of them have no entry in the language}, both under RLS. */
	private static int[] counts(JdbcTemplate on, String language) {
		return on.queryForObject("""
				SELECT count(DISTINCT lower(i.name)),
				       count(DISTINCT lower(i.name)) FILTER (WHERE NOT EXISTS (
				           SELECT 1 FROM translation_glossary g
				           WHERE g.language = ? AND lower(g.source_term) = lower(i.name)))
				FROM inventory_items ii
				JOIN ingredients i ON i.id = ii.ingredient_id
				""", (rs, n) -> new int[] {rs.getInt(1), rs.getInt(2)}, language);
	}

	private static void requireSupported(String language) {
		if (!Languages.isSupported(language)) {
			throw new ApplicationException(ErrorCode.VALIDATION_FAILED,
					Map.of("field", "language", "value", String.valueOf(language)));
		}
	}

	/**
	 * The tenant is a parameter so a background fill can be handed it, but it is never a free
	 * choice: it must be the temple already in the caller's verified context. A mismatch is a
	 * programming error, and failing loudly beats filling the wrong temple's glossary (which RLS
	 * would refuse anyway, since the insert takes its tenant from the session).
	 */
	private static void requireCurrentTenant(UUID tenant) {
		UUID current = TenantContext.get().orElse(null);
		if (tenant == null || !tenant.equals(current)) {
			throw new IllegalStateException("name fill: the tenant must be the caller's own");
		}
	}

	private static String key(UUID tenant, String language) {
		return tenant + ":" + language;
	}

	private static long millisSince(long nanoStart) {
		return (System.nanoTime() - nanoStart) / 1_000_000;
	}

	/** Test hook: how many background fills this instance has running. */
	int fillsRunning() {
		return filling.size();
	}

	/** Test hook: forget this instance's retry timers, so one test's failure does not hold the next. */
	void forgetAttempts() {
		endedShort.clear();
	}
}
