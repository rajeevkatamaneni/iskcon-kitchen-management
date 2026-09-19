package org.iskcon.kms.document;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.ingredient.Quantities;
import org.iskcon.kms.ingredient.Unit;
import org.iskcon.kms.purchaseorder.PurchaseOrderService;
import org.iskcon.kms.recipe.RecipeIngredientView;
import org.iskcon.kms.translation.GlossaryService;
import org.iskcon.kms.translation.TranslationProvider;
import org.iskcon.kms.recipe.RecipeService;
import org.iskcon.kms.recipe.RecipeView;
import org.iskcon.kms.recipe.ScaledLine;
import org.iskcon.kms.recipe.ScaledRecipeView;
import org.iskcon.kms.translation.RecipeTranslationService;
import org.iskcon.kms.translation.TranslatedRecipe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.iskcon.kms.tenancy.TempleClock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Turns a PENDING document into a rendered file (E2-S5). This is the worker-side core the background
 * job calls; kept separate so it can be tested synchronously without Quartz.
 *
 * <p>Idempotent, as every job must be: a document already READY is left alone, and a re-run
 * overwrites the same storage key. Renders the recipe card (base or scaled), produces the PDF via
 * the configured {@link PdfRenderer}, stores it via the configured {@link DocumentStorage}, and
 * moves the row to READY — or FAILED with the reason if anything goes wrong.
 */
@Service
public class DocumentGenerationService {

	private static final Logger log = LoggerFactory.getLogger(DocumentGenerationService.class);
	/**
	 * One date format, zoned where it is used rather than here.
	 *
	 * <p>There were two of these — one carrying {@code .withZone(Asia/Kolkata)} for instants and one
	 * without for dates — and stripping the fixed zone left them character for character identical.
	 * A zone belongs to the temple whose sheet is being printed, so every instant is zoned at the
	 * point of formatting and a plain LocalDate needs no zone at all.
	 *
	 * <p>The format itself is {@link DisplayDates#DAY}, shared with every other document and the
	 * WhatsApp messages (T-312). T-310 pinned it to British English here, so the sheet writes
	 * "20 Sept 2026" as the screens do; {@code DisplayDates} says why.
	 */
	private static final DateTimeFormatter DATE = DisplayDates.DAY;


	private final TempleClock clock;
	private final JdbcTemplate jdbc;
	private final RecipeService recipeService;
	private final RecipeTranslationService translationService;
	private final PurchaseOrderService purchaseOrderService;
	private final GlossaryService glossaryService;
	private final TranslationProvider translationProvider;
	private final PurchaseOrderLabelTranslator labelTranslator;
	private final JobCardService jobCardService;
	private final WorkOrderService workOrderService;
	private final org.iskcon.kms.donation.DonationReceiptService donationReceiptService;
	private final PdfRenderer pdfRenderer;
	private final DocumentStorage storage;

	public DocumentGenerationService(
			JdbcTemplate jdbc, RecipeService recipeService, RecipeTranslationService translationService,
			PurchaseOrderService purchaseOrderService, GlossaryService glossaryService,
			TranslationProvider translationProvider, PurchaseOrderLabelTranslator labelTranslator,
			JobCardService jobCardService, WorkOrderService workOrderService,
			org.iskcon.kms.donation.DonationReceiptService donationReceiptService,
			PdfRenderer pdfRenderer, DocumentStorage storage, TempleClock clock) {
		this.clock = clock;
		this.jobCardService = jobCardService;
		this.workOrderService = workOrderService;
		this.donationReceiptService = donationReceiptService;
		this.jdbc = jdbc;
		this.recipeService = recipeService;
		this.translationService = translationService;
		this.purchaseOrderService = purchaseOrderService;
		this.glossaryService = glossaryService;
		this.translationProvider = translationProvider;
		this.labelTranslator = labelTranslator;
		this.pdfRenderer = pdfRenderer;
		this.storage = storage;
	}

