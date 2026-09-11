package org.iskcon.kms.diag;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * Counts the Spring application contexts a test run builds, and how many of them it is still
 * holding on to while it runs.
 *
 * <p><strong>Why this exists rather than a log grep.</strong> Two claims about this suite have been
 * carried in {@code build.gradle.kts} and in the work ledger for weeks — that it builds "about a
 * hundred" contexts, and that it keeps several dozen <em>closed</em> ones reachable — and neither
 * could be re-checked without attaching {@code jcmd} to a running worker at the right moment and
 * reading a class histogram. That is not a measurement anyone repeats, which is how a number goes
 * stale without anyone noticing. This makes both numbers fall out of an ordinary {@code ./gradlew
 * test -PcontextCensus} run, so the next person to touch the test infrastructure can prove whether
 * their change moved them.
 *
 * <p><strong>Why it reports while the run is still going.</strong> At JVM exit every context is
 * closed, so "closed and still reachable" measured there says nothing at all — it is the normal end
 * state. The number that means something is taken mid-run: the context cache holds 32, so any
 * context beyond those 32 that is still reachable has been evicted, closed, and kept anyway. Hence a
 * snapshot every {@link #CHECKPOINT_EVERY} contexts, each one printing both figures side by side.
 *
 * <p><strong>It is off unless asked for.</strong> Nothing here runs unless the test JVM is started
 * with {@code -Dkms.context.census=true}, and {@link ContextCensusCustomizerFactory} contributes no
 * {@code ContextCustomizer} at all in that case. That matters more than it looks: a
 * {@code ContextCustomizer} is part of the TestContext framework's context cache key, so an
 * instrument that was always on would itself change the thing it is measuring. When it *is* on,
 * every context gets the same singleton customizer, so every cache key shifts identically and the
 * number of distinct keys — the thing being counted — is unchanged.
 *
 * <p>References to the contexts are weak on purpose. A census that held them would guarantee the
 * answer it is looking for.
 */
public final class ContextCensus {

	/** Set {@code -Dkms.context.census=true} on the test JVM to turn the census on. */
	public static final String ENABLED_PROPERTY = "kms.context.census";

	/** Where the report is written. Relative paths resolve against the test JVM's working directory. */
	public static final String OUTPUT_PROPERTY = "kms.context.census.out";

	/**
	 * Set {@code -Dkms.context.census.paths=true} to also walk the object graph and print the route
	 * by which a named suspect can still see a closed context. Separate from the census itself
	 * because the walk visits millions of objects and takes a while; the counts are wanted on every
	 * measuring run, the route only when someone is hunting.
	 */
	public static final String PATHS_PROPERTY = "kms.context.census.paths";

	private static final String DEFAULT_OUTPUT = "build/reports/context-census.txt";

	/**
	 * How many contexts must be built between snapshots. Each snapshot asks for a full collection,
	 * so this is a trade: often enough to show the trajectory, rare enough that the measurement is
	 * not the thing slowing the run down. Twenty gives five or six readings across a full suite.
	 */
	private static final int CHECKPOINT_EVERY =
			Integer.getInteger("kms.context.census.every", 20);

	private static final List<WeakReference<ConfigurableApplicationContext>> CONTEXTS =
			new ArrayList<>();

	private static final AtomicInteger CREATED = new AtomicInteger();

	/**
	 * The distinct cache keys this run has asked for, and the distinct keys it would have asked for
	 * if every {@code @Import} of a stub-verifier configuration had named one shared class.
	 *
	 * <p>The second set is the whole evidence for whether collapsing those imports is worth doing.
	 * It is a measurement rather than an estimate: the key is built from the real
	 * {@code MergedContextConfiguration} of every context the suite actually creates, with the one
	 * customizer that carries {@code @Import} removed. So it answers "how many contexts would there
	 * be" without anybody editing a hundred test classes to find out.
	 */
	private static final Set<String> KEYS = new LinkedHashSet<>();

	private static final Set<String> KEYS_WITHOUT_IMPORTS = new LinkedHashSet<>();

	/**
	 * And the same again with the per-test-class {@code @TestConfiguration} classes taken out of the
	 * configuration-class list as well as out of the imports.
	 *
	 * <p>This third count exists because the second one turned out to answer the wrong question.
	 * Spring Boot detects a test class's nested {@code @TestConfiguration} and puts it straight into
	 * {@code MergedContextConfiguration.getClasses()}, which is the first thing the cache key is
	 * built from. So deleting the {@code @Import} line alone would collapse nothing at all: the
	 * nested class has to go too. The gap between the second number and the third is exactly the
	 * size of that distinction.
	 */
	private static final Set<String> KEYS_WITHOUT_STUB_CONFIGURATIONS = new LinkedHashSet<>();

	private static final StringWriter REPORT = new StringWriter();

	private static boolean hookInstalled;

	private static boolean routeProbed;

	private ContextCensus() {}

	public static boolean enabled() {
		return Boolean.getBoolean(ENABLED_PROPERTY);
	}

	/** Called once per context creation, from the customizer. */
	static synchronized void record(
			ConfigurableApplicationContext context, MergedContextConfiguration mergedConfig) {
		installReportHookOnce();
		int created = CREATED.incrementAndGet();
		CONTEXTS.add(new WeakReference<>(context));
		KEYS.add(cacheKey(mergedConfig, true, true));
		KEYS_WITHOUT_IMPORTS.add(cacheKey(mergedConfig, false, true));
		KEYS_WITHOUT_STUB_CONFIGURATIONS.add(cacheKey(mergedConfig, false, false));
		if (created % CHECKPOINT_EVERY == 0) {
			snapshot("after " + created + " contexts had been built");
		}
	}

	private static void installReportHookOnce() {
		if (hookInstalled) {
			return;
		}
		hookInstalled = true;
		PrintWriter out = new PrintWriter(REPORT);
		out.println("Spring context census — " + java.time.ZonedDateTime.now());
		out.println("Every line below is a snapshot taken after a full collection was requested.");
		out.println();
		out.flush();
		Runtime.getRuntime()
				.addShutdownHook(new Thread(() -> snapshot("at JVM exit"), "context-census"));
	}

	/**
	 * Compacts the heap as hard as a Java program is allowed to ask, then counts what survived.
	 *
	 * <p>{@code System.gc()} is a request, not an instruction, so this asks repeatedly and gives the
	 * collector a moment between attempts. A weak reference cleared after this sequence is the
	 * ordinary case; one still holding a context after it is a context something is holding on to.
	 */
	static synchronized void snapshot(String label) {
		for (int attempt = 0; attempt < 4; attempt++) {
			System.gc();
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}

		int reachable = 0;
		int reachableAndRunning = 0;
		int reachableAndClosed = 0;
		for (WeakReference<ConfigurableApplicationContext> reference : CONTEXTS) {
			ConfigurableApplicationContext context = reference.get();
			if (context == null) {
				continue;
			}
			reachable++;
			if (context.isActive()) {
				reachableAndRunning++;
			} else {
				reachableAndClosed++;
			}
		}

		PrintWriter out = new PrintWriter(REPORT);
		out.println("── " + label + " ──");
		out.println("  contexts built so far:        " + CREATED.get());
		out.println("  live heap after that GC:      " + liveHeapMegabytes() + " MB");
		out.println("  still reachable after a GC:   " + reachable);
		out.println("    of which still running:     " + reachableAndRunning);
		out.println("    of which already closed:    " + reachableAndClosed
				+ "   <- a closed context nothing should still be able to see");
		out.println("  spring's own cache:           " + springContextCacheStatistics());
		out.println("  distinct cache keys:          " + KEYS.size());
		out.println("  the same, ignoring @Import:   " + KEYS_WITHOUT_IMPORTS.size()
				+ "   <- if every @Import named one shared class and nothing else changed");
		out.println("  ...and ignoring nested @TestConfiguration too: "
				+ KEYS_WITHOUT_STUB_CONFIGURATIONS.size()
				+ "   <- if the nested stub configurations were deleted as well");
		for (String line : Retainers.inventory()) {
			out.println("  " + line);
		}
		// Once, mid-run, and only mid-run: at JVM exit every context is closed, including the
		// thirty-two the cache is legitimately holding, so a route found there would prove nothing.
		if (Boolean.getBoolean(PATHS_PROPERTY) && reachableAndClosed > 0 && !routeProbed
				&& !label.startsWith("at JVM exit")) {
			routeProbed = true;
			out.println("  CONTROL — route from Spring's own cache to any context (must be found):");
			out.println("        " + Retainers.controlRouteFromTheContextCache());
			out.println("  route from Metrics.globalRegistry to a closed context:");
			out.println("        " + Retainers.routeFromMicrometerToAClosedContext());
			out.println("  route from a live thread to a closed context:");
			out.println("        " + Retainers.routeFromThreadsToAClosedContext());
			out.println("  route from the logback LoggerContext to a closed context:");
			out.println("        " + Retainers.routeFromLogbackToAClosedContext(3_000_000));
		}
		if (label.startsWith("at JVM exit")) {
			// Printed once, at the end, because reading them is how anyone works out what is
			// actually splitting the cache. A count alone tells you there are a hundred keys; it
			// does not tell you which part of the key is doing it.
			out.println();
			out.println("── the distinct keys, with the @Import customizer left out ──");
			for (String key : KEYS_WITHOUT_IMPORTS) {
				out.println("  " + key);
			}
		}
		out.println();
		out.flush();

		writeReport(REPORT.toString());
	}

	/**
	 * What the run is actually holding, in megabytes, measured straight after the collections above.
	 * The ledger's figure for this suite came from a class histogram taken by hand with {@code jcmd};
	 * this is the same quantity, printed by the run itself.
	 */
	private static long liveHeapMegabytes() {
		Runtime runtime = Runtime.getRuntime();
		return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
	}

	private static void writeReport(String report) {
		try {
			Path path = Path.of(System.getProperty(OUTPUT_PROPERTY, DEFAULT_OUTPUT));
			if (path.getParent() != null) {
				Files.createDirectories(path.getParent());
			}
			Files.writeString(path, report);
		} catch (IOException e) {
			// The census is a diagnostic. It must never be the reason a run fails.
			System.out.println("context census: could not write the report file: " + e);
		}
	}

	/**
	 * Renders a {@code MergedContextConfiguration} the way the cache compares it.
	 *
	 * <p>Every part that Spring's own {@code equals} considers goes in: the configuration classes,
	 * the inlined properties, the active profiles, the loader, and the context customizers. The
	 * customizers are rendered as class name plus hash code because each one that participates in
	 * the key implements {@code equals}/{@code hashCode} for exactly that purpose.
	 *
	 * <p>With {@code withImports} false, the customizer that carries the test class's
	 * {@code @Import} is left out — which is precisely the question "what would this suite's context
	 * count be if those imports all named the same class?".
	 */
	private static String cacheKey(
			MergedContextConfiguration mergedConfig, boolean withImports, boolean withStubs) {
		Set<String> customizers = new TreeSet<>();
		for (ContextCustomizer customizer : mergedConfig.getContextCustomizers()) {
			String name = customizer.getClass().getName();
			if (!withImports && name.contains("ImportsContextCustomizer")) {
				continue;
			}
			customizers.add(name + "#" + customizer.hashCode());
		}
		Set<String> classes = new TreeSet<>();
		for (Class<?> configurationClass : mergedConfig.getClasses()) {
			if (!withStubs && configurationClass.isAnnotationPresent(
					org.springframework.boot.test.context.TestConfiguration.class)) {
				continue;
			}
			classes.add(configurationClass.getName());
		}
		return classes
				+ " | " + Arrays.toString(mergedConfig.getLocations())
				+ " | " + Arrays.toString(mergedConfig.getActiveProfiles())
				+ " | " + Arrays.toString(mergedConfig.getPropertySourceProperties())
				+ " | " + mergedConfig.getContextLoader().getClass().getName()
				+ " | " + customizers;
	}

	/**
	 * Spring's own view of the cache, read reflectively.
	 *
	 * <p>{@code DefaultContextCache.toString()} carries size, maxSize, hitCount and missCount, and
	 * {@code missCount} is the count of contexts the framework actually built. Having it beside our
	 * own tally is the point: two independent counters that agree are worth more than one, and
	 * {@code size} is how many of the reachable contexts are legitimately held — everything
	 * reachable beyond it has been evicted and kept regardless.
	 */
	private static String springContextCacheStatistics() {
		try {
			Class<?> delegate = Class.forName(
					"org.springframework.test.context.cache.DefaultCacheAwareContextLoaderDelegate");
			Field field = delegate.getDeclaredField("defaultContextCache");
			field.setAccessible(true);
			Object cache = field.get(null);
			return String.valueOf(cache);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return "unavailable (" + e + ")";
		}
	}
}
