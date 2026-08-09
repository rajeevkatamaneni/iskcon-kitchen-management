package org.iskcon.kms.auth;

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

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		// ROLE_ prefix is Spring Security's convention for hasRole() checks.
		return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
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