	// Deliberately not @Transactional: the success path is a single UPDATE, and the reads go through
	// RecipeService's own read-only transactions. A surrounding transaction would be marked
	// rollback-only by an exception thrown from one of those reads, and the "mark FAILED" write in
	// the catch could then never commit.
	public void generate(UUID documentId) {
		Map<String, Object> doc;
		try {
			doc = jdbc.queryForMap("""
					SELECT kind, recipe_id, po_id, meal_id, ingredient_request_id, donation_id,
						   target_yield, language, status
					FROM documents WHERE id = ?
					""", documentId);
		} catch (org.springframework.dao.EmptyResultDataAccessException e) {
			// RLS-hidden or gone — nothing to do.
			log.warn("Document {} not visible for generation", documentId);
			return;
		}
		if ("READY".equals(doc.get("status"))) {
			return;
		}

		String kind = (String) doc.get("kind");
		String language = (String) doc.get("language");

		try {
			String html;
			String path;
			// Only the job card asks for one: it is the only document here that runs to several pages
			// which are then physically separated, and a loose sheet has to say which card and which
			// version it belongs to. See JobCardService.renderForPdf.
			PdfRenderer.Footer footer = null;
			if ("PURCHASE_ORDER_PDF".equals(kind)) {
				html = PurchaseOrderSheetTemplate.render(buildSheetModel((UUID) doc.get("po_id"), language));
				path = "generated/purchase-orders/" + documentId + ".pdf";
			} else if ("JOB_CARD_PDF".equals(kind)) {
				JobCardService.RenderedCard card =
						jobCardService.renderForPdf((UUID) doc.get("meal_id"), language);
				html = card.html();
				footer = card.footer();
				path = "generated/job-cards/" + documentId + ".pdf";
			} else if ("WORK_ORDER_PDF".equals(kind)) {
				html = workOrderService.render((UUID) doc.get("ingredient_request_id"), language);
				path = "generated/work-orders/" + documentId + ".pdf";
			} else if ("DONATION_RECEIPT_PDF".equals(kind)) {
				// No language branch, unlike every kind above it. A receipt is a tax document read in
				// English by an assessing officer, and its statutory sentence is not something to put
				// through machine translation — see DonationReceiptTemplate's header.
				html = donationReceiptService.render((UUID) doc.get("donation_id"));
				path = "generated/donation-receipts/" + documentId + ".pdf";
			} else {
				html = RecipeCardTemplate.render(
						buildModel((UUID) doc.get("recipe_id"), (BigDecimal) doc.get("target_yield"), language));
				path = "generated/recipes/" + documentId + ".pdf";
			}
			byte[] pdf = pdfRenderer.renderPdf(html, footer);
			String key = storage.store(path, pdf, "application/pdf");

			// Provenance: the MT engine that handled non-English text, null for an English sheet. A
			// job card asking for the worksheet alone carries no translated text either, so its
			// sentinel language counts as English here.
			String translationProvider =
					isEnglish(language) || JobCardService.WORKSHEET_ONLY.equals(language)
							? null : this.translationProvider.name();
			jdbc.update("""
					UPDATE documents
					SET status = 'READY', storage_key = ?, translation_provider = ?, error = NULL,
						ready_at = now(), updated_at = now()
					WHERE id = ?
					""", key, translationProvider, documentId);
			log.info("Document {} generated ({} bytes)", documentId, pdf.length);

		} catch (RuntimeException e) {
			log.error("Document {} generation failed", documentId, e);
			jdbc.update("""
					UPDATE documents SET status = 'FAILED', error = ?, updated_at = now() WHERE id = ?
					""", trim(e.getMessage()), documentId);
			// Not rethrown: the failure is recorded on the row; a PDF failure is usually a data
			// problem, not a transient one, so the user re-requests rather than us blindly retrying.
		}
	}

	/**
	 * Renders a PO sheet to HTML directly (E5-S4), for the browser print view — no PDF, no worker.
	 * The same template the PDF is built from, so print and PDF are the same document.
	 */
	public String renderPurchaseOrderHtml(UUID purchaseOrderId, String language) {
		return PurchaseOrderSheetTemplate.render(buildSheetModel(purchaseOrderId, language));
	}

