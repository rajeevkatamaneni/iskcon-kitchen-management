package org.iskcon.kms.meal;

import java.util.UUID;

/**
 * One kitchen's section of a meal, as the planner reads it (Epic 12, T-354).
 *
 * <p>A meal is cooked by one or more of the temple's kitchens, each a row of {@code meal_kitchens}
 * (V150), and every dish sits under one of them ({@link MealDishView#kitchenId()}). A section exists
 * even before anybody has put a dish in it: a kitchen can be on a meal before its menu is decided.
 *
 * <p>The sections of a {@link ServedMeal} arrive <strong>already in the order to show them</strong> to
 * the person who asked — their own kitchen first when it is on the meal, else the main kitchen first
 * when it is, then Settings order ({@code KitchenOrder.forViewer}). A screen draws them in that order
 * and never re-sorts, so every screen agrees about which kitchen comes first.
 *
 * @param kitchenId    the kitchen
 * @param kitchenName  its name as the temple wrote it, read through the key, so a renamed kitchen reads
 *                     renamed here with nothing cascaded
 * @param isMain       whether it is the temple's main kitchen
 * @param crewRequired this kitchen's People needed for this meal. Null where nobody has said; null is
 *                     not zero. The meal's own {@link ServedMeal#crewRequired()} is the sum of these.
 */
public record MealKitchenView(UUID kitchenId, String kitchenName, boolean isMain, Integer crewRequired) {
}
