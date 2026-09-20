package org.iskcon.kms.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.iskcon.kms.error.ApplicationException;
import org.iskcon.kms.error.ErrorCode;
import org.iskcon.kms.kitchen.KitchenOrder;
import org.iskcon.kms.user.User;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Keeps the meal planner to the people whose kitchen plans its meals here (Epic 12, {@code KMS-400183}).
 *
 * <p><strong>The rule, as Rajeev set it on 2026-09-19:</strong> "Only people whose kitchen plans its
 * meals here can open the meal planner (server-enforced, and hidden from the menu) … the Temple Admin
 * sees and plans for every kitchen." So: the Temple Admin, always; anybody else only while their current
 * staff record is in a kitchen that is open and uses the planner. A Kitchen Staff or Kitchen Manager
 * account with no staff record at all is refused too (the coordinator's correction of the same day: a
 * person with no kitchen has no kitchen to plan for). The decision itself is
 * {@link KitchenOrder#mayPlan(java.util.UUID, boolean)}, written once, and {@code WhoAmIController} asks
 * the same method to decide whether the menu shows the planner at all.
 *
 * <p><strong>Why an interceptor and not a permission.</strong> The rule is not a property of a role —
 * two cooks with the same role get different answers depending on which kitchen they work in — so it
 * cannot live in {@code RolePermissions}, and no new permission constant was wanted. It is a second
 * question asked after the permission: {@code MANAGE_MEAL_PLANS} still decides who may plan at all, and
 * this decides whether this particular person's kitchen lets them.
 *
 * <p><strong>Why here, and in this order.</strong> {@link PermissionFirstInterceptor} runs first
 * ({@link PlannerKitchenGuardConfiguration} orders it so), so somebody without the permission — a
 * Volunteer — is still refused exactly as before, 403 {@code KMS-400021}, and is not told anything about
 * kitchens. This then runs before Spring MVC reads the body, for the same reason that interceptor does:
 * a cook in the store kitchen who posts a half-filled meal is told the planner is not theirs, not how
 * to fill the form in.
 *
 * <p><strong>Which endpoints.</strong> Chosen by path in {@link PlannerKitchenGuardConfiguration}, where
 * the list is, with the reason for each one left open. A path list rather than an annotation on each
 * controller because the planner's endpoints sit in two packages (meal and document) that other tasks
 * own, and a list read in one place is easier to check against the screens than annotations spread
 * across both.
 *
 * <p><strong>The answer.</strong> An {@link ApplicationException} thrown from here is handled by
 * {@code GlobalExceptionHandler} exactly as one thrown from a controller, so the response is the
 * ordinary 403 with the code and its next step — "ask a Temple Admin to change your kitchen on the
 * Staff page".
 */
public class PlannerKitchenGuard implements HandlerInterceptor {

	private final KitchenOrder kitchenOrder;

	public PlannerKitchenGuard(KitchenOrder kitchenOrder) {
		this.kitchenOrder = kitchenOrder;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (!(handler instanceof HandlerMethod)) {
			// CORS preflight and the like: no controller method, nothing is planned.
			return true;
		}
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
			// The filter chain has already answered an unauthenticated request with 401; anything that
			// reached here without our principal is left to method security, as PermissionFirst leaves it.
			return true;
		}
		boolean templeAdmin = user.getRole() == User.Role.TEMPLE_ADMIN;
		if (templeAdmin || kitchenOrder.mayPlan(user.getUserId(), false)) {
			return true;
		}
		throw new ApplicationException(ErrorCode.PLANNER_NOT_FOR_YOUR_KITCHEN,
				Map.of("userId", String.valueOf(user.getUserId()), "path", request.getRequestURI()));
	}
}