	/**
	 * Renders a job card to HTML directly (B5), for the browser print view — no PDF, no worker. The
	 * same template the PDF is built from, so what is printed and what is filed are the same sheet.
	 */
	public String renderJobCardHtml(UUID mealId, String language) {
		return jobCardService.render(mealId, language);
	}

	/**
	 * Renders a work order to HTML directly (E10-S11), for the browser print view — no PDF, no
	 * worker. The same template the PDF is built from, so the sheet carried round the store room and
	 * the sheet filed afterwards are the same sheet.
	 */
	public String renderWorkOrderHtml(UUID ingredientRequestId, String language) {
		return workOrderService.render(ingredientRequestId, language);
	}

	private PurchaseOrderSheetTemplate.SheetModel buildSheetModel(UUID poId, String language) {
		var po = purchaseOrderService.get(poId);
		var order = po.order();
		Map<String, Object> v = jdbc.queryForMap(
				"SELECT name, address, gstin, phone FROM vendors WHERE id = ?", order.vendorId());
		var vendor = new PurchaseOrderSheetTemplate.VendorBlock(
				(String) v.get("name"), (String) v.get("address"),
				(String) v.get("gstin"), (String) v.get("phone"));

		boolean showPrices = po.lines().stream().anyMatch(l -> l.expectedPrice() != null);

		// Translate ingredient names (glossary first, MT for the rest), notes and delivery location.
		// Numbers, dates, the PO number, units and prices are never sent to translation.
		List<String> ingredientNames = translateLines(po.lines(), language);
		String notes = translateFreeText(order.notes(), language);
		String deliveryLocation = translateFreeText(order.deliveryLocation(), language);

		List<PurchaseOrderSheetTemplate.Line> lines = new ArrayList<>();
		BigDecimal total = BigDecimal.ZERO;
		boolean anyTotal = false;
		for (int i = 0; i < po.lines().size(); i++) {
			var l = po.lines().get(i);
			// A sheet somebody carries to a vendor and buys against, so the cook's form — and the
			// unit written the way it is said, rather than the name the column happens to store it as.
			// 2792 gm is said "3 Kg" (R-SL-1: nothing from 1,000 g/ml up in g/ml).
			String amount = Quantities.cooks(l.quantity(), l.unit());
			// A line ordered in a pack reads as the vendor sells it: "4 × Bag (25 Kg)" (R-SL-3).
			// The stock-unit amount (100 Kg) stays on the line for receiving and costing; the
			// vendor is asked for the bags.
			String quantityText = l.packCount() == null ? amount
					: say(l.packCount()) + " × " + l.packLabel();
			String price = null;
			if (showPrices && l.expectedPrice() != null) {
				// The rate names the unit it is a rate for. It never did, which went unnoticed while
				// the quantity beside it was always printed in that same stored unit — the reader
				// could infer it. Now that a 0.6 Kg line reads "600 gm", inferring it gives the
				// wrong answer by a factor of a thousand, so the sheet says it: "₹45 / Kg".
				// Untranslated, like every other number and unit on this sheet.
				//
				// A rate is a price for ONE of the unit, so the unit is asked for its word at a count
				// of one: "₹80 / piece", not "₹80 / pieces" (T-148). That is not a trick to reach the
				// singular — the one is really there, it is the "per" read aloud. Kg, gm, L and ml
				// have one word at every count, so a KG line still reads "₹45 / Kg".
				//
				// T-260, conductor's ruling 2026-09-19: the rate is said per the unit the quantity is
				// SHOWN in, not the one it is stored in. A 2792 gm line reads "3 Kg", so its rate reads
				// "₹71.20 / Kg" and not "₹0.07 / gm" — a figure two places cannot even hold since
				// expected_price went to four (V146), and a unit the reader would have to convert
				// against the Kg beside it. Same money formatter as every other figure on the sheet.
				//
				// T-288 (VERIFY-B D-8) finished that thought: a rate is said per the READABLE unit,
				// which for a mass or a volume is always Kg or L, whatever the quantity beside it.
				// Following the quantity alone left a single 500 gm tea pack reading "₹250 / 500 gm ·
				// ₹0.50 / gm" on the same sheet as pepper at "₹800 / Kg" — two ways of saying one kind
				// of price, and the per-gram one is a figure nobody in a market quotes. See rateUnit.
				//
				// A pack line shows both, exactly as the vendor page and the invoice word a pack
				// price: "₹1,500 / bag · ₹60 / Kg" (same ruling: "Same label in every view").
				//
				// Every figure goes through money(), which writes rupees the way the screen does
				// (T-268): Indian grouping, paise only where there are any. See SheetRupees.
				Unit stored = Unit.valueOf(l.unit());
				Unit shown = rateUnit(stored);
				String perUnit = money(l.expectedPrice().multiply(BigDecimal.valueOf(shown.baseFactor()))
						.divide(BigDecimal.valueOf(stored.baseFactor()), 6, java.math.RoundingMode.HALF_UP))
						+ " / " + shown.label(BigDecimal.ONE);
				price = l.packCount() == null || l.packQuantity() == null ? perUnit
						: money(l.expectedPrice().multiply(l.packQuantity())) + " / " + packWord(l.packLabel())
								+ " · " + perUnit;
				total = total.add(l.expectedPrice().multiply(l.quantity()));
				anyTotal = true;
			}
			lines.add(new PurchaseOrderSheetTemplate.Line(ingredientNames.get(i), quantityText, price));
		}
		String totalText = anyTotal ? money(total) : null;

		// Labels are translated through the same glossary + MT path as the content (E5-S5), so a
		// sheet renders in any language offered, not just a hand-curated few. Index 0 is the title.
		List<String> labels = labelTranslator.labels(language);
		return new PurchaseOrderSheetTemplate.SheetModel(
				templeName(),
				labels.get(0),
				order.poNumber(),
				order.orderDate() == null ? "" : DATE.format(order.orderDate()),
				// Null on a draft, which has reached nobody yet. The sheet leaves the line out rather
				// than printing a blank beside a label.
				order.sentAt() == null ? null : DATE.format(order.sentAt().atZone(clock.zone())),
				order.neededBy() == null ? null : DATE.format(order.neededBy()),
				vendor,
				deliveryLocation,
				notes,
				lines,
				showPrices,
				totalText,
				DATE.format(Instant.now().atZone(clock.zone())),
				labels);
	}

