package org.iskcon.kms.recipe;

import java.util.UUID;

/** A recipe category. Ekadashi is the fasting-compatible one E4 consumes. */
public record RecipeCategoryView(UUID id, String name, boolean fastingCompatible) {
}
