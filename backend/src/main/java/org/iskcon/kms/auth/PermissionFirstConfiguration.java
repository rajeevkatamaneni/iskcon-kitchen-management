package org.iskcon.kms.auth;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Puts {@link PermissionFirstInterceptor} in front of every controller (T-301).
 *
 * <p>Registered on every path rather than on {@code /api/**}: the interceptor asks nothing of a
 * method without {@code @PreAuthorize}, so a narrower pattern would only add a way for a controller
 * mounted somewhere else to slip past it.
 *
 * <p><strong>Which expression handler.</strong> If the application ever declares its own
 * {@link MethodSecurityExpressionHandler} bean — to add a role hierarchy or a permission evaluator —
 * method security uses it, and so must this, or the two would answer the same question differently.
 * Today there is none, and {@code @EnableMethodSecurity} builds a
 * {@link DefaultMethodSecurityExpressionHandler} with the application context set so that {@code @bean}
 * references resolve. The fallback here is built the same way, for the same reason.
 *
 * <p>Kept out of {@link SecurityConfiguration} on purpose: that class is the filter chain, which runs
 * before Spring MVC has chosen a controller, and this is a Spring MVC concern that needs the chosen
 * controller to know which question to ask.
 */
@Configuration
public class PermissionFirstConfiguration implements WebMvcConfigurer {

	private final PermissionFirstInterceptor interceptor;

	public PermissionFirstConfiguration(
			ObjectProvider<MethodSecurityExpressionHandler> declaredHandler,
			ApplicationContext applicationContext) {
		MethodSecurityExpressionHandler handler = declaredHandler.getIfAvailable(() -> {
			DefaultMethodSecurityExpressionHandler fallback = new DefaultMethodSecurityExpressionHandler();
			fallback.setApplicationContext(applicationContext);
			return fallback;
		});
		this.interceptor = new PermissionFirstInterceptor(handler);
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(interceptor);
	}
}
