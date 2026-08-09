package org.iskcon.kms.tenancy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Applies the current tenant to every database connection so PostgreSQL Row-Level Security
 * can enforce isolation.
 *
 * <p>On checkout, sets the {@code app.tenant_id} setting that the RLS policies in
 * {@code V1__tenancy_foundation.sql} read. On return to the pool, resets it — connections are
 * reused, and a stale value would hand the next borrower the previous tenant's visibility.
 *
 * <p>When no tenant is set, the setting is cleared rather than defaulted. The RLS policy
 * compares {@code tenant_id} against a NULL, which matches no rows: the system fails closed.
 * A connection with no tenant context sees nothing at all.
 *
 * <p>Applied at connection level rather than per-transaction so that it holds regardless of
 * how the query is issued — JPA, JdbcTemplate, or native SQL all go through here, and none of
 * them can opt out.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

	private static final String RESET_TENANT = "RESET app.tenant_id";
	private static final String RESET_AUTH_UID = "RESET app.auth_uid";

	public TenantAwareDataSource(DataSource delegate) {
		super(delegate);
	}

	@Override
	public Connection getConnection() throws SQLException {
		return prepare(super.getConnection());
	}

	@Override
	public Connection getConnection(String username, String password) throws SQLException {
		return prepare(super.getConnection(username, password));
	}

	private Connection prepare(Connection connection) throws SQLException {
		applyTenant(connection);
		return wrapSoResetOnClose(connection);
	}

	private void applyTenant(Connection connection) throws SQLException {
		UUID tenantId = TenantContext.get().orElse(null);
		String authLookupUid = TenantContext.getAuthLookupUid().orElse(null);

		// set_config rather than string-concatenating into SET: values reach the database as
		// bound parameters, never as SQL text.
		setConfig(connection, "app.tenant_id", tenantId == null ? "" : tenantId.toString());
		setConfig(connection, "app.auth_uid", authLookupUid == null ? "" : authLookupUid);
	}

	private void setConfig(Connection connection, String key, String value) throws SQLException {
		try (var ps = connection.prepareStatement("SELECT set_config(?, ?, false)")) {
			ps.setString(1, key);
			ps.setString(2, value);
			ps.execute();
		}
	}

	/**
	 * Returns a proxy that resets the tenant setting before the connection goes back to the
	 * pool. Without this, a pooled connection would carry one request's tenant into the next.
	 */
	private Connection wrapSoResetOnClose(Connection connection) {
		return (Connection) Proxy.newProxyInstance(
				Connection.class.getClassLoader(),
				new Class<?>[] {Connection.class},
				new ResetOnCloseHandler(connection));
	}

	private record ResetOnCloseHandler(Connection delegate) implements InvocationHandler {

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			if ("close".equals(method.getName())) {
				resetQuietly();
			}
			try {
				return method.invoke(delegate, args);
			} catch (InvocationTargetException e) {
				throw e.getCause();
			}
		}

		private void resetQuietly() {
			try {
				if (!delegate.isClosed()) {
					try (Statement statement = delegate.createStatement()) {
						statement.execute(RESET_TENANT);
						statement.execute(RESET_AUTH_UID);
					}
				}
			} catch (SQLException ignored) {
				// A connection being discarded due to an error cannot be reset, and that is
				// safe: it will not be reused. Failing the close for this would mask the
				// original error.
			}
		}
	}
}
