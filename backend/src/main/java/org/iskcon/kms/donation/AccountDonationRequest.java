package org.iskcon.kms.donation;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * A gift from inside the app, which is only ever an amount (E7-S2, E7-S6).
 *
 * <p>There is no donor on this request, and that is the point: the temple already knows who is
 * giving, because they are signed in to it. A name or an email arriving from the browser would be a
 * claim about identity that the server has no reason to believe when it can read the real one from
 * the token.
 */
public record AccountDonationRequest(@NotNull @Positive BigDecimal amountInr) {
}
