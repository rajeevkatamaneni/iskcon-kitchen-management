package org.iskcon.kms.document;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The server-rendered job card (B5, rebuilt for build brief 2026-08-21 item 17): one worksheet per
 * meal, and — when the person printing asks for them — the recipes behind it as an appendix.
 *
 * <p><strong>Page one is a worksheet, not a summary.</strong> It carries what the kitchen needs to
 * start and, more importantly, the two columns nobody can fill in from a screen: how many portions
 * of each preparation were actually cooked, and how many actually went out. Those are ruled boxes
 * filled in with a pen, and they are the reason the sheet comes back to the office. Everything
 * interactive that could have gone on here was left off on purpose: a cook mid-service will not
 * touch a screen, and a checklist nobody uses is worse than a sheet everybody does.
 *
 * <p><strong>Pages two onward are the recipes</strong> — ingredients and method, one block per
 * preparation, never split across a page break. They come after the worksheet rather than woven
 * through it, because the two halves have two readers: the office reads the worksheet and files it,
 * the cooks read the recipes and throw them away.
 *
 * <p><strong>The worksheet is always English; the recipes are the printer's choice.</strong> The
 * app's Phase 1 UI is English-only and the worksheet is read by the office, so translating it would
 * be translating for nobody. The recipes are read by cooks who, per {@code DESIGN_SYSTEM.md} §1, do
 * not read English comfortably. {@link JobCardService} decides which language that is.
 *
 * <p>Built with a StringBuilder like the recipe card and the PO sheet, and self-contained for the
 * same reason: Chromium renders it with no network, so the emblem is inlined from the classpath and
 * every colour and size is a literal. The Noto stack names one family per script the runtime image
 * actually ships, so Indic text shapes into glyphs rather than tofu. Every interpolated value is
 * escaped — all of it is text somebody typed.
 */
public final class JobCardTemplate {

	private static final Logger log = LoggerFactory.getLogger(JobCardTemplate.class);

	/**
	 * The ISKCON lotus emblem, read once from the classpath and inlined into every card.
	 *
	 * <p>Inlined rather than linked because the renderer has no network and the browser print view
	 * opens a document with no origin of its own — a {@code <img src>} would be a broken box on
	 * paper. It is the same file the web app uses, copied into the backend's resources so the two
	 * cannot drift apart silently: a card that carried a different mark from the screen it was
	 * printed from would be a small lie about where it came from.
	 *
	 * <p>A missing file costs the card its emblem and nothing else. A temple that cannot print
	 * today's dinner because an asset went astray would be a far worse failure than a plain header.
	 */
	private static final String EMBLEM = loadEmblem();

	/**
	 * The one script stack, naming a family per script rather than leaning on Chromium's codepoint
	 * fallback.
	 *
	 * <p>Every family here was confirmed present in the runtime image — {@code fonts-noto-core} plus
	 * {@code fonts-indic}, installed by {@code backend/Dockerfile} — so nothing is declared that the
	 * renderer cannot honour. Between them they cover the scripts of all 22 scheduled languages the
	 * picker offers. Naming them matters even though fallback already worked: fallback picks whatever
	 * fontconfig happens to rank first, which can change when the base image changes, and a temple
	 * would find out from a printed sheet.
	 */
	private static final String FONT_STACK =
			"'Noto Sans','Noto Sans Devanagari','Noto Sans Bengali','Noto Sans Gujarati',"
					+ "'Noto Sans Gurmukhi','Noto Sans Kannada','Noto Sans Malayalam','Noto Sans Oriya',"
					+ "'Noto Sans Tamil','Noto Sans Telugu','Noto Sans Ol Chiki','Noto Sans Meetei Mayek',"
					+ "'Noto Nastaliq Urdu','Noto Sans Arabic',system-ui,sans-serif";

	/** One ingredient line of a preparation, scaled to what that preparation is actually cooking. */
	public record Ingredient(String name, String quantity, boolean prohibited) {
	}

