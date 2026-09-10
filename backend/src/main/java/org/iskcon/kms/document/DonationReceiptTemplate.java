package org.iskcon.kms.document;

/**
 * The server-rendered donation receipt (T-110): the one document a donor actually asks the temple
 * for, and the one they file with their return.
 *
 * <p>The fifth template in this package and built the same way as the other four — a StringBuilder,
 * a self-contained A4 page, every colour and size a literal, every interpolated value escaped —
 * because Chromium renders it with no network and the browser print view has no origin of its own.
 *
 * <p><strong>It is not translated, and that is a decision rather than an omission.</strong> The PO
 * sheet translates because a vendor reads it; the work order translates because a storekeeper does.
 * This is a tax document. The words on it are the words the Income-tax Act uses, an assessing
 * officer reads it in English, and a receipt whose statutory sentence had been through machine
 * translation would be worth less than one that was never translated at all.
 *
 * <p><strong>It never claims more than the temple can support.</strong> {@link EightyGStatus} is
 * carried in the model rather than decided here, and each of its three values prints a different
 * sentence. There is no fourth branch and no default that quietly says "80G".
 */
public final class DonationReceiptTemplate {

	/**
	 * What this receipt can honestly say about tax, decided by {@code DonationReceiptService} from
	 * two facts: whether the temple holds 80G approval, and whether the gift was money.
	 */
	public enum EightyGStatus {

		/** Money, at a temple with 80G approval. The only case that names a deduction. */
		ELIGIBLE,

		/**
		 * Goods, at a temple that does hold 80G approval. Gifts in kind do not qualify for deduction
		 * under 80G — only money does — so the receipt acknowledges what arrived and says plainly
		 * that it is not the other kind of document, rather than leaving a donor to find out from
		 * their accountant in March.
		 */
		IN_KIND_NOT_ELIGIBLE,

		/**
		 * A temple with no 80G approval recorded. The receipt is still worth issuing — it is the
		 * temple's acknowledgement that the gift arrived — but it supports no deduction and says so.
		 */
		TEMPLE_NOT_APPROVED
	}

	/**
	 * Everything the receipt renders.
	 *
	 * <p>Every field is already formatted. The template makes no decisions about money, dates or
	 * eligibility — those belong to the service, where they can be tested without parsing HTML.
	 *
	 * @param donorName    null for a gift given anonymously, which keeps no name to print (V38's
	 *                     CHECK sees to that). The receipt then says so in place of the name.
	 * @param donorPan     the donor's PAN, decrypted, and only where they asked for an 80G receipt
	 *                     and gave one. Null everywhere else — a receipt does not invent a field.
	 * @param amountText   the payment, in rupees, and for a monetary gift it is {@code amount_inr}
	 *                     exactly. A gift that went partly to a wish-list item is still one payment
	 *                     and this is that payment; the split is not this document's business.
	 * @param section      the section a certificate cites, as captured at donation time. Printed only
	 *                     where the gift is actually eligible.
	 */
	public record ReceiptModel(
			String templeName,
			String templeAddress,
			String receiptNumber,
			String issuedOn,
			String donorName,
			String donorAddress,
			String donorPan,
			String amountText,
			String receivedOn,
			String paymentModeText,
			String reference,
			String purpose,
			EightyGStatus eightyG,
			String section,
			String generatedOn) {
	}

	private DonationReceiptTemplate() {
	}

	public static String render(ReceiptModel m) {
		StringBuilder h = new StringBuilder();
		h.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">");
		h.append("<title>").append(esc(m.receiptNumber())).append("</title>");
		h.append("<style>")
				.append("@page{size:A4;margin:18mm}")
				.append("body{font-family:'Noto Sans','Noto Sans Devanagari','Noto Sans Kannada',"
						+ "system-ui,sans-serif;color:#2B2621;margin:0;padding:0;font-size:11pt;line-height:1.55}")
				.append("header{display:flex;justify-content:space-between;align-items:flex-start;"
						+ "border-bottom:2px solid #BE6444;padding-bottom:10px;margin-bottom:20px}")
				.append(".temple{font-size:15pt;font-weight:700}")
				.append(".temple-address{font-size:9.5pt;color:#6E6660;max-width:78mm}")
				.append(".doc-title{font-size:10pt;color:#6E6660;text-transform:uppercase;"
						+ "letter-spacing:.1em;text-align:right}")
				.append(".receipt-no{font-size:15pt;font-weight:700;text-align:right}")
				.append(".issued{font-size:9.5pt;color:#6E6660;text-align:right}")
				.append(".label{font-size:9pt;color:#6E6660;text-transform:uppercase;letter-spacing:.05em}")
				.append(".donor{margin-bottom:18px}")
				.append(".donor .name{font-weight:700;font-size:12.5pt}")
				.append(".amount{border:1px solid #E7E1DD;border-left:4px solid #BE6444;"
						+ "padding:12px 16px;margin:0 0 18px}")
				.append(".amount .figure{font-size:20pt;font-weight:700;"
						+ "font-variant-numeric:tabular-nums}")
				.append("table{width:100%;border-collapse:collapse;margin:0 0 18px}")
				.append("th,td{text-align:left;padding:6px 8px;border-bottom:1px solid #E7E1DD;"
						+ "vertical-align:top}")
				.append("th{width:38mm;font-size:9pt;color:#6E6660;text-transform:uppercase;"
						+ "letter-spacing:.05em;font-weight:400}")
				.append(".status{border:1px solid #E7E1DD;padding:10px 14px;margin:0 0 26px;"
						+ "font-size:10pt;background:#FBF8F6}")
				.append(".status strong{display:block;margin-bottom:2px}")
				.append(".sign{margin-top:44px;display:flex;justify-content:flex-end}")
				.append(".sign .line{border-top:1px solid #2B2621;width:62mm;padding-top:6px;"
						+ "text-align:center;font-size:9pt;color:#6E6660}")
				.append("footer{margin-top:26px;border-top:1px solid #E7E1DD;padding-top:6px;"
						+ "color:#9C948C;font-size:8pt}")
				.append("</style></head><body>");

