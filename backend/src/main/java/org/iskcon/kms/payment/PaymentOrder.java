package org.iskcon.kms.payment;

/** A payment order created at the provider (E7): its id and the amount it was created for. */
public record PaymentOrder(String orderId, long amountMinorUnits, String currency) {
}
