package org.iskcon.kms.diag;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Walks the object graph from a named starting point and reports the field-by-field route to the
 * first object that matches.
 *
 * <p><strong>What it is for.</strong> "Something holds a closed application context" is not a
 * finding anybody can act on. A route — <em>this static field, then this list, then this map value,
 * then that context</em> — is. Java gives no way to ask the collector who points at an object, and
 * the usual answer is a heap dump and a desktop analyser; this is the small version of the same
 * question, asked from inside the JVM about one suspect at a time.
 *
 * <p><strong>It only traverses strong references.</strong> The referent of a
 * {@code java.lang.ref.Reference} is skipped, because a weakly-held object is not held: reporting a
 * route through a {@code WeakReference} would name an innocent party. That distinction is the whole
 * point when the suspects are caches and registries, which are usually careful to hold weakly and
 * occasionally are not.
 *
 * <p>Diagnostic code, run by hand. It is bounded by a node budget rather than by cleverness, and it
 * swallows every reflection failure: on a modern JDK plenty of fields cannot be read at all, and a
 * probe that died on the first of them would be useless.
 */
final class ReferencePath {

	private ReferencePath() {}

	/**
	 * Breadth-first, so the route returned is the shortest one — which is also the one most likely
	 * to name the actual holder rather than a long way round to it.
	 *
	 * @param root where to start, typically a static field's value
	 * @param rootName what to call it in the printed route
	 * @param target what counts as a hit
	 * @param nodeBudget how many objects to look at before giving up
	 * @return the route, or a line saying it did not find one
	 */
	static String find(Object root, String rootName, Predicate<Object> target, int nodeBudget) {
		if (root == null) {
			return rootName + ": null";
		}
		return findFromAny(List.of(root), rootName, target, nodeBudget);
	}

	/**
	 * The same search from several starting points at once, sharing one visited set — which is what
	 * a root <em>set</em> means. Used for threads, of which a test worker has dozens.
	 */
	static String findFromAny(
			List<?> roots, String rootName, Predicate<Object> target, int nodeBudget) {
		List<String> names = new ArrayList<>();
		for (Object root : roots) {
			names.add(rootName + " (" + describe(root) + ")");
		}
		return findFromNamedRoots(roots, names, target, nodeBudget);
	}

	/** The same, where each starting point has a name of its own to print. */
	static String findFromNamedRoots(
			List<?> roots, List<String> names, Predicate<Object> target, int nodeBudget) {
		Map<Object, Object> seen = new IdentityHashMap<>();
		Deque<Node> queue = new ArrayDeque<>();
		for (int i = 0; i < roots.size(); i++) {
			Object root = roots.get(i);
			if (root != null && seen.put(root, Boolean.TRUE) == null) {
				queue.add(new Node(root, null, names.get(i)));
			}
		}

		int examined = 0;
		while (!queue.isEmpty() && examined < nodeBudget) {
			Node node = queue.poll();
			examined++;
			if (node.parent != null && target.test(node.value)) {
				String route = render(node);
				return route + "\n        [" + examined + " objects examined; "
						+ (route.contains("[SOFT]")
								? "this route passes through a SOFT reference, so it is a cache the "
										+ "collector will break under pressure, not a leak"
								: "every link on this route is a strong reference")
						+ "]";
			}
			for (Node next : successors(node)) {
				if (next.value == null || seen.put(next.value, Boolean.TRUE) != null) {
					continue;
				}
				queue.add(next);
			}
		}
		return "no route found (" + examined + " objects examined"
				+ (queue.isEmpty() ? ", graph exhausted" : ", budget spent") + ")";
	}

