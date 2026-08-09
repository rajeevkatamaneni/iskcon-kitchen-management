package org.iskcon.kms.auth;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.iskcon.kms.user.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The authenticated principal: our user record, not Firebase's.
 *
 * <p>Carries tenant and role so downstream authorisation reads them from a verified source
 * rather than from anything the caller supplied.
 */
public class AuthenticatedUser implements UserDetails {

	private final UUID userId;
	private final UUID tenantId;
	private final String firebaseUid;
	private final User.Role role;
	private final boolean active;

	public AuthenticatedUser(User user) {
		this.userId = user.getId();
		this.tenantId = user.getTenantId();
		this.firebaseUid = user.getFirebaseUid();
		this.role = user.getRole();
		this.active = user.isActive();
	}

	public UUID getUserId() {
		return userId;
	}

	public UUID getTenantId() {
		return tenantId;
	}

	public User.Role getRole() {
		return role;
	}

	/**
	 * Grants the role itself plus every permission the role carries.
	 *
	 * <p>Expanding permissions here, at authentication time, is what lets endpoints declare a
	 * permission rather than a list of roles — and lets Spring Security's existing machinery do
	 * the enforcement, instead of custom checks in each controller.
	 */
	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		List<GrantedAuthority> authorities = new ArrayList<>();

		// ROLE_ prefix is Spring Security's convention for hasRole() checks.
		authorities.add(new SimpleGrantedAuthority("ROLE_" + role.name()));

		RolePermissions.forRole(role).stream()
				.map(permission -> new SimpleGrantedAuthority(permission.name()))
				.forEach(authorities::add);

		return authorities;
	}

	@Override
	public String getPassword() {
		// Passwords live in Firebase and never reach this application.
		return null;
	}

	@Override
	public String getUsername() {
		return firebaseUid;
	}

	@Override
	public boolean isEnabled() {
		return active;
	}

	@Override
	public boolean isAccountNonExpired() {
		return active;
	}

	@Override
	public boolean isAccountNonLocked() {
		return active;
	}

	@Override
	public boolean isCredentialsNonExpired() {
		return true;
	}
}
