package org.iskcon.kms.document;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The server-rendered job card (B5), rebuilt against Rajeev's review of 2026-09-05.
 *
 * <p><strong>It is a pack of separate documents, not one sheet.</strong> Four of them, each starting
 * a fresh page, because they have four readers and get physically separated the moment they reach a
 * kitchen:
 *
 * <ol>
 *   <li>the <em>worksheet</em> — notes, what to cook, who is cooking it, and the boxes the kitchen
 *       signs;</li>
 *   <li>the <em>recipes</em>, when the person printing asked for them;</li>
 *   <li>the <em>serving sheet</em> — what went out, and the signatures of the people who handed it
 *       out;</li>
 *   <li>the <em>delivery sheet</em>, on an outside delivery only — who to ring, where to go, how
 *       long to allow, and a map.</li>
 * </ol>
 *
 * <p>They used to be one squeezed sheet. Rajeev: <em>"There is no need to sqeeze them like this.
 * Readabulity is MORE important than an extra sheet of paper we might save."</em> So whitespace at
 * the foot of a document is left alone, and nothing flows up into the gap.
 *
 * <p><strong>Cooked and served are on different sheets now.</strong> The kitchen signs for what it
 * cooked; the servers sign for what went out. That is one more piece of paper for the office to
 * reconcile and two people each signing only for what they actually saw.
 *
 * <p><strong>Black, white and greys.</strong> No accent, no status colour, no temple theme — asked
 * for on 2026-09-05 and it suits the medium: most of these come off a mono laser printer in a room
 * behind a kitchen, where a pale terracotta wash is a grey smudge and a red badge is a black one.
 * What must not be missed is carried by weight, size and rule thickness, which survive photocopying.
 *
 * <p><strong>Every page identifies itself.</strong> Version and print time on the left, card number
 * on the right, page N of M. A single sheet picked up off a counter has to say which meal it belongs
 * to and whether it is the current version, because it will be separated from its pack. See
 * {@link CardModel#cardVersion()} for what the version means.
 *
 * <p><strong>The worksheet is always English; the recipes are the printer's choice.</strong> The
 * app's Phase 1 UI is English-only and the worksheet is read by the office, so translating it would
 * be translating for nobody. The recipes are read by cooks who, per {@code DESIGN_SYSTEM.md} §1, do
 * not read English comfortably. {@link JobCardService} decides which language that is.
 *
 * <p>Built with a StringBuilder and self-contained: Chromium renders it with no network, so the
 * emblem and the map are inlined and every colour and size is a literal. The Noto stack names one
 * family per script the runtime image actually ships, so Indic text shapes into glyphs rather than
 * tofu. Every interpolated value is escaped — all of it is text somebody typed.
 */
public final class JobCardTemplate {

	private static final Logger log = LoggerFactory.getLogger(JobCardTemplate.class);

	/**
	 * The ISKCON lotus emblem, read once from the classpath and inlined into every card.
	 *
	 * <p>Inlined rather than linked because the renderer has no network and the browser print view
	 * opens a document with no origin of its own — a {@code <img src>} would be a broken box on
	 * paper. It is the same file the web app uses, copied into the backend's resources so the two
	 * cannot drift apart silently.
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
	 * {@code fonts-indic}, installed by {@code backend/Dockerfile}. Between them they cover the
	 * scripts of all 22 scheduled languages the picker offers. Naming them matters even though
	 * fallback already worked: fallback picks whatever fontconfig happens to rank first, which can
	 * change when the base image changes, and a temple would find out from a printed sheet.
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
	 * One row of the food-items table: what is being made, and how much was planned.
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

	/**
	 * The delivery sheet's own contents. Null on everything that is not going outside by van.
	 *
	 * @param subLocation  where exactly, once the driver is there — "Clubhouse", "Block C, second
	 *                     gate". Never part of the route: being at the right gate is what matters,
	 *                     and the last fifty metres is a phone call.
	 * @param allowanceText how long the temple allows for the drive, in words. The temple's own
	 *                     figure, not a live one — see {@code V93}.
	 * @param allowanceNote whether that figure is Google's or a person's, said plainly on the sheet
	 *                     so a driver knows how much to trust it.
	 * @param mapDataUri   a static map of the destination, already base64 and inlined because the
	 *                     renderer has no network. Null when there is no map service or no map.
	 */
	public record Delivery(
			String contactName,
			String contactPhone,
			String address,
			String subLocation,
			String allowanceText,
			String allowanceNote,
			String leaveByText,
			String deliverByText,
			String mapDataUri) {
	}

	/** Everything the card renders, built by {@link JobCardService} from one meal. */
	public record CardModel(
			String templeName,

			/**
			 * The card's filing reference — {@code EC-2026-0013}. It exists so that a signed sheet in
			 * a folder can be traced back to its record six months later (V64).
			 *
			 * <p><strong>It lives in the footer and nowhere else.</strong> It was in the top-right
			 * corner until 2026-09-05, where Rajeev objected that it "is taking up prime real estate
			 * and it does not belong there" — the corner of a job card belongs to the one thing a cook
			 * picking it up needs, which is which meal this is. Do not promote it back.
			 */
			String cardNumber,

			/**
			 * Which version of this card has been printed, and the reason the footer exists at all.
			 *
			 * <p>Two people in one kitchen holding two sheets after a late change need a way to tell
			 * which is current, and the rule is simply that the higher number wins. It moves only when
			 * the meal itself changes — reprinting an unchanged plan gives the same number, so two
			 * sheets reading v2 <em>are</em> the same sheet and nobody hunts for a difference that is
			 * not there. {@link JobCardService} derives it by hashing this very model, which is what
			 * makes "no field can be forgotten" true by construction rather than by diligence.
			 */
			int cardVersion,

			/** What kind of meal this is — "Lunch", "Event", "Outside Event". Never blank. */
			String mealKindLabel,
			/** What this event is called, where the meal is one. Null for the three main meals. */
			String eventName,
			String dateText,

			String readyByText,

			/** The head count as one number, set large — the figure everything else scales from. */
			String headCountText,

			/**
			 * The breakdown under it, small: "150 adults · 30 children". Worth keeping beside the
			 * total because a hall of children eats differently from a hall of adults, and worth
			 * keeping <em>under</em> it because the total is what the strip is for.
			 */
			String headCountDetail,

			/** What the day asks of the kitchen: the fast, and anything the temple's own rule forbids. */
			List<String> warnings,

			String kitchenNotes,
			/** The serving sheet's equivalent — what the people handing food out need to know. */
			String serverNotes,

			/** One row of the food-items table per preparation, in the order the meal lists them. */
			List<Preparation> preparations,
			List<String> equipment,

			/**
			 * How many people the meal was planned to take, already worded for print. Null until that
			 * column exists, and the line simply does not appear — the card is not the right place to
			 * learn that a field has not been built yet.
			 */
			String plannedCrewText,
			List<Person> staff,
			List<Person> volunteers,

			/** The delivery sheet, or null when nothing is leaving the temple by van. */
			Delivery delivery,

			/** The appendix. Empty when the person printing asked for the worksheet alone. */
			List<RecipePage> recipes,

			/** What to call the appendix's language on the sheet. Null for English. */
			String recipeLanguageLabel,

			String generatedOn,

			/**
			 * Whether this render must carry its own running footer.
			 *
			 * <p>Two renderers, two mechanisms, one footer. The PDF is made by Playwright, whose
			 * {@code footerTemplate} is the only way to get a page number at all — Blink supports
			 * neither {@code @page} margin boxes nor {@code counter(page)}, so no stylesheet this file
			 * could write would produce "Page 3 of 7". The browser print view has no such hook and
			 * gets a {@code position:fixed} footer instead, which Chromium repeats on every sheet,
			 * with the page numbers coming from the print dialog's own.
			 *
			 * <p>So this is true for the print view and false for the PDF. The words are identical
			 * either way; only the machinery differs.
			 */
			boolean footerInDocument,

			List<String> labels) {
	}

	/**
	 * The appendix's fixed wording, and the only text on this card that is ever translated.
	 *
	 * <p>English is the source that {@code DocumentLabelTranslator} turns into whichever language the
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
		servingSheet(h, m);
		deliverySheet(h, m);

		if (m.footerInDocument()) {
			runningFooter(h, m);
		}
		h.append("</body></html>");
		return h.toString();
	}

	/**
	 * The footer's words, shared by the two renderers so they cannot drift.
	 *
	 * <p>{@link PlaywrightPdfRenderer} asks for this and hands it to Chromium as a footer template
	 * with a page number appended; the print view gets the same string in a fixed element. Version
	 * and print time on the left because they answer "is this the current sheet?", card number on the
	 * right because it answers "what is this?" — and the right is where a filing clerk's eye goes.
	 */
	public static String footerLeft(CardModel m) {
		return "v" + m.cardVersion() + " · printed " + m.generatedOn();
	}

	// ---- 1. The worksheet -----------------------------------------------

	/**
	 * What the kitchen cooks from, and what it signs.
	 *
	 * <p>Everything that used to compete with the three facts at the top has gone somewhere else or
	 * gone entirely: the occasion (removed outright — the people holding this sheet know what day it
	 * is better than the sheet does), and the contact and delivery address, which moved to their own
	 * sheet because, as Rajeev put it, they have no business in "the most important part of the job
	 * card".
	 */
	private static void worksheet(StringBuilder h, CardModel m) {
		h.append("<section class=\"sheet\">");

		// The corner answers "which meal is this?" and nothing else. Two lines, because one line of
		// "Outside Event: Bhagavad Gita Parayanam · Saturday 5 September 2026" is a line nobody reads
		// the end of.
		h.append("<header><div class=\"mark\">").append(EMBLEM)
				.append("<div class=\"temple\">").append(esc(m.templeName())).append("</div></div>")
				.append("<div class=\"when\"><div class=\"meal\">").append(esc(heading(m)))
				.append("</div><div class=\"date\">").append(esc(m.dateText()))
				.append("</div></div></header>");

		// The three things a cook needs before anything else, and the sheet says so with size. They
		// used to be 11pt facts in a wrapping row beside four that did not matter, which is how the
		// numbers that decide the morning ended up camouflaged by the ones that decide nothing.
		h.append("<div class=\"facts\">");
		fact(h, "Ready by", m.readyByText(), null);
		fact(h, "Head count", m.headCountText(), m.headCountDetail());
		if (m.delivery() != null) {
			// Only where the food actually leaves by van. A pickup has no delivery time to miss.
			fact(h, "Deliver by", m.delivery().deliverByText(), null);
		}
		h.append("</div>");

		warnings(h, m);
		noteBox(h, "Notes for the kitchen", m.kitchenNotes());
		foodItems(h, m);
		crew(h, m);

		if (!m.equipment().isEmpty()) {
			h.append("<h2>Equipment</h2>");
			h.append("<p class=\"chips\">").append(esc(String.join(" · ", m.equipment())))
					.append("</p>");
		}

		h.append("<div class=\"signoff\">");
		signBox(h, "Kitchen manager / head cook", "The cooked figures above were checked.");
		signBox(h, "Second cook", "Present through service.");
		h.append("</div>");

		h.append("</section>");
	}

	/**
	 * The food items to prepare — the heart of the worksheet.
	 *
	 * <p>Called <em>Servings</em> until 2026-09-05, which named the numbers in it rather than the
	 * thing it is: a list of what to make. Planned is printed because the office already knows it;
	 * cooked is a ruled box because nobody knows it until the meal has happened.
	 *
	 * <p><strong>Served is not here any more.</strong> It moved to the serving sheet, so that the two
	 * signatures each cover only what that person actually saw. The gap between planned and served —
	 * the thing this sheet is worth printing for — is now reconciled across two pages of one pack.
	 */
	private static void foodItems(StringBuilder h, CardModel m) {
		h.append("<h2>Food items to prepare</h2>");
		h.append("<table class=\"items\"><thead><tr>")
				.append("<th>Preparation</th>")
				.append("<th class=\"num\">Planned</th>")
				.append("<th class=\"pen\">Cooked</th>")
				.append("</tr></thead><tbody>");
		for (Preparation p : m.preparations()) {
			h.append("<tr><td><span class=\"name\">").append(esc(p.name())).append("</span>");
			if (notBlank(p.localName())) {
				h.append("<span class=\"local\">").append(esc(p.localName())).append("</span>");
			}
			h.append("</td><td class=\"num\">").append(esc(p.plannedText())).append("</td>")
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
	private static void crew(StringBuilder h, CardModel m) {
		h.append("<h2>People working on this job card</h2>");
		if (notBlank(m.plannedCrewText())) {
			h.append("<p class=\"crew\">").append(esc(m.plannedCrewText())).append("</p>");
		}
		roll(h, "Staff", m.staff(), false);
		roll(h, "Volunteers", m.volunteers(), true);
	}

	private static void roll(StringBuilder h, String heading, List<Person> people, boolean withJob) {
		h.append("<h3>").append(esc(heading)).append(" · ").append(people.size()).append("</h3>");
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

	// ---- 2. The recipes -------------------------------------------------

	private static void appendix(StringBuilder h, CardModel m, Labels l) {
		if (m.recipes().isEmpty()) {
			return;
		}
		h.append("<section class=\"sheet break\">");
		sheetHead(h, m, esc(l.recipes())
				+ (notBlank(m.recipeLanguageLabel()) ? " · " + esc(m.recipeLanguageLabel()) : ""));

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

	// ---- 3. The serving sheet -------------------------------------------

	/**
	 * What actually went out, and who says so.
	 *
	 * <p>Its own page since 2026-09-05. The served figures were beside the cooked ones on the
	 * worksheet, which put one signature under two facts that two different people witness — the
	 * kitchen knows what came out of the pot and the servers know what left the counter.
	 */
	private static void servingSheet(StringBuilder h, CardModel m) {
		h.append("<section class=\"sheet break\">");
		sheetHead(h, m, "Serving sheet");

		noteBox(h, "Notes for the servers", m.serverNotes());

		h.append("<h2>What went out</h2>");
		h.append("<table class=\"items\"><thead><tr>")
				.append("<th>Preparation</th>")
				.append("<th class=\"num\">Planned</th>")
				.append("<th class=\"pen\">Served</th>")
				.append("</tr></thead><tbody>");
		for (Preparation p : m.preparations()) {
			h.append("<tr><td><span class=\"name\">").append(esc(p.name())).append("</span></td>")
					.append("<td class=\"num\">").append(esc(p.plannedText())).append("</td>")
					.append("<td class=\"pen\"><span class=\"box\"></span></td></tr>");
		}
		h.append("</tbody></table>");

		// One figure the whole meal is judged by, and the reason the sheet comes back to the office:
		// the gap between what was planned and what was actually eaten is how a temple learns its
		// head counts are wrong, and by how much.
		h.append("<h2>Head count actually served</h2>");
		h.append("<div class=\"count\"><span class=\"box wide\"></span>")
				.append("<span class=\"count-note\">Planned for ").append(esc(m.headCountText()))
				.append("</span></div>");

		h.append("<div class=\"signoff\">");
		signBox(h, "Serving staff", "The served figures above were recorded.");
		signBox(h, "Kitchen manager", "Seen and filed.");
		h.append("</div>");
		h.append("</section>");
	}

	// ---- 4. The delivery sheet ------------------------------------------

	/**
	 * The sheet the driver takes, and the only one of the four that leaves the building.
	 *
	 * <p>Everything on it was on page one until 2026-09-05, competing with the cooking instructions.
	 * Rajeev: <em>"Why do we have For and Going to in the most important part of the job card. Move
	 * this to the bottom of the Job Card. Even betwwer if it can be a sperate sheet."</em> It is a
	 * separate sheet, which also means it can be handed to somebody without handing them the recipes.
	 */
	private static void deliverySheet(StringBuilder h, CardModel m) {
		Delivery d = m.delivery();
		if (d == null) {
			return;
		}
		h.append("<section class=\"sheet break\">");
		sheetHead(h, m, "Delivery sheet");

		// Who to ring, in the largest type on the sheet. A driver at a gate with the wrong number is
		// the failure this page exists to prevent, and they are reading it through a windscreen.
		h.append("<div class=\"panel contact\">");
		h.append("<div class=\"panel-label\">Deliver to</div>");
		h.append("<div class=\"contact-name\">").append(esc(d.contactName())).append("</div>");
		if (notBlank(d.contactPhone())) {
			h.append("<div class=\"contact-phone\">").append(esc(d.contactPhone())).append("</div>");
		}
		h.append("</div>");

		h.append("<div class=\"panel\">");
		h.append("<div class=\"panel-label\">Address</div>");
		h.append("<div class=\"address\">").append(esc(d.address())).append("</div>");
		if (notBlank(d.subLocation())) {
			// Never geocoded and never routed on — the van goes to the gate, and this is what the
			// driver asks about when they get there.
			h.append("<div class=\"panel-label\">Once you are there</div>");
			h.append("<div class=\"sub-location\">").append(esc(d.subLocation())).append("</div>");
		}
		h.append("</div>");

		h.append("<div class=\"facts\">");
		fact(h, "Leave the temple by", d.leaveByText(), null);
		fact(h, "Allow", d.allowanceText(), null);
		fact(h, "Guests eat at", d.deliverByText(), null);
		h.append("</div>");
		if (notBlank(d.allowanceNote())) {
			h.append("<p class=\"allowance-note\">").append(esc(d.allowanceNote())).append("</p>");
		}

		if (notBlank(d.mapDataUri())) {
			// Zoomed out far enough to show something a driver recognises. A map tight on the pin is
			// a picture of a rooftop; what helps is the main road it comes off.
			h.append("<div class=\"map\"><img src=\"").append(esc(d.mapDataUri()))
					.append("\" alt=\"Map of the delivery address\"></div>");
		}

		h.append("<div class=\"signoff\">");
		signBox(h, "Driver", "The food left the temple.");
		signBox(h, "Received by", "The food arrived.");
		h.append("</div>");
		h.append("</section>");
	}

	// ---- Shared pieces --------------------------------------------------

	/** A quiet band at the head of every sheet but the first: which meal, and which sheet. */
	private static void sheetHead(StringBuilder h, CardModel m, String title) {
		h.append("<div class=\"sheet-head\"><span class=\"sheet-title\">").append(title)
				.append("</span><span class=\"sheet-meal\">").append(esc(heading(m)))
				.append(" · ").append(esc(m.dateText())).append("</span></div>");
	}

	/**
	 * The one thing on this pack that must not be skimmed past: a fasting day changes what may go in
	 * a pot, and it is read at speed in a hot room. With colour gone it is carried by a heavy left
	 * rule and bold text, both of which survive a photocopier and a cheap toner cartridge.
	 */
	private static void warnings(StringBuilder h, CardModel m) {
		if (m.warnings().isEmpty()) {
			return;
		}
		h.append("<ul class=\"warn\">");
		for (String warning : m.warnings()) {
			h.append("<li>").append(esc(warning)).append("</li>");
		}
		h.append("</ul>");
	}

	/** Notes, in a box that asks to be read rather than a paragraph under a hairline. */
	private static void noteBox(StringBuilder h, String title, String body) {
		if (!notBlank(body)) {
			return;
		}
		h.append("<div class=\"panel notes\"><div class=\"panel-label\">").append(esc(title))
				.append("</div><div class=\"notes-body\">").append(esc(body)).append("</div></div>");
	}

	private static void signBox(StringBuilder h, String who, String what) {
		h.append("<div class=\"sign\"><div class=\"who\">").append(esc(who)).append("</div>")
				.append("<div class=\"what\">").append(esc(what)).append("</div>")
				.append("<div class=\"rule\"><span>Signature</span><span>Time</span></div></div>");
	}

	/** The print view's own running footer. The PDF gets these words from Playwright instead. */
	private static void runningFooter(StringBuilder h, CardModel m) {
		h.append("<footer class=\"running\"><span>").append(esc(footerLeft(m)))
				.append("</span><span class=\"card-no\">").append(esc(m.cardNumber()))
				.append("</span></footer>");
	}

	/** "Outside Event: Bhagavad Gita Parayanam", or plain "Lunch". */
	private static String heading(CardModel m) {
		return notBlank(m.eventName())
				? m.mealKindLabel() + ": " + m.eventName()
				: m.mealKindLabel();
	}

	// ---- Style ----------------------------------------------------------

	/**
	 * The card's own stylesheet.
	 *
	 * <p>Greys only, by decision of 2026-09-05, and it suits the medium: these come off a mono laser
	 * printer in a room behind a kitchen, where a pale wash is a smudge. Emphasis is carried by
	 * weight, size and rule thickness instead, all of which survive a photocopy.
	 *
	 * <p>The type scale is {@code DESIGN_SYSTEM.md} §3 restated in points — a printed sheet has no
	 * 16px, and 11pt is the paper equivalent of the app's body size, so every step is the screen
	 * scale multiplied by 11/16 and rounded to the nearest half point. Corners are 2mm throughout,
	 * because Rajeev is right that a printed box does not have to look like it was drawn in 1962.
	 */
	private static void style(StringBuilder h) {
		h.append("<style>")
				.append(":root{")
				.append("--paper:#FFFFFF;--panel:#F7F7F7;--band:#EFEFEF;")
				.append("--rule:#D9D9D9;--rule-strong:#9A9A9A;")
				.append("--ink:#141414;--ink-2:#4A4A4A;--ink-3:#767676;")
				.append("--xs:8.5pt;--sm:9.5pt;--base:11pt;--lg:12.5pt;--xl:15pt;--xxl:22pt;")
				.append("--r:2mm;")
				.append("}")
				.append("@page{size:A4;margin:14mm}")
				.append("*{box-sizing:border-box}")
				.append("body{font-family:").append(FONT_STACK)
				.append(";color:var(--ink);background:var(--paper);margin:0;padding:0;"
						+ "font-size:var(--base);line-height:1.5}")

				// The print window the browser opens has no page margin of its own, so the document
				// went edge to edge on screen and printed correctly — two different sheets from one
				// file, which is exactly the surprise a preview exists to prevent. This gives the
				// screen an A4-shaped page on a grey ground, padded by the same 14mm @page uses.
				.append("@media screen{")
				.append("html{background:var(--band)}")
				.append("body{width:210mm;margin:8mm auto;padding:14mm;background:var(--paper);"
						+ "box-shadow:0 1px 4px rgba(0,0,0,.16)}")
				.append("}")

				// Each document of the pack starts a page. Nothing flows up into the whitespace at
				// the foot of the one before it — that whitespace is the point.
				.append("section.break{break-before:page}")
				.append("section.sheet{padding-bottom:6mm}")

				.append("header{display:flex;justify-content:space-between;align-items:flex-start;"
						+ "gap:12mm;border-bottom:1.5px solid var(--ink);padding-bottom:10px;"
						+ "margin-bottom:16px}")
				.append(".mark{display:flex;align-items:center;gap:8px}")
				.append(".mark svg{height:9mm;width:auto;fill:var(--ink)}")
				.append(".temple{font-size:var(--lg);font-weight:600}")
				// Two lines, and the meal is the one that carries weight. Rajeev, on the old single
				// line with the card number under it: the corner is prime real estate.
				.append(".when{text-align:right}")
				.append(".meal{font-size:var(--lg);font-weight:700}")
				.append(".date{font-size:var(--base);color:var(--ink-2)}")

				.append(".sheet-head{display:flex;justify-content:space-between;align-items:baseline;"
						+ "gap:8mm;border-bottom:1.5px solid var(--ink);padding-bottom:8px;"
						+ "margin-bottom:16px}")
				.append(".sheet-title{font-size:var(--lg);font-weight:700}")
				.append(".sheet-meal{font-size:var(--sm);color:var(--ink-2);text-align:right}")

				// The facts strip. Evenly spaced across the full width and set large, because these
				// are the numbers the kitchen needs to jump at them.
				.append(".facts{display:flex;gap:4mm;margin:0 0 16px}")
				.append(".facts .f{flex:1;border:1px solid var(--rule);border-radius:var(--r);"
						+ "background:var(--panel);padding:8px 10px}")
				.append(".facts dt{font-size:var(--xs);font-weight:600;color:var(--ink-2);"
						+ "text-transform:uppercase;letter-spacing:.08em}")
				.append(".facts dd{margin:2px 0 0;font-size:var(--xxl);font-weight:700;line-height:1.15;"
						+ "font-variant-numeric:tabular-nums}")
				.append(".facts dd.detail{font-size:var(--sm);font-weight:400;color:var(--ink-2);"
						+ "margin-top:2px}")

				.append("ul.warn{border:1px solid var(--rule-strong);border-left:3mm solid var(--ink);"
						+ "border-radius:var(--r);background:var(--panel);padding:10px 14px;"
						+ "margin:0 0 16px;list-style:none;font-size:var(--base);font-weight:600}")
				.append("ul.warn li{margin:3px 0}")

				.append(".panel{border:1px solid var(--rule-strong);border-radius:var(--r);"
						+ "background:var(--panel);padding:10px 14px;margin:0 0 16px;"
						+ "break-inside:avoid}")
				.append(".panel-label{font-size:var(--xs);font-weight:600;color:var(--ink-2);"
						+ "text-transform:uppercase;letter-spacing:.08em;margin-bottom:4px}")
				.append(".notes-body{font-size:var(--base);white-space:pre-wrap}")

				// 14px above rather than 22px. Measured, not chosen: the first card off staging put the
				// kitchen's signature boxes alone on a second sheet, about 10mm short of fitting — and
				// Rajeev asked for the QC signatures at the end of THAT sheet. The three headings, the
				// roster rows and the sign-off's own margin give back roughly 15mm between them, which
				// clears it. A very long roster can still push the sign-off over; the fix then is a
				// two-column crew list, not more shaving.
				.append("h2{font-size:var(--sm);font-weight:700;text-transform:uppercase;"
						+ "letter-spacing:.08em;color:var(--ink-2);margin:14px 0 8px}")
				.append("h3{font-size:var(--sm);font-weight:600;color:var(--ink-2);margin:12px 0 4px}")
				.append("p{margin:0 0 8px}")
				.append(".chips{font-size:var(--sm)}")

				.append("table{width:100%;border-collapse:collapse;margin:4px 0 8px}")
				.append("th,td{text-align:left;padding:6px 10px;border-bottom:1px solid var(--rule);"
						+ "vertical-align:top}")
				.append("thead th{background:var(--band);font-size:var(--xs);font-weight:700;"
						+ "text-transform:uppercase;letter-spacing:.08em;color:var(--ink-2);"
						+ "border-bottom:1.5px solid var(--rule-strong)}")
				.append("td.num,th.num{text-align:right;white-space:nowrap;"
						+ "font-variant-numeric:tabular-nums}")

				.append(".items td{vertical-align:middle}")
				.append(".items .name{display:block;font-weight:600;font-size:var(--lg)}")
				// The same name in the appendix's script, so a cook can carry a row on this page to a
				// page further back without reading a word of English in between.
				.append(".items .local{display:block;font-size:var(--sm);color:var(--ink-2)}")
				.append(".items th.pen{text-align:center;width:30mm}")
				.append(".items td.pen{text-align:center}")
				// Ruled, empty, and big enough to write a three-digit figure into with a biro.
				.append(".box{display:block;height:10mm;border:1px solid var(--rule-strong);"
						+ "border-radius:1mm;background:var(--paper)}")
				.append(".box.wide{width:44mm;height:14mm}")
				.append(".count{display:flex;align-items:center;gap:6mm;margin:4px 0 8px}")
				.append(".count-note{font-size:var(--sm);color:var(--ink-2)}")

				.append(".crew{font-size:var(--sm);color:var(--ink-2)}")
				// Two columns, which is what actually got the sign-off back onto the worksheet. Shaving
				// margins bought about 15mm and the sign boxes needed roughly 4mm more than that; the
				// roster is the tallest block on the sheet and halving it gives back 20mm at six staff
				// and far more at twenty. It also stops a large temple's card being mostly a phone
				// list. A row must not split across the column break — half a name in each is worse
				// than a longer list.
				.append("ul.roll{list-style:none;margin:0 0 4px;padding:0;font-size:var(--sm);"
						+ "columns:2;column-gap:8mm}")
				.append("ul.roll li{display:flex;gap:8px;padding:3px 0;break-inside:avoid;"
						+ "border-bottom:1px solid var(--rule)}")
				.append("ul.roll .who{flex:1;font-weight:500}")
				.append("ul.roll .job{flex:1;color:var(--ink-2)}")
				.append("ul.roll .phone{white-space:nowrap;font-variant-numeric:tabular-nums;"
						+ "color:var(--ink-2)}")
				.append(".none{color:var(--ink-3);font-size:var(--sm)}")

				.append(".contact-name{font-size:var(--xl);font-weight:700}")
				.append(".contact-phone{font-size:var(--xl);font-weight:700;"
						+ "font-variant-numeric:tabular-nums}")
				.append(".address{font-size:var(--lg);margin-bottom:8px}")
				.append(".sub-location{font-size:var(--lg);font-weight:700}")
				.append(".allowance-note{font-size:var(--sm);color:var(--ink-2);margin-top:-8px}")
				.append(".map{border:1px solid var(--rule-strong);border-radius:var(--r);"
						+ "overflow:hidden;margin:0 0 16px;break-inside:avoid}")
				.append(".map img{display:block;width:100%;height:auto}")

				.append(".signoff{break-inside:avoid;display:flex;gap:4mm;margin-top:14px}")
				.append(".sign{flex:1;border:1px solid var(--rule-strong);border-radius:var(--r);"
						+ "padding:10px 12px 20px}")
				.append(".sign .who{font-size:var(--sm);font-weight:700}")
				.append(".sign .what{font-size:var(--xs);color:var(--ink-2);margin-bottom:16px}")
				.append(".sign .rule{border-top:1px solid var(--ink);font-size:var(--xs);"
						+ "color:var(--ink-2);padding-top:4px;display:flex;"
						+ "justify-content:space-between}")

				.append(".recipe{break-inside:avoid;margin-bottom:16px}")
				.append(".recipe-name{font-size:var(--lg);font-weight:700}")
				.append(".recipe-name .local{font-weight:400;color:var(--ink-2);margin-left:8px}")
				.append(".untranslated{font-size:var(--xs);color:var(--ink-3)}")
				// Greys, like everything else. A prohibited ingredient is called out by weight and a
				// dark ground, which a mono printer renders and a red one does not.
				.append(".badge{font-size:var(--xs);font-weight:700;background:var(--ink);"
						+ "color:var(--paper);border-radius:1mm;padding:1px 6px;margin-left:6px;"
						+ "text-transform:uppercase;letter-spacing:.06em}")
				.append("ol.method{margin:4px 0 0 18px;padding:0;font-size:var(--sm)}")
				.append("ol.method li{margin:3px 0}")

				// The print view's running footer. Fixed, so Chromium repeats it on every sheet — a
				// page picked up on its own still says which card and which version it belongs to.
				.append("footer.running{position:fixed;bottom:0;left:0;right:0;display:flex;"
						+ "justify-content:space-between;align-items:baseline;"
						+ "border-top:1px solid var(--rule);padding-top:4px;"
						+ "color:var(--ink-2);font-size:var(--xs);background:var(--paper)}")
				.append("footer.running .card-no{font-size:var(--sm);font-weight:700;color:var(--ink);"
						+ "font-variant-numeric:tabular-nums}")
				.append("@media screen{footer.running{position:static;margin-top:12mm}}")
				.append("</style>");
	}

	// ---- Helpers --------------------------------------------------------

	private static void fact(StringBuilder h, String label, String value, String detail) {
		if (!notBlank(value)) {
			return;
		}
		h.append("<dl class=\"f\"><dt>").append(esc(label)).append("</dt><dd>").append(esc(value))
				.append("</dd>");
		if (notBlank(detail)) {
			h.append("<dd class=\"detail\">").append(esc(detail)).append("</dd>");
		}
		h.append("</dl>");
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
