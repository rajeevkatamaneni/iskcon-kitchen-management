package org.iskcon.kms.document;

import java.util.List;

/**
 * The server-rendered job card (B5): one A4 sheet per meal kind, carrying everything the kitchen
 * needs for one meal and nothing it does not — the date, the meal, the time it must be ready, the
 * head count it scales to, every dish with its scaled ingredients and its method, what the day
 * forbids, the equipment, who is in, and the boxes people sign.
 *
 * <p><strong>It is paper.</strong> There is no ticking and no app: marking off and signing happen
 * with a pen, because a cook mid-service will not touch a screen and a checklist nobody uses is
 * worse than a sheet everybody does. Everything interactive that could have gone on here was left
 * off on purpose.
 *
 * <p>The card number is printed in the header — <em>Lunch · 21 Aug 2026 · LC-2026-0142</em> — so a
 * signed sheet in a folder can be traced back to its record six months later.
 *
 * <p>Built with a StringBuilder like the recipe card and the PO sheet, and self-contained for the
 * same reason: Chromium renders it with no network. The Noto stack is what makes Indic scripts
 * shape into glyphs rather than tofu when the card is printed in the temple's own language. Every
 * interpolated value is escaped — all of it is text somebody typed.
 */
public final class JobCardTemplate {

	/** One ingredient line of a dish, scaled to what that dish is actually cooking. */
	public record Ingredient(String name, String quantity, boolean prohibited) {
	}

	/** One dish of the meal: what it is, how much of it, what goes in and how it is made. */
	public record Dish(
			String name,
			String servingsText,
			List<Ingredient> ingredients,
			List<String> method,
			/** Named ingredients that this day's fast forbids, if any — the cook's own warning. */
			List<String> fastingConflicts) {
	}

	/** A person on the card: a rostered cook or a signed-up volunteer, and their hours. */
	public record Person(String name, String detail) {
	}

	/** Everything the card renders, built by {@link JobCardService} from one meal. */
	public record CardModel(
			String templeName,
			String cardNumber,
			String mealKind,
			String dateText,
			String readyByText,
			String occasion,

			String headCountText,
			String platesText,

			/** What the day asks of the kitchen: the fast, and anything the temple's own rule forbids. */
			List<String> warnings,

			String clientName,
			String venue,
			String purpose,
			String kitchenNotes,

			List<Dish> dishes,
			List<String> equipment,
			List<Person> staff,
			List<Person> volunteers,

			String generatedOn,
			List<String> labels) {
	}

	/**
	 * The card's fixed wording. English is the source that {@link DocumentLabelTranslator} turns into
	 * whichever language the temple asked for; it is also the fallback when translation fails, so a
	 * card always prints.
	 */
	public record Labels(
			String jobCard, String readyBy, String headCount, String plates, String forWhom,
			String goingTo, String whatFor, String notesForTheKitchen, String dishes, String ingredient,
			String quantity, String method, String equipment, String staffOnToday, String volunteers,
			String cookedBy, String checkedBy, String servedBy, String signature, String time,
			String nothingRecorded, String occasion, String servings) {

		static final int VERSION = 1;

		static Labels english() {
			return new Labels("Job card", "Ready by", "Head count", "Scales to", "For",
					"Going to", "What it is for", "Notes for the kitchen", "Dishes", "Ingredient",
					"Quantity", "Method", "Equipment", "Staff on today", "Volunteers",
					"Cooked by", "Checked by", "Served by", "Signature", "Time",
					"Nobody is rostered", "Occasion", "servings");
		}

		static List<String> englishList() {
			return english().asList();
		}

		List<String> asList() {
			return List.of(jobCard, readyBy, headCount, plates, forWhom, goingTo, whatFor,
					notesForTheKitchen, dishes, ingredient, quantity, method, equipment, staffOnToday,
					volunteers, cookedBy, checkedBy, servedBy, signature, time, nothingRecorded,
					occasion, servings);
		}
	}

	private JobCardTemplate() {
	}