	/**
	 * Line subjects for the sheet: glossary override first, then one MT batch for the rest.
	 *
	 * <p>The <em>subject</em>, not the ingredient name (T-024). A PO line may describe something the
	 * catalogue has never heard of — four plastic stools — in which case {@code ingredientName()} is
	 * null and {@code description()} carries the words. Two things went wrong with the null before
	 * this coalesce, and only one of them was loud: the glossary lookup below calls
	 * {@code toLowerCase()} on each entry and would have thrown NPE on any non-English sheet, and
	 * the English path would have rendered the line as a blank cell — the template escapes null to
	 * "" (PurchaseOrderSheetTemplate.esc) — so a vendor would have been handed a sheet with a
	 * quantity, a price and nothing saying what to bring.
	 *
	 * <p>A description goes through the same glossary-then-MT path as an ingredient name rather than
	 * being left in English. It is free text a person typed, like the notes and the delivery
	 * location beside it, and a sheet a Kannada-speaking vendor buys against should read as one
	 * document. The glossary will not usually have an entry for "Plastic stool", so it falls to MT,
	 * which is the same route those two already take.
	 */
	private List<String> translateLines(List<org.iskcon.kms.purchaseorder.PurchaseOrderLineView> poLines,
			String language) {
		List<String> names = new ArrayList<>();
		for (var l : poLines) {
			names.add(l.subject());
		}
		if (isEnglish(language) || names.isEmpty()) {
			return names;
		}
		Map<String, String> glossary = glossaryService.lookup(language);
		String[] out = new String[names.size()];
		List<String> mt = new ArrayList<>();
		int[] mtIndex = new int[names.size()];
		for (int i = 0; i < names.size(); i++) {
			String override = glossary.get(names.get(i).toLowerCase());
			if (override != null) {
				out[i] = override;
				mtIndex[i] = -1;
			} else {
				mtIndex[i] = mt.size();
				mt.add(names.get(i));
			}
		}
		if (!mt.isEmpty()) {
			List<String> translated = translationProvider.translate(mt, "en", language);
			for (int i = 0; i < names.size(); i++) {
				if (mtIndex[i] >= 0) {
					out[i] = translated.get(mtIndex[i]);
				}
			}
		}
		return List.of(out);
	}

