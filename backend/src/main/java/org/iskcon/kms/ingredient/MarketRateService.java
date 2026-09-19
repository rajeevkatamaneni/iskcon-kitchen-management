package org.iskcon.kms.ingredient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.iskcon.kms.auth.AuthenticatedUser;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.tenancy.TempleClock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * An ingredient's market rate: what it would cost to buy today, in rupees per one of its canonical
 * (stock) unit (R-ING-3, PROCUREMENT-REQUIREMENTS.md).
 *
 * <p><strong>The only writer of it.</strong> The rate is three columns on {@code ingredients}
 * ({@code market_rate}, {@code market_rate_on}, {@code market_rate_source}) that V144 holds to be all
 * set or all null, and one append-only row in {@code ingredient_market_rate_history} for every change.
 * Two writes that must always happen together belong in one method, or the day comes when someone
 * updates the column and forgets the history, and "how did it get to ₹64?" has no answer. Every way a
 * rate is set comes through {@link #set}:
 *
 * <ul>
 *   <li>{@link Source#MANUAL} — typed on the ingredient's page ({@link MarketRateController});</li>
 *   <li>{@link Source#STOCK_TAKE} — the "What it would cost to buy today" box on "Add to inventory"
 *       and on a count correction that adds stock ({@code InventoryItemService.adjust});</li>
 *   <li>{@link Source#INVOICE} — each saved invoice line (R-INV-6), which names the line it came from.
 *       The invoice service calls {@link #setFromInvoiceLine}, dated by the bill (T-271).</li>
 * </ul>
 *
 * <p><strong>Why it exists at all.</strong> "Issued from the temple store" costed the rice the Deity
 * Kitchen received at ₹0, because only 4 of 232 ingredients had a vendor price. The market rate is the
 * last line of the costing fallback in {@code BasketCostingService} — preferred vendor, then any
 * vendor, then this — so stock the temple is actually holding always has a figure. Hence the one rule
 * here that the database also enforces: <strong>strictly positive</strong>. A market rate of ₹0 is
 * the exact defect this work exists to remove, so it is refused rather than stored.
 *
 * <p><strong>Tenant.</strong> The ingredient is found under RLS, so an id from another temple is
 * simply not found; the history row's {@code tenant_id} comes from the session setting, never from
 * the caller.
 */
@Service
public class MarketRateService {

	/**
	 * Where a market rate came from. Mirrors V144's CHECK on both {@code ingredients.market_rate_source}
	 * and {@code ingredient_market_rate_history.source}.
	 */
	public enum Source {
		STOCK_TAKE,
		INVOICE,
		MANUAL
	}

	/**
	 * The column is NUMERIC(14, 4): a rate per gram or millilitre lives in fractions of a paisa (₹65/Kg is
	 * ₹0.065/gm), and V144 widened it for exactly that. Rounding here, once, means what is stored and what
	 * the history says are the same figure, and a rate so small it rounds to nothing is caught as zero
	 * before the database's CHECK has to.
	 */
	private static final int RATE_SCALE = 4;

	private final JdbcTemplate jdbc;
	private final TempleClock clock;

	public MarketRateService(JdbcTemplate jdbc, TempleClock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	/**
	 * Sets an ingredient's market rate and appends its history row, in the caller's transaction.
	 *
	 * @param ratePerCanonicalUnit rupees per one of the ingredient's canonical unit; must be positive
	 * @param source               why it changed; not {@link Source#INVOICE}, which must name its line
	 * @return the rate as stored (rounded to four places)
	 * @throws ApplicationException {@link ErrorCode#STOCK_VALUE_REQUIRED} for a missing, zero or
	 *                              negative rate; {@link ErrorCode#RESOURCE_NOT_FOUND} for an ingredient
	 *                              this temple does not have
	 */
	@Transactional
	public BigDecimal set(AuthenticatedUser actor, UUID ingredientId, BigDecimal ratePerCanonicalUnit,
			Source source) {
		if (source == Source.INVOICE) {
			// V144 refuses an INVOICE row without its line, and would say so as a constraint violation;
			// the programming error is named here instead, where it was made.
			throw new IllegalArgumentException("An INVOICE market rate must name its invoice line");
		}
		return write(actor, ingredientId, ratePerCanonicalUnit, source, null);
	}

	/**
	 * The invoice work's entry point (R-ING-3: "every saved invoice line also refreshes the market
	 * rate"). The line's derived rate, per canonical unit, becomes the market rate, and the history row
	 * points at the line, as V144's shape constraint requires of every INVOICE row.
	 *
	 * <p><strong>Dated by the bill, not by the day it was keyed</strong> (conductor's ruling for T-271,
	 * 2026-09-19, on the builder's proposal). A bill is evidence of what the ingredient cost on the day
	 * the vendor charged it, so {@code effectiveOn} is the invoice date. It follows that a bill entered
	 * late — dated 1 Sept, keyed after one dated 10 Sept — is history, not news: its row goes into
	 * {@code ingredient_market_rate_history} in its date's place, and the ingredient's current rate
	 * (the three {@code market_rate*} columns) is left as the later evidence set it. Only a bill dated
	 * on or after the current rate's date moves it. Without that guard, keying a pile of old bills in
	 * date-reverse order would leave the oldest price standing as "what it would cost to buy today".
	 *
	 * <p>A stock-take or a typed rate is dated the temple's today ({@link #set}), so a bill dated
	 * yesterday does not overwrite a count made this morning. That is the same rule read the same way:
	 * the latest evidence by date wins.
	 *
	 * @return true when the ingredient's current market rate was moved; false when only the history
	 *     row was written because a later-dated rate already stands
	 */
	@Transactional
	public boolean setFromInvoiceLine(AuthenticatedUser actor, UUID ingredientId,
			BigDecimal ratePerCanonicalUnit, UUID vendorInvoiceLineId, LocalDate effectiveOn) {
		if (vendorInvoiceLineId == null) {
			throw new IllegalArgumentException("An INVOICE market rate must name its invoice line");
		}
		if (effectiveOn == null) {
			throw new IllegalArgumentException("An INVOICE market rate is dated by its bill");
		}
		BigDecimal stored = normalise(ratePerCanonicalUnit);
		if (stored == null) {
			throw new ApplicationException(ErrorCode.STOCK_VALUE_REQUIRED, Map.of("field", "pricePerUnit"));
		}
		// Moves the current rate only when nothing later-dated stands; reports whether the row exists
		// at all, so an ingredient this temple cannot see is still refused as not found.
		List<Boolean> current = jdbc.query("""
				SELECT market_rate_on IS NULL OR market_rate_on <= ? AS current
				FROM ingredients WHERE id = ?
				""", (rs, n) -> rs.getBoolean("current"), effectiveOn, ingredientId);
		if (current.isEmpty()) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("ingredientId", ingredientId));
		}
		boolean moved = current.get(0);
		if (moved) {
			jdbc.update("""
					UPDATE ingredients
					SET market_rate = ?, market_rate_on = ?, market_rate_source = ?
					WHERE id = ?
					""", stored, effectiveOn, Source.INVOICE.name(), ingredientId);
		}
		jdbc.update("""
				INSERT INTO ingredient_market_rate_history (
					tenant_id, ingredient_id, rate, effective_on, source, vendor_invoice_line_id, set_by)
				VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?, ?)
				""", ingredientId, stored, effectiveOn, Source.INVOICE.name(), vendorInvoiceLineId,
				actor.getUserId());
		return moved;
	}

	/**
	 * What the stock-take box starts with: the preferred vendor's list price, else the market rate
	 * (R-ING-3; the clarifier confirmed these two sources only, 2026-09-19). Not "any vendor's" price,
	 * which costing does use: the document names two sources for the pre-fill and the client's
	 * {@code StockValueSuggestion.source} has exactly two values.
	 *
	 * <p>{@code vendor_supplies.last_price} is already rupees per canonical unit (see
	 * {@code BasketCostingService.catalogue}), so it is returned as it stands. A list price of ₹0
	 * (V24 allows it, for goods given free) is not offered: the box it pre-fills refuses 0, so
	 * suggesting it would hand the person a value they cannot save. Costing skips a ₹0 list price for
	 * the same reason (the conductor's ruling, 2026-09-19), so the two agree about what a price is.
	 */
	@Transactional(readOnly = true)
	public StockValueSuggestion suggestion(UUID ingredientId) {
		return jdbc.query("""
				SELECT (SELECT vs.last_price FROM vendor_supplies vs
						 WHERE vs.ingredient_id = i.id AND vs.preferred AND vs.last_price > 0) AS preferred_price,
					   i.market_rate
				FROM ingredients i
				WHERE i.id = ?
				""", (rs, n) -> {
			BigDecimal preferred = rs.getBigDecimal("preferred_price");
			if (preferred != null) {
				return new StockValueSuggestion(preferred, StockValueSuggestion.PREFERRED_VENDOR);
			}
			BigDecimal market = rs.getBigDecimal("market_rate");
			if (market != null) {
				return new StockValueSuggestion(market, StockValueSuggestion.MARKET_RATE);
			}
			return StockValueSuggestion.none();
		}, ingredientId).stream().findFirst()
				.orElseThrow(() -> new ApplicationException(
						ErrorCode.RESOURCE_NOT_FOUND, Map.of("ingredientId", ingredientId)));
	}

	/**
	 * A rate as it will be stored, or null when it is not a usable rate (missing, zero once rounded, or
	 * negative). Public so the stock-take path can refuse a bad value <em>before</em> it writes the
	 * movement, rather than writing it and rolling back — the refusal is then the first thing that
	 * happens, and nothing about it depends on transaction boundaries.
	 */
	public static BigDecimal normalise(BigDecimal rate) {
		if (rate == null) {
			return null;
		}
		BigDecimal scaled = rate.setScale(RATE_SCALE, RoundingMode.HALF_UP);
		return scaled.signum() > 0 ? scaled : null;
	}

	private BigDecimal write(AuthenticatedUser actor, UUID ingredientId, BigDecimal rate, Source source,
			UUID vendorInvoiceLineId) {
		BigDecimal stored = normalise(rate);
		if (stored == null) {
			throw new ApplicationException(ErrorCode.STOCK_VALUE_REQUIRED, Map.of("field", "pricePerUnit"));
		}
		// The temple's day, not the server's: a rate set at 1am IST is that morning's.
		LocalDate today = clock.today();

		int updated = jdbc.update("""
				UPDATE ingredients
				SET market_rate = ?, market_rate_on = ?, market_rate_source = ?
				WHERE id = ?
				""", stored, today, source.name(), ingredientId);
		if (updated == 0) {
			throw new ApplicationException(ErrorCode.RESOURCE_NOT_FOUND, Map.of("ingredientId", ingredientId));
		}

		jdbc.update("""
				INSERT INTO ingredient_market_rate_history (
					tenant_id, ingredient_id, rate, effective_on, source, vendor_invoice_line_id, set_by)
				VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?, ?, ?, ?, ?, ?)
				""", ingredientId, stored, today, source.name(), vendorInvoiceLineId, actor.getUserId());
		return stored;
	}
}
