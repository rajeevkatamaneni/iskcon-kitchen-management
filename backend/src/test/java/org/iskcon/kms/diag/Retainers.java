package org.iskcon.kms.diag;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * A roll-call of the static holders that can keep a closed Spring context alive in a test JVM.
 *
 * <p>Each entry answers one question — "how many things is this static object holding right now?" —
 * and nothing else. It is deliberately a list of named suspects rather than a heap walk: a retention
 * that cannot be named cannot be fixed, and a number beside a name is something the next reader can
 * check. Everything is read reflectively and every read is allowed to fail: these are other people's
 * internals, they change between versions, and a diagnostic that throws is worse than one that says
 * it could not look.
 */
final class Retainers {

	private Retainers() {}

	/**
	 * Walks out from {@code Metrics.globalRegistry} looking for a context that has been closed, and
	 * reports the route if there is one. Counting closed contexts says a leak exists; this is what
	 * says who is doing it.
	 */
	static String routeFromMicrometerToAClosedContext() {
		try {
			Class<?> metrics = Class.forName("io.micrometer.core.instrument.Metrics");
			Object global = metrics.getField("globalRegistry").get(null);
			return ReferencePath.find(global, "Metrics.globalRegistry", Retainers::isClosedContext,
					1_200_000);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return "unavailable (" + e + ")";
		}
	}

	/**
	 * Walks out from every live thread — its {@code Runnable}, and its thread-locals — looking for a
	 * closed context.
	 *
	 * <p>Threads are the other family of garbage-collection root a Java program can actually name.
	 * A thread-local's value is held strongly even though its key is not, and a pool that was never
	 * shut down keeps everything its tasks can see; both are ordinary ways for a test JVM to hold a
	 * context that every visible registry has already let go of.
	 */
	static String routeFromThreadsToAClosedContext() {
		try {
			java.util.List<Thread> threads = new ArrayList<>(Thread.getAllStackTraces().keySet());
			return ReferencePath.findFromAny(threads, "a live thread", Retainers::isClosedContext,
					6_000_000);
		} catch (RuntimeException | Error e) {
			return "unavailable (" + e + ")";
		}
	}

	/**
	 * The logging back end is a process-wide singleton that every context reconfigures on startup,
	 * and an appender or a turbo filter added to it is never taken off by a context closing. If the
	 * count here climbs with the number of contexts built, the logger context is the holder.
	 */
	private static String logbackAppenders() {
		try {
			Object factory = org.slf4j.LoggerFactory.getILoggerFactory();
			Object root = factory.getClass().getMethod("getLogger", String.class)
					.invoke(factory, "ROOT");
			java.util.Iterator<?> appenders =
					(java.util.Iterator<?>) root.getClass().getMethod("iteratorForAppenders")
							.invoke(root);
			java.util.List<String> names = new ArrayList<>();
			while (appenders.hasNext()) {
				names.add(appenders.next().getClass().getSimpleName());
			}
			Object turbo = factory.getClass().getMethod("getTurboFilterList").invoke(factory);
			int turboCount = ((Collection<?>) turbo).size();
			return "logback root appenders:            " + names.size() + " " + names
					+ ", turbo filters: " + turboCount;
		} catch (ReflectiveOperationException | RuntimeException | Error e) {
			return "logback root appenders:            unavailable (" + e + ")";
		}
	}

	/** Walks out from the logging back end's process-wide context. */
	static String routeFromLogbackToAClosedContext(int nodeBudget) {
		try {
			Object factory = org.slf4j.LoggerFactory.getILoggerFactory();
			return ReferencePath.find(factory, "the logback LoggerContext",
					Retainers::isClosedContext, nodeBudget);
		} catch (RuntimeException | Error e) {
			return "unavailable (" + e + ")";
		}
	}

	// A search from every static field of every loaded class was tried here and removed, so that
	// the next person does not spend the afternoon on it: the JVM hides `ClassLoader.classes` from
	// reflection (`getDeclaredField("classes")` throws NoSuchFieldException, with or without
	// --add-opens), so a Java program cannot enumerate the classes it has loaded without an
	// instrumentation agent. Enumerable roots are therefore the ones below plus live threads, and
	// a holder outside that set needs a heap dump and an analyser rather than this file.

	/**
	 * The control for the two searches above: a route the walker <em>must</em> find.
	 *
	 * <p>Spring's own context cache holds thirty-two contexts on purpose, so a walk that reports no
	 * route from it has not proved anything about anywhere else — it has proved the walker cannot
	 * see. Reading "no route found" without this line beside it would be reading a broken
	 * instrument as a clean bill of health.
	 */
	static String controlRouteFromTheContextCache() {
		try {
			Class<?> delegate = Class.forName(
					"org.springframework.test.context.cache.DefaultCacheAwareContextLoaderDelegate");
			java.lang.reflect.Field field = delegate.getDeclaredField("defaultContextCache");
			field.setAccessible(true);
			Object cache = field.get(null);
			return ReferencePath.find(cache, "Spring's own context cache",
					c -> c instanceof org.springframework.context.ApplicationContext, 1_200_000);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return "unavailable (" + e + ")";
		}
	}

	private static boolean isClosedContext(Object candidate) {
		return candidate instanceof org.springframework.context.ConfigurableApplicationContext context
				&& !context.isActive();
	}

