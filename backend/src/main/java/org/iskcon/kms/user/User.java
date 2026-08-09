package org.iskcon.kms.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A person's account at a temple.
 *
 * <p>Distinct from their Firebase identity: Firebase proves who they are, this decides what
 * they may do and which temple's data they may see.
 */
@Entity
@Table(name = "users")
public class User {

	@Id
	private UUID id;

	@Column(name = "tenant_id")
	private UUID tenantId;

	@Column(name = "firebase_uid", nullable = false, updatable = false)
	private String firebaseUid;

	@Column(name = "full_name", nullable = false)
	private String fullName;

	@Column(nullable = false)
	private String email;

	@Column(nullable = false)
	private String phone;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Role role;

	@Enumerated(EnumType.STRING)
	@Column(name = "preferred_channel", nullable = false)
	private NotificationChannel preferredChannel;

	@Column(name = "contact_consent_at")
	private Instant contactConsentAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected User() {
		// for JPA
	}

	public enum Role {
		SUPER_ADMIN,
		TEMPLE_ADMIN,
		KITCHEN_STAFF,
		VOLUNTEER
	}

	public enum NotificationChannel {
		WHATSAPP,
		SMS,
		EMAIL
	}

	public enum Status {
		ACTIVE,
		DISABLED
	}

	public UUID getId() {
		return id;
	}

	public UUID getTenantId() {
		return tenantId;
	}

	public String getFirebaseUid() {
		return firebaseUid;
	}

	public String getFullName() {
		return fullName;
	}

	public String getEmail() {
		return email;
	}

	public String getPhone() {
		return phone;
	}

	public Role getRole() {
		return role;
	}

	public NotificationChannel getPreferredChannel() {
		return preferredChannel;
	}

	public Instant getContactConsentAt() {
		return contactConsentAt;
	}

	public Status getStatus() {
		return status;
	}

	public boolean isActive() {
		return status == Status.ACTIVE;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
