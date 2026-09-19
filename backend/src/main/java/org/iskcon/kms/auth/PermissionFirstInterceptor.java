package org.iskcon.kms.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.expression.ExpressionUtils;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.util.SimpleMethodInvocation;
import org.springframework.util.ClassUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Asks an endpoint's {@code @PreAuthorize} question before Spring reads the request body, so that a
 * person who may not use an endpoint is told so — 403, KMS-400021 — whatever they sent (T-301).
 *
 * <p><strong>The defect this closes.</strong> Method security is an interceptor around the controller
 * method, and a controller method cannot be called until its arguments exist. Building them is where
 * Spring parses the JSON, converts path variables and runs {@code @Valid}. So a Volunteer who posted
 * {@code {"lines": []}} to {@code /api/v1/purchase-orders} was answered 400 KMS-400001 with the
 * order form's field errors — "add at least one item" — and only a well-formed body reached the
 * check that said no. Nothing was ever written, but the answer was wrong twice over: it told a person
 * with no business there how to fill the form in, and it described the endpoint's shape to anyone
 * probing it without permission, which is exactly what {@code GlobalExceptionHandler} declines to do
 * when it refuses without naming the permission. The verifiers found it on deliveries, receipts,
 * purchase orders and the shopping list; it was true of every endpoint in the product with a body.
 *
 * <p><strong>Why here, and once.</strong> {@link HandlerInterceptor#preHandle} runs after the request
 * has been matched to its controller method and before the handler adapter resolves any argument, so
 * it is the one point that knows which question to ask and has not yet read the body. One
 * interceptor covers every controller, including the ones written after it, which editing each
 * controller would not: the alternatives — taking the body as a raw string and validating it by hand
 * after the check, or moving every check into the service — are a per-endpoint discipline, and a
 * per-endpoint discipline is what an endpoint added next month forgets. It also needs nothing from
 * the controllers: the question it asks is the one already written on the method.
 *
 * <p><strong>It asks the same question, not a copy of it.</strong> The expression is read off the
 * method's own {@code @PreAuthorize} (or its class's, which is where method security looks next) and
 * evaluated by a {@link MethodSecurityExpressionHandler} — the application's own if it declares one,
 * otherwise the same default method security builds for itself. So {@code hasAuthority},
 * {@code hasAnyAuthority}, {@code isAuthenticated()}, {@code permitAll()} and {@code or} mean exactly
 * what they mean to method security, and {@link RolePermissions} stays the only place the policy is
 * written down. There is no list of endpoints or permissions in this class to fall out of date.
 *
 * <p><strong>What it deliberately leaves alone.</strong>
 * <ul>
 *   <li>An expression that mentions a method argument ({@code #id}, {@code #p0}, {@code #request})
 *       cannot be answered before the arguments exist. Anything containing {@code #} is skipped and
 *       left entirely to method security, which still runs. None of the product's expressions uses one
 *       today (every one is a plain authority test); if one is written, that endpoint quietly keeps
 *       the old order, rather than being refused on a guess about an argument we have not got.
 *   <li>A request with no authentication at all. The filter chain already answers those with 401
 *       before any controller is matched, and a {@code permitAll()} path that reached here without
 *       one is left to method security to answer exactly as it did before.
 *   <li>Method security itself. It stays switched on and still runs after this, so a handler invoked
 *       some other way than through the dispatcher is still guarded, and an expression this class
 *       skipped is still enforced. Asking the question twice costs one extra authority lookup.
 * </ul>
 *
 * <p><strong>The answer is the one that already existed.</strong> A refusal here throws Spring's
 * {@link AccessDeniedException}, which the dispatcher hands to {@code GlobalExceptionHandler} exactly
 * as it does a refusal from method security, so the response — 403, KMS-400021, no mention of which
 * permission was missing — and its log line are the ones the product has always given. A person who
 * <em>is</em> permitted passes straight through and still gets the 400 with field errors for a bad
 * body, because that answer is useful to them.
 */
public class PermissionFirstInterceptor implements HandlerInterceptor {

	private final MethodSecurityExpressionHandler expressionHandler;

	/**
	 * The parsed expression for each handler method, or empty for a method with nothing this class
	 * should ask. Parsing is done once per method rather than per request; handler methods are a
	 * fixed set once the application has started, so the map cannot grow without bound.
	 */
	private final ConcurrentMap<Method, Optional<Expression>> expressions = new ConcurrentHashMap<>();

	public PermissionFirstInterceptor(MethodSecurityExpressionHandler expressionHandler) {
		this.expressionHandler = expressionHandler;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (!(handler instanceof HandlerMethod handlerMethod)) {
			// Static resources, CORS preflight and the like: no controller method, so no question.
			return true;
		}

		Optional<Expression> expression = expressions.computeIfAbsent(
				handlerMethod.getMethod(), method -> expressionFor(handlerMethod));
		if (expression.isEmpty()) {
			return true;
		}

		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null) {
			return true;
		}

		// The arguments are deliberately empty: they do not exist yet, which is the whole point, and
		// expressionFor has already declined every expression that would look at one.
		SimpleMethodInvocation invocation = new SimpleMethodInvocation(
				handlerMethod.getBean(),
				handlerMethod.getMethod(),
				new Object[handlerMethod.getMethod().getParameterCount()]);
		EvaluationContext context = expressionHandler.createEvaluationContext(() -> authentication, invocation);

		if (!ExpressionUtils.evaluateAsBoolean(expression.get(), context)) {
			// The same exception, and so the same handler and the same 403, as method security.
			throw new AccessDeniedException("Access Denied");
		}
		return true;
	}

	/**
	 * The {@code @PreAuthorize} expression method security would evaluate for this handler: the
	 * method's own, else the controller class's. Empty when there is none, or when it refers to an
	 * argument and so can only be answered after the body has been read.
	 */
	private Optional<Expression> expressionFor(HandlerMethod handlerMethod) {
		PreAuthorize annotation = AnnotatedElementUtils.findMergedAnnotation(
				handlerMethod.getMethod(), PreAuthorize.class);
		if (annotation == null) {
			annotation = AnnotatedElementUtils.findMergedAnnotation(
					ClassUtils.getUserClass(handlerMethod.getBeanType()), PreAuthorize.class);
		}
		if (annotation == null || annotation.value().contains("#")) {
			return Optional.empty();
		}
		return Optional.of(expressionHandler.getExpressionParser().parseExpression(annotation.value()));
	}
}
