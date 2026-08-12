package org.iskcon.kms.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A temple. The unit of isolation for the entire system.
 */
@Entity
@Table(name = "tenants")
public class Tenant {

	@Id
	private UUID id;

	@Column(nullable = false, unique = true, updatable = false)
	private String slug;

	@Column(nullable = false)
	private String name;

	private String address;

	/**
	 * Required, not optional. The Vaishnava calendar computes tithi at local sunrise, so a
	 * temple without coordinates cannot have a calendar — and without a calendar the meal
	 * planner cannot tell an Ekadashi from any other Tuesday.
	 */
	@Column(nullable = false)
	private BigDecimal latitude;

	@Column(nullable = false)
	private BigDecimal longitude;

	@Column(nullable = false)
	private String timezone;

	// A fixed-width ISO 4217 code. The column is CHAR(3) by deliberate choice (V1); mapping it as
	// a plain String would ask Hibernate for varchar and fail schema validation on boot.
	@Column(nullable = false, length = 3)
	@JdbcTypeCode(SqlTypes.CHAR)
	private String currency;

	@Column(nullable = false)
	private String locale;

	/** Drives whether the 80G donor-data path is offered at all (E7-S4). */
	@Column(name = "is_80g_approved", nullable = false)
	private boolean is80gApproved;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Tenant() {
		// for JPA
	}

	public UUID getId() {
		return id;
	}

	public String getSlug() {
		return slug;
	}

	public String getName() {
		return name;
	}

	public String getAddress() {
		return address;
	}

	public BigDecimal getLatitude() {
		return latitude;
	}

	public BigDecimal getLongitude() {
		return longitude;
	}

	public String getTimezone() {
		return timezone;
	}

	public String getCurrency() {
		return currency;
	}

	public String getLocale() {
		return locale;
	}

	public boolean is80gApproved() {
		return is80gApproved;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
