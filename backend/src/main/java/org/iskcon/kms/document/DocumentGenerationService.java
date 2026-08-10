package org.iskcon.kms.document;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.ingredient.Unit;
import org.iskcon.kms.purchaseorder.PurchaseOrderService;
import org.iskcon.kms.recipe.RecipeIngredientView;
import org.iskcon.kms.recipe.RecipeService;
import org.iskcon.kms.recipe.RecipeView;
import org.iskcon.kms.recipe.ScaledLine;
import org.iskcon.kms.recipe.ScaledRecipeView;
import org.iskcon.kms.translation.RecipeTranslationService;
import org.iskcon.kms.translation.TranslatedRecipe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
	private static final DateTimeFormatter DATE =
			DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.of("Asia/Kolkata"));
	private static final DateTimeFormatter DATE_ONLY = DateTimeFormatter.ofPattern("d MMM yyyy");

	private final JdbcTemplate jdbc;
	private final RecipeService recipeService;
	private final RecipeTranslationService translationService;
	private final PurchaseOrderService purchaseOrderService;
	private final PdfRenderer pdfRenderer;
	private final DocumentStorage storage;

	public DocumentGenerationService(
			JdbcTemplate jdbc, RecipeService recipeService, RecipeTranslationService translationService,
			PurchaseOrderService purchaseOrderService, PdfRenderer pdfRenderer, DocumentStorage storage) {
		this.jdbc = jdbc;
		this.recipeService = recipeService;
		this.translationService = translationService;
		this.purchaseOrderService = purchaseOrderService;
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
			doc = jdbc.queryForMap(
					"SELECT kind, recipe_id, po_id, target_yield, language, status FROM documents WHERE id = ?",
					documentId);
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
			if ("PURCHASE_ORDER_PDF".equals(kind)) {
				html = PurchaseOrderSheetTemplate.render(buildSheetModel((UUID) doc.get("po_id"), language));
				path = "generated/purchase-orders/" + documentId + ".pdf";
			} else {
				html = RecipeCardTemplate.render(
						buildModel((UUID) doc.get("recipe_id"), (BigDecimal) doc.get("target_yield"), language));
				path = "generated/recipes/" + documentId + ".pdf";
			}
			byte[] pdf = pdfRenderer.renderPdf(html);
			String key = storage.store(path, pdf, "application/pdf");

			jdbc.update("""
					UPDATE documents
					SET status = 'READY', storage_key = ?, error = NULL, ready_at = now(), updated_at = now()
					WHERE id = ?
					""", key, documentId);
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

	private PurchaseOrderSheetTemplate.SheetModel buildSheetModel(UUID poId, String language) {
		var po = purchaseOrderService.get(poId);
		var order = po.order();
		Map<String, Object> v = jdbc.queryForMap(
				"SELECT name, address, gstin, phone FROM vendors WHERE id = ?", order.vendorId());
		var vendor = new PurchaseOrderSheetTemplate.VendorBlock(
				(String) v.get("name"), (String) v.get("address"),
				(String) v.get("gstin"), (String) v.get("phone"));

		boolean showPrices = po.lines().stream().anyMatch(l -> l.expectedPrice() != null);
		List<PurchaseOrderSheetTemplate.Line> lines = new ArrayList<>();
		BigDecimal total = BigDecimal.ZERO;
		boolean anyTotal = false;
		for (var l : po.lines()) {
			String price = null;
			if (showPrices && l.expectedPrice() != null) {
				price = money(l.expectedPrice());
				total = total.add(l.expectedPrice().multiply(l.quantity()));
				anyTotal = true;
			}
			lines.add(new PurchaseOrderSheetTemplate.Line(
					l.ingredientName(), plain(l.quantity()) + " " + l.unit(), price));
		}
		String totalText = anyTotal ? money(total) : null;

		return new PurchaseOrderSheetTemplate.SheetModel(
				templeName(),
				"Purchase Order",
				order.poNumber(),
				order.orderDate() == null ? "" : DATE_ONLY.format(order.orderDate()),
				order.neededBy() == null ? null : DATE_ONLY.format(order.neededBy()),
				vendor,
				order.deliveryLocation(),
				order.notes(),
				lines,
				showPrices,
				totalText,
				DATE.format(Instant.now()),
				PurchaseOrderSheetTemplate.Labels.english().asList());
	}

	private static String money(BigDecimal amount) {
		return "₹" + amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
	}

	private RecipeCardTemplate.CardModel buildModel(UUID recipeId, BigDecimal targetYield, String language) {
		RecipeView recipe = recipeService.get(recipeId);
		String templeName = templeName();
		String generatedOn = DATE.format(Instant.now());

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
						ingredientName(t, i, lines.get(i).ingredientName()),
						amount(lines.get(i).quantity(), Unit.valueOf(lines.get(i).unit())),
						lines.get(i).sattvicProhibited()));
			}
		} else {
			ScaledRecipeView scaled = recipeService.scale(recipeId, targetYield);
			List<ScaledLine> lines = scaled.ingredients();
			for (int i = 0; i < lines.size(); i++) {
				rows.add(new RecipeCardTemplate.Row(
						ingredientName(t, i, lines.get(i).ingredientName()),
						plain(lines.get(i).displayQuantity()) + " " + lines.get(i).displayUnit(),
						lines.get(i).sattvicProhibited()));
			}
		}

		String yieldText = targetYield == null
				? "Yields %s %s".formatted(plain(recipe.baseYieldQty()), yieldUnit(recipe.baseYieldUnit()))
				: "Scaled to %s %s (base %s)".formatted(
						plain(targetYield), yieldUnit(recipe.baseYieldUnit()), plain(recipe.baseYieldQty()));

		return new RecipeCardTemplate.CardModel(templeName, recipeName, categoryName,
				yieldText, recipe.sattvicOverrideReason(), rows, method, recipe.notes(), generatedOn);
	}

	/** The translated ingredient name for a line when translating, else the English name. */
	private static String ingredientName(TranslatedRecipe t, int index, String fallback) {
		if (t != null && index < t.ingredientNames().size()) {
			return t.ingredientNames().get(index);
		}
		return fallback;
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

	private static String amount(BigDecimal qty, Unit unit) {
		return plain(qty) + " " + unit.label();
	}

	private static String plain(BigDecimal value) {
		return value == null ? "" : value.stripTrailingZeros().toPlainString();
	}

	private static String yieldUnit(String baseYieldUnit) {
		return "SERVINGS".equals(baseYieldUnit) ? "servings" : "litres";
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
