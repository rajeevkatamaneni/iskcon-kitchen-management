package org.iskcon.kms.document;

import java.util.List;

/**
 * The server-rendered purchase-order sheet (E5-S4): a self-contained, print-ready A4 HTML document
 * Chromium turns into a PDF, equally usable as a direct browser-print view. It carries the temple's
 * identity, the PO number and dates, the vendor block (with GSTIN when present), the ordered lines,
 * and a signature space.
 *
 * <p>The price column renders only when at least one line carries a price — POs often omit prices
 * when the temple negotiates on delivery. The font stack names Noto for Devanagari and Kannada so a
 * translated sheet (E5-S5) shapes correctly. All interpolated values are HTML-escaped.
 */
public final class PurchaseOrderSheetTemplate {

	/** A vendor's identity block on the sheet. */
	public record VendorBlock(String name, String address, String gstin, String phone) {
	}

	/** One ordered line. {@code price} is null (and its column absent) when prices are not shown. */
	public record Line(String ingredient, String quantity, String price) {
	}

	/** Everything the sheet renders, built by the service from a PO and its vendor. */
	public record SheetModel(
			String templeName,
			String title,
			String poNumber,
			String orderDate,
			String neededBy,
			VendorBlock vendor,
			String deliveryLocation,
			String notes,
			List<Line> lines,
			boolean showPrices,
			String totalText,
			String generatedOn,
			List<String> labels) {
	}

	/** UI strings, kept in the model so E5-S5 can translate them without touching the template. */
	public record Labels(
			String purchaseOrder, String to, String gstin, String orderDate, String neededBy,
			String deliverTo, String item, String quantity, String price, String total, String notes,
			String authorisedSignature) {

		/**
		 * The English label set — the source that {@link PurchaseOrderLabelTranslator} translates
		 * (glossary first, then MT) into the chosen language, and the fallback when no language is set.
		 */
		static Labels english() {
			return new Labels("Purchase Order", "To", "GSTIN", "Order date", "Needed by", "Deliver to",
					"Item", "Quantity", "Price", "Total", "Notes", "Authorised signature");
		}

		List<String> asList() {
			return List.of(purchaseOrder, to, gstin, orderDate, neededBy, deliverTo, item, quantity,
					price, total, notes, authorisedSignature);
		}
	}

	private PurchaseOrderSheetTemplate() {
	}

	public static String render(SheetModel m) {
		Labels l = labels(m.labels());
		StringBuilder h = new StringBuilder();
		h.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">");
		h.append("<title>").append(esc(m.poNumber())).append("</title>");
		h.append("<style>")
				.append("@page{size:A4;margin:16mm}")
				.append("body{font-family:'Noto Sans','Noto Sans Devanagari','Noto Sans Kannada',"
						+ "system-ui,sans-serif;color:#2B2621;margin:0;padding:0;font-size:11pt;line-height:1.5}")
				.append("header{display:flex;justify-content:space-between;align-items:flex-start;"
						+ "border-bottom:2px solid #BE6444;padding-bottom:8px;margin-bottom:16px}")
				.append(".temple{font-size:15pt;font-weight:700}")
				.append(".doc-title{font-size:10pt;color:#6E6660;text-transform:uppercase;letter-spacing:.1em}")
				.append(".po-no{font-size:16pt;font-weight:700;text-align:right}")
				.append(".meta{display:flex;justify-content:space-between;gap:24px;margin-bottom:16px}")
				.append(".block{font-size:11pt}")
				.append(".label{font-size:9pt;color:#6E6660;text-transform:uppercase;letter-spacing:.05em}")
				.append(".vendor .name{font-weight:700;font-size:12pt}")
				.append("table{width:100%;border-collapse:collapse;margin:8px 0 16px}")
				.append("th,td{text-align:left;padding:6px 8px;border-bottom:1px solid #E7E1DD}")
				.append("th{font-size:9pt;color:#6E6660;text-transform:uppercase;letter-spacing:.05em}")
				.append("td.num,th.num{text-align:right;white-space:nowrap;font-variant-numeric:tabular-nums}")
				.append("tfoot td{font-weight:700;border-top:2px solid #BE6444}")
				.append(".notes{color:#6E6660;font-size:10pt;margin-top:4px}")
				.append(".sign{margin-top:48px;display:flex;justify-content:flex-end}")
				.append(".sign .line{border-top:1px solid #2B2621;width:60mm;padding-top:6px;"
						+ "text-align:center;font-size:9pt;color:#6E6660}")
				.append("footer{margin-top:24px;border-top:1px solid #E7E1DD;padding-top:6px;"
						+ "color:#9C948C;font-size:8pt}")
				.append("</style></head><body>");

