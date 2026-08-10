package org.iskcon.kms.ingredient;

/**
 * A request to set (or clear) an ingredient's sattvic-prohibited flag. A Temple Admin only, and
 * always audited — this is a religious-compliance decision, so a plain boolean carries real weight.
 */
public record SetSattvicFlagRequest(boolean sattvicProhibited) {
}
