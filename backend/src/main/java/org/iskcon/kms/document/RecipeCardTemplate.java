package org.iskcon.kms.document;

import java.util.List;

/**
 * The server-rendered recipe card (E2-S5): a self-contained, print-ready A4 HTML document that
 * Chromium turns into a PDF, and that is equally usable as a direct browser-print view.
 *
 * <p>The font stack names Noto for Devanagari and Kannada so that when E2-S6 renders a translated
 * card, Chromium (with those fonts installed in the worker image) shapes the script correctly.
 * All interpolated values are HTML-escaped — recipe and ingredient text is user-entered.
 */
public final class RecipeCardTemplate {

	/** A row of the ingredient table: the ingredient and its (possibly scaled) amount. */
	public record Row(String ingredient, String amount, boolean prohibited) {
	}

	/** Everything the card renders. Built by the service from a base or scaled recipe. */
	public record CardModel(
			String templeName,
			String recipeName,
			String categoryName,
			String yieldText,
			String overrideReason,
			List<Row> rows,
			List<String> method,
			String notes,
			String generatedOn) {
	}

	private RecipeCardTemplate() {
	}

	public static String render(CardModel m) {
		StringBuilder h = new StringBuilder();
		h.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">");
		h.append("<title>").append(esc(m.recipeName())).append("</title>");
		h.append("<style>")
				// The same page geometry as the PO sheet. It used to be margin:0 with 6mm of padding
				// top and bottom only, which ran the rules and the title into the paper's edge and
				// let a printer clip them.
				.append("@page{size:A4;margin:16mm}")
				.append("body{font-family:'Noto Sans','Noto Sans Devanagari','Noto Sans Kannada',"
						+ "system-ui,sans-serif;color:#2B2621;margin:0;padding:0;font-size:12pt;line-height:1.5}")
				.append("header{border-bottom:2px solid #BE6444;padding-bottom:6px;margin-bottom:14px}")
				.append(".temple{font-size:10pt;color:#6E6660;text-transform:uppercase;letter-spacing:.08em}")
				.append("h1{font-size:22pt;margin:2px 0 0}")
				.append(".cat{color:#6E6660;font-size:11pt}")
				.append(".yield{margin:10px 0;font-size:13pt}")
				.append(".badge{display:inline-block;background:#F4EAD1;color:#8F6A1C;border-radius:4px;"
						+ "padding:3px 8px;font-size:9pt;margin:6px 0}")
				.append("table{width:100%;border-collapse:collapse;margin:8px 0 16px}")
				.append("th,td{text-align:left;padding:5px 8px;border-bottom:1px solid #E7E1DD}")
				.append("th{font-size:10pt;color:#6E6660;text-transform:uppercase;letter-spacing:.05em}")
				.append("td.amt{text-align:right;white-space:nowrap;font-variant-numeric:tabular-nums}")
				.append("h2{font-size:13pt;margin:14px 0 6px;color:#BE6444}")
				.append("ol{margin:0;padding-left:20px}li{margin-bottom:6px}")
				.append(".notes{color:#6E6660;font-size:11pt;margin-top:12px}")
				.append("footer{margin-top:18px;border-top:1px solid #E7E1DD;padding-top:6px;"
						+ "color:#9C948C;font-size:9pt}")
				.append("</style></head><body><div class=\"card\">");

		h.append("<header><div class=\"temple\">").append(esc(m.templeName())).append("</div>");
		h.append("<h1>").append(esc(m.recipeName())).append("</h1>");
		h.append("<div class=\"cat\">").append(esc(m.categoryName())).append("</div></header>");

		h.append("<div class=\"yield\">").append(esc(m.yieldText())).append("</div>");
		if (m.overrideReason() != null && !m.overrideReason().isBlank()) {
			h.append("<div class=\"badge\">Sattvic override: ").append(esc(m.overrideReason())).append("</div>");
		}

		h.append("<table><thead><tr><th>Ingredient</th><th class=\"amt\">Quantity</th></tr></thead><tbody>");
		for (Row r : m.rows()) {
			h.append("<tr><td>").append(esc(r.ingredient()));
			if (r.prohibited()) {
				h.append(" <span class=\"badge\">prohibited</span>");
			}
			h.append("</td><td class=\"amt\">").append(esc(r.amount())).append("</td></tr>");
		}
		h.append("</tbody></table>");

		if (m.method() != null && !m.method().isEmpty()) {
			h.append("<h2>Method</h2><ol>");
			for (String step : m.method()) {
				h.append("<li>").append(esc(step)).append("</li>");
			}
			h.append("</ol>");
		}
		if (m.notes() != null && !m.notes().isBlank()) {
			h.append("<div class=\"notes\">").append(esc(m.notes())).append("</div>");
		}

		h.append("<footer>").append(esc(m.templeName())).append(" · generated ")
				.append(esc(m.generatedOn())).append("</footer>");
		h.append("</div></body></html>");
		return h.toString();
	}

	private static String esc(String s) {
		if (s == null) {
			return "";
		}
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}
}
