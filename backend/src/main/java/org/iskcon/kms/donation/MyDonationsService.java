package org.iskcon.kms.donation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.iskcon.kms.auth.TokenVerifier.VerifiedSubject;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which gifts are the signed-in devotee's own (T-179), and nothing else.
 *
 * <p>Rajeev's ruling, 2026-09-13: the admin's Send receipt stays as the safety net, and the default
 * is that a devotee can see every successful gift they made and download the receipt for each. On
 * how a gift is recognised as theirs he chose "option 2": the gift was given from their account, or
 * it was recorded at the counter against a phone number or email address <em>they have proven they
 * control</em>.
 *
 * <h2>What "successful" means</h2>
 *
 * <p>Every gift — money given online, cash at the counter, goods at the gate — is one row in
 * {@code donations} (V17, extended to money by V38). A gift counts here when all three hold:
 *
 * <ul>
 *   <li>{@code status = 'COMPLETED'}. V38's other values are {@code PENDING} (checkout started, not
 *       paid), {@code FAILED} and {@code EXPIRED}. A counter gift and a gift in kind are written
 *       without a status and take V38's default, {@code COMPLETED}, which is right: the temple has
 *       the money or the goods in hand.
 *   <li>{@code voided_at IS NULL}. A struck gift (V104) keeps {@code COMPLETED}, so a status filter
 *       alone would list a gift the temple has told the tax authority it never received.
 *   <li>{@code is_anonymous = false}. Somebody who gave anonymously asked not to be connected to the
 *       gift; showing it back to them would be the product connecting them to it. V38's CHECK means an
 *       anonymous row carries no contact anyway, but an online gift carries an account id regardless,
 *       so this is stated rather than inferred.
 * </ul>
 *
 * <h2>What "theirs" means, and what it never means</h2>
 *
 * <p>A gift is theirs if {@code donor_account_user_id} is the caller's own user id at this temple, or
 * if its {@code donor_phone} or {@code donor_email} equals a contact in {@link VerifiedContacts}. It
 * is <strong>never</strong> matched on name or PAN. A name is not an identity — two devotees are
 * called Govind Das — and a PAN is exactly the fact a receipt prints, so matching on it would hand
 * one person's 80G paper to anybody who could type another's PAN. The admin's donor history does
 * link by PAN fingerprint ({@code DonationLedgerService.donorHistory}); that is an office tool behind
 * {@code VIEW_DONATIONS}, and this page is not.
 *
 * <p><strong>How the contacts are compared.</strong> Email: trimmed, and compared without regard to
 * case, the same folding {@code PendingAccountClaim} applies before it matches an email. Phone: the
 * stored number has T-157's separators removed (whitespace, dashes, invisible format characters — the
 * exact set {@code PhoneNumberDeserializer} removes), and must then be <em>identical</em> to the
 * verified number, which Firebase always gives in E.164 ({@code +919876543210}). Nothing looser: no
 * dropping a leading 0, no assuming +91, no comparing the last ten digits. A number typed at the
 * counter as {@code 98765 43210} therefore does not match {@code +919876543210}, and that is the
 * point — guessing a country code is how one person's receipts reach another person's phone.
 *
 * <h2>Why the matching is decided in Java, after SQL</h2>
 *
 * <p>The query narrows with a deliberately wider net for phones (every character but digits and
 * {@code +} removed), and {@link #owns} then applies the exact rule above to each row. PostgreSQL's
 * regular expressions have no {@code \p{Z}}, {@code \p{Pd}} or {@code \p{Cf}}, so writing T-157's
 * set in SQL would have meant hand-listing code points that could drift from the Java pattern; this
 * way the one rule that decides is the one written in the same language as T-157's. The SQL net is a
 * superset by construction — it removes everything T-157 removes and more — so it can only let
 * extra rows through for {@link #owns} to refuse, never hide a row that should match.
 *
 * <h2>Tenant</h2>
 *
 * <p>Row-Level Security scopes every read to the temple the request speaks for, and that is the
 * boundary. The explicit {@code tenant_id} predicate beside it is not a second boundary; it is there
 * so a reader of this query does not have to know RLS exists to see that it is scoped, and so the
 * planner can use {@code donations_tenant_date}.
 */
@Service
public class MyDonationsService {

	/**
	 * T-157's separators, copied from {@code config.PhoneNumberDeserializer.SEPARATORS}. Copied rather
	 * than called because that class's {@code normalise} is package-private in {@code config}, which
	 * is outside this task. The two must be kept identical; see the follow-up in T-179's proof.
	 */
	private static final Pattern PHONE_SEPARATORS = Pattern.compile("[\\s\\p{Z}\\p{Pd}\\p{Cf}]");

	/** The shape every E.164 field in this application validates against. */
	private static final Pattern E164 = Pattern.compile("^\\+[1-9][0-9]{7,14}$");

	private final JdbcTemplate jdbc;

	public MyDonationsService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * The contacts a person has <em>proven</em> they control, taken from their verified token.
	 *
	 * <p>What Firebase proves, and so what counts:
	 *
	 * <ul>
	 *   <li><strong>Phone:</strong> the token's {@code phone_number} claim. Firebase sets it only on an
	 *       account that has completed phone sign-in, which requires the one-time code sent to that
	 *       number, so its presence is the proof. It is always E.164.
	 *   <li><strong>Email:</strong> the token's {@code email}, and <em>only</em> when
	 *       {@code email_verified} is true. Firebase will put an address on a token that nobody has
	 *       confirmed (an email-and-password account that never clicked the link), so an address alone
	 *       proves nothing. Google sign-in reports a Gmail address as verified.
	 * </ul>
	 *
	 * <p>This is the same rule {@code PendingAccountClaim.verifiedContact} applies before it lets a
	 * first sign-in adopt a pending account, and for the same reason: an unverified email is a claim,
	 * and anyone can make one.
	 */
	public record VerifiedContacts(String phone, String email) {

		public static final VerifiedContacts NONE = new VerifiedContacts(null, null);

		/**
		 * @param subject what the token proved, or null where it could not be read again
		 * @param expectedUid the Firebase uid of the principal this request was authenticated as; a
		 *     subject for anybody else contributes nothing
		 */
		public static VerifiedContacts from(VerifiedSubject subject, String expectedUid) {
			if (subject == null || expectedUid == null || !expectedUid.equals(subject.uid())) {
				return NONE;
			}
			String email = null;
			if (subject.emailVerified() && hasText(subject.email())) {
				email = subject.email().trim().toLowerCase(Locale.ROOT);
			}
			String phone = null;
			if (hasText(subject.phoneNumber())) {
				String canonical = canonicalPhone(subject.phoneNumber());
				// Firebase only ever gives E.164. Anything else is not a number we can compare exactly,
				// so it proves nothing rather than being matched approximately.
				if (E164.matcher(canonical).matches()) {
					phone = canonical;
				}
			}
			return new VerifiedContacts(phone, email);
		}
	}

	/** Every successful gift that is the caller's own, newest first. */
	@Transactional(readOnly = true)
	public List<MyDonation> list(UUID callerUserId, VerifiedContacts contacts) {
		return candidates(callerUserId, contacts, null).stream()
				.filter(row -> owns(row, callerUserId, contacts))
				.map(MyDonationsService::toView)
				.toList();
	}

	/**
	 * The receipt number of one of the caller's own gifts, or {@code KMS-400030}.
	 *
	 * <p>One answer for every reason a download cannot happen: the gift does not exist, belongs to
	 * another temple, belongs to somebody else, was matched only by an unverified email, is struck,
	 * anonymous or not completed, or has no receipt issued. A stranger is told exactly what a person
	 * asking about a gift that never existed is told, so the endpoint cannot be used to learn that a
	 * gift id is real. The same answer RLS already gives for another temple's gift on the admin path.
	 */
	@Transactional(readOnly = true)
	public String requireOwnReceiptNumber(UUID donationId, UUID callerUserId, VerifiedContacts contacts) {
		return candidates(callerUserId, contacts, donationId).stream()
				.filter(row -> owns(row, callerUserId, contacts))
				.map(row -> (String) row.get("receipt_number"))
				.filter(Objects::nonNull)
				.findFirst()
				.orElseThrow(() -> new ApplicationException(
						ErrorCode.RESOURCE_NOT_FOUND, Map.of("donationId", donationId)));
	}

	// ---------------------------------------------------------------------

	/**
	 * Successful gifts at this temple that <em>might</em> be the caller's. A superset: {@link #owns}
	 * decides. See the class comment for why the phone net is wider than the rule.
	 */
	private List<Map<String, Object>> candidates(UUID callerUserId, VerifiedContacts contacts, UUID onlyId) {
		String phoneNet = contacts.phone() == null ? null : contacts.phone().replaceAll("[^0-9+]", "");
		return jdbc.queryForList("""
				SELECT d.id, d.type, d.donated_on, d.amount_inr, d.provider, d.receipt_number,
					   d.donor_account_user_id, d.donor_phone, d.donor_email,
					   wi.title AS wishlist_title,
					   (SELECT string_agg(i.name || ', ' || trim_scale(m.quantity) || ' ' ||
								CASE m.unit WHEN 'KG' THEN 'Kg' WHEN 'GM' THEN 'gm' WHEN 'ML' THEN 'ml'
											WHEN 'PIECES' THEN 'pieces' ELSE m.unit END,
								'; ' ORDER BY i.name)
						  FROM stock_movements m JOIN ingredients i ON i.id = m.ingredient_id
						 WHERE m.reference_type = 'DONATION' AND m.reference_id = d.id
						   AND m.movement_type = 'DONATION_IN_KIND' AND m.quantity > 0) AS food,
					   (SELECT string_agg(e.name, '; ' ORDER BY e.name)
						  FROM equipment_items e WHERE e.donation_id = d.id) AS equipment
				FROM donations d
				LEFT JOIN wishlist_items wi ON wi.id = d.wishlist_item_id
				WHERE d.tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				  AND d.status = 'COMPLETED'
				  AND d.voided_at IS NULL
				  AND d.is_anonymous = false
				  AND (?::uuid IS NULL OR d.id = ?::uuid)
				  AND (d.donor_account_user_id = ?::uuid
					   OR (?::text IS NOT NULL AND regexp_replace(d.donor_phone, '[^0-9+]', '', 'g') = ?::text)
					   OR (?::text IS NOT NULL AND lower(btrim(d.donor_email)) = ?::text))
				ORDER BY d.donated_on DESC, d.created_at DESC
				""",
				onlyId, onlyId,
				callerUserId,
				phoneNet, phoneNet,
				contacts.email(), contacts.email());
	}

	/** The exact rule. Account, else a verified phone identical after T-157, else a verified email. */
	private static boolean owns(Map<String, Object> row, UUID callerUserId, VerifiedContacts contacts) {
		if (callerUserId != null && callerUserId.equals(row.get("donor_account_user_id"))) {
			return true;
		}
		String storedPhone = (String) row.get("donor_phone");
		if (contacts.phone() != null && storedPhone != null
				&& contacts.phone().equals(canonicalPhone(storedPhone))) {
			return true;
		}
		String storedEmail = (String) row.get("donor_email");
		return contacts.email() != null && storedEmail != null
				&& contacts.email().equals(storedEmail.trim().toLowerCase(Locale.ROOT));
	}

	private static MyDonation toView(Map<String, Object> row) {
		boolean goods = "IN_KIND".equals(row.get("type"));
		return new MyDonation(
				(UUID) row.get("id"),
				goods ? "GOODS" : "MONEY",
				donatedOn(row.get("donated_on")),
				goods ? null : (BigDecimal) row.get("amount_inr"),
				goods ? goodsDescription(row) : moneyDescription(row),
				(String) row.get("receipt_number"));
	}

	/**
	 * What the money was, in plain words. A wish-list item's own title where the gift was towards one,
	 * because that is how the devotee will remember it; otherwise how it reached the temple.
	 */
	private static String moneyDescription(Map<String, Object> row) {
		String wish = (String) row.get("wishlist_title");
		if (wish != null) {
			return "Towards " + wish;
		}
		// provider is set only by the gateway; a person at the counter never sets it (DonationRecorder).
		return row.get("provider") != null ? "Online donation" : "Given at the temple";
	}

	/** What was given, e.g. "Rice, 25 Kg; Wet grinder". Units written as {@code format.ts} writes them. */
	private static String goodsDescription(Map<String, Object> row) {
		String food = (String) row.get("food");
		String equipment = (String) row.get("equipment");
		if (food != null && equipment != null) {
			return food + "; " + equipment;
		}
		if (food != null) {
			return food;
		}
		return equipment != null ? equipment : "Goods given to the temple";
	}

	private static LocalDate donatedOn(Object value) {
		return value instanceof java.sql.Date sql ? sql.toLocalDate() : (LocalDate) value;
	}

	private static String canonicalPhone(String phone) {
		return PHONE_SEPARATORS.matcher(phone).replaceAll("");
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}
}
