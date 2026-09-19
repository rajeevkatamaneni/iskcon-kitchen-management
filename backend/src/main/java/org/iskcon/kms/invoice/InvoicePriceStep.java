package org.iskcon.kms.invoice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.UUID;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.ingredient.MarketRateService;
import org.iskcon.kms.ingredient.Unit;
import org.iskcon.kms.inventory.InventoryUnits;
import org.iskcon.kms.vendor.VendorService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The price step: what a saved invoice line does to prices (R-INV-6, R-VEN-4, R-ING-3).
 *
 * <p>This is where the ₹0 rice of §0 is closed at its source. A bill is the one document that says
 * what the temple actually paid, so each saved line that names an ingredient does three things, all in
 * the invoice's transaction:
 *
 * <ol>
 *   <li>the line's rate becomes this vendor's <strong>list price</strong> for the ingredient — per pack
 *       when the vendor's supply is sold in packs, and per stock unit always
 *       ({@link VendorService#setListPriceFromInvoice}, which also creates the vendor's supply link,
 *       not preferred, if the bill is the first news that they sell it);</li>
 *   <li>a <strong>{@code vendor_price_history}</strong> row, source {@code INVOICE}, per stock unit,
 *       naming the line (written by the same call, through {@code VendorPriceHistoryService});</li>
 *   <li>the ingredient's <strong>market rate</strong> is refreshed
 *       ({@link MarketRateService#setFromInvoiceLine}).</li>
 * </ol>
 *
 * <p>Both are dated by the bill, and a bill older than the newest price already recorded is kept as
 * history without moving the current figure (conductor's ruling for T-271; see each callee).
 *
 * <p><strong>What sets nothing.</strong> A one-off line (no ingredient) has no price to set. A line
 * billed at 0 quantity or ₹0 has no rate: "an item not billed stays at 0" is not a statement that
 * rice costs nothing, and §13 is explicit that a bill never makes a price of ₹0. The same holds for a
 * rate so small it rounds to nothing at four places.
 *
 * <p>Kept to one class and one conversion so the open question below changes one place.
 */
@Component
public class InvoicePriceStep {

	/** The scale of every per-unit rate in the schema (V144's NUMERIC(14, 4)). */
	private static final int RATE_SCALE = 4;

	private final JdbcTemplate jdbc;
	private final VendorService vendorService;
	private final MarketRateService marketRateService;

	public InvoicePriceStep(JdbcTemplate jdbc, VendorService vendorService, MarketRateService marketRateService) {
		this.jdbc = jdbc;
		this.vendorService = vendorService;
		this.marketRateService = marketRateService;
	}

	/** One saved line, as the price step needs it. {@code packCount} is null unless billed in packs. */
	record SavedLine(UUID lineId, UUID ingredientId, BigDecimal billedQty, Unit unit, UUID packSizeId,
			BigDecimal packCount, BigDecimal amount) {
	}

	/**
	 * Applies one saved line's price. Returns the rate per stock unit it set, or null when the line
	 * sets nothing.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public BigDecimal apply(AuthenticatedUser actor, UUID vendorId, LocalDate invoiceDate, SavedLine line) {
		if (line.ingredientId() == null
				|| line.billedQty().signum() <= 0
				|| line.amount().signum() <= 0) {
			return null;
		}
		Unit stockUnit = Unit.valueOf(jdbc.queryForObject(
				"SELECT canonical_unit FROM ingredients WHERE id = ?", String.class, line.ingredientId()));
		BigDecimal rate = ratePerStockUnit(line.amount(), line.billedQty(), line.unit(), stockUnit);
		if (rate.signum() <= 0) {
			return null;
		}
		BigDecimal packPrice = line.packCount() == null ? null
				: line.amount().divide(line.packCount(), 2, RoundingMode.HALF_UP);
		vendorService.setListPriceFromInvoice(actor, vendorId, line.ingredientId(), rate,
				line.packSizeId(), packPrice, line.lineId(), invoiceDate);
		marketRateService.setFromInvoiceLine(actor, line.ingredientId(), rate, line.lineId(), invoiceDate);
		return rate;
	}

	/**
	 * The line's rate, restated per one of the ingredient's stock unit.
	 *
	 * <p>The rate is before GST. Rajeev decided it on the Decisions Desk on 2026-09-19 (question Q-5):
	 * "Before GST (the line amount as typed)". So the rate is the line's Amount as typed ÷ Billed qty,
	 * and the bill's GST stays held separately on the invoice, as §12 suggested. Had he chosen the
	 * other way, this method would have been the only place to change.
	 *
	 * <p>The rate is Amount ÷ Billed qty, as R-INV-4 states it; the only step added here is the unit.
	 * A line billed in grams of an ingredient counted in Kg (₹0.06 / gm) is ₹60 / Kg, converted through
	 * {@link InventoryUnits#toBase} and nothing else — the line and the ingredient are in one family,
	 * which the invoice service has already checked. Four places, the schema's scale for rates, so
	 * ₹65 / Kg kept per gram is ₹0.0650, not ₹0.07.
	 */
	static BigDecimal ratePerStockUnit(BigDecimal amount, BigDecimal billedQty, Unit billedIn, Unit stockUnit) {
		BigDecimal inBase = InventoryUnits.toBase(billedQty, billedIn);
		BigDecimal inStockUnits = inBase.divide(BigDecimal.valueOf(stockUnit.baseFactor()), 6, RoundingMode.HALF_UP);
		return amount.divide(inStockUnits, RATE_SCALE, RoundingMode.HALF_UP);
	}
}
