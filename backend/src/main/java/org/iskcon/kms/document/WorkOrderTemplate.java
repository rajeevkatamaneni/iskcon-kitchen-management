package org.iskcon.kms.document;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The server-rendered work order (E10-S11): the one sheet a storekeeper carries round the store
 * room, and the one that comes back with two signatures on it.
 *
 * <p><strong>It is a picking list, not a receipt.</strong> Every line says what to take, how much,
 * and which lot to take it from, in the order the lots go off. The batch list is worked out when the
 * sheet is rendered rather than frozen when the request was approved (V79's header says why): an
 * afternoon's cooking can empty the lot a sheet printed this morning would have named, and a work
 * order that sends somebody to a bare shelf is worse than no work order at all.
 *
 * <p><strong>The dishes are on the sheet on purpose.</strong> The audit this paper exists for is the
 * comparison between "40 kg of rice" and "200 servings of khichdi" — over-provisioning shows up in
 * the gap between the two halves, and a sheet carrying one half cannot be audited. So the dish list
 * travels with the request wherever it is read, this sheet included.
 *
 * <p><strong>Two ruled boxes, signed with a pen.</strong> One for the storekeeper who hands the food
 * over, one for whoever takes delivery for the kitchen. Signing is paper, as {@code E4-S11} D1
 * settled for the job card: a screen in a store room at six in the morning is a screen nobody
 * touches.
 *
 * <p><strong>The whole sheet translates.</strong> Unlike the job card — whose worksheet stays
 * English because the office reads it — every reader of this one is standing at a shelf. The fixed
 * wording comes through {@link DocumentLabelTranslator}; the ingredient and dish names are tenant
 * content and come through the glossary and then the provider. {@link WorkOrderService} decides
 * which language and hands both down already translated.
 *
 * <p>Built with a StringBuilder like the job card and the PO sheet, and self-contained for the same
 * reason: Chromium renders it with no network, so the emblem is inlined from the classpath and every
 * colour and size is a literal. The Noto stack names one family per script the runtime image
 * actually ships, so Indic text shapes into glyphs rather than tofu. Every interpolated value is
 * escaped — all of it is text somebody typed.
 */
public final class WorkOrderTemplate {

	private static final Logger log = LoggerFactory.getLogger(WorkOrderTemplate.class);

	/**
	 * The ISKCON lotus emblem, read once from the classpath and inlined into every sheet.
	 *
	 * <p>Inlined rather than linked for the reason the job card gives: the renderer has no network
	 * and the browser print view has no origin of its own, so a {@code <img src>} would print as a
	 * broken box. A missing file costs the sheet its emblem and nothing else — a store room that
	 * cannot issue today's rice because an asset went astray would be a far worse failure.
	 */
	private static final String EMBLEM = loadEmblem();

	/**
	 * The one script stack, naming a family per script rather than leaning on Chromium's codepoint
	 * fallback — the same stack the job card names, and for the same reason. Every family is present
	 * in the runtime image ({@code fonts-noto-core} plus {@code fonts-indic}, installed by
	 * {@code backend/Dockerfile}), and between them they cover the scripts of all 22 scheduled
	 * languages the picker offers. Fallback already worked; it picks whatever fontconfig happens to
	 * rank first, which can change when the base image changes, and a temple would find out from a
	 * printed sheet.
	 */
	private static final String FONT_STACK =
			"'Noto Sans','Noto Sans Devanagari','Noto Sans Bengali','Noto Sans Gujarati',"
					+ "'Noto Sans Gurmukhi','Noto Sans Kannada','Noto Sans Malayalam','Noto Sans Oriya',"
					+ "'Noto Sans Tamil','Noto Sans Telugu','Noto Sans Ol Chiki','Noto Sans Meetei Mayek',"
					+ "'Noto Nastaliq Urdu','Noto Sans Arabic',system-ui,sans-serif";

	/**
	 * One lot to pick from, in the order it should be emptied.
	 *
	 * <p>A batch has no code anywhere in this system — {@code stock_movements.batch_id} is a UUID,
	 * and a hex id is not something anybody can recognise on a shelf. What a storekeeper actually
	 * uses is the date it goes off and the date it arrived, which is exactly what the inventory
	 * screen already shows for the same reason. The two are carried as formatted dates and the words
	 * in front of them come from the label set, because those words translate and the dates do not.
	 *
	 * @param takeText how much to take out of this lot, in the cook's form
	 * @param useBy    the expiry date, or null where the lot has none
	 * @param arrived  the date the lot came in, or null where nothing recorded one
	 */
	public record Batch(String takeText, String useBy, String arrived) {
	}

	/**
	 * One line of the picking list: an ingredient, the whole amount of it, and where it comes from.
	 *
	 * <p><strong>One row per ingredient, not per request line.</strong> A request may name rice
	 * twice; the shelf does not care, and two rows each drawing independently would between them
	 * name more of the earliest lot than it holds. The rows are aggregated exactly as
	 * {@code IngredientIssueService} aggregates them, so the sheet names the lots issuing would
	 * actually draw.
	 *
	 * @param shortfallText what the shelf holds against what the line asks for — {@code "3 Kg / 12
	 *                      Kg"} — set only when the store cannot cover it, so the storekeeper learns
	 *                      it here rather than at the shelf. Two figures and no words: the words in
	 *                      front of them are a label, because they translate and the figures do not.
	 *                      The batches listed are still every lot there is; they simply do not add up.
	 */
	public record Line(
			String ingredientName, String localName, String quantityText,
			List<Batch> batches, String shortfallText) {
	}

	/** One dish the kitchen said it was cooking, and how much of it. Half of the audit. */
	public record Dish(String name, String localName, String quantityText) {
	}

	/** Everything the sheet renders, built by {@link WorkOrderService} from one approved request. */
	public record SheetModel(
			String templeName,

			/**
			 * The kitchen the food is going to — the fact that distinguishes this sheet from every
			 * other piece of paper in the store room, and the reason the epic exists at all.
			 */
			String kitchenName,
			String kitchenLocation,

			/** {@code IR-2026-0041} — the number somebody says down a phone. */
			String reference,

			/** The date the kitchen wants it, written out in full. */
			String neededOnText,

			/** Why the kitchen wants it. The request's own words, translated with the rest. */
			String purpose,

			List<Dish> dishes,
			List<Line> lines,

			String requestedByName,
			String requestedOnText,
			String approvedByName,
			String approvedOnText,

			/**
			 * The person who approved it is the person who raised it.
			 *
			 * <p>Allowed — forbidding it would deadlock a temple whose administrator is its only
			 * approver, which is most of them — and printed, so the fact sits on the paper an auditor
			 * reads rather than in a log nobody opens.
			 */
			boolean selfApproved,

			/** What to call the sheet's language, in that language's own script. Null for English. */
			String languageLabel,

			String generatedOn,
			List<String> labels) {
	}

	/**
	 * The sheet's fixed wording — all of it, because every reader of this sheet is standing at a
	 * shelf rather than sitting in an office.
	 *
	 * <p>English is the source {@link DocumentLabelTranslator} turns into whichever language was
	 * asked for, and it is also the fallback when translation fails, so a sheet always prints.
	 */
	public record Labels(
			String title, String kitchen, String wantedBy, String reason,
			String dishes, String dish, String quantity,
			String picking, String ingredient, String takeFrom,
			String useBy, String arrived, String noExpiry, String nothingOnTheShelf, String notEnough,
			String requestedBy, String approvedBy, String selfApproved,
			String issuedBy, String issuedByWhat, String receivedBy, String receivedByWhat,
			String signature, String date) {

		/**
		 * First version of this set. The cache in {@code document_label_translations} keys on it, so
		 * changing the English wording below means bumping this rather than clearing a table.
		 */
		static final int VERSION = 1;

		static Labels english() {
			return new Labels(
					"Work order", "Kitchen", "Wanted by", "Reason",
					"What is being cooked", "Dish", "Quantity",
					"What to pick", "Ingredient", "Take from",
					"Use by", "Arrived", "No expiry date", "Nothing on the shelf",
					"Not enough on the shelf",
					"Requested by", "Approved by", "Approved by the person who raised it.",
					"Issued by", "The store handed this over.",
					"Received by", "The kitchen took delivery.",
					"Signature", "Date");
		}

		static List<String> englishList() {
			return english().asList();
		}

		List<String> asList() {
			return List.of(title, kitchen, wantedBy, reason, dishes, dish, quantity,
					picking, ingredient, takeFrom, useBy, arrived, noExpiry, nothingOnTheShelf,
					notEnough, requestedBy, approvedBy, selfApproved, issuedBy, issuedByWhat,
					receivedBy, receivedByWhat, signature, date);
		}
	}

	private WorkOrderTemplate() {
	}

	public static String render(SheetModel m) {
		Labels l = labels(m.labels());
		StringBuilder h = new StringBuilder();
		h.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">");
		h.append("<title>").append(esc(m.reference())).append("</title>");
		style(h);
		h.append("</head><body>");

		header(h, m, l);
		facts(h, m, l);
		warnings(h, m, l);
		dishes(h, m, l);
		picking(h, m, l);
		signOff(h, l);

		h.append("<footer>").append(esc(m.templeName())).append(" &middot; ").append(esc(m.reference()))
				.append(" &middot; ").append(esc(m.generatedOn())).append("</footer>");
		h.append("</body></html>");
		return h.toString();
	}

	// ---- The sheet ------------------------------------------------------

	/**
	 * Which kitchen and which request, in that order.
	 *
	 * <p>The kitchen leads because a store room issuing to five kitchens in a morning sorts the
	 * sheets by kitchen and nothing else. The reference is small grey type underneath, the way the
	 * job card's number is: it is how the office finds this sheet again in six months, not what the
	 * person holding it needs first.
	 */
	private static void header(StringBuilder h, SheetModel m, Labels l) {
		h.append("<header><div class=\"mark\">").append(EMBLEM)
				.append("<div class=\"temple\">").append(esc(m.templeName())).append("</div></div>")
				.append("<div class=\"what\"><div class=\"title\">").append(esc(l.title()));
		if (notBlank(m.languageLabel())) {
			h.append(" &middot; ").append(esc(m.languageLabel()));
		}
		h.append("</div><div class=\"ref\">").append(esc(m.reference()))
				.append("</div></div></header>");
	}

	private static void facts(StringBuilder h, SheetModel m, Labels l) {
		h.append("<div class=\"facts\">");
		String kitchen = notBlank(m.kitchenLocation())
				? m.kitchenName() + " · " + m.kitchenLocation() : m.kitchenName();
		fact(h, l.kitchen(), kitchen);
		fact(h, l.wantedBy(), m.neededOnText());
		fact(h, l.reason(), m.purpose());
		fact(h, l.requestedBy(), person(m.requestedByName(), m.requestedOnText()));
		fact(h, l.approvedBy(), person(m.approvedByName(), m.approvedOnText()));
		h.append("</div>");
		if (m.selfApproved()) {
			h.append("<p class=\"self\">").append(esc(l.selfApproved())).append("</p>");
		}
	}

	/**
	 * Every line the store cannot cover, gathered at the top of the sheet.
	 *
	 * <p>Built from the rows rather than passed in beside them, so the banner and the row can never
	 * come to say different things. It is the one thing on this sheet that must not be skimmed past:
	 * it is read standing up, and it decides whether the walk is worth making at all.
	 */
	private static void warnings(StringBuilder h, SheetModel m, Labels l) {
		List<Line> short_ = m.lines().stream().filter(line -> notBlank(line.shortfallText())).toList();
		if (short_.isEmpty()) {
			return;
		}
		h.append("<ul class=\"warn\">");
		for (Line line : short_) {
			h.append("<li>").append(esc(line.ingredientName())).append(" &middot; ")
					.append(esc(l.notEnough())).append(" &middot; ")
					.append(esc(line.shortfallText())).append("</li>");
		}
		h.append("</ul>");
	}

	/**
	 * What the kitchen said it was cooking. The other half of the comparison an auditor makes, and
	 * the reason this section is on a picking list at all.
	 */
	private static void dishes(StringBuilder h, SheetModel m, Labels l) {
		if (m.dishes().isEmpty()) {
			return;
		}
		h.append("<h2>").append(esc(l.dishes())).append("</h2>");
		h.append("<table class=\"dishes\"><thead><tr><th>").append(esc(l.dish())).append("</th>")
				.append("<th class=\"num\">").append(esc(l.quantity()))
				.append("</th></tr></thead><tbody>");
		for (Dish dish : m.dishes()) {
			h.append("<tr><td><span class=\"name\">").append(esc(dish.name())).append("</span>");
			if (notBlank(dish.localName())) {
				h.append("<span class=\"local\">").append(esc(dish.localName())).append("</span>");
			}
			h.append("</td><td class=\"num\">").append(esc(dish.quantityText()))
					.append("</td></tr>");
		}
		h.append("</tbody></table>");
	}

	/**
	 * The picking list — the working half of the sheet.
	 *
	 * <p>The lots are listed in the order they should be emptied, nearest expiry first, which is the
	 * order the ledger will draw them in when the issue is recorded. Printing the amount to take out
	 * of each is what makes the sheet usable by somebody who is not going to do the arithmetic
	 * standing up.
	 */
	private static void picking(StringBuilder h, SheetModel m, Labels l) {
		h.append("<h2>").append(esc(l.picking())).append("</h2>");
		h.append("<table class=\"picking\"><thead><tr><th>").append(esc(l.ingredient())).append("</th>")
				.append("<th class=\"num\">").append(esc(l.quantity())).append("</th>")
				.append("<th>").append(esc(l.takeFrom()))
				.append("</th></tr></thead><tbody>");
		for (Line line : m.lines()) {
			h.append("<tr><td><span class=\"name\">").append(esc(line.ingredientName()))
					.append("</span>");
			if (notBlank(line.localName())) {
				h.append("<span class=\"local\">").append(esc(line.localName())).append("</span>");
			}
			h.append("</td><td class=\"num\">").append(esc(line.quantityText())).append("</td><td>");
			if (line.batches().isEmpty()) {
				h.append("<span class=\"none\">").append(esc(l.nothingOnTheShelf()))
						.append("</span>");
			} else {
				h.append("<ol class=\"lots\">");
				for (Batch batch : line.batches()) {
					h.append("<li><span class=\"take\">").append(esc(batch.takeText()))
							.append("</span><span class=\"lot\">")
							.append(esc(batch.useBy() == null
									? l.noExpiry() : l.useBy() + " " + batch.useBy()));
					if (batch.arrived() != null) {
						h.append(" &middot; ").append(esc(l.arrived() + " " + batch.arrived()));
					}
					h.append("</span></li>");
				}
				h.append("</ol>");
			}
			if (notBlank(line.shortfallText())) {
				h.append("<div class=\"shortfall\">").append(esc(l.notEnough()))
						.append(" &middot; ").append(esc(line.shortfallText())).append("</div>");
			}
			h.append("</td></tr>");
		}
		h.append("</tbody></table>");
	}

	/**
	 * Two boxes, signed with a pen: the storekeeper who handed it over and the person who took it.
	 *
	 * <p>Two rather than one because a work order with a single signature records a hand-off with one
	 * side to it, which is the thing this sheet exists to make impossible.
	 */
	private static void signOff(StringBuilder h, Labels l) {
		h.append("<div class=\"signoff\">");
		signBox(h, l.issuedBy(), l.issuedByWhat(), l);
		signBox(h, l.receivedBy(), l.receivedByWhat(), l);
		h.append("</div>");
	}

	private static void signBox(StringBuilder h, String who, String what, Labels l) {
		h.append("<div class=\"sign\"><div class=\"who\">").append(esc(who)).append("</div>")
				.append("<div class=\"what\">").append(esc(what)).append("</div>")
				.append("<div class=\"rule\"><span>").append(esc(l.signature())).append("</span>")
				.append("<span>").append(esc(l.date())).append("</span></div></div>");
	}

	// ---- Style ----------------------------------------------------------

	/**
	 * The sheet's own stylesheet, holding the app's tokens rather than a second set of opinions —
	 * colour is {@code DESIGN_SYSTEM.md} §2 verbatim and the type scale is §3 restated in points, as
	 * the job card's is, so the two sheets are recognisably the same product.
	 */
	private static void style(StringBuilder h) {
		h.append("<style>")
				.append(":root{")
				.append("--canvas:#FFFFFF;--raised:#FAF8F7;--sunken:#F1EDEB;")
				.append("--border:#E7E1DD;--border-strong:#DAD1CB;")
				.append("--ink:#2B2621;--ink-secondary:#6E6660;--ink-muted:#716B65;")
				.append("--accent:#AE5838;--accent-bg:#F6EBE4;--accent-border:#ECD9CF;--accent-text:#8A4A2F;")
				.append("--danger-bg:#F7E7E3;--danger-text:#9B2C1F;")
				.append("--xs:8.5pt;--sm:9.5pt;--base:11pt;--lg:12.5pt;--xl:15pt;")
				.append("}")
				.append("@page{size:A4;margin:16mm}")
				.append("*{box-sizing:border-box}")
				.append("body{font-family:").append(FONT_STACK)
				.append(";color:var(--ink);background:var(--canvas);margin:0;padding:0;"
						+ "font-size:var(--base);line-height:1.5}")

				// The print window has no page margin of its own, so without this the document goes
				// edge to edge on screen and prints correctly — one file, two different sheets, which
				// is exactly the surprise a preview exists to prevent. Print ignores all of it.
				.append("@media screen{")
				.append("html{background:var(--sunken)}")
				.append("body{width:210mm;min-height:297mm;margin:8mm auto;padding:16mm;"
						+ "box-shadow:0 1px 4px rgba(43,38,33,.16)}")
				.append("}")

				.append("header{display:flex;justify-content:space-between;align-items:flex-start;"
						+ "gap:12mm;border-bottom:1px solid var(--border-strong);padding-bottom:8px;"
						+ "margin-bottom:14px}")
				.append(".mark{display:flex;align-items:center;gap:8px}")
				// The emblem is the temple's own mark, so it takes the ink colour: terracotta is the
				// accent and the accent means "the main thing to do here"; a logo is not an action.
				.append(".mark svg{height:9mm;width:auto;fill:var(--ink)}")
				.append(".temple{font-size:var(--lg);font-weight:600}")
				.append(".what{text-align:right}")
				.append(".title{font-size:var(--lg);font-weight:600;white-space:nowrap}")
				.append(".ref{font-size:var(--xs);color:var(--ink-muted);"
						+ "font-variant-numeric:tabular-nums;white-space:nowrap}")

				.append(".facts{display:flex;flex-wrap:wrap;gap:6px 20px;margin-bottom:12px}")
				.append(".facts dl{margin:0}")
				.append(".facts dt{font-size:var(--xs);color:var(--ink-secondary)}")
				.append(".facts dd{margin:0;font-size:var(--base);font-weight:600}")
				.append(".self{font-size:var(--xs);color:var(--ink-muted);margin:-6px 0 12px}")

				// The shortfall is the one thing on this sheet that must not be skimmed past: it is
				// read at speed, standing up, and it decides whether the walk is worth making.
				.append("ul.warn{border:1px solid var(--accent-border);background:var(--accent-bg);"
						+ "color:var(--accent-text);padding:8px 12px 8px 28px;margin:0 0 12px;"
						+ "font-size:var(--base)}")
				.append("ul.warn li{margin:2px 0}")

				.append("h2{font-size:var(--sm);font-weight:600;color:var(--ink-secondary);"
						+ "border-bottom:1px solid var(--border);padding-bottom:4px;margin:16px 0 8px}")
				.append("p{margin:0 0 8px}")

				.append("table{width:100%;border-collapse:collapse;margin:4px 0 8px}")
				.append("th,td{text-align:left;padding:5px 8px;border-bottom:1px solid var(--border);"
						+ "vertical-align:top}")
				.append("thead th{background:var(--sunken);font-size:var(--xs);font-weight:600;"
						+ "color:var(--ink-secondary)}")
				.append("td.num,th.num{text-align:right;white-space:nowrap;"
						+ "font-variant-numeric:tabular-nums}")
				.append(".name{display:block;font-weight:600}")
				// The same name in the sheet's language, under the English one, so a storekeeper and
				// whoever wrote the request can point at the same row.
				.append(".local{display:block;font-size:var(--sm);color:var(--ink-secondary)}")

				.append(".picking th:last-child{width:52%}")
				.append("ol.lots{list-style:none;margin:0;padding:0;font-size:var(--sm)}")
				.append("ol.lots li{display:flex;gap:10px;padding:2px 0}")
				// The amount first and in bold, because it is the number somebody weighs against; the
				// lot is how they find the sack it comes out of.
				.append("ol.lots .take{font-weight:600;white-space:nowrap;"
						+ "font-variant-numeric:tabular-nums;min-width:22mm;text-align:right}")
				.append("ol.lots .lot{color:var(--ink-secondary)}")
				.append(".none{color:var(--ink-muted);font-size:var(--sm)}")
				.append(".shortfall{margin-top:4px;font-size:var(--xs);font-weight:600;"
						+ "background:var(--danger-bg);color:var(--danger-text);border-radius:8px;"
						+ "padding:2px 8px;display:inline-block}")

				.append(".signoff{break-inside:avoid;display:flex;gap:12px;margin-top:20px}")
				.append(".sign{flex:1;border:1px solid var(--border);padding:8px 10px 22px}")
				.append(".sign .who{font-size:var(--sm);font-weight:600}")
				.append(".sign .what{font-size:var(--xs);color:var(--ink-secondary);margin-bottom:18px}")
				.append(".sign .rule{border-top:1px solid var(--ink);font-size:var(--xs);"
						+ "color:var(--ink-secondary);padding-top:4px;display:flex;"
						+ "justify-content:space-between}")

				.append("footer{margin-top:18px;border-top:1px solid var(--border);padding-top:6px;"
						+ "color:var(--ink-muted);font-size:var(--xs)}")
				.append("</style>");
	}

	// ---- Helpers --------------------------------------------------------

	/** "Gopal Das · 28 Aug 2026", or just the name where there is no date to go with it. */
	private static String person(String name, String when) {
		if (!notBlank(name)) {
			return null;
		}
		return notBlank(when) ? name + " · " + when : name;
	}

	private static void fact(StringBuilder h, String label, String value) {
		if (!notBlank(value)) {
			return;
		}
		h.append("<dl><dt>").append(esc(label)).append("</dt><dd>").append(esc(value))
				.append("</dd></dl>");
	}

	/** Rebuilds the label set from the model's flat list, falling back to English if absent. */
	private static Labels labels(List<String> flat) {
		if (flat == null || flat.size() < Labels.englishList().size()) {
			return Labels.english();
		}
		return new Labels(flat.get(0), flat.get(1), flat.get(2), flat.get(3), flat.get(4),
				flat.get(5), flat.get(6), flat.get(7), flat.get(8), flat.get(9), flat.get(10),
				flat.get(11), flat.get(12), flat.get(13), flat.get(14), flat.get(15), flat.get(16),
				flat.get(17), flat.get(18), flat.get(19), flat.get(20), flat.get(21), flat.get(22),
				flat.get(23));
	}

	private static String loadEmblem() {
		try (InputStream in =
				WorkOrderTemplate.class.getResourceAsStream("/brand/iskcon-icon.svg")) {
			if (in == null) {
				log.warn("The ISKCON emblem is not on the classpath; work orders will print without it");
				return "";
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
		} catch (IOException e) {
			log.warn("The ISKCON emblem could not be read; work orders will print without it", e);
			return "";
		}
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