	public static String render(CardModel m) {
		Labels l = labels(m.labels());
		StringBuilder h = new StringBuilder();
		h.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">");
		h.append("<title>").append(esc(m.cardNumber())).append("</title>");
		h.append("<style>")
				.append("@page{size:A4;margin:16mm}")
				.append("body{font-family:'Noto Sans','Noto Sans Devanagari','Noto Sans Kannada',"
						+ "system-ui,sans-serif;color:#2B2621;margin:0;padding:0;font-size:11pt;line-height:1.5}")
				.append("header{display:flex;justify-content:space-between;align-items:flex-start;"
						+ "border-bottom:2px solid #BE6444;padding-bottom:8px;margin-bottom:14px}")
				.append(".temple{font-size:15pt;font-weight:700}")
				.append(".doc-title{font-size:10pt;color:#6E6660;text-transform:uppercase;letter-spacing:.1em}")
				.append(".card-no{font-size:14pt;font-weight:700;text-align:right;white-space:nowrap}")
				.append(".card-when{font-size:10pt;color:#6E6660;text-align:right}")
				.append(".facts{display:flex;flex-wrap:wrap;gap:20px;margin-bottom:14px}")
				.append(".label{font-size:9pt;color:#6E6660;text-transform:uppercase;letter-spacing:.05em}")
				.append(".value{font-size:12pt;font-weight:600}")
				// The warnings are the one thing on this sheet that must not be skimmed past: a fasting
				// day changes what may go in a pot, and it is read at speed in a hot room.
				.append(".warn{border:1.5px solid #BE6444;background:#FBF1EC;padding:8px 12px;"
						+ "margin-bottom:14px;font-size:11pt}")
				.append(".warn li{margin:2px 0}")
				.append("h2{font-size:10pt;color:#6E6660;text-transform:uppercase;letter-spacing:.05em;"
						+ "border-bottom:1px solid #E7E1DD;padding-bottom:4px;margin:18px 0 8px}")
				// Never split a dish across a page break: a cook holding page two with half the
				// ingredients on page one is the failure this sheet exists to prevent.
				.append(".dish{break-inside:avoid;margin-bottom:14px}")
				.append(".dish-head{display:flex;justify-content:space-between;align-items:baseline;gap:12px}")
				.append(".dish-name{font-size:13pt;font-weight:700}")
				.append(".dish-servings{font-size:11pt;color:#6E6660;white-space:nowrap}")
				.append("table{width:100%;border-collapse:collapse;margin:6px 0}")
				.append("th,td{text-align:left;padding:4px 8px;border-bottom:1px solid #E7E1DD;"
						+ "vertical-align:top}")
				.append("th{font-size:9pt;color:#6E6660;text-transform:uppercase;letter-spacing:.05em}")
				.append("td.num,th.num{text-align:right;white-space:nowrap;font-variant-numeric:tabular-nums}")
				.append(".badge{font-size:8pt;border:1px solid #BE6444;color:#BE6444;border-radius:8px;"
						+ "padding:0 5px;margin-left:6px}")
				.append("ol.method{margin:6px 0 0 18px;padding:0;font-size:10.5pt}")
				.append("ol.method li{margin:2px 0}")
				.append(".people{display:flex;flex-wrap:wrap;gap:6px 20px;font-size:10.5pt}")
				.append(".people span.detail{color:#6E6660}")
				.append(".none{color:#9C948C;font-size:10.5pt}")
				.append(".chips{font-size:10.5pt}")
				.append(".signoff{break-inside:avoid;display:flex;gap:16px;margin-top:26px}")
				.append(".signoff .box{flex:1;border:1px solid #E7E1DD;padding:8px 10px 26px}")
				.append(".signoff .who{font-size:10pt;font-weight:600;margin-bottom:22px}")
				.append(".signoff .rule{border-top:1px solid #2B2621;font-size:8.5pt;color:#6E6660;"
						+ "padding-top:4px;display:flex;justify-content:space-between}")
				.append("footer{margin-top:20px;border-top:1px solid #E7E1DD;padding-top:6px;"
						+ "color:#9C948C;font-size:8pt}")
				.append("</style></head><body>");

		// Header: the temple, what this sheet is, and the number that ties it back to the record.
		h.append("<header><div><div class=\"temple\">").append(esc(m.templeName())).append("</div>")
				.append("<div class=\"doc-title\">").append(esc(l.jobCard())).append("</div></div>")
				.append("<div><div class=\"card-no\">").append(esc(m.cardNumber())).append("</div>")
				.append("<div class=\"card-when\">").append(esc(m.mealKind())).append(" &middot; ")
				.append(esc(m.dateText())).append("</div></div></header>");

		h.append("<div class=\"facts\">");
		fact(h, l.readyBy(), m.readyByText());
		fact(h, l.headCount(), m.headCountText());
		fact(h, l.plates(), m.platesText());
		fact(h, l.forWhom(), m.clientName());
		fact(h, l.goingTo(), m.venue());
		fact(h, l.whatFor(), m.purpose());
		fact(h, l.occasion(), m.occasion());
		h.append("</div>");

		if (!m.warnings().isEmpty()) {
			h.append("<div class=\"warn\"><ul style=\"margin:0;padding-left:18px\">");
			for (String warning : m.warnings()) {
				h.append("<li>").append(esc(warning)).append("</li>");
			}
			h.append("</ul></div>");
		}

