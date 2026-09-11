package org.iskcon.kms.perf;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Every SQL statement one request sends, and the application method that sent it.
 *
 * <h2>Why this exists rather than another reading of {@code pg_stat_all_tables}</h2>
 *
 * <p>T-139 counted the reads from the database's own statistics and found the shopping list touching
 * {@code stock_movements} nine times for one page load. That count is a real observation but it is
 * not a <em>safe</em> one, for two reasons this class removes:
 *
 * <ul>
 *   <li><strong>PostgreSQL's statistics lag.</strong> A backend accumulates its counters locally and
 *       flushes them when a transaction ends, at most about once a second. A snapshot taken
 *       immediately after a burst of page loads therefore still has some of that burst's counters in
 *       flight, and they land inside the next window — so a delta bracketing one request can contain
 *       several requests' worth of reads. T-139's harness slept after its measured call but not
 *       before it, which is exactly the shape that inflates a count.
 *   <li><strong>{@code idx_scan} counts index scan <em>executions</em>, not statements.</strong> One
 *       statement whose plan drives a nested loop over 26 rows registers 26 index scans. A count of
 *       scans therefore cannot answer "how many times did the application ask for this table", which
 *       is the question T-140 was set.
 * </ul>
 *
 * <p>This records at the JDBC boundary instead: what the application asked for, once per ask,
 * attributed to the method that asked. No lag, no plan-shape arithmetic, and it names the caller —
 * which is the part the statistics can never give and the part T-140's first step needs.
 *
 * <p><strong>It does not replace the {@code pg_stat} reading; it cross-checks it.</strong> The two
 * are evidence from opposite sides of the connection, and a count that is right on both sides is a
 * count worth quoting.
 *
 * <h2>How it is wired</h2>
 *
 * <p>{@link #wrapTheDataSource()} is a {@link BeanPostProcessor} that puts a recording
 * {@link DataSource} in front of the application's. That is the same mechanism, at the same seam,
 * that {@code TenancyConfiguration} already uses to apply the tenant to every connection — and the
 * reason it is the right seam is the same one stated there: JPA, {@code JdbcTemplate} and native SQL
 * all resolve the one {@code DataSource} bean, so nothing can issue a statement past it.
 *
 * <p><strong>Recording is off unless a test turns it on</strong>, and the switch is a static because
 * the statements being recorded are sent from a Tomcat worker thread while the test that wants them
 * sits on another. Gradle runs this suite in a single JVM with no parallel forks, and the recorded
 * window is one HTTP call wide, so there is nothing else in flight to cross-talk with; each entry
 * carries its thread name anyway, so a caller that wants to be strict can filter.
 */
public final class StatementRecorder {

	/** One statement, and the chain of application frames that led to it. */
	public record Executed(String sql, List<String> callers, String thread) {

		/** The nearest application frame — the method that actually issued the statement. */
		public String caller() {
			return callers.isEmpty() ? "(no application frame)" : callers.get(0);
		}

		/** Whether this statement names a table, matched on the raw SQL text. */
		public boolean touches(String table) {
			return sql.toLowerCase(Locale.ROOT).contains(table.toLowerCase(Locale.ROOT));
		}

		/** The statement on one line, with runs of whitespace collapsed, for a report. */
		public String oneLine() {
			return sql.replaceAll("\\s+", " ").strip();
		}
	}

	private static final List<Executed> RECORDED = Collections.synchronizedList(new ArrayList<>());

	private static volatile boolean recording;

	private StatementRecorder() {
	}

	/** Starts a fresh recording window, discarding anything held from an earlier one. */
	public static void start() {
		RECORDED.clear();
		recording = true;
	}

	/** Closes the window and hands back what was recorded, in the order it was issued. */
	public static List<Executed> stop() {
		recording = false;
		synchronized (RECORDED) {
			return List.copyOf(RECORDED);
		}
	}

	/** How many recorded statements name this table. */
	public static long countTouching(List<Executed> statements, String table) {
		return statements.stream().filter(s -> s.touches(table)).count();
	}

	/**
	 * Wraps the application's {@code DataSource} so every statement issued through it is seen.
	 *
	 * <p>Declared {@code static} because a {@code BeanPostProcessor} is created before the
	 * configuration class that declares it can be fully initialised; a non-static factory method
	 * makes Spring log that the configuration was instantiated early, and pulls whatever else it
	 * declares into that early instantiation.
	 */
	public static BeanPostProcessor wrapTheDataSource() {
		return new BeanPostProcessor() {
			@Override
			public Object postProcessAfterInitialization(Object bean, String beanName) {
				if (bean instanceof DataSource dataSource && !(bean instanceof RecordingDataSource)) {
					return new RecordingDataSource(dataSource);
				}
				return bean;
			}
		};
	}

	// ---------------------------------------------------------------------------------------------

	private static void record(String sql) {
		if (!recording || sql == null) {
			return;
		}
		RECORDED.add(new Executed(sql, applicationFrames(), Thread.currentThread().getName()));
	}

	/**
	 * The application frames below this statement, nearest first.
	 *
	 * <p>The plumbing is dropped — this class, Spring's JDBC and transaction machinery, and the
	 * tenancy wrapper — because the question being asked is <em>which service read the ledger</em>,
	 * and every statement in the application has the same twenty frames of {@code JdbcTemplate}
	 * underneath it. Six frames is enough to see a call chain and short enough to print.
	 */
	private static List<String> applicationFrames() {
		return StackWalker.getInstance().walk(frames -> frames
				.filter(f -> f.getClassName().startsWith("org.iskcon.kms."))
				.filter(f -> !f.getClassName().startsWith("org.iskcon.kms.perf."))
				.filter(f -> !f.getClassName().startsWith("org.iskcon.kms.tenancy."))
				.limit(6)
				.map(f -> f.getClassName().substring("org.iskcon.kms.".length())
						+ "." + f.getMethodName() + ":" + f.getLineNumber())
				.toList());
	}

	/** The application's DataSource with a recording connection in front of it. */
	private static final class RecordingDataSource extends DelegatingDataSource {

		RecordingDataSource(DataSource delegate) {
			super(delegate);
		}

		@Override
		public Connection getConnection() throws SQLException {
			return wrap(super.getConnection());
		}

		@Override
		public Connection getConnection(String username, String password) throws SQLException {
			return wrap(super.getConnection(username, password));
		}

		/**
		 * A {@link Proxy} rather than a subclass, for the reason {@code TenantAwareDataSource} already
		 * proxies its connections: {@code Connection} is an interface with a hundred methods and only
		 * three of them are interesting here, so delegating everything and intercepting those three
		 * is the change that cannot break the other ninety-seven.
		 */
		private Connection wrap(Connection connection) {
			return (Connection) Proxy.newProxyInstance(
					Connection.class.getClassLoader(),
					new Class<?>[] {Connection.class},
					new RecordingConnection(connection));
		}
	}

	private record RecordingConnection(Connection delegate) implements InvocationHandler {

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			Object result = call(method, args);

			// prepareStatement / prepareCall carry the SQL at the point of preparation, which is where
			// the calling service's frames are still on the stack. A plain createStatement() does not,
			// so its Statement is proxied and the SQL is taken when it executes — same stack, because
			// Spring creates and executes it inside one callback.
			if (args != null && args.length > 0 && args[0] instanceof String sql
					&& ("prepareStatement".equals(method.getName()) || "prepareCall".equals(method.getName()))) {
				record(sql);
			}
			if (result instanceof Statement statement && !(result instanceof java.sql.PreparedStatement)) {
				return Proxy.newProxyInstance(Statement.class.getClassLoader(),
						new Class<?>[] {Statement.class}, new RecordingStatement(statement));
			}
			return result;
		}

		private Object call(Method method, Object[] args) throws Throwable {
			try {
				return method.invoke(delegate, args);
			} catch (InvocationTargetException e) {
				throw e.getCause();
			}
		}
	}

	private record RecordingStatement(Statement delegate) implements InvocationHandler {

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			if (method.getName().startsWith("execute")
					&& args != null && args.length > 0 && args[0] instanceof String sql) {
				record(sql);
			}
			try {
				return method.invoke(delegate, args);
			} catch (InvocationTargetException e) {
				throw e.getCause();
			}
		}
	}
}