		// Header: whose receipt this is, and the number that identifies it for ever.
		h.append("<header><div><div class=\"temple\">").append(esc(m.templeName())).append("</div>");
		if (notBlank(m.templeAddress())) {
			h.append("<div class=\"temple-address\">").append(esc(m.templeAddress())).append("</div>");
		}
		h.append("</div><div><div class=\"doc-title\">").append(esc(title(m.eightyG())))
				.append("</div><div class=\"receipt-no\">").append(esc(m.receiptNumber())).append("</div>");
		if (notBlank(m.issuedOn())) {
			h.append("<div class=\"issued\">Issued ").append(esc(m.issuedOn())).append("</div>");
		}
		h.append("</div></header>");

		// Who it is made out to. An anonymous gift has no name, and the line says that rather than
		// standing empty above the amount — a receipt to nobody is still the temple's own record.
		h.append("<div class=\"donor\"><div class=\"label\">Received with thanks from</div>");
		h.append("<div class=\"name\">")
				.append(notBlank(m.donorName()) ? esc(m.donorName()) : "A donor who gave anonymously")
				.append("</div>");
		if (notBlank(m.donorAddress())) {
			h.append("<div>").append(esc(m.donorAddress())).append("</div>");
		}
		if (notBlank(m.donorPan())) {
			h.append("<div><span class=\"label\">PAN</span> ").append(esc(m.donorPan())).append("</div>");
		}
		h.append("</div>");

		// One figure, large, and nothing beside it to be mistaken for it.
		h.append("<div class=\"amount\"><div class=\"label\">Amount received</div>")
				.append("<div class=\"figure\">")
				.append(notBlank(m.amountText()) ? esc(m.amountText()) : "—")
				.append("</div></div>");

		h.append("<table><tbody>");
		row(h, "Received on", m.receivedOn());
		row(h, "Towards", m.purpose());
		row(h, "Received as", m.paymentModeText());
		row(h, "Payment reference", m.reference());
		if (m.eightyG() == EightyGStatus.ELIGIBLE) {
			row(h, "Section", m.section());
		}
		h.append("</tbody></table>");

		h.append("<div class=\"status\">").append(statusText(m.eightyG())).append("</div>");

		h.append("<div class=\"sign\"><div class=\"line\">For ").append(esc(m.templeName()))
				.append("</div></div>");

		h.append("<footer>").append(esc(m.templeName())).append(" · Receipt ")
				.append(esc(m.receiptNumber())).append(" · Printed ").append(esc(m.generatedOn()))
				.append("</footer>");
		h.append("</body></html>");
		return h.toString();
	}

	/**
	 * What the sheet calls itself, which follows what it can support.
	 *
	 * <p>A document headed "80G receipt" that supports no deduction would be the worst of the three
	 * outcomes here: the donor files it, claims against it, and finds out at assessment.
	 */
	private static String title(EightyGStatus status) {
		return status == EightyGStatus.ELIGIBLE ? "Donation receipt · 80G" : "Donation receipt";
	}

	/**
	 * The one paragraph an assessing officer reads, and the one a donor's accountant reads.
	 *
	 * <p>Not escaped, because there is no interpolated value in any of the three — each is a fixed
	 * sentence written here. Every value that came from a person goes through {@link #esc}.
	 */
	private static String statusText(EightyGStatus status) {
		return switch (status) {
			case ELIGIBLE -> "<strong>Eligible for deduction under Section 80G "
					+ "of the Income-tax Act, 1961.</strong>"
					+ "This temple is registered under Section 80G. Keep this receipt with your "
					+ "return.";
			case IN_KIND_NOT_ELIGIBLE -> "<strong>This is an acknowledgement of goods received, "
					+ "not an 80G receipt.</strong>"
					+ "This temple is registered under Section 80G, but a gift in kind does not "
					+ "qualify for deduction under it — only money does. The value shown is the "
					+ "temple's own estimate, for its records.";
			case TEMPLE_NOT_APPROVED -> "<strong>This receipt does not support a tax "
					+ "deduction.</strong>"
					+ "The temple has no Section 80G registration recorded. It is grateful for the "
					+ "gift and this is its acknowledgement that the gift arrived.";
		};
	}

	/** One labelled row, left out entirely when there is nothing to put in it. */
	private static void row(StringBuilder h, String label, String value) {
		if (!notBlank(value)) {
			return;
		}
		h.append("<tr><th>").append(esc(label)).append("</th><td>").append(esc(value)).append("</td></tr>");
	}

	private static boolean notBlank(String s) {
		return s != null && !s.isBlank();
	}

	private static String esc(String s) {
		if (s == null) {
			return "";
		}
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}
}