	private String translateFreeText(String text, String language) {
		if (isEnglish(language) || text == null || text.isBlank()) {
			return text;
		}
		return translationProvider.translate(List.of(text), "en", language).get(0);
	}

	private static boolean isEnglish(String language) {
		return language == null || language.isBlank() || "en".equalsIgnoreCase(language);
	}

	/**
	 * Every rupee figure on the purchase order sheet: line rates, pack prices and the total. Written
	 * the screen's way since T-268, "₹1,500" and "₹71.20" where it used to print "₹1500.00", because
	 * the vendor page and the paper the vendor is handed must name the same price the same way. The
	 * PO sheet is the only document with money on it built here: the recipe card, job card and work
	 * order carry none, and the donation receipt is handed its amount already formatted.
	 */
	private static String money(BigDecimal amount) {
		return SheetRupees.format(amount);
	}

	/**
	 * The unit a rate is said per: Kg for anything weighed, L for anything poured, and a piece for
	 * anything counted — "₹60 / Kg" for curry leaves kept in grams, "₹500 / Kg" for a 500 gm tea
	 * pack, never "₹0.06 / gm" (T-288, VERIFY-B D-8; the conductor's rule, 2026-09-19).
	 *
	 * <p>Why the large unit and not the unit the quantity happens to be printed in, which is what
	 * T-260 did: a quantity below 1,000 gm is rightly printed in gm ("500 gm"), but a price per gram
	 * is a figure of a few paise that two places round to a different price, and no vendor quotes
	 * one. The screens say the same — the vendor page, the create form's type-ahead and the shared
	 * {@code ratePerReadableUnit} in {@code frontend/lib/format.ts} all state a gm or ml price per Kg
	 * or L — so the paper the vendor is handed names the price the way the screen did.
	 */
	private static Unit rateUnit(Unit stored) {
		return switch (stored) {
			case GM, KG -> Unit.KG;
			case ML, L -> Unit.L;
			case PIECES -> Unit.PIECES;
		};
	}

	/**
	 * The word a pack's price is "per": "bag" for "Bag (25 Kg)", and the size itself, "500 gm", for a
	 * pack with no name — the vendor page's rule (its {@code packWord}), so a pack price reads the same
	 * on the sheet as on the screen: "₹1,500 / bag".
	 */
	private static String packWord(String packLabel) {
		int at = packLabel.indexOf(" (");
		return at > 0 ? packLabel.substring(0, at).toLowerCase(java.util.Locale.ROOT) : packLabel;
	}

	/**
	 * A pack count as a person writes it: 4, not 4.000; Indian grouping, as every figure on the sheet.
	 * Through {@link IndianNumbers} since T-279, because the JDK's en-IN NumberFormat it used before
	 * groups in threes and would have printed 1,00,000 packets as "100,000".
	 */
	private static String say(BigDecimal count) {
		return IndianNumbers.group(count, 0, 3);
	}