		if (notBlank(m.kitchenNotes())) {
			h.append("<div class=\"label\">").append(esc(l.notesForTheKitchen())).append("</div>");
			h.append("<div style=\"margin-bottom:12px\">").append(esc(m.kitchenNotes())).append("</div>");
		}

		h.append("<h2>").append(esc(l.dishes())).append("</h2>");
		for (Dish dish : m.dishes()) {
			h.append("<div class=\"dish\">");
			h.append("<div class=\"dish-head\"><span class=\"dish-name\">").append(esc(dish.name()))
					.append("</span><span class=\"dish-servings\">").append(esc(dish.servingsText()))
					.append("</span></div>");
			if (!dish.fastingConflicts().isEmpty()) {
				h.append("<div class=\"warn\" style=\"margin:6px 0\">")
						.append(esc(String.join(", ", dish.fastingConflicts()))).append("</div>");
			}
			h.append("<table><thead><tr><th>").append(esc(l.ingredient())).append("</th>")
					.append("<th class=\"num\">").append(esc(l.quantity())).append("</th></tr></thead><tbody>");
			for (Ingredient line : dish.ingredients()) {
				h.append("<tr><td>").append(esc(line.name()));
				if (line.prohibited()) {
					h.append(" <span class=\"badge\">prohibited</span>");
				}
				h.append("</td><td class=\"num\">").append(esc(line.quantity())).append("</td></tr>");
			}
			h.append("</tbody></table>");
			if (!dish.method().isEmpty()) {
				h.append("<div class=\"label\">").append(esc(l.method())).append("</div>");
				h.append("<ol class=\"method\">");
				for (String step : dish.method()) {
					h.append("<li>").append(esc(step)).append("</li>");
				}
				h.append("</ol>");
			}
			h.append("</div>");
		}

		if (!m.equipment().isEmpty()) {
			h.append("<h2>").append(esc(l.equipment())).append("</h2>");
			h.append("<div class=\"chips\">").append(esc(String.join(" · ", m.equipment()))).append("</div>");
		}

		people(h, l.staffOnToday(), m.staff(), l.nothingRecorded());
		people(h, l.volunteers(), m.volunteers(), l.nothingRecorded());

		// Three boxes, signed with a pen. Cooked, checked, served — the three moments a temple wants
		// a name against when it looks at this sheet again months later.
		h.append("<div class=\"signoff\">");
		signOff(h, l.cookedBy(), l.signature(), l.time());
		signOff(h, l.checkedBy(), l.signature(), l.time());
		signOff(h, l.servedBy(), l.signature(), l.time());
		h.append("</div>");

		h.append("<footer>").append(esc(m.templeName())).append(" · ").append(esc(m.cardNumber()))
				.append(" · ").append(esc(m.generatedOn())).append("</footer>");
		h.append("</body></html>");
		return h.toString();
	}

	private static void fact(StringBuilder h, String label, String value) {
		if (!notBlank(value)) {
			return;
		}
		h.append("<div><div class=\"label\">").append(esc(label)).append("</div>")
				.append("<div class=\"value\">").append(esc(value)).append("</div></div>");
	}

	private static void people(StringBuilder h, String heading, List<Person> people, String none) {
		h.append("<h2>").append(esc(heading)).append("</h2>");
		if (people.isEmpty()) {
			h.append("<div class=\"none\">").append(esc(none)).append("</div>");
			return;
		}
		h.append("<div class=\"people\">");
		for (Person person : people) {
			h.append("<span>").append(esc(person.name()));
			if (notBlank(person.detail())) {
				h.append(" <span class=\"detail\">").append(esc(person.detail())).append("</span>");
			}
			h.append("</span>");
		}
		h.append("</div>");
	}

	private static void signOff(StringBuilder h, String who, String signature, String time) {
		h.append("<div class=\"box\"><div class=\"who\">").append(esc(who)).append("</div>")
				.append("<div class=\"rule\"><span>").append(esc(signature)).append("</span><span>")
				.append(esc(time)).append("</span></div></div>");
	}

	/** Rebuilds the label set from the model's flat list, falling back to English if absent. */
	private static Labels labels(List<String> flat) {
		List<String> source = Labels.englishList();
		if (flat == null || flat.size() < source.size()) {
			return Labels.english();
		}
		return new Labels(flat.get(0), flat.get(1), flat.get(2), flat.get(3), flat.get(4), flat.get(5),
				flat.get(6), flat.get(7), flat.get(8), flat.get(9), flat.get(10), flat.get(11),
				flat.get(12), flat.get(13), flat.get(14), flat.get(15), flat.get(16), flat.get(17),
				flat.get(18), flat.get(19), flat.get(20), flat.get(21), flat.get(22));
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