		// Header: temple identity and the PO number.
		h.append("<header><div><div class=\"temple\">").append(esc(m.templeName())).append("</div>")
				.append("<div class=\"doc-title\">").append(esc(l.purchaseOrder())).append("</div></div>")
				.append("<div class=\"po-no\">").append(esc(m.poNumber())).append("</div></header>");

		// Vendor block and dates.
		h.append("<div class=\"meta\">");
		h.append("<div class=\"block vendor\"><div class=\"label\">").append(esc(l.to())).append("</div>");
		h.append("<div class=\"name\">").append(esc(m.vendor().name())).append("</div>");
		if (notBlank(m.vendor().address())) {
			h.append("<div>").append(esc(m.vendor().address())).append("</div>");
		}
		if (notBlank(m.vendor().phone())) {
			h.append("<div>").append(esc(m.vendor().phone())).append("</div>");
		}
		if (notBlank(m.vendor().gstin())) {
			h.append("<div>").append(esc(l.gstin())).append(": ").append(esc(m.vendor().gstin())).append("</div>");
		}
		h.append("</div>");

		h.append("<div class=\"block\">");
		h.append("<div><span class=\"label\">").append(esc(l.orderDate())).append("</span><br>")
				.append(esc(m.orderDate())).append("</div>");
		if (notBlank(m.neededBy())) {
			h.append("<div style=\"margin-top:8px\"><span class=\"label\">").append(esc(l.neededBy()))
					.append("</span><br>").append(esc(m.neededBy())).append("</div>");
		}
		if (notBlank(m.deliveryLocation())) {
			h.append("<div style=\"margin-top:8px\"><span class=\"label\">").append(esc(l.deliverTo()))
					.append("</span><br>").append(esc(m.deliveryLocation())).append("</div>");
		}
		h.append("</div></div>");

		// Lines. Price and total columns only when prices exist.
		h.append("<table><thead><tr><th>").append(esc(l.item())).append("</th>")
				.append("<th class=\"num\">").append(esc(l.quantity())).append("</th>");
		if (m.showPrices()) {
			h.append("<th class=\"num\">").append(esc(l.price())).append("</th>");
		}
		h.append("</tr></thead><tbody>");
		for (Line line : m.lines()) {
			h.append("<tr><td>").append(esc(line.ingredient())).append("</td>")
					.append("<td class=\"num\">").append(esc(line.quantity())).append("</td>");
			if (m.showPrices()) {
				h.append("<td class=\"num\">").append(esc(line.price())).append("</td>");
			}
			h.append("</tr>");
		}
		h.append("</tbody>");
		if (m.showPrices() && notBlank(m.totalText())) {
			h.append("<tfoot><tr><td>").append(esc(l.total())).append("</td>")
					.append("<td class=\"num\"></td><td class=\"num\">").append(esc(m.totalText()))
					.append("</td></tr></tfoot>");
		}
		h.append("</table>");

		if (notBlank(m.notes())) {
			h.append("<div class=\"label\">").append(esc(l.notes())).append("</div>");
			h.append("<div class=\"notes\">").append(esc(m.notes())).append("</div>");
		}

		h.append("<div class=\"sign\"><div class=\"line\">").append(esc(l.authorisedSignature()))
				.append("</div></div>");

		h.append("<footer>").append(esc(m.templeName())).append(" · ").append(esc(m.poNumber()))
				.append(" · ").append(esc(m.generatedOn())).append("</footer>");
		h.append("</body></html>");
		return h.toString();
	}

	/** Rebuilds the label set from the model's flat list, falling back to English if absent. */
	private static Labels labels(List<String> flat) {
		if (flat == null || flat.size() < 12) {
			return Labels.english();
		}
		return new Labels(flat.get(0), flat.get(1), flat.get(2), flat.get(3), flat.get(4), flat.get(5),
				flat.get(6), flat.get(7), flat.get(8), flat.get(9), flat.get(10), flat.get(11));
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
