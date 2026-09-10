package org.iskcon.kms.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.iskcon.kms.auth.Permission;
import org.iskcon.kms.auth.RolePermissions;
import org.iskcon.kms.user.User;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Whether an error's next step names a door the reader is <em>permitted</em> to open.
 *
 * <p><strong>Read the name of this class literally.</strong> It checks permissions and nothing
 * else. It is not a check that next steps are reachable, and it must never be renamed into one.
 * Six next steps in this product have named a door the reader could not open, and they failed in
 * three different ways:
 *
 * <ol>
 *   <li><strong>Permission.</strong> The door exists, the reader is in the right state, and the
 *       reader does not hold the permission. {@code KMS-400098} told kitchen staff to record a
 *       correction when {@link Permission#CORRECT_RECORDED_MEAL} is the Temple Admin's alone.
 *       <strong>This is the only one of the three this class can decide</strong>, because it is
 *       decidable from {@link RolePermissions} and the {@code @PreAuthorize} annotations, both of
 *       which are in the source tree and can be read.
 *   <li><strong>State.</strong> The door exists and the reader may open it, but not from where
 *       they are standing. {@code KMS-400143} said "send it first" to somebody the draft guard
 *       then refused. Deciding that needs each feature's state machine, which is not written down
 *       anywhere a test can read. <strong>Not covered here.</strong>
 *   <li><strong>Never built.</strong> The next step names something that does not exist at all.
 *       {@code KMS-400129} pointed at an action with no endpoint, no column and no control.
 *       Invisible to a permission cross-reference, which can only compare doors that are there.
 *       <strong>Not covered here.</strong>
 * </ol>
 *
 * <p>{@link ErrorCodeTest} beside this one enforces that every code <em>offers</em> an action, that
 * the words are plain and that they read as sentences. It cannot tell whether the action is
 * possible, which is why every suite stayed green through all six defects. This class narrows that
 * gap by exactly one of the three modes, and the report it writes says out loud how narrow that is.
 *
 * <h2>What it actually does</h2>
 *
 * <p>For each error code, three things are derived from the source tree — never from a list kept
 * by hand, because a hand-kept list of codes to actions is one refactor away from being fiction:
 *
 * <ul>
 *   <li><strong>Who meets the error.</strong> Every controller method carrying a
 *       {@code @PreAuthorize} is walked into the services it calls, within its own package, and
 *       every {@code ErrorCode.X} reachable from it is attributed to that method's permission. The
 *       union over all such methods is the code's audience.
 *   <li><strong>Which doors are in the same feature area.</strong> The permissions declared by the
 *       controllers of the packages the code is thrown in.
 *   <li><strong>Which of those doors the sentence names.</strong> By matching the words of the
 *       next step against the words of the permission's own name — {@code CORRECT_RECORDED_MEAL}
 *       against "record a correction to the meal". Two tokens must match and at most one may be
 *       missing, or the sentence is treated as naming no door at all.
 * </ul>
 *
 * <p>Then one assertion: <strong>every role that can meet the error holds the permission the next
 * step tells them to use.</strong> Plus its mirror for a next step that delegates — "ask a Temple
 * Admin to …" is safe only if a Temple Admin actually holds it.
 *
 * <h2>What it does not do, stated here so a green run is not over-read</h2>
 *
 * <p>The word-matching is deliberately conservative and the honest consequence is <strong>low
 * coverage</strong>: on the enum as it stands only a handful of the 148 codes are checked at all.
 * Every code it declines to check is listed, with the reason, in the report this test writes to
 * {@code build/reports/next-step-permission-audit.txt}. Read that file before believing anything
 * about this class's reach. Specifically it cannot see:
 *
 * <ul>
 *   <li>A door named in words the permission does not use. "Post a new shift instead" is
 *       {@link Permission#MANAGE_VOLUNTEER_SHIFTS} to a reader and nothing to a matcher, and
 *       teaching it that <em>post</em> means <em>create a shift</em> would be the hand-kept list
 *       this design exists to avoid. <strong>KMS-400058 is a live instance of exactly that</strong>
 *       and this check does not catch it.
 *   <li>A door in another feature area. Candidates are drawn only from the packages the code is
 *       thrown in, because widening the search to all 39 permissions makes the matching ambiguous
 *       far more often than it makes it right.
 *   <li>Anything an instruction shares a sentence with. Only imperative clauses are read; a clause
 *       that states a rule ("a cancelled meal never went to the kitchen") is skipped, and a clause
 *       wrongly judged to be one is skipped silently.
 * </ul>
 *
 * <p>Three defects of the permission kind were found by hand during T-095 and none of the three is
 * caught below — KMS-400043, KMS-400058 and KMS-400139. They are written up in
 * {@code docs/work/proof/T-095.md}. That is the measure of this check: it is worth having as a
 * guard on codes added from here, and it is not worth trusting as an audit.
 *
 * <h2>Why the vacuity guards matter</h2>
 *
 * <p>Every assertion here is of the form "nothing was found wrong", and a parser that quietly stops
 * matching — a refactor, a renamed annotation, a moved package — produces exactly that answer with
 * no work done. {@link #theModelThisCheckRestsOnIsActuallyPopulated()} exists so a green run means
 * the check ran rather than that it found nothing to run on.
 */
class NextStepPermissionTest {

	private static final Path SOURCE = Path.of("src/main/java/org/iskcon/kms");

	private static final Path REPORT = Path.of("build/reports/next-step-permission-audit.txt");

	private static Model model;

	@BeforeAll
	static void readTheSourceTree() throws IOException {
		model = Model.read(SOURCE);
	}

	// -----------------------------------------------------------------------------------------
	// The two assertions.
	// -----------------------------------------------------------------------------------------

	@Test
	@DisplayName("an instructing next step names no door its own reader lacks the permission for")
	void instructedDoorsAreOpenToEveryoneWhoCanMeetTheError() throws IOException {
		List<String> lies = new ArrayList<>();
		List<String> report = new ArrayList<>();

		for (ErrorCode code : ErrorCode.values()) {
			Verdict verdict = check(subject(code), model);
			report.add(verdict.line(code.reference()));
			if (verdict.kind() == Verdict.Kind.SHUT && !verdict.delegated()) {
				lies.add(verdict.line(code.reference()));
			}
		}
		writeReport(report);

		assertThat(lies)
				.as("each of these tells its reader to open a door their own permissions do not "
						+ "open — the shape of KMS-400098. Reword the next step, or widen the "
						+ "permission; do not widen this test")
				.isEmpty();
	}

	@Test
	@DisplayName("a next step that delegates names somebody who holds the permission")
	void delegatedNextStepsNameSomebodyWhoCanActuallyHelp() {
		List<String> lies = new ArrayList<>();

		for (ErrorCode code : ErrorCode.values()) {
			Verdict verdict = check(subject(code), model);
			if (verdict.kind() == Verdict.Kind.SHUT && verdict.delegated()) {
				lies.add(verdict.line(code.reference()));
			}
		}

		// "Ask your temple administrator to …" is the safest shape a next step has: it names a
		// person rather than an action, so it cannot be wrong about a screen. It can still be
		// wrong about the person. This is the half of that family a machine can settle.
		assertThat(lies)
				.as("these send the reader to somebody who does not hold the permission either")
				.isEmpty();
	}

	// -----------------------------------------------------------------------------------------
	// The negative control. T-095 asked for a deliberately unreachable next step that is not in
	// ErrorCode.java, so that a green run above is evidence the check works rather than evidence
	// it is inert.
	// -----------------------------------------------------------------------------------------

	@Test
	@DisplayName("the check catches a next step whose door the reader cannot open")
	void theCheckCatchesADoorTheReaderCannotOpen() {
		// KMS-400043 sharpened. The real code says "Register a replacement, or reinstate this item
		// if it's back in use." and the matcher cannot see the door in those words; this fixture
		// says the same thing in the permission's own vocabulary, which is what the matcher reads.
		// Everything else about it is real: the package, the audience and the policy all come from
		// the tree. Equipment condition is changed behind MANAGE_INVENTORY, which every cook holds,
		// and taking a scrapping back is REINSTATE_SCRAPPED_EQUIPMENT, which is the Temple Admin's
		// alone (D-15).
		Subject unreachable = new Subject(
				"Reinstate the scrapped equipment.",
				Set.of("equipment"),
				Set.of("MANAGE_INVENTORY"));

		Verdict verdict = check(unreachable, model);

		assertThat(verdict.kind())
				.as("the control must be caught; if this passes as OPEN or NOT_CHECKED the check "
						+ "above is inert and its green run means nothing")
				.isEqualTo(Verdict.Kind.SHUT);
		assertThat(verdict.door()).isEqualTo("REINSTATE_SCRAPPED_EQUIPMENT");
		assertThat(verdict.line("KMS-999001"))
				.contains("KITCHEN_MANAGER", "KITCHEN_STAFF", "REINSTATE_SCRAPPED_EQUIPMENT");
	}

	@Test
	@DisplayName("the same sentence behind the right permission is left alone")
	void theCheckDoesNotCryWolfWhenTheDoorIsOpen() {
		// The positive half of the control, and the reason it is here: a check that flags the
		// sentence regardless of who can reach it would also be "caught" by the test above.
		Subject reachable = new Subject(
				"Reinstate the scrapped equipment.",
				Set.of("equipment"),
				Set.of("REINSTATE_SCRAPPED_EQUIPMENT"));

		Verdict verdict = check(reachable, model);

		assertThat(verdict.kind()).isEqualTo(Verdict.Kind.OPEN);
		assertThat(verdict.door()).isEqualTo("REINSTATE_SCRAPPED_EQUIPMENT");
	}

	@Test
	@DisplayName("the check catches a delegation to somebody who cannot help either")
	void theCheckCatchesADelegationToARoleThatCannotHelp() {
		// A Temple Admin deliberately does not hold SIGN_UP_FOR_SHIFTS — signing up for seva is
		// the volunteer's own act. Sending a reader to one is therefore a dead end, and this is
		// the shape KMS-400020 had when it told somebody with no account to ask an administrator
		// to add them.
		Subject deadEnd = new Subject(
				"Ask a Temple Admin to sign up for the shift.",
				Set.of("shift"),
				Set.of("SIGN_UP_FOR_SHIFTS"));

		Verdict verdict = check(deadEnd, model);

		assertThat(verdict.kind()).isEqualTo(Verdict.Kind.SHUT);
		assertThat(verdict.delegated()).isTrue();
		assertThat(verdict.line("KMS-999002")).contains("TEMPLE_ADMIN", "SIGN_UP_FOR_SHIFTS");
	}

	// -----------------------------------------------------------------------------------------
	// Vacuity guards.
	// -----------------------------------------------------------------------------------------

	@Test
	@DisplayName("the model this check rests on is actually populated")
	void theModelThisCheckRestsOnIsActuallyPopulated() {
		// If the source scan silently stops finding things, every assertion above passes with no
		// work done and the suite reports the same green it reports when the product is correct.
		// That is the failure this project has been bitten by often enough to have a rule about.
		assertThat(model.permissionsByPackage())
				.as("no controller permissions were found — has @PreAuthorize moved or been renamed?")
				.hasSizeGreaterThan(25);

		assertThat(model.audienceByCode())
				.as("no error code could be traced back to an endpoint — has the call walk broken?")
				.hasSizeGreaterThan(100);

		long checked = Arrays.stream(ErrorCode.values())
				.map(code -> check(subject(code), model))
				.filter(v -> v.kind() != Verdict.Kind.NOT_CHECKED)
				.count();

		// Deliberately a floor and not an equality: the number will move as codes are added and
		// reworded, and an exact figure here would be a test of nothing but the wording. What it
		// guards is the matcher going dark and declining to check anything at all.
		assertThat(checked)
				.as("the matcher checked almost nothing — see %s for which codes it declined and "
						+ "why. Coverage is low by design, but not zero", REPORT)
				.isGreaterThanOrEqualTo(4);
	}

	// -----------------------------------------------------------------------------------------
	// The check itself.
	// -----------------------------------------------------------------------------------------

	/** What the check reads: a sentence, the feature areas it is raised in, and who can raise it. */
	private record Subject(String nextStep, Set<String> featurePackages, Set<String> raisedBehind) {
	}

	private static Subject subject(ErrorCode code) {
		return new Subject(
				code.whatToDo(),
				model.packagesByCode().getOrDefault(code.name(), Set.of()),
				model.audienceByCode().getOrDefault(code.name(), Set.of()));
	}

	private record Verdict(Kind kind, String door, boolean delegated, String detail) {

		enum Kind {
			/** Every reader who can meet the error can open the door the next step names. */
			OPEN,
			/** Some reader who can meet the error cannot. This is the defect. */
			SHUT,
			/** No door could be identified in this sentence, so nothing was decided. */
			NOT_CHECKED
		}

		String line(String reference) {
			return "%-10s %-14s %-32s %s".formatted(reference, kind, door == null ? "-" : door, detail);
		}
	}

	private static Verdict check(Subject subject, Model model) {
		boolean delegated = DELEGATES.matcher(subject.nextStep()).find();

		// A delegating sentence is read whole — the door it names sits inside "ask X to …". An
		// instructing one is read clause by clause, and only the imperative clauses count: a
		// clause restating the rule ("you can only sign up before it begins") names no door, and
		// reading it as though it did is how a check of this kind manufactures false alarms.
		String searchable = delegated ? subject.nextStep() : String.join(" ", imperativeClauses(subject.nextStep()));
		if (searchable.isBlank()) {
			return new Verdict(Verdict.Kind.NOT_CHECKED, null, delegated, "no instruction in this sentence");
		}

		Set<String> candidates = new LinkedHashSet<>();
		for (String pkg : subject.featurePackages()) {
			candidates.addAll(model.permissionsByPackage().getOrDefault(pkg, Set.of()));
		}
		if (candidates.isEmpty()) {
			return new Verdict(Verdict.Kind.NOT_CHECKED, null, delegated, "no guarded endpoint in this feature area");
		}

		Optional<String> named = doorNamedBy(searchable, candidates);
		if (named.isEmpty()) {
			return new Verdict(Verdict.Kind.NOT_CHECKED, null, delegated,
					"names no door of this area in the area's own words");
		}
		String door = named.get();
		Set<User.Role> holders = model.holdersOf(door);

		if (delegated) {
			Optional<User.Role> asked = delegatedTo(subject.nextStep());
			if (asked.isEmpty()) {
				return new Verdict(Verdict.Kind.NOT_CHECKED, door, true, "delegates to nobody this test can name");
			}
			boolean canHelp = holders.contains(asked.get());
			return new Verdict(canHelp ? Verdict.Kind.OPEN : Verdict.Kind.SHUT, door, true,
					"delegates to " + asked.get() + (canHelp ? ", who holds it" : ", who does NOT hold " + door));
		}

		Set<User.Role> audience = new LinkedHashSet<>();
		for (String permission : subject.raisedBehind()) {
			audience.addAll(model.holdersOf(permission));
		}
		if (audience.isEmpty()) {
			return new Verdict(Verdict.Kind.NOT_CHECKED, door, false, "no endpoint audience could be derived");
		}

		Set<User.Role> lacking = new LinkedHashSet<>(audience);
		lacking.removeAll(holders);
		if (lacking.isEmpty()) {
			return new Verdict(Verdict.Kind.OPEN, door, false, "reachable by " + audience + ", all of whom hold it");
		}
		return new Verdict(Verdict.Kind.SHUT, door, false,
				"reachable by " + lacking + " who do NOT hold " + door);
	}

	// -----------------------------------------------------------------------------------------
	// Reading English, as little of it as possible.
	// -----------------------------------------------------------------------------------------

	/**
	 * Sentence openers that are never a verb. A closed set of English function words, not a list
	 * about this product — that distinction is the whole reason it is allowed to exist here.
	 */
	private static final Set<String> NOT_A_VERB = Set.of(
			"a", "an", "the", "it", "its", "this", "that", "these", "those", "there", "they",
			"them", "he", "she", "we", "you", "your", "our", "i", "some", "any", "every",
			"everyone", "everybody", "nobody", "nothing", "no", "one", "only", "and", "or", "but",
			"so", "if", "when", "while", "because", "of", "in", "on", "at", "to", "for", "with",
			"from", "as", "than", "then", "both", "each", "all", "most", "more", "less", "either",
			"neither", "once");

	/** Modals and negations. A clause carrying one is stating a rule, not issuing an instruction. */
	private static final Pattern STATES_A_RULE = Pattern.compile(
			"\\b(can't|cannot|can only|could|would|should|must|never|isn't|is not|aren't|are not"
					+ "|won't|will not|doesn't|don't|has to|have to|there is|there's|it may|may have)\\b",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern DELEGATES = Pattern.compile(
			"\\bask (a|an|your|another|the)\\b|\\bcontact the platform operator\\b|\\bask us\\b",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern WORD = Pattern.compile("[A-Za-z']+");

	private static List<String> imperativeClauses(String text) {
		List<String> out = new ArrayList<>();
		for (String clause : text.split("[.;]")) {
			String trimmed = clause.strip();
			if (trimmed.isEmpty() || STATES_A_RULE.matcher(trimmed).find()) {
				continue;
			}
			Matcher first = WORD.matcher(trimmed);
			if (!first.find() || NOT_A_VERB.contains(first.group().toLowerCase(Locale.ROOT))) {
				continue;
			}
			out.add(trimmed);
		}
		return out;
	}

	private static Optional<User.Role> delegatedTo(String text) {
		String lower = text.toLowerCase(Locale.ROOT);
		if (lower.contains("platform operator")) {
			return Optional.of(User.Role.SUPER_ADMIN);
		}
		if (lower.contains("admin")) {
			return Optional.of(User.Role.TEMPLE_ADMIN);
		}
		return Optional.empty();
	}

	/**
	 * The door a sentence names, or nothing.
	 *
	 * <p>A permission's constant name is treated as its vocabulary: {@code CORRECT_RECORDED_MEAL}
	 * is the words <em>correct</em>, <em>record</em> and <em>meal</em>. Two of them must appear in
	 * the sentence and at most one may be missing, and the winner must be unique — a tie means the
	 * sentence could be naming either, and guessing would be worse than declining.
	 */
	private static Optional<String> doorNamedBy(String sentence, Set<String> candidates) {
		Set<String> words = stems(sentence);
		String best = null;
		int bestScore = 0;
		boolean tied = false;

		for (String permission : candidates) {
			Set<String> vocabulary = new LinkedHashSet<>();
			for (String token : permission.split("_")) {
				String stem = stem(token);
				if (!FILLER.contains(stem)) {
					vocabulary.add(stem);
				}
			}
			Set<String> matched = new HashSet<>(vocabulary);
			matched.retainAll(words);
			int missing = vocabulary.size() - matched.size();
			if (matched.size() < 2 || missing > 1) {
				continue;
			}
			if (matched.size() > bestScore) {
				best = permission;
				bestScore = matched.size();
				tied = false;
			} else if (matched.size() == bestScore) {
				tied = true;
			}
		}
		return tied || best == null ? Optional.empty() : Optional.of(best);
	}

	private static final Set<String> FILLER = Set.of("a", "an", "the", "for", "up", "own", "any", "of", "to");

	private static Set<String> stems(String text) {
		Set<String> out = new LinkedHashSet<>();
		Matcher matcher = WORD.matcher(text);
		while (matcher.find()) {
			out.add(stem(matcher.group()));
		}
		out.removeAll(FILLER);
		return out;
	}

	/**
	 * Crude suffix stripping, and crude on purpose: it exists to make "correction" and
	 * "CORRECT_", "recorded" and "RECORDED_", "payments" and "PAYMENTS" the same word. A real
	 * stemmer would be a dependency, and would not make this check see any more doors.
	 */
	private static String stem(String word) {
		String lower = word.toLowerCase(Locale.ROOT).replace("'", "");
		for (String suffix : new String[] {"ations", "ation", "ions", "ion", "ings", "ing", "ers", "er", "ed", "es", "s"}) {
			if (lower.length() - suffix.length() >= 4 && lower.endsWith(suffix)) {
				return lower.substring(0, lower.length() - suffix.length());
			}
		}
		return lower;
	}

	// -----------------------------------------------------------------------------------------
	// The model, read out of the source tree.
	// -----------------------------------------------------------------------------------------

	/**
	 * @param permissionsByPackage feature package to the permissions its controllers declare
	 * @param packagesByCode error code name to the packages it is mentioned in
	 * @param audienceByCode error code name to the permissions of the endpoints that can raise it
	 */
	private record Model(
			Map<String, Set<String>> permissionsByPackage,
			Map<String, Set<String>> packagesByCode,
			Map<String, Set<String>> audienceByCode) {

		Set<User.Role> holdersOf(String permission) {
			Permission parsed;
			try {
				parsed = Permission.valueOf(permission);
			} catch (IllegalArgumentException e) {
				return Set.of();
			}
			Set<User.Role> holders = new LinkedHashSet<>();
			for (User.Role role : User.Role.values()) {
				if (RolePermissions.has(role, parsed)) {
					holders.add(role);
				}
			}
			return holders;
		}

		static Model read(Path source) throws IOException {
			Map<String, Set<String>> permissionsByPackage = new TreeMap<>();
			Map<String, Set<String>> packagesByCode = new TreeMap<>();
			Map<String, Method> methods = new LinkedHashMap<>();
			Map<String, Set<String>> methodsByPackageAndName = new HashMap<>();
			List<Endpoint> endpoints = new ArrayList<>();

			try (Stream<Path> files = Files.walk(source)) {
				for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
					String body = withoutComments(Files.readString(file));
					String pkg = source.relativize(file).getParent() == null
							? ""
							: source.relativize(file).getParent().toString();
					String type = file.getFileName().toString().replace(".java", "");

					// The whole file's authorities are the feature area's doors. Read from the
					// file rather than the method so that a class-level annotation counts too.
					Matcher authority = AUTHORITY.matcher(body);
					while (authority.find()) {
						permissionsByPackage.computeIfAbsent(pkg, k -> new LinkedHashSet<>()).add(authority.group(1));
					}
					Matcher mentioned = ERROR_CODE.matcher(body);
					while (mentioned.find()) {
						packagesByCode.computeIfAbsent(mentioned.group(1), k -> new LinkedHashSet<>()).add(pkg);
					}

					Matcher classLevel = CLASS_AUTHORITY.matcher(body);
					String classPermission = classLevel.find() ? classLevel.group(1) : null;

					int previousEnd = 0;
					for (Span span : spans(body)) {
						String preamble = body.substring(previousEnd, span.start());
						previousEnd = span.end();

						Matcher own = AUTHORITY.matcher(preamble);
						String permission = own.find() ? own.group(1) : classPermission;

						String key = pkg + "#" + type + "#" + span.name();
						methods.put(key, new Method(pkg, codesIn(span.body()), callsIn(span.body())));
						methodsByPackageAndName
								.computeIfAbsent(pkg + "#" + span.name(), k -> new LinkedHashSet<>())
								.add(key);
						if (type.endsWith("Controller") && permission != null) {
							endpoints.add(new Endpoint(pkg, span.name(), permission));
						}
					}
				}
			}

			Map<String, Set<String>> audienceByCode = new TreeMap<>();
			for (Endpoint endpoint : endpoints) {
				for (String code : reachableFrom(endpoint.pkg(), endpoint.method(), methods,
						methodsByPackageAndName, 4, new HashSet<>())) {
					audienceByCode.computeIfAbsent(code, k -> new LinkedHashSet<>()).add(endpoint.permission());
				}
			}
			return new Model(permissionsByPackage, packagesByCode, audienceByCode);
		}

		/**
		 * Every error code a controller method can end up raising.
		 *
		 * <p>The walk follows calls by name and stays inside one package, which is the shape this
		 * codebase actually has: a controller talks to the services beside it, and those services
		 * talk to each other. It over-reaches where two classes in one package share a method name
		 * and under-reaches where a package calls across to another. Both directions are reported
		 * rather than hidden: an over-reach widens an audience and can only produce a finding a
		 * human then reads, and an under-reach leaves the code unchecked and named in the report.
		 */
		private static Set<String> reachableFrom(String pkg, String name, Map<String, Method> methods,
				Map<String, Set<String>> byName, int depth, Set<String> seen) {
			String key = pkg + "#" + name;
			if (depth == 0 || !seen.add(key)) {
				return Set.of();
			}
			Set<String> found = new LinkedHashSet<>();
			for (String methodKey : byName.getOrDefault(key, Set.of())) {
				Method method = methods.get(methodKey);
				found.addAll(method.codes());
				for (String called : method.calls()) {
					if (byName.containsKey(pkg + "#" + called)) {
						found.addAll(reachableFrom(pkg, called, methods, byName, depth - 1, seen));
					}
				}
			}
			return found;
		}
	}

	private record Method(String pkg, Set<String> codes, Set<String> calls) {
	}

	private record Endpoint(String pkg, String method, String permission) {
	}

	private record Span(String name, int start, int end, String body) {
	}

	private static final Pattern AUTHORITY = Pattern.compile("hasAuthority\\('([A-Z_]+)'\\)");

	private static final Pattern CLASS_AUTHORITY = Pattern.compile(
			"@PreAuthorize\\(\"hasAuthority\\('([A-Z_]+)'\\)\"\\)[^{]*?\\bclass\\b", Pattern.DOTALL);

	private static final Pattern ERROR_CODE = Pattern.compile("ErrorCode\\.([A-Z][A-Z0-9_]+)");

	private static final Pattern DECLARATION = Pattern.compile(
			"(?:public|private|protected)\\s+(?:static\\s+)?(?:final\\s+)?[\\w<>\\[\\],\\s?.]+?\\s(\\w+)"
					+ "\\s*\\([^;{)]*\\)\\s*(?:throws [\\w,\\s.]+?)?\\{");

	private static Set<String> codesIn(String body) {
		Set<String> out = new LinkedHashSet<>();
		Matcher matcher = ERROR_CODE.matcher(body);
		while (matcher.find()) {
			out.add(matcher.group(1));
		}
		return out;
	}

	private static final Pattern CALL = Pattern.compile("\\b(\\w+)\\s*\\(");

	private static Set<String> callsIn(String body) {
		Set<String> out = new LinkedHashSet<>();
		Matcher matcher = CALL.matcher(body);
		while (matcher.find()) {
			out.add(matcher.group(1));
		}
		return out;
	}

	/** Method bodies, by brace matching. Regex alone cannot tell where a method ends. */
	private static List<Span> spans(String source) {
		List<Span> out = new ArrayList<>();
		Matcher matcher = DECLARATION.matcher(source);
		int from = 0;
		while (matcher.find(from)) {
			int open = source.indexOf('{', matcher.end() - 1);
			if (open < 0) {
				break;
			}
			int depth = 0;
			int close = open;
			while (close < source.length()) {
				char c = source.charAt(close);
				if (c == '{') {
					depth++;
				} else if (c == '}' && --depth == 0) {
					break;
				}
				close++;
			}
			if (close >= source.length()) {
				break;
			}
			out.add(new Span(matcher.group(1), matcher.start(), close + 1, source.substring(open, close + 1)));
			from = close + 1;
		}
		return out;
	}

	/**
	 * Comments out, line count kept.
	 *
	 * <p>This file is full of comments that name permissions and error codes while explaining why
	 * something does <em>not</em> hold them — {@code RolePermissions} says "what is absent beside
	 * it is CORRECT_RECORDED_ATTENDANCE" in as many words. Reading those as code would invert the
	 * policy.
	 */
	private static String withoutComments(String source) {
		String withoutBlocks = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL).matcher(source).replaceAll(match -> {
			long newlines = match.group().chars().filter(c -> c == '\n').count();
			return "\n".repeat((int) newlines);
		});
		return withoutBlocks.replaceAll("//[^\\n]*", "");
	}

	private static void writeReport(List<String> lines) throws IOException {
		Files.createDirectories(REPORT.getParent());
		List<String> out = new ArrayList<>();
		out.add("Next-step PERMISSION audit — what this check decided about each error code.");
		out.add("");
		out.add("PERMISSION mode only. It says nothing about whether the door is open in the");
		out.add("reader's current STATE, and nothing about whether it was ever BUILT. NOT_CHECKED");
		out.add("means no verdict was reached, never that the sentence is sound.");
		out.add("");
		long checked = lines.stream().filter(l -> !l.contains("NOT_CHECKED")).count();
		out.add("checked: %d of %d codes.".formatted(checked, lines.size()));
		out.add("");
		out.addAll(lines);
		Files.write(REPORT, out);
	}
}
