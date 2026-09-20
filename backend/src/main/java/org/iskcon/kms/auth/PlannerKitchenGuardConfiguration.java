package org.iskcon.kms.auth;

import org.iskcon.kms.kitchen.KitchenOrder;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Puts {@link PlannerKitchenGuard} in front of the meal planner's endpoints, after
 * {@link PermissionFirstInterceptor} (Epic 12).
 *
 * <p><strong>Order.</strong> {@link PermissionFirstConfiguration} registers its interceptor with the
 * default order, 0. This one is registered with {@link #ORDER}, 100, and the registry sorts by order
 * across every configurer, so the permission is always asked first whichever configuration class Spring
 * happens to read first. A Volunteer is refused for the permission, and only somebody who holds
 * {@code MANAGE_MEAL_PLANS} is ever asked about their kitchen. Both run in {@code preHandle}, before any
 * argument is resolved, so neither reads the body.
 *
 * <p><strong>Which endpoints, and which were left open.</strong> Every endpoint the planner screens
 * (/planner, its day, compose, meal and reuse pages, and the job card they print) call, found by reading
 * which screens call each method in {@code frontend/lib/api.ts}:
 * <ul>
 *   <li>{@code /api/v1/meals} and below — the meals themselves: list, summary, one meal, save, update,
 *       cancel, repeat and its preview, later-in-series, travel estimate, record, correct. Only the
 *       planner's pages and components call them.
 *   <li>{@code /api/v1/meal-plans} and below — day context, Ekadashi check, menu history, sufficiency,
 *       shortfall, outside commitments, event names, travel estimate, reuse and its preview. Only the
 *       planner calls them.
 *   <li>{@code /api/v1/meal-crew} and below — the planner's crew readouts. Only the planner calls them.
 *   <li>{@code /api/v1/job-cards} and below — printing a meal's job card, a button on the meal.
 * </ul>
 *
 * <p>Deliberately left open, each because a screen that is not the planner relies on it:
 * <ul>
 *   <li>{@code GET /api/v1/meal-kinds} — Settings → Meal kinds lists them (and its writes are
 *       {@code MANAGE_TEMPLE_SETTINGS}, the Temple Admin's, who always passes this guard anyway). A
 *       list of meal names tells a cook nothing about another kitchen's plans.
 *   <li>{@code /api/v1/today} (Today), {@code /api/v1/workforce}, the leave queue, the staff schedule,
 *       occasions, calendar, costing and places — all under {@code MANAGE_MEAL_PLANS} or near it, none
 *       of them the planner. A cook in the store kitchen still has a Today screen, a roster and leave.
 * </ul>
 * Each path is listed twice, bare and with {@code /**}, so matching does not rest on whether
 * {@code /**} matches the bare path under whichever path matcher the registry uses.
 */
@Configuration
public class PlannerKitchenGuardConfiguration implements WebMvcConfigurer {

	/** After {@link PermissionFirstInterceptor}, which is registered with the default order of 0. */
	static final int ORDER = 100;

	static final String[] PLANNER_PATHS = {
		"/api/v1/meals", "/api/v1/meals/**",
		"/api/v1/meal-plans", "/api/v1/meal-plans/**",
		"/api/v1/meal-crew", "/api/v1/meal-crew/**",
		"/api/v1/job-cards", "/api/v1/job-cards/**",
	};

	private final KitchenOrder kitchenOrder;

	public PlannerKitchenGuardConfiguration(KitchenOrder kitchenOrder) {
		this.kitchenOrder = kitchenOrder;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new PlannerKitchenGuard(kitchenOrder))
				.addPathPatterns(PLANNER_PATHS)
				.order(ORDER);
	}
}
