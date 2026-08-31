package org.iskcon.kms.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One batch, and how much of it a drawdown would take — in the family's base unit, because that is
 * the only unit batches can be summed and compared in (E3-S6).
 *
 * <p>The expiry is carried alongside so a caller can show <em>why</em> this batch came first without
 * going back to the ledger for it. It is the FEFO order made visible.
 */
public record BatchDraw(UUID batchId, BigDecimal takeBase, LocalDate expiry) {
}
