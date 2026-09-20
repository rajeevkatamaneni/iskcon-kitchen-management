package org.iskcon.kms.staff;

import java.util.UUID;

/**
 * Moving one person to a kitchen, from the Temple Admin's "Check these kitchen assignments" list or
 * anywhere else a kitchen is changed on its own (Epic 12).
 *
 * <p>Not {@code @NotNull}, for the reason the hire and edit forms' {@code kitchenId} is not: a missing
 * kitchen is refused as {@code KMS-400184}, which tells the person what to pick, rather than as a
 * generic field error.
 *
 * @param kitchenId the kitchen they work in now
 */
public record SetStaffKitchenRequest(UUID kitchenId) {
}