	static List<String> inventory() {
		List<String> lines = new ArrayList<>();
		lines.add(micrometerGlobalRegistry());
		lines.add(bootShutdownHook());
		lines.add(quartzSchedulerRepository());
		lines.add(hibernateSessionFactoryRegistry());
		lines.add(liveThreads());
		lines.add(logbackAppenders());
		return lines;
	}

	/**
	 * Live threads, and the three commonest names among them.
	 *
	 * <p>A running thread is a garbage-collection root, and whatever its task object can see is kept
	 * alive with it. So a thread pool a closed context forgot to shut down does not merely waste a
	 * thread — it pins the entire context behind it. If the count here climbs with the number of
	 * contexts built, that is the answer; if it stays flat, threads are not the holder and the
	 * search goes elsewhere. Names are reduced to a prefix because pools number their threads.
	 */
	private static String liveThreads() {
		try {
			java.util.Map<String, Integer> byPrefix = new java.util.TreeMap<>();
			int total = 0;
			for (Thread thread : Thread.getAllStackTraces().keySet()) {
				total++;
				String prefix = thread.getName().replaceAll("[-_ ]?\\d+.*$", "");
				byPrefix.merge(prefix.isEmpty() ? thread.getName() : prefix, 1, Integer::sum);
			}
			String commonest = byPrefix.entrySet().stream()
					.sorted((a, b) -> b.getValue() - a.getValue())
					.limit(4)
					.map(e -> e.getKey() + " x" + e.getValue())
					.collect(java.util.stream.Collectors.joining(", "));
			return "live threads:                      " + total + "   (" + commonest + ")";
		} catch (RuntimeException | Error e) {
			return "live threads:                      unavailable (" + e + ")";
		}
	}

	/**
	 * {@code Metrics.globalRegistry} is a process-wide composite that Spring Boot adds every
	 * context's {@code MeterRegistry} to, because {@code management.metrics.use-global-registry}
	 * defaults to true. Boot closes the registry when the context closes but does not take it back
	 * out of the composite, so the count here only ever grows.
	 */
	private static String micrometerGlobalRegistry() {
		try {
			Class<?> metrics = Class.forName("io.micrometer.core.instrument.Metrics");
			Object global = metrics.getField("globalRegistry").get(null);
			Object registries = global.getClass().getMethod("getRegistries").invoke(global);
			Collection<?> collection = (Collection<?>) registries;
			int closed = 0;
			for (Object registry : collection) {
				Object isClosed = registry.getClass().getMethod("isClosed").invoke(registry);
				if (Boolean.TRUE.equals(isClosed)) {
					closed++;
				}
			}
			return "Metrics.globalRegistry:            " + collection.size()
					+ " registries (" + closed + " of them already closed)";
		} catch (ReflectiveOperationException | RuntimeException e) {
			return "Metrics.globalRegistry:            unavailable (" + e + ")";
		}
	}

	/**
	 * Boot's shutdown hook keeps a strong set of the contexts it would have to close. It removes a
	 * context when that context publishes {@code ContextClosedEvent}, so a non-zero count of closed
	 * contexts here would mean the removal is not happening.
	 */
	private static String bootShutdownHook() {
		try {
			Class<?> springApplication = Class.forName("org.springframework.boot.SpringApplication");
			Field hookField = springApplication.getDeclaredField("shutdownHook");
			hookField.setAccessible(true);
			Object hook = hookField.get(null);
			Field contextsField = hook.getClass().getDeclaredField("contexts");
			contextsField.setAccessible(true);
			Collection<?> contexts = (Collection<?>) contextsField.get(hook);
			return "SpringApplicationShutdownHook:     " + contexts.size() + " contexts held";
		} catch (ReflectiveOperationException | RuntimeException e) {
			return "SpringApplicationShutdownHook:     unavailable (" + e + ")";
		}
	}

	/**
	 * Quartz keeps every scheduler in a process-wide registry keyed by name, and a scheduler reaches
	 * its Spring context through the job factory. This project runs exactly one scheduler context in
	 * the suite for that reason; the count is here to prove that rule is still being kept.
	 */
	private static String quartzSchedulerRepository() {
		try {
			Class<?> repository = Class.forName("org.quartz.impl.SchedulerRepository");
			Object instance = repository.getMethod("getInstance").invoke(null);
			Collection<?> all = (Collection<?>) repository.getMethod("lookupAll").invoke(instance);
			return "Quartz SchedulerRepository:        " + all.size() + " schedulers";
		} catch (ReflectiveOperationException | RuntimeException e) {
			return "Quartz SchedulerRepository:        unavailable (" + e + ")";
		}
	}

	/**
	 * Hibernate registers each named {@code SessionFactory} in a static map and removes it on close.
	 * The ledger's class histogram found one {@code SessionFactoryImpl} beside every retained
	 * context, so this is worth counting even though closing is expected to clear it.
	 */
	private static String hibernateSessionFactoryRegistry() {
		try {
			Class<?> registry = Class.forName("org.hibernate.internal.SessionFactoryRegistry");
			Field instanceField = registry.getDeclaredField("INSTANCE");
			instanceField.setAccessible(true);
			Object instance = instanceField.get(null);
			Field mapField = registry.getDeclaredField("sessionFactoryMap");
			mapField.setAccessible(true);
			Map<?, ?> map = (Map<?, ?>) mapField.get(instance);
			return "Hibernate SessionFactoryRegistry:  " + map.size() + " session factories";
		} catch (ReflectiveOperationException | RuntimeException e) {
			return "Hibernate SessionFactoryRegistry:  unavailable (" + e + ")";
		}
	}
}