	private static List<Node> successors(Node node) {
		List<Node> out = new ArrayList<>();
		Object value = node.value;
		Class<?> type = value.getClass();

		if (type.isArray()) {
			if (type.getComponentType().isPrimitive()) {
				return out;
			}
			int length = Array.getLength(value);
			for (int i = 0; i < length; i++) {
				out.add(new Node(Array.get(value, i), node, "[" + i + "]"));
			}
			return out;
		}

		// A class or a classloader leads to the whole world and to nothing anyone can fix.
		if (value instanceof Class<?> || value instanceof ClassLoader) {
			return out;
		}

		// Collections are walked through their own interfaces rather than through their fields.
		// On Java 17 and later java.util is exported but not opened, so setAccessible on
		// HashMap.table or ConcurrentHashMap.table throws and the walk stops dead at the first
		// collection it meets — which, on this suite's first attempt, was twenty-two objects in.
		//
		// Anything whose type name says "Weak" is left alone: iterating it would materialise
		// referents the collector is free to discard, and a route through one of those would
		// accuse an innocent party. Collections.newSetFromMap(new WeakHashMap<>()) cannot be
		// recognised from outside, so it is skipped by name too.
		String typeName = type.getName();
		if (typeName.contains("Weak") || typeName.equals("java.util.Collections$SetFromMap")) {
			return out;
		}
		if (value instanceof Map<?, ?> map) {
			int index = 0;
			for (Map.Entry<?, ?> entry : safeEntries(map)) {
				out.add(new Node(entry.getKey(), node, ".key[" + index + "]"));
				out.add(new Node(entry.getValue(), node, ".value[" + index + "]"));
				index++;
			}
			return out;
		}
		if (value instanceof Iterable<?> iterable) {
			int index = 0;
			for (Object element : safeElements(iterable)) {
				out.add(new Node(element, node, ".element[" + index++ + "]"));
			}
			return out;
		}

		for (Class<?> current = type; current != null && current != Object.class;
				current = current.getSuperclass()) {
			for (Field field : declaredFields(current)) {
				if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
					continue;
				}
				// A weak or phantom referent is exactly what "not held" means, so those edges are
				// not followed at all. A *soft* referent is different and the distinction is the
				// whole answer to T-065: the collector keeps a softly-held object until it needs
				// the room, so it survives System.gc() and shows up in a class histogram looking
				// every bit like a leak. Those edges are followed and labelled SOFT, so a route
				// says plainly whether what it found is held or merely cached.
				boolean soft = false;
				if (java.lang.ref.Reference.class.isAssignableFrom(current)
						&& "referent".equals(field.getName())) {
					if (!(value instanceof java.lang.ref.SoftReference<?>)) {
						continue;
					}
					soft = true;
				}
				try {
					field.setAccessible(true);
					out.add(new Node(field.get(value), node,
							"." + current.getSimpleName() + "#" + field.getName()
									+ (soft ? "  [SOFT]" : "")));
				} catch (RuntimeException | ReflectiveOperationException | Error e) {
					// Unreadable on this JDK. Nothing to do but carry on.
				}
			}
		}
		return out;
	}

	/**
	 * A live suite is mutating these collections while the walk reads them, so a
	 * {@code ConcurrentModificationException} is an ordinary event here rather than a bug. Copying
	 * what can be copied and giving up on the rest costs a missed edge, not a wrong answer.
	 */
	private static List<Map.Entry<?, ?>> safeEntries(Map<?, ?> map) {
		List<Map.Entry<?, ?>> entries = new ArrayList<>();
		try {
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				entries.add(new java.util.AbstractMap.SimpleEntry<>(entry.getKey(), entry.getValue()));
			}
		} catch (RuntimeException | Error e) {
			// Partial is fine.
		}
		return entries;
	}

	private static List<Object> safeElements(Iterable<?> iterable) {
		List<Object> elements = new ArrayList<>();
		try {
			for (Object element : iterable) {
				elements.add(element);
			}
		} catch (RuntimeException | Error e) {
			// Partial is fine.
		}
		return elements;
	}

	private static Field[] declaredFields(Class<?> type) {
		try {
			return type.getDeclaredFields();
		} catch (RuntimeException | Error e) {
			return new Field[0];
		}
	}

	private static String render(Node node) {
		List<String> steps = new ArrayList<>();
		for (Node current = node; current != null; current = current.parent) {
			steps.add(current.step + "  (" + describe(current.value) + ")");
		}
		java.util.Collections.reverse(steps);
		return String.join("\n        -> ", steps);
	}

	private static String describe(Object value) {
		if (value == null) {
			return "null";
		}
		String name = value.getClass().getName();
		if (value instanceof String) {
			return name + " \"" + value + "\"";
		}
		// The name is the whole point when the root is a thread: "a live thread" names nobody,
		// "HikariPool-7 housekeeper" names the pool that has to be shut down.
		if (value instanceof Thread thread) {
			return name + " \"" + thread.getName() + "\"";
		}
		if (value instanceof ThreadGroup group) {
			return name + " \"" + group.getName() + "\"";
		}
		return name;
	}

	private record Node(Object value, Node parent, String step) {}
}