	/**
	 * The recipe card's content. Package-private, not private, so that DocumentGenerationIT can read
	 * what the card says: the stub renderer keeps only the HTML's length, and whether the
	 * preparation note reached the card (R-DUP-1) is a fact about the words.
	 */
	RecipeCardTemplate.CardModel buildModel(UUID recipeId, BigDecimal targetYield, String language) {
		RecipeView recipe = recipeService.get(recipeId);
		String templeName = templeName();
		String generatedOn = DATE.format(Instant.now().atZone(clock.zone()));

		boolean translated = language != null && !language.isBlank() && !"en".equalsIgnoreCase(language);
		TranslatedRecipe t = translated ? translationService.translate(recipeId, language) : null;

		String recipeName = translated ? t.name() : recipe.name();
		String categoryName = translated ? t.categoryName() : recipe.categoryName();
		List<String> method = translated ? t.method() : splitMethod(recipe.method());

		List<RecipeCardTemplate.Row> rows = new ArrayList<>();
		if (targetYield == null) {
			List<RecipeIngredientView> lines = recipe.ingredients();
			for (int i = 0; i < lines.size(); i++) {
				rows.add(new RecipeCardTemplate.Row(
						RecipeIngredientView.withPreparation(
								ingredientName(t, i, lines.get(i).ingredientName()),
								preparationNote(t, i, lines.get(i).preparationNote())),
						Quantities.cooks(lines.get(i).quantity(), lines.get(i).unit())));
			}
		} else {
			ScaledRecipeView scaled = recipeService.scale(recipeId, targetYield);
			List<ScaledLine> lines = scaled.ingredients();
			for (int i = 0; i < lines.size(); i++) {
				// Off the raw quantity, not the scaler's own display pair: both promote a unit, but
				// only this one rounds the way a cook rounds, and a card that agreed with the scaler
				// and disagreed with the job card would be the same fault in a new place.
				rows.add(new RecipeCardTemplate.Row(
						RecipeIngredientView.withPreparation(
								ingredientName(t, i, lines.get(i).ingredientName()),
								preparationNote(t, i, lines.get(i).preparationNote())),
						Quantities.cooks(lines.get(i).rawQuantity(), lines.get(i).rawUnit())));
			}
		}

		// The base yield carries its unit even though the target has just said it: scaling can move
		// the two into different units of the one family — 2 L made from a base of 500 ml — and a
		// bare "(base 500)" would read as half a litre.
		String yieldText = targetYield == null
				? "Yields %s".formatted(Quantities.cooks(recipe.baseYieldQty(), recipe.baseYieldUnit()))
				: "Scaled to %s (base %s)".formatted(
						Quantities.cooks(targetYield, recipe.baseYieldUnit()),
						Quantities.cooks(recipe.baseYieldQty(), recipe.baseYieldUnit()));

		return new RecipeCardTemplate.CardModel(templeName, recipeName, categoryName,
				yieldText, rows, method, recipe.notes(), generatedOn);
	}

	/**
	 * The translated ingredient name for a line when translating, else the English name.
	 *
	 * <p>The name only. The line's preparation note, from {@link #preparationNote}, is added after
	 * it by the callers above, in the one printed form "Green chilli · slit" (R-DUP-1).
	 */
	private static String ingredientName(TranslatedRecipe t, int index, String fallback) {
		if (t != null && index < t.ingredientNames().size()) {
			return t.ingredientNames().get(index);
		}
		return fallback;
	}

	/** The translated preparation note for a line when translating, else the note as written. */
	private static String preparationNote(TranslatedRecipe t, int index, String fallback) {
		return t == null ? fallback : t.preparationNote(index, fallback);
	}

	private String templeName() {
		try {
			return jdbc.queryForObject("""
					SELECT name FROM tenants WHERE id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
					""", String.class);
		} catch (RuntimeException e) {
			return "Temple";
		}
	}

	private static List<String> splitMethod(String method) {
		if (method == null || method.isBlank()) {
			return List.of();
		}
		List<String> steps = new ArrayList<>();
		for (String line : method.split("\\R")) {
			if (!line.isBlank()) {
				steps.add(line.trim());
			}
		}
		return steps;
	}

	private static String trim(String message) {
		if (message == null) {
			return "Generation failed.";
		}
		return message.length() > 500 ? message.substring(0, 500) : message;
	}
}
