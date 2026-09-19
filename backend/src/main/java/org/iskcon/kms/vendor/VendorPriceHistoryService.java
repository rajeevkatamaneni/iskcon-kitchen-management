package org.iskcon.kms.vendor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one writer of {@code vendor_price_history} (R-VEN-3, R-VEN-4, V144 section 7).
 *
 * <p>Every change to a vendor's list price for an ingredient leaves one row here: the price per
 * canonical unit, the day it took effect, where it came from, and who set it. The arrows beside a
 * List price compare the newest row with the one before it, so the whole feature depends on every
 * price change arriving through this class and nothing else. That is why it is a service of its own
 * rather than a private method on {@link VendorService}: the invoice task (stage 6, source
 * {@link Source#INVOICE}) calls {@link #record} too, and a second hand-written INSERT somewhere
 * else would be a second opinion about what counts as a change.
 *
 * <p><strong>What counts as a change.</strong> A row is written when the price differs from the
 * newest row already held for the pair, or when there is no row yet. "Differs" means the per-unit
 * price, or the pack it was quoted in, or the price per that pack. The per-unit figure is what the
 * arrows compare, but a vendor who moves from "Bag = 25 Kg at ₹1,500" to "Bag = 50 Kg at ₹3,000" has
 * told the temple something new even though ₹60 / Kg did not move, and the history would otherwise
 * say the old bag is still the offer. That row reads as a flat dash, which is the truth.
 *
 * <p>The comparison is with the history, not with {@code vendor_supplies.last_price}, on purpose:
 * the history is the record of what the temple was told, and {@code ReceivingService} still
 * rewrites {@code last_price} from a delivery's price without passing through here (removed by
 * R-DEL-5, a later stage). Comparing with the column would let that path decide whether a typed
 * price gets recorded.
 *
 * <p>Nothing is written for a price that is absent: the column is NOT NULL, and "no price" is not a
 * price the arrows can compare against. Clearing a list price therefore leaves the history as it
 * was, and the next price typed is compared with the last one there was.
 *
 * <p>Rows written here always name who set the price. The only rows without one are the prices
 * V145 copied from {@code vendor_supplies.last_price}, marked {@code backfilled}, because nobody
 * recorded who typed a price before the history existed. Those count as "the last recorded price"
 * here like any other.
 *
 * <p>Reading the previous price is a join in {@code VendorService}'s supply query, ordered exactly
 * as the comparison below: newest effective date first, then newest written.
 *
 * <p>Append-only in the database (make_append_only). A wrong price is corrected by a new one.
 */
@Service
public class VendorPriceHistoryService {

	/** Where a list price came from. The names are the values V144's CHECK admits. */
	public enum Source {
		/** Typed while a vendor's list was first entered, in the bulk onboarding table (R-VEN-1). */
		ONBOARDING,
		/** Typed on the vendor page or the ingredient page afterwards. */
		MANUAL,
		/** Taken from a saved invoice line (R-VEN-4, stage 6). Must name the line. */
		INVOICE
	}

	private final JdbcTemplate jdbc;

	public VendorPriceHistoryService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Records a list price for a vendor and ingredient, if it is a change.
	 *
	 * <p>MANDATORY propagation: a price row belongs to the write that changed the price and must
	 * commit or roll back with it. Called outside a transaction it would be a price recorded for a
	 * supply that may never have been saved, so it refuses to run at all.
	 *
	 * @param pricePerUnit rupees per one canonical unit. Null writes nothing.
	 * @param pricePerPack the price per pack when quoted per pack, else null
	 * @param packDescription the pack as it reads today ("Bag = 25 Kg"), exactly when
	 *     {@code pricePerPack} is set
	 * @param invoiceLineId the invoice line that set it: required for {@link Source#INVOICE}, and
	 *     null for anything typed
	 * @param setBy the user who set it
	 * @return true when a row was written
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean record(
			UUID vendorId,
			UUID ingredientId,
			BigDecimal pricePerUnit,
			BigDecimal pricePerPack,
			String packDescription,
			LocalDate effectiveOn,
			Source source,
			UUID invoiceLineId,
			UUID setBy) {
		if (pricePerUnit == null) {
			return false;
		}
		// Only V145's backfilled rows may lack who set the price; the column allows null for them
		// alone, and a runtime write without one would pass the CHECK only by lying about being one.
		Objects.requireNonNull(setBy, "a recorded price names who set it");
		List<Object[]> latest = jdbc.query("""
				SELECT price_per_unit, price_per_pack, pack_description
				FROM vendor_price_history
				WHERE vendor_id = ? AND ingredient_id = ?
				ORDER BY effective_on DESC, created_at DESC
				LIMIT 1
				""", (rs, n) -> new Object[] {
						rs.getBigDecimal("price_per_unit"),
						rs.getBigDecimal("price_per_pack"),
						rs.getString("pack_description")},
				vendorId, ingredientId);
		if (!latest.isEmpty()
				&& sameAmount((BigDecimal) latest.get(0)[0], pricePerUnit)
				&& sameAmount((BigDecimal) latest.get(0)[1], pricePerPack)
				&& Objects.equals(latest.get(0)[2], packDescription)) {
			return false;
		}
		jdbc.update("""
				INSERT INTO vendor_price_history (
					tenant_id, vendor_id, ingredient_id, price_per_unit, price_per_pack,
					pack_description, effective_on, source, vendor_invoice_line_id, set_by)
				VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid,
						?, ?, ?, ?, ?, ?, ?, ?, ?)
				""", vendorId, ingredientId, pricePerUnit, pricePerPack, packDescription,
				effectiveOn, source.name(), invoiceLineId, setBy);
		return true;
	}

	/**
	 * compareTo, never equals: the stored price comes back at the column's scale (60.0000) and the
	 * typed one at whatever scale JSON gave it (60), and equals would call those a change.
	 */
	private static boolean sameAmount(BigDecimal a, BigDecimal b) {
		return a == null ? b == null : b != null && a.compareTo(b) == 0;
	}
}
