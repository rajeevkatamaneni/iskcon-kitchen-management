package org.iskcon.kms.ban;

/** One entry of the category picklist, served by the API so the vocabulary lives in one place. */
public record BanCategoryOption(BanCategory value, String label) {
}
