package org.iskcon.kms.ingredient;

import java.math.BigDecimal;

/**
 * The market rate typed on the ingredient's page (R-ING-3), in rupees per one of the ingredient's
 * canonical unit.
 *
 * <p>No bean-validation annotations, on purpose. Blank, zero and negative are all the one refusal,
 * {@code STOCK_VALUE_REQUIRED}, made by {@link MarketRateService} — the same code and wording the
 * stock-take box gives for the same mistake, so the rule reads identically wherever a rate is typed.
 */
public record SetMarketRateRequest(BigDecimal marketRate) {
}
