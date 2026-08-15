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
	private final String fullName;
	private final String email;
	private final User.Role role;
	private final boolean active;
	/** Only carried for someone with no membership yet, whose contact is all we know of them. */
	private final String phone;

	/**
	 * Someone Firebase has verified who belongs to no temple yet — a devotee signing in with Google
	 * before they have said where they serve. They carry no tenant, no role and no user record,
	 * because none exists: the only thing they may do is join a temple, which creates one.
	 */
	public static AuthenticatedUser unaffiliated(String firebaseUid, String email, String phone) {
		return new AuthenticatedUser(firebaseUid, email, phone);
	}

	private AuthenticatedUser(String firebaseUid, String email, String phone) {
		this.userId = null;
		this.tenantId = null;
		this.firebaseUid = firebaseUid;
		this.fullName = null;
		this.email = email;
		this.phone = phone;
		this.role = null;
		this.active = true;
	}

	public AuthenticatedUser(User user) {
		this.userId = user.getId();
		this.tenantId = user.getTenantId();
		this.firebaseUid = user.getFirebaseUid();
		this.fullName = user.getFullName();
		this.email = user.getEmail();
		this.role = user.getRole();
		this.active = user.isActive();
		this.phone = user.getPhone();
	}

	public UUID getUserId() {
		return userId;
	}

	public UUID getTenantId() {
		return tenantId;
	}

	/** The person's name, carried so audit entries can name the actor without a second lookup. */
	public String getFullName() {
		return fullName;
	}

	/** The person's email, likewise captured into audit entries as part of the actor's identity. */
	/** The Firebase subject this principal was verified as — the identity behind every membership. */
	public String getFirebaseUid() {
		return firebaseUid;
	}

	/** The person's phone, where we have one. Carried for the same reason as the email. */
	public String getPhone() {
		return phone;
	}

	public String getEmail() {
		return email;
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

		if (role == null) {
			// No membership, so no role to read a policy from. One permission, named in
			// RolePermissions with the rest so the whole policy still reads as one document.
			RolePermissions.forNoMembership().stream()
					.map(permission -> new SimpleGrantedAuthority(permission.name()))
					.forEach(authorities::add);
			return authorities;
		}

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
