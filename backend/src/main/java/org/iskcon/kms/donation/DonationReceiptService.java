package org.iskcon.kms.donation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.audit.AuditAction;
import org.iskcon.kms.audit.AuditEntityType;
import org.iskcon.kms.audit.AuditService;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.document.DonationReceiptTemplate;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.notification.NotificationRecipient;
import org.iskcon.kms.notification.NotificationService;
import org.iskcon.kms.notification.NotificationTemplate;
import org.iskcon.kms.security.PanCipher;
import org.iskcon.kms.tenancy.TempleClock;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The receipt a donor is given, and given again (T-110).
 *
 * <p>The product has captured PAN since E7-S4, enforces the 80G shape with a CHECK, and accumulates
 * a Form 10BD-shaped dataset by construction. The one document a donor actually asks for — the piece
 * of paper they file with their return — did not exist until this class. It is rendered through the
 * documents pipeline (V12, extended by V117), not beside it: PENDING to READY, the bytes in object
 * storage, an authorised download, exactly as the recipe card, the PO sheet, the job card and the
 * work order already are.
 *
 * <p><strong>One payment, one receipt.</strong> The number is issued once by
 * {@link #issueNumber(UUID, AuthenticatedUser)} and never reissued, and V117's unique index refuses
 * a second receipt row for the same gift. Re-sending finds what is already there. That is not
 * tidiness: two receipts for one gift is the failure under 80G that this whole feature exists to
 * avoid, and it is the reason T-081 kept a split gift as one donation row rather than two.
 *
 * <p><strong>{@code amount_inr} and nothing else.</strong> A gift that went partly to a wish-list
 * item and partly to general funds is still one payment the donor made, and the receipt reports that
 * payment. The split lives in {@code wishlist_applied_inr} and the receipt never reads it — see
 * V113's header, which settled this before the split was built.
 */
@Service
public class DonationReceiptService {

	/** "14 Aug 2026" — the way a date is written on a receipt somebody will file. */
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy");

	private final JdbcTemplate jdbc;
	private final PanCipher panCipher;
	private final NotificationService notificationService;
	private final AuditService auditService;
	private final TempleClock clock;

	public DonationReceiptService(
			JdbcTemplate jdbc, PanCipher panCipher, NotificationService notificationService,
			AuditService auditService, TempleClock clock) {
		this.jdbc = jdbc;
		this.panCipher = panCipher;
		this.notificationService = notificationService;
		this.auditService = auditService;
		this.clock = clock;
	}

	/**
	 * The permanent number on this gift's receipt, issued on first ask and returned unchanged
	 * thereafter.
	 *
	 * <p>Refuses a struck gift. A void says the temple did not receive the money — every 80G figure
	 * already ignores it (V104) — so issuing a receipt for one would put the temple's name on a
	 * document contradicting its own return. The refusal is here rather than in the controller
	 * because it must hold for the re-send path too, which comes through the same door.
	 *
	 * <p>Returned rather than merely written, because the caller that queues the PDF wants to know
	 * the gift was issuable before it enqueues anything: a document queued only to fail in the worker
	 * turns a clear refusal into a FAILED row somebody has to interpret.
	 */
	@Transactional
	public String issueNumber(UUID donationId, AuthenticatedUser actor) {
		Map<String, Object> d = require(donationId);
		if (d.get("voided_at") != null) {
			throw new ApplicationException(
					ErrorCode.DONATION_ALREADY_VOIDED, Map.of("donationId", donationId));
		}

		String existing = (String) d.get("receipt_number");
		if (existing != null) {
			return existing;
		}

		String number = nextReceiptNumber(donatedOn(d));
		jdbc.update("""
				UPDATE donations SET receipt_number = ?, receipt_issued_at = now() WHERE id = ?
				""", number, donationId);

		// Recorded once, at the moment a person decides the temple will put its name to this. The
		// entry is deliberately not filed by the worker that renders the bytes: the worker has no
		// actor, and "who issued this receipt" is the only question the entry is worth keeping for.
		auditService.record(actor, AuditAction.DONATION_RECEIPT_ISSUED, AuditEntityType.DONATION,
				donationId, null, Map.of("receiptNumber", number), null);

		// A receipt for an 80G gift prints the donor's PAN, so issuing one *is* a decryption of PII
		// and is recorded as one, under the same action an explicit reveal uses. A reader auditing
		// who has seen a donor's PAN should not have to know that a receipt is a second way to see it.
		if (Boolean.TRUE.equals(d.get("wants_80g")) && d.get("donor_pan_ciphertext") != null) {
			auditService.record(actor, AuditAction.DONOR_PAN_VIEWED, AuditEntityType.DONATION,
					donationId, null, Map.of("via", "80G receipt"), null);
		}
		return number;
	}

	/**
	 * The receipt as HTML — the worker's input, and the same document either way.
	 *
	 * <p>Read-only and actorless: by the time this runs the decision to issue was already taken and
	 * audited by {@link #issueNumber}, and this method only lays out what that decision produced.
	 */
	@Transactional(readOnly = true)
	public String render(UUID donationId) {
		Map<String, Object> d = require(donationId);
		Map<String, Object> t = jdbc.queryForMap("""
				SELECT name, address, is_80g_approved FROM tenants
				WHERE id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""");

		boolean inKind = "IN_KIND".equals(d.get("type"));
		boolean templeApproved = Boolean.TRUE.equals(t.get("is_80g_approved"));

		// The one figure on the sheet. For money it is amount_inr and nothing derived from it; for
		// goods there is no payment at all, only the temple's own estimate of worth, and the status
		// line below says in so many words that the two are not the same kind of fact.
		BigDecimal amount = (BigDecimal) (inKind ? d.get("estimated_value_inr") : d.get("amount_inr"));

		String pan = null;
		if (Boolean.TRUE.equals(d.get("wants_80g")) && d.get("donor_pan_ciphertext") != null) {
			pan = panCipher.decrypt((byte[]) d.get("donor_pan_ciphertext"));
		}

		return DonationReceiptTemplate.render(new DonationReceiptTemplate.ReceiptModel(
				(String) t.get("name"),
				(String) t.get("address"),
				(String) d.get("receipt_number"),
				issuedOn(d.get("receipt_issued_at")),
				donorName(d),
				(String) d.get("donor_address"),
				pan,
				amount == null ? null : Rupees.format(amount),
				DATE.format(donatedOn(d)),
				paymentModeText(d, inKind),
				reference(d),
				purpose(d, inKind),
				status(inKind, templeApproved),
				(String) d.get("section"),
				DATE.format(LocalDate.now(clock.zone()))));
	}

	/**
	 * Sends the donor word that their receipt has been issued, and says honestly whether it went.
	 *
	 * <p><strong>Not an error when it cannot go.</strong> A gift given anonymously, or one recorded
	 * at the gate with no phone number and no email address, has nobody to send anything to. That is
	 * a fact about the record rather than a mistake somebody made, and the same shape the donations
	 * ledger already uses when it withholds the Void control from a gift that is already struck: the
	 * screen says why there is nothing to press instead of offering a button that refuses.
	 *
	 * <p>Best-effort beyond that, like every other donor acknowledgement in this package: a message
	 * the queue would not take must not undo an issued receipt.
	 */
	@Transactional
	public boolean send(UUID donationId) {
		Map<String, Object> d = require(donationId);
		String phone = (String) d.get("donor_phone");
		String email = (String) d.get("donor_email");
		if (Boolean.TRUE.equals(d.get("is_anonymous")) || (phone == null && email == null)) {
			return false;
		}
		String temple = jdbc.queryForObject("""
				SELECT name FROM tenants WHERE id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
				""", String.class);
		notificationService.notify(
				NotificationRecipient.contact(phone, email),
				NotificationTemplate.DONATION_RECEIPT,
				Map.of(
						"donor", donorName(d) == null ? "" : donorName(d),
						"temple", temple == null ? "the temple" : temple,
						"receiptNumber", d.get("receipt_number") == null ? "" : d.get("receipt_number").toString(),
						"date", DATE.format(donatedOn(d))),
				null);
		return true;
	}

	// ---------------------------------------------------------------------

	/**
	 * The gift, or a clean 404. RLS scopes the read to one temple, so a donation belonging to another
	 * is genuinely not there rather than forbidden — which is the answer we want it to give.
	 */
	private Map<String, Object> require(UUID donationId) {
		try {
			return jdbc.queryForMap("""
					SELECT d.id, d.type, d.status, d.voided_at, d.donated_on, d.amount_inr,
						   d.estimated_value_inr, d.currency, d.payment_mode, d.provider,
						   d.provider_payment_id, d.provider_order_id, d.section,
						   d.is_anonymous, d.donor_name, d.donor_phone, d.donor_email, d.donor_address,
						   d.donor_pan_ciphertext, d.wants_80g,
						   d.receipt_number, d.receipt_issued_at, d.notes,
						   wi.title AS wishlist_title
					FROM donations d LEFT JOIN wishlist_items wi ON wi.id = d.wishlist_item_id
					WHERE d.id = ?
					""", donationId);
		} catch (EmptyResultDataAccessException e) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("donationId", donationId), e);
		}
	}

	/**
	 * The next number for this temple, as {@code po_sequence} and {@code meal_card_sequence} do it.
	 *
	 * <p>The atomic INSERT ... ON CONFLICT DO UPDATE ... RETURNING row-locks per tenant, so two
	 * people issuing at once never get one number, and a rolled-back issue leaves a gap. Gaps are
	 * fine: the number identifies a receipt, it does not count them.
	 *
	 * <p>The year printed into it is the year of the <em>gift</em>, not of the issuing. A receipt
	 * written in April for a gift given the previous March belongs, to everybody who will ever read
	 * it, to the year the money arrived.
	 */
	private String nextReceiptNumber(LocalDate donatedOn) {
		Integer seq = jdbc.queryForObject("""
				INSERT INTO donation_receipt_sequence (tenant_id, last_number)
				VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, 1)
				ON CONFLICT (tenant_id) DO UPDATE SET last_number = donation_receipt_sequence.last_number + 1
				RETURNING last_number
				""", Integer.class);
		return "R-" + donatedOn.getYear() + "-" + String.format("%04d", seq);
	}

	/**
	 * The day the receipt was issued, in the temple's own clock.
	 *
	 * <p>Both shapes are handled because the driver hands back whichever it likes for a
	 * {@code TIMESTAMPTZ} depending on how the row was read — {@code queryForMap} gives a
	 * {@link java.sql.Timestamp}, a typed {@code getObject} an {@link OffsetDateTime} — and a cast
	 * that assumed one of them threw a ClassCastException inside the worker, which caught it as a
	 * generation failure and marked the receipt FAILED. Found by the test that downloads one.
	 *
	 * <p>Zoned at the point of formatting rather than at the point of reading, for the reason
	 * {@code DocumentGenerationService} gives: the zone belongs to the temple whose sheet is being
	 * printed, not to the machine printing it.
	 */
	private String issuedOn(Object value) {
		if (value == null) {
			return null;
		}
		java.time.Instant at = value instanceof java.sql.Timestamp stamp
				? stamp.toInstant()
				: ((OffsetDateTime) value).toInstant();
		return DATE.format(at.atZone(clock.zone()));
	}

	private static LocalDate donatedOn(Map<String, Object> d) {
		Object value = d.get("donated_on");
		return value instanceof java.sql.Date sql ? sql.toLocalDate() : (LocalDate) value;
	}

	/**
	 * Who the receipt is made out to.
	 *
	 * <p>An anonymous gift keeps no name — V38's CHECK guarantees there is none to print — so the
	 * receipt says so rather than leaving a blank line above the amount. A receipt made out to nobody
	 * is still a real document: it is the temple's own record that the money arrived.
	 */
	private static String donorName(Map<String, Object> d) {
		if (Boolean.TRUE.equals(d.get("is_anonymous"))) {
			return null;
		}
		return (String) d.get("donor_name");
	}

	/**
	 * How the money arrived, in words rather than in the stored shouting.
	 *
	 * <p>Goods are not a payment mode and the receipt does not pretend they are: an in-kind gift says
	 * what it was, which is the honest answer to "how was this paid".
	 */
	private static String paymentModeText(Map<String, Object> d, boolean inKind) {
		if (inKind) {
			return "Goods given to the temple";
		}
		String mode = (String) d.get("payment_mode");
		if (mode == null) {
			return null;
		}
		return switch (mode) {
			case "CASH" -> "Cash";
			case "UPI" -> "UPI";
			case "BANK_TRANSFER" -> "Bank transfer";
			case "CHEQUE" -> "Cheque";
			case "CARD" -> "Card";
			case "NETBANKING" -> "Net banking";
			case "WALLET" -> "Wallet";
			// A mode nobody has a word for yet is still better shown than blanked — the same fallback
			// the ledger's own column makes, and for the same reason.
			default -> mode;
		};
	}

	/** The payment reference a donor can quote to their bank, where the gateway gave us one. */
	private static String reference(Map<String, Object> d) {
		Object ref = d.get("provider_payment_id");
		return (String) (ref != null ? ref : d.get("provider_order_id"));
	}

	/**
	 * What the gift was for, in one line.
	 *
	 * <p>Deliberately the wish-list item's own title and nothing about how much of the payment
	 * reached it. A gift that only partly fitted (T-081) still finished the item it names, and a
	 * receipt carrying two figures would leave a donor's accountant asking which of them is the
	 * deductible one. One payment, one figure, and the purpose beside it.
	 */
	private static String purpose(Map<String, Object> d, boolean inKind) {
		String title = (String) d.get("wishlist_title");
		if (title != null) {
			return title;
		}
		return inKind ? "Goods received by the kitchen" : "General kitchen";
	}

	/**
	 * The temple's 80G standing as it applies to <em>this</em> gift, which is not the same question as
	 * whether the temple is approved.
	 *
	 * <p>Two facts decide it and both have to be said. A temple with no 80G approval cannot support a
	 * deduction at all. And a gift in kind does not qualify under 80G even at a temple that is
	 * approved — only money does — so a sack of rice gets an honest acknowledgement of goods received
	 * rather than a document implying a deduction the donor cannot claim. Printing "80G" over either
	 * would be the app putting words in the temple's mouth on a tax document.
	 */
	private static DonationReceiptTemplate.EightyGStatus status(boolean inKind, boolean templeApproved) {
		if (!templeApproved) {
			return DonationReceiptTemplate.EightyGStatus.TEMPLE_NOT_APPROVED;
		}
		return inKind
				? DonationReceiptTemplate.EightyGStatus.IN_KIND_NOT_ELIGIBLE
				: DonationReceiptTemplate.EightyGStatus.ELIGIBLE;
	}
}