	/**
	 * One row of the servings table: what is being made, how much was planned, and two empty boxes.
	 *
	 * @param localName the same name in the appendix's language, so a cook can match this row to a
	 *                  page further back. Null when the appendix is English or was not asked for.
	 */
	public record Preparation(String name, String localName, String plannedText) {
	}

	/**
	 * One preparation's page in the appendix.
	 *
	 * @param untranslated the appendix is in another language but this one has no translation stored,
	 *                     so it prints in English under a line saying so. Three recipes of four in
	 *                     Kannada beats none.
	 */
	public record RecipePage(
			String name,
			String localName,
			List<Ingredient> ingredients,
			List<String> method,
			/** Named ingredients that this day's fast forbids, if any — the cook's own warning. */
			List<String> fastingConflicts,
			boolean untranslated) {
	}

	/** A person on the card: a rostered cook or a signed-up volunteer, with a number to ring. */
	public record Person(String name, String phone, String detail) {
	}

	/** Everything the card renders, built by {@link JobCardService} from one meal. */
	public record CardModel(
			String templeName,

			/**
			 * The card's filing reference — {@code DC-2026-0003}: {@code D} for Dinner, {@code C} for
			 * card, the year, a per-temple counter. It exists so that a signed sheet in a folder can be
			 * traced back to its record six months later (V64).
			 *
			 * <p><strong>It is a reference, not a heading, and it is set as one.</strong> Until item 17
			 * it was 14pt bold in the top right corner — the place the eye lands first on a sheet of A4
			 * — which told a cook holding it the one thing about the meal they already knew least about.
			 * What they need there is <em>Dinner · Friday 21 August 2026</em>. The number keeps the
			 * corner, in small grey type, underneath. Do not promote it back.
			 */
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

			/** One row of the servings table per preparation, in the order the meal lists them. */
			List<Preparation> preparations,
			List<String> equipment,

			/**
			 * How many people the meal was planned to take (item 24), already worded for print.
			 *
			 * <p>Null until that column exists, and the line simply does not appear — the card is not
			 * the right place to learn that a field has not been built yet.
			 */
			String plannedCrewText,
			List<Person> staff,
			List<Person> volunteers,

			/** The appendix. Empty when the person printing asked for the worksheet alone. */
			List<RecipePage> recipes,

			/** What to call the appendix's language on the sheet. Null for English. */
			String recipeLanguageLabel,

			String generatedOn,
			List<String> labels) {
	}

	/**
	 * The appendix's fixed wording, and the only text on this card that is ever translated.
	 *
	 * <p>English is the source that {@link DocumentLabelTranslator} turns into whichever language the
	 * recipes were asked for; it is also the fallback when translation fails, so a card always
	 * prints. The worksheet's own labels are English literals in the markup below, because the
	 * worksheet is always English.
	 */
	public record Labels(
			String recipes, String ingredient, String quantity, String method, String untranslated) {

		/**
		 * Bumped from 1 when the worksheet stopped being translated and this set shrank to the
		 * appendix. The cache in {@code document_label_translations} keys on it, so old rows are
		 * simply never read again rather than having to be cleared.
		 */
		static final int VERSION = 2;

		static Labels english() {
			return new Labels("Recipes", "Ingredient", "Quantity", "Method",
					"Not translated yet. Printed in English.");
		}

		static List<String> englishList() {
			return english().asList();
		}

		List<String> asList() {
			return List.of(recipes, ingredient, quantity, method, untranslated);
		}
	}

	private JobCardTemplate() {
	}

	public static String render(CardModel m) {
		Labels l = labels(m.labels());
		StringBuilder h = new StringBuilder();
		h.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">");
		h.append("<title>").append(esc(m.cardNumber())).append("</title>");
		style(h);
		h.append("</head><body>");

