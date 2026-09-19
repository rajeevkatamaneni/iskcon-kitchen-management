package org.iskcon.kms.ingredient;

import java.math.BigDecimal;

/**
 * The pre-fill for the stock-take box "What it would cost to buy today (₹ per Kg)" (R-ING-3):
 * "pre-filled from the preferred vendor's list price, else the market rate".
 *
 * <p>Both fields null when neither exists: the box starts empty and the person has to type a value,
 * which is the point — an empty box is honest, a suggested ₹0 is the defect this work removes.
 *
 * @param pricePerUnit rupees per one of the ingredient's canonical unit, or null
 * @param source       {@code PREFERRED_VENDOR}, {@code MARKET_RATE}, or null with no suggestion
 */
public record StockValueSuggestion(BigDecimal pricePerUnit, String source) {

	public static final String PREFERRED_VENDOR = "PREFERRED_VENDOR";
	public static final String MARKET_RATE = "MARKET_RATE";

	static StockValueSuggestion none() {
		return new StockValueSuggestion(null, null);
	}
}
