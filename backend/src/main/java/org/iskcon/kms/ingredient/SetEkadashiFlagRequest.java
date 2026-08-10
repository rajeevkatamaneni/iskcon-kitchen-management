package org.iskcon.kms.ingredient;

/** Sets or clears an ingredient's Ekadashi-prohibited flag (E4-S6). */
public record SetEkadashiFlagRequest(boolean ekadashiProhibited) {
}
