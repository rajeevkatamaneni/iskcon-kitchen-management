package org.iskcon.kms.error;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Constraint;
import jakarta.validation.Valid;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedArrayType;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Whether the <em>second</em> channel of user-facing error text is written in the product's voice.
 *
 * <h2>The gap this closes</h2>
 *
 * <p>{@link ErrorCodeTest} beside this one holds every {@link ErrorCode} to plain language, a next
 * step and a neutral tone. It has been green throughout, and while it was, a temple admin posting a
 * vendor with an empty name box was answered:
 *
 * <pre>
 * {"code":"KMS-400001","fieldErrors":[{"field":"name","message":"must not be blank"}]}
 * </pre>
 *
 * <p><strong>{@code "must not be blank"} is Jakarta Bean Validation's own default</strong>, shipped
 * in the library's resource bundle, and it is rendered next to a box on a form. So the product had
 * two ways of speaking to the same person, on the same screen, at the same moment: one governed by
 * a test suite, and one governed by whatever the annotation happened to come with. The rule in
 * {@code CLAUDE.md} — <em>"nothing technical reaches the user"</em> — does not have a carve-out for
 * field errors, and there was never any argument that it should. They are simply the channel nobody
 * had thought to police.
 *
 * <p>So: <strong>field errors are part of the product's voice</strong>, and this class is what makes
 * that true tomorrow as well as today. Without it the answer rots the first time somebody adds a
 * record with an {@code @NotBlank} on it, which is the same week.
 *
 * <h2>What counts as a field error, stated as a rule rather than a list</h2>
 *
 * <p>A constraint reaches a user if, and only if, it sits on something a request body is made of.
 * That is decidable from the tree, so this class decides it rather than keeping a list of DTOs by
 * hand — a hand-kept list is exactly what stops being true when somebody adds an endpoint.
 *
 * <ol>
 *   <li>Every {@code @RestController} on the classpath is scanned for.
 *   <li>Every parameter of every one of their methods that carries {@code @RequestBody} gives a
 *       <em>root</em> type.
 *   <li>From each root, fields are walked transitively — through {@code List<Line>},
 *       {@code Optional<X>}, {@code Map<K,V>} and arrays — following only types in
 *       {@code org.iskcon.kms}, because {@code String}, {@code UUID} and {@code BigDecimal} declare
 *       no constraints of ours.
 *   <li>Every constraint annotation found on those fields, and on the type arguments of those
 *       fields ({@code List<@Size(max = 100) String> tags} is a real pattern here and its message
 *       reaches a user just as directly), is a site this class holds to the house style.
 * </ol>
 *
 * <p><strong>Everything else in the tree is deliberately out of scope, and the rule above is what
 * excludes it</strong> rather than an exclusion list. JPA entities carry column-shaped constraints
 * that fail at the persistence layer, where the answer is an {@link ErrorCode} and not a field
 * error; internal service arguments the same. Neither is reachable from a {@code @RequestBody}, so
 * neither is walked, and neither will start being walked because somebody edited a list.
 *
 * <p>"Carries a message" is tested as <em>the message is not a resource-bundle key</em>. An
 * unspecified {@code message} is not empty — it defaults to the literal
 * {@code "{jakarta.validation.constraints.NotBlank.message}"}, which the validator then resolves out
 * of the library's bundle. Testing for the braces is therefore exact: it is the presence of the
 * default itself, not a guess at the English it resolves to, and it will keep working when a
 * constraint we write ourselves is added.
 *
 * <h2>What this does not check, so a green run is not over-read</h2>
 *
 * <ul>
 *   <li><strong>Whether the constraint runs at all.</strong> A {@code @RequestBody} without
 *       {@code @Valid}, or a nested record reached through a field without {@code @Valid}, is never
 *       validated and its messages never reach anybody. Those are listed in the report this class
 *       writes rather than asserted on, because fixing one is an edit to a controller signature and
 *       not to a message. Two are live as this is written and are recorded in
 *       {@code docs/work/proof/T-098.md}.
 *   <li><strong>Whether the message is <em>true</em> of the constraint.</strong> Nothing here
 *       notices "That name is too long." on a {@code @NotBlank}. Words are checked for register and
 *       tone; only a reader can check them for accuracy.
 *   <li><strong>The object-level failures</strong> that {@code @AssertTrue} on a whole record
 *       produces. They are held to the same style here — they are constraints on a walked type —
 *       but they arrive as a global error rather than against a named field, and no test here
 *       proves the screen shows them.
 * </ul>
 *
 * <h2>The vacuity guard, and why it is the most important test in the file</h2>
 *
 * <p>Every assertion below is of the shape "nothing was found wrong", and a walk that finds nothing
 * to walk — a mistyped package, a renamed annotation, a scanner that quietly returns an empty set —
 * passes all of them instantly and proves nothing at all. {@link #theWalkActuallyReachedTheProduct()}
 * asserts the size of what was walked, so a green run means the check ran.
 */
class FieldErrorMessageTest {

	/** Only types of ours are walked into; nothing in {@code java.*} declares our constraints. */
	private static final String ROOT_PACKAGE = "org.iskcon.kms";

	private static final Path REPORT = Path.of("build/reports/field-error-messages.txt");

	/**
	 * Floors, not exact counts, and deliberately so.
	 *
	 * <p>An exact count fails on every commit that adds an endpoint, which trains people to edit
	 * the number without reading it. A floor fails only when the walk starts finding <em>less</em>
	 * than it did — which is the failure this guard exists for, and is either a real deletion or a
	 * broken walk. Measured on 2026-09-10 and rounded down a little.
	 */
	private static final int FEWEST_CONTROLLERS_EXPECTED = 60;

	private static final int FEWEST_REQUEST_BODIES_EXPECTED = 90;

	private static final int FEWEST_TYPES_EXPECTED = 90;

	private static final int FEWEST_CONSTRAINTS_EXPECTED = 500;

	/**
	 * Jakarta's own phrasing, in the shapes it actually ships in.
	 *
	 * <p>Held separately from the "is it still a bundle key" test because a message can be written
	 * out by hand and still be the library's sentence — somebody resolving a failure of that test
	 * by pasting {@code message = "must not be blank"} into the annotation has changed nothing for
	 * the person reading it. Fragments, not whole strings, so "Name must not be blank." is caught
	 * too.
	 *
	 * <p>Note what is <em>not</em> here: the bare word "must". "Latitude must be between -90 and
	 * 90." was written by a person, is about the temple's data, and is fine. It is the specific
	 * library sentences that are banned, not a part of speech.
	 */
	private static final List<String> JAKARTA_PHRASING = List.of(
			"must not be blank", "must not be null", "must not be empty",
			"must be a well-formed email address", "size must be between",
			"must be greater than or equal to", "must be less than or equal to",
			"must be a past date", "must be a future date", "must be a date in the past",
			"must be a date in the future", "numeric value out of bounds",
			"must match \"", "must be true", "must be false");

	/**
	 * The same list {@link ErrorCodeTest} holds error codes to, on purpose. One product, one
	 * register: a word that would be wrong in a KMS-nnnnnn sentence is wrong an inch away from it
	 * on the same screen.
	 */
	private static final List<String> JARGON = List.of(
			"exception", "stack trace", "database", "constraint violation",
			"sql", "http", "jwt", "server", "endpoint", "payload",
			"unable to process", "invalid input syntax", "internal error");

	/** Also from {@link ErrorCodeTest}. */
	private static final List<String> BANNED_TONE =
			List.of("sorry", "oops", "unfortunately", "you failed", "!");

	private static Model model;

	@BeforeAll
	static void walkTheRequestBodies() throws IOException {
		model = Model.walk();
		model.writeReport(REPORT);
	}

	// -----------------------------------------------------------------------------------------
	// The guard that makes the rest of the file mean something.
	// -----------------------------------------------------------------------------------------

	@Test
	@DisplayName("the walk actually reached the product's request bodies")
	void theWalkActuallyReachedTheProduct() {
		assertThat(model.controllers)
				.as("found %d @RestController classes under %s — a walk that finds no controllers "
						+ "passes every other test in this file without checking anything",
						model.controllers.size(), ROOT_PACKAGE)
				.hasSizeGreaterThanOrEqualTo(FEWEST_CONTROLLERS_EXPECTED);

		assertThat(model.roots)
				.as("found %d @RequestBody parameter types — expected at least %d",
						model.roots.size(), FEWEST_REQUEST_BODIES_EXPECTED)
				.hasSizeGreaterThanOrEqualTo(FEWEST_REQUEST_BODIES_EXPECTED);

		assertThat(model.walked)
				.as("walked %d of our own types reachable from a request body — expected at "
						+ "least %d. A drop means either types were deleted or the field walk "
						+ "stopped following something it used to follow",
						model.walked.size(), FEWEST_TYPES_EXPECTED)
				.hasSizeGreaterThanOrEqualTo(FEWEST_TYPES_EXPECTED);

		assertThat(model.sites)
				.as("inspected %d constraint annotations — expected at least %d. This is the "
						+ "number that makes a green run worth anything",
						model.sites.size(), FEWEST_CONSTRAINTS_EXPECTED)
				.hasSizeGreaterThanOrEqualTo(FEWEST_CONSTRAINTS_EXPECTED);
	}

	// -----------------------------------------------------------------------------------------
	// The four things asked of the words themselves.
	// -----------------------------------------------------------------------------------------

	@Test
	@DisplayName("every constraint a user can trip carries a message written for a person")
	void everyConstraintCarriesAMessage() {
		List<String> silent = model.sites.stream()
				.filter(Site::usesTheLibrarysDefault)
				.map(Site::describe)
				.sorted()
				.toList();

		assertThat(silent)
				.as("%d constraint(s) leave `message` unset, so Jakarta Bean Validation's own "
						+ "English is what a temple volunteer reads next to the box they got "
						+ "wrong. Give each one a sentence in the product's voice:%n%s",
						silent.size(), String.join("\n", silent))
				.isEmpty();
	}

	@Test
	@DisplayName("no message is the validation library's sentence written out by hand")
	void noMessageIsJakartasPhrasing() {
		List<String> offences = new ArrayList<>();

		model.written().forEach(site -> {
			String text = site.message().toLowerCase(Locale.ROOT);
			JAKARTA_PHRASING.stream()
					.filter(text::contains)
					.forEach(phrase -> offences.add(
							site.describe() + " — \"" + site.message() + "\" contains \"" + phrase + "\""));
		});

		assertThat(offences)
				.as("a message that says what the constraint is called has not been translated, "
						+ "only retyped. Say what the person should do instead — the phone field's "
						+ "\"Include the country code, for example +919876543210.\" is the "
						+ "standard:%n%s", String.join("\n", offences))
				.isEmpty();
	}

	@Test
	@DisplayName("every message reads as a sentence, not a fragment")
	void everyMessageReadsAsASentence() {
		List<String> offences = new ArrayList<>();

		model.written().forEach(site -> {
			String text = site.message();
			if (!Character.isUpperCase(text.charAt(0))) {
				offences.add(site.describe() + " — \"" + text + "\" does not start with a capital");
			}
			if (!text.endsWith(".")) {
				offences.add(site.describe() + " — \"" + text + "\" does not end in a full stop");
			}
		});

		assertThat(offences)
				.as("field errors sit beside KMS-nnnnnn messages on the same screen and are read "
						+ "by the same person, so they are written the same way — a capital, a "
						+ "verb and a full stop:%n%s", String.join("\n", offences))
				.isEmpty();
	}

	@Test
	@DisplayName("no message contains language that only means something to a developer")
	void noMessageContainsJargon() {
		List<String> offences = new ArrayList<>();

		model.written().forEach(site -> {
			String text = site.message().toLowerCase(Locale.ROOT);
			JARGON.stream()
					.filter(text::contains)
					.forEach(term -> offences.add(
							site.describe() + " — \"" + site.message() + "\" contains \"" + term + "\""));
		});

		assertThat(offences)
				.as("the person reading this runs a temple kitchen, not a server:%n%s",
						String.join("\n", offences))
				.isEmpty();
	}

	@Test
	@DisplayName("no message blames the user or apologises theatrically")
	void toneIsNeutral() {
		List<String> offences = new ArrayList<>();

		model.written().forEach(site -> {
			String text = site.message().toLowerCase(Locale.ROOT);
			BANNED_TONE.stream()
					.filter(text::contains)
					.forEach(term -> offences.add(
							site.describe() + " — \"" + site.message() + "\" contains \"" + term + "\""));
		});

		assertThat(offences)
				.as("state what is wanted plainly instead:%n%s", String.join("\n", offences))
				.isEmpty();
	}

	// -----------------------------------------------------------------------------------------
	// The walk.
	// -----------------------------------------------------------------------------------------

	/**
	 * One constraint annotation, on one thing a request body is made of.
	 *
	 * @param owner the type declaring the field
	 * @param path the field, plus {@code <0>} and so on for a constraint on a type argument
	 * @param constraint the annotation type — {@code NotBlank}, {@code Size}, …
	 * @param message whatever its {@code message} attribute resolves to at this site
	 */
	private record Site(Class<?> owner, String path, Class<? extends Annotation> constraint, String message) {

		/**
		 * Whether nobody wrote a message here.
		 *
		 * <p>An unset {@code message} is not blank — it is the literal bundle key
		 * {@code "{jakarta.validation.constraints.NotBlank.message}"}, which the validator looks up
		 * in the library's own English. Braces are therefore the exact test for "the default", and
		 * one that keeps working for a constraint we write ourselves.
		 */
		boolean usesTheLibrarysDefault() {
			return message.startsWith("{") && message.endsWith("}");
		}

		/**
		 * Package-qualified below {@code org.iskcon.kms}, because simple names are not unique here:
		 * {@code ingredient.CreateIngredientRequest} and {@code ingredientrequest
		 * .CreateIngredientRequest} are two different records, and a failure naming only
		 * "CreateIngredientRequest" sends whoever reads it to the wrong file.
		 */
		String describe() {
			String owned = owner.getName().startsWith(ROOT_PACKAGE + ".")
					? owner.getName().substring(ROOT_PACKAGE.length() + 1)
					: owner.getName();
			return owned.replace('$', '.') + "." + path + " @" + constraint.getSimpleName();
		}
	}

	/** Everything the walk found, so each test can read it without walking again. */
	private record Model(
			List<Class<?>> controllers,
			List<Class<?>> roots,
			Set<Class<?>> walked,
			List<Site> sites,
			List<String> neverValidated) {

		List<Site> written() {
			return sites.stream().filter(site -> !site.usesTheLibrarysDefault()).toList();
		}

		static Model walk() {
			List<Class<?>> controllers = findControllers();
			List<Class<?>> roots = new ArrayList<>();
			List<String> neverValidated = new ArrayList<>();

			Deque<Class<?>> pending = new ArrayDeque<>();
			Set<Class<?>> walked = new LinkedHashSet<>();

			for (Class<?> controller : controllers) {
				for (Method method : controller.getDeclaredMethods()) {
					for (Parameter parameter : method.getParameters()) {
						if (!parameter.isAnnotationPresent(RequestBody.class)) {
							continue;
						}
						// Recorded whether or not it is ours: `byte[]` and `Map<String,String>`
						// bodies are real here and are counted as request bodies, they just
						// declare nothing for us to walk into.
						roots.add(parameter.getType());
						if (!parameter.isAnnotationPresent(Valid.class)) {
							neverValidated.add(controller.getSimpleName() + "#" + method.getName()
									+ "(" + parameter.getType().getSimpleName() + ") has no @Valid, "
									+ "so nothing on it is checked at all");
						}
						enqueue(parameter.getParameterizedType(), pending);
					}
				}
			}

			List<Site> sites = new ArrayList<>();
			while (!pending.isEmpty()) {
				Class<?> type = pending.pop();
				if (!walked.add(type)) {
					continue;
				}
				for (Field field : type.getDeclaredFields()) {
					if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
						continue;
					}
					// Declaration annotations on the field itself. A record component's
					// annotation is propagated here by the compiler, which is why fields are
					// read rather than record components: it is the one place every DTO shape
					// in this tree — record or class — puts them exactly once.
					for (Annotation annotation : field.getAnnotations()) {
						addIfConstraint(annotation, type, field.getName(), sites);
					}
					// And on the type arguments: `List<@Size(max = 100) String> tags`.
					collectFromTypeArguments(field.getAnnotatedType(), type, field.getName(), sites);
					enqueue(field.getGenericType(), pending);
				}
			}

			return new Model(controllers, roots, walked, sites, neverValidated);
		}

		private static List<Class<?>> findControllers() {
			// A scan rather than a Spring context: this is a unit test and has no business
			// starting a database to read annotations off classes that are already loaded.
			var scanner = new ClassPathScanningCandidateComponentProvider(false);
			scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

			List<Class<?>> found = new ArrayList<>();
			for (BeanDefinition definition : scanner.findCandidateComponents(ROOT_PACKAGE)) {
				try {
					found.add(Class.forName(definition.getBeanClassName()));
				}
				catch (ClassNotFoundException e) {
					throw new IllegalStateException(
							"the classpath scan named a class that will not load: "
									+ definition.getBeanClassName(), e);
				}
			}
			found.sort(Comparator.comparing(Class::getName));
			return found;
		}

		private static void addIfConstraint(
				Annotation annotation, Class<?> owner, String path, List<Site> sites) {

			Class<? extends Annotation> type = annotation.annotationType();
			if (!type.isAnnotationPresent(Constraint.class)) {
				return;
			}
			sites.add(new Site(owner, path, type, messageOf(annotation)));
		}

		/**
		 * Every constraint declares {@code message()} — the specification requires it — so the
		 * absence of one is a broken constraint rather than a case to tolerate quietly.
		 */
		private static String messageOf(Annotation annotation) {
			try {
				return (String) annotation.annotationType().getMethod("message").invoke(annotation);
			}
			catch (ReflectiveOperationException e) {
				throw new IllegalStateException(
						"@" + annotation.annotationType().getSimpleName()
								+ " is marked @Constraint but has no readable message()", e);
			}
		}

		private static void collectFromTypeArguments(
				AnnotatedType annotated, Class<?> owner, String path, List<Site> sites) {

			if (annotated instanceof AnnotatedParameterizedType parameterized) {
				AnnotatedType[] arguments = parameterized.getAnnotatedActualTypeArguments();
				for (int i = 0; i < arguments.length; i++) {
					String argumentPath = path + "<" + i + ">";
					for (Annotation annotation : arguments[i].getAnnotations()) {
						addIfConstraint(annotation, owner, argumentPath, sites);
					}
					collectFromTypeArguments(arguments[i], owner, argumentPath, sites);
				}
			}
			else if (annotated instanceof AnnotatedArrayType array) {
				AnnotatedType component = array.getAnnotatedGenericComponentType();
				String componentPath = path + "[]";
				for (Annotation annotation : component.getAnnotations()) {
					addIfConstraint(annotation, owner, componentPath, sites);
				}
				collectFromTypeArguments(component, owner, componentPath, sites);
			}
		}

		/** Follow a field's type, and anything inside it, as far as our own packages go. */
		private static void enqueue(Type type, Deque<Class<?>> pending) {
			if (type instanceof Class<?> concrete) {
				if (concrete.isArray()) {
					enqueue(concrete.getComponentType(), pending);
					return;
				}
				if (concrete.getName().startsWith(ROOT_PACKAGE)
						&& !concrete.isEnum()
						&& !concrete.isInterface()
						&& !concrete.isPrimitive()) {
					pending.push(concrete);
				}
			}
			else if (type instanceof ParameterizedType parameterized) {
				enqueue(parameterized.getRawType(), pending);
				for (Type argument : parameterized.getActualTypeArguments()) {
					enqueue(argument, pending);
				}
			}
			else if (type instanceof GenericArrayType array) {
				enqueue(array.getGenericComponentType(), pending);
			}
			else if (type instanceof WildcardType wildcard) {
				for (Type bound : wildcard.getUpperBounds()) {
					enqueue(bound, pending);
				}
			}
		}

		/**
		 * The audit trail, written whether the run is green or red.
		 *
		 * <p>Its point is the same as {@code NextStepPermissionTest}'s: the numbers in the
		 * assertions above are only trustworthy if somebody can see what was actually walked, and
		 * a green run that walked the wrong thing is worse than a red one.
		 */
		void writeReport(Path report) throws IOException {
			List<String> lines = new ArrayList<>();
			lines.add("Constraint messages on everything reachable from a @RequestBody.");
			lines.add("");
			lines.add(controllers.size() + " @RestController classes");
			lines.add(roots.size() + " @RequestBody parameters");
			lines.add(walked.size() + " of our own types walked");
			lines.add(sites.size() + " constraint annotations inspected");
			lines.add(written().size() + " of them carry a message of their own");
			lines.add("");
			lines.add("--- request bodies nothing validates (reported, not asserted) ---");
			lines.addAll(neverValidated.isEmpty() ? List.of("(none)") : new TreeSet<>(neverValidated));
			lines.add("");
			lines.add("--- every site, and what it says ---");
			sites.stream()
					.map(site -> site.describe() + " = " + site.message())
					.sorted()
					.forEach(lines::add);

			Files.createDirectories(report.getParent());
			Files.write(report, lines);
		}
	}
}