		worksheet(h, m);
		appendix(h, m, l);

		h.append("<footer>").append(esc(m.templeName())).append(" &middot; ").append(esc(m.cardNumber()))
				.append(" &middot; ").append(esc(m.generatedOn())).append("</footer>");
		h.append("</body></html>");
		return h.toString();
	}

	// ---- The sheet ------------------------------------------------------

	private static void worksheet(StringBuilder h, CardModel m) {
		// The header answers "which meal is this?" first and "which sheet is this?" second, because
		// that is the order somebody picking it up needs them in.
		h.append("<header><div class=\"mark\">").append(EMBLEM)
				.append("<div class=\"temple\">").append(esc(m.templeName())).append("</div></div>")
				.append("<div class=\"when\"><div class=\"meal\">").append(esc(m.mealKind()));
		if (notBlank(m.dateText())) {
			h.append(" &middot; ").append(esc(m.dateText()));
		}
		h.append("</div><div class=\"card-no\">").append(esc(m.cardNumber()))
				.append("</div></div></header>");

		h.append("<div class=\"facts\">");
		fact(h, "Ready by", m.readyByText());
		fact(h, "Head count", m.headCountText());
		fact(h, "Scales to", m.platesText());
		fact(h, "Occasion", m.occasion());
		fact(h, "For", m.clientName());
		fact(h, "Going to", m.venue());
		fact(h, "What it is for", m.purpose());
		h.append("</div>");

		if (!m.warnings().isEmpty()) {
			h.append("<ul class=\"warn\">");
			for (String warning : m.warnings()) {
				h.append("<li>").append(esc(warning)).append("</li>");
			}
			h.append("</ul>");
		}

		if (notBlank(m.kitchenNotes())) {
			h.append("<h2>Notes for the kitchen</h2>");
			h.append("<p class=\"notes\">").append(esc(m.kitchenNotes())).append("</p>");
		}

		servings(h, m);
		whoIsOn(h, m);

		if (!m.equipment().isEmpty()) {
			h.append("<h2>Equipment</h2>");
			h.append("<p class=\"chips\">").append(esc(String.join(" · ", m.equipment())))
					.append("</p>");
		}

		signOff(h);
	}

	/**
	 * The servings table — the heart of the sheet.
	 *
	 * <p>Planned is printed because the office already knows it. Cooked and served are ruled boxes
	 * because nobody knows them until the meal has happened, and the gap between planned and served,
	 * gathered over a month, is what tells a temple its head counts are wrong and by how much. That
	 * gap is the whole reason this sheet is worth printing.
	 */
	private static void servings(StringBuilder h, CardModel m) {
		h.append("<h2>Servings</h2>");
		h.append("<table class=\"servings\"><thead><tr>")
				.append("<th>Preparation</th>")
				.append("<th class=\"num\">Planned</th>")
				.append("<th class=\"pen\">Cooked</th>")
				.append("<th class=\"pen\">Served</th>")
				.append("</tr></thead><tbody>");
		for (Preparation p : m.preparations()) {
			h.append("<tr><td><span class=\"name\">").append(esc(p.name())).append("</span>");
			if (notBlank(p.localName())) {
				h.append("<span class=\"local\">").append(esc(p.localName())).append("</span>");
			}
			h.append("</td><td class=\"num\">").append(esc(p.plannedText())).append("</td>")
					.append("<td class=\"pen\"><span class=\"box\"></span></td>")
					.append("<td class=\"pen\"><span class=\"box\"></span></td></tr>");
		}
		h.append("</tbody></table>");
	}

	/**
	 * Who the meal has, against how many it was planned to take.
	 *
	 * <p>Phone numbers are on here because the one thing this sheet is asked for at 05:40 is a way to
	 * ring the person who has not arrived.
	 */
	private static void whoIsOn(StringBuilder h, CardModel m) {
		h.append("<h2>Who is on</h2>");
		if (notBlank(m.plannedCrewText())) {
			h.append("<p class=\"crew\">").append(esc(m.plannedCrewText())).append("</p>");
		}
		roll(h, "Staff", m.staff(), false);
		roll(h, "Volunteers", m.volunteers(), true);
	}

	private static void roll(StringBuilder h, String heading, List<Person> people, boolean withJob) {
		h.append("<h3>").append(esc(heading)).append(" &middot; ").append(people.size())
				.append("</h3>");
		if (people.isEmpty()) {
			h.append("<p class=\"none\">Nobody is rostered</p>");
			return;
		}
		h.append("<ul class=\"roll\">");
		for (Person person : people) {
			h.append("<li><span class=\"who\">").append(esc(person.name())).append("</span>");
			if (withJob && notBlank(person.detail())) {
				h.append("<span class=\"job\">").append(esc(person.detail())).append("</span>");
			}
			h.append("<span class=\"phone\">").append(esc(person.phone())).append("</span></li>");
		}
		h.append("</ul>");
	}

	/**
	 * Two boxes, signed with a pen.
	 *
	 * <p>Three used to sign here — cooked, checked and served — which asked for a name against a
	 * moment (checking) that nobody in a temple kitchen is separately responsible for. Two names
	 * against the two columns above them is the same accountability with one fewer blank box to
	 * explain: whoever checked the cooked figures, and whoever recorded the served ones.
	 */
	private static void signOff(StringBuilder h) {
		h.append("<div class=\"signoff\">");
		signBox(h, "Kitchen manager / head cook", "The cooked figures were checked.");
		signBox(h, "Serving staff", "The served figures were recorded.");
		h.append("</div>");
	}

	private static void signBox(StringBuilder h, String who, String what) {
		h.append("<div class=\"sign\"><div class=\"who\">").append(esc(who)).append("</div>")
				.append("<div class=\"what\">").append(esc(what)).append("</div>")
				.append("<div class=\"rule\"><span>Signature</span><span>Time</span></div></div>");
	}

	// ---- The appendix ---------------------------------------------------

	private static void appendix(StringBuilder h, CardModel m, Labels l) {
		if (m.recipes().isEmpty()) {
			return;
		}
		h.append("<section class=\"appendix\"><h2>").append(esc(l.recipes()));
		if (notBlank(m.recipeLanguageLabel())) {
			h.append(" &middot; ").append(esc(m.recipeLanguageLabel()));
		}
		h.append("</h2>");

		for (RecipePage recipe : m.recipes()) {
			// Never split a preparation across a page break: a cook holding page two with half the
			// ingredients on page one is the failure this sheet exists to prevent.
			h.append("<div class=\"recipe\"><div class=\"recipe-name\">").append(esc(recipe.name()));
			if (notBlank(recipe.localName())) {
				h.append("<span class=\"local\">").append(esc(recipe.localName())).append("</span>");
			}
			h.append("</div>");
			if (recipe.untranslated()) {
				h.append("<p class=\"untranslated\">").append(esc(l.untranslated())).append("</p>");
			}
			if (!recipe.fastingConflicts().isEmpty()) {
				h.append("<ul class=\"warn\"><li>")
						.append(esc(String.join(", ", recipe.fastingConflicts()))).append("</li></ul>");
			}
			h.append("<table><thead><tr><th>").append(esc(l.ingredient())).append("</th>")
					.append("<th class=\"num\">").append(esc(l.quantity()))
					.append("</th></tr></thead><tbody>");
			for (Ingredient line : recipe.ingredients()) {
				h.append("<tr><td>").append(esc(line.name()));
				if (line.prohibited()) {
					h.append(" <span class=\"badge\">prohibited</span>");
				}
				h.append("</td><td class=\"num\">").append(esc(line.quantity())).append("</td></tr>");
			}
			h.append("</tbody></table>");
			if (!recipe.method().isEmpty()) {
				h.append("<h3>").append(esc(l.method())).append("</h3>");
				h.append("<ol class=\"method\">");
				for (String step : recipe.method()) {
					h.append("<li>").append(esc(step)).append("</li>");
				}
				h.append("</ol>");
			}
			h.append("</div>");
		}
		h.append("</section>");
	}

	// ---- Style ----------------------------------------------------------

	/**
	 * The card's own stylesheet, holding the app's tokens rather than a second set of opinions.
	 *
	 * <p>Colour is {@code DESIGN_SYSTEM.md} §2 verbatim, and the type scale is §3 restated in points:
	 * a printed sheet has no 16px, and 11pt is the paper equivalent of the app's body size, so every
	 * step is the screen scale multiplied by 11/16 and rounded to the nearest half point. That keeps
	 * the sheet recognisably the same product as the screen it was printed from without embedding a
	 * font file — Q4 settled that Noto stays and no ~80KB face rides along in every PDF.
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

				// The print window the browser opens has no page margin of its own, so the document
				// went edge to edge on screen and printed correctly — two different sheets from one
				// file, which is exactly the surprise a preview exists to prevent. This gives the
				// screen an A4-shaped page on a grey ground, padded by the same 16mm @page uses, so
				// what is previewed is what comes out of the printer. Print ignores all of it.
				.append("@media screen{")
				.append("html{background:var(--sunken)}")
				.append("body{width:210mm;min-height:297mm;margin:8mm auto;padding:16mm;"
						+ "box-shadow:0 1px 4px rgba(43,38,33,.16)}")
				.append("}")

				.append("header{display:flex;justify-content:space-between;align-items:flex-start;"
						+ "gap:12mm;border-bottom:1px solid var(--border-strong);padding-bottom:8px;"
						+ "margin-bottom:14px}")
				.append(".mark{display:flex;align-items:center;gap:8px}")
				// The emblem is the temple's own mark, so it takes the ink colour. Terracotta is the
				// accent and the accent means "the main thing to do here"; a logo is not an action.
				.append(".mark svg{height:9mm;width:auto;fill:var(--ink)}")
				.append(".temple{font-size:var(--lg);font-weight:600}")
				.append(".when{text-align:right}")
				.append(".meal{font-size:var(--lg);font-weight:600;white-space:nowrap}")
				.append(".card-no{font-size:var(--xs);color:var(--ink-muted);"
						+ "font-variant-numeric:tabular-nums;white-space:nowrap}")

				.append(".facts{display:flex;flex-wrap:wrap;gap:6px 20px;margin-bottom:12px}")
				.append(".facts dl{margin:0}")
				.append(".facts dt{font-size:var(--xs);color:var(--ink-secondary)}")
				.append(".facts dd{margin:0;font-size:var(--base);font-weight:600}")

				// The warnings are the one thing on this sheet that must not be skimmed past: a fasting
				// day changes what may go in a pot, and it is read at speed in a hot room.
				.append("ul.warn{border:1px solid var(--accent-border);background:var(--accent-bg);"
						+ "color:var(--accent-text);padding:8px 12px 8px 28px;margin:0 0 12px;"
						+ "font-size:var(--base)}")
				.append("ul.warn li{margin:2px 0}")

				.append("h2{font-size:var(--sm);font-weight:600;color:var(--ink-secondary);"
						+ "border-bottom:1px solid var(--border);padding-bottom:4px;margin:16px 0 8px}")
				.append("h3{font-size:var(--sm);font-weight:600;color:var(--ink-secondary);"
						+ "margin:10px 0 4px}")
				.append("p{margin:0 0 8px}")
				.append(".notes,.chips{font-size:var(--sm)}")

				.append("table{width:100%;border-collapse:collapse;margin:4px 0 8px}")
				.append("th,td{text-align:left;padding:5px 8px;border-bottom:1px solid var(--border);"
						+ "vertical-align:top}")
				.append("thead th{background:var(--sunken);font-size:var(--xs);font-weight:600;"
						+ "color:var(--ink-secondary)}")
				.append("td.num,th.num{text-align:right;white-space:nowrap;"
						+ "font-variant-numeric:tabular-nums}")

				.append(".servings td{vertical-align:middle}")
				.append(".servings .name{display:block;font-weight:600}")
				// The same name in the appendix's script, so a cook can carry a row on this page to a
				// page further back without reading a word of English in between.
				.append(".servings .local{display:block;font-size:var(--sm);color:var(--ink-secondary)}")
				.append(".servings th.pen{text-align:center;width:26mm}")
				// Ruled, empty, and big enough to write a three-digit figure into with a biro.
				.append(".servings td.pen{text-align:center}")
				.append(".servings .box{display:block;height:9mm;border:1px solid var(--border-strong);"
						+ "border-radius:1mm;background:var(--canvas)}")

				.append(".crew{font-size:var(--sm);color:var(--ink-secondary)}")
				.append("ul.roll{list-style:none;margin:0 0 4px;padding:0;font-size:var(--sm)}")
				.append("ul.roll li{display:flex;gap:8px;padding:3px 0;"
						+ "border-bottom:1px solid var(--border)}")
				.append("ul.roll .who{flex:1;font-weight:500}")
				.append("ul.roll .job{flex:1;color:var(--ink-secondary)}")
				.append("ul.roll .phone{white-space:nowrap;font-variant-numeric:tabular-nums;"
						+ "color:var(--ink-secondary)}")
				.append(".none{color:var(--ink-muted);font-size:var(--sm)}")

				.append(".signoff{break-inside:avoid;display:flex;gap:12px;margin-top:20px}")
				.append(".sign{flex:1;border:1px solid var(--border);padding:8px 10px 22px}")
				.append(".sign .who{font-size:var(--sm);font-weight:600}")
				.append(".sign .what{font-size:var(--xs);color:var(--ink-secondary);margin-bottom:18px}")
				.append(".sign .rule{border-top:1px solid var(--ink);font-size:var(--xs);"
						+ "color:var(--ink-secondary);padding-top:4px;display:flex;"
						+ "justify-content:space-between}")

				// The recipes start their own page. The worksheet is one sheet that comes back signed;
				// the recipes are pages a cook works from and then throws away.
				.append(".appendix{break-before:page}")
				.append(".recipe{break-inside:avoid;margin-bottom:14px}")
				.append(".recipe-name{font-size:var(--lg);font-weight:600}")
				.append(".recipe-name .local{font-weight:400;color:var(--ink-secondary);margin-left:8px}")
				.append(".untranslated{font-size:var(--xs);color:var(--ink-muted)}")
				.append(".badge{font-size:var(--xs);font-weight:600;background:var(--danger-bg);"
						+ "color:var(--danger-text);border-radius:8px;padding:1px 6px;margin-left:6px}")
				.append("ol.method{margin:4px 0 0 18px;padding:0;font-size:var(--sm)}")
				.append("ol.method li{margin:2px 0}")

				.append("footer{margin-top:18px;border-top:1px solid var(--border);padding-top:6px;"
						+ "color:var(--ink-muted);font-size:var(--xs)}")
				.append("</style>");
	}

	// ---- Helpers --------------------------------------------------------

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
		return new Labels(flat.get(0), flat.get(1), flat.get(2), flat.get(3), flat.get(4));
	}

	private static String loadEmblem() {
		try (InputStream in =
				JobCardTemplate.class.getResourceAsStream("/brand/iskcon-icon.svg")) {
			if (in == null) {
				log.warn("The ISKCON emblem is not on the classpath; job cards will print without it");
				return "";
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
		} catch (IOException e) {
			log.warn("The ISKCON emblem could not be read; job cards will print without it", e);
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
