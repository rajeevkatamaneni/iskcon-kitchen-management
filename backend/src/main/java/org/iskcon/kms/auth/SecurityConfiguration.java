package org.iskcon.kms.auth;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * HTTP security rules.
 *
 * <p>Deliberately deny-by-default: anything not listed as public requires authentication, so a
 * new endpoint is protected unless someone explicitly opens it. The reverse — public by default
 * with a list of protected paths — fails open when a route is forgotten.
 *
 * <p>Fine-grained role checks live with the endpoints themselves via method security (E1-S5),
 * not here. This class decides only who may reach the application at all.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

	@Bean
	public SecurityFilterChain filterChain(
			HttpSecurity http,
			AuthenticationFilter authenticationFilter,
			LoggingAccessDeniedHandler accessDeniedHandler)
			throws Exception {

		http
				// No cookies, no sessions — every request carries its own bearer token, so
				// there is no session for a forged cross-site request to ride on.
				.csrf(AbstractHttpConfigurer::disable)

				// The frontend is served from a different origin than the API, so the browser needs
				// to be told the API accepts its requests. Uses the CorsConfigurationSource bean
				// below; preflight (OPTIONS) is handled before authentication.
				.cors(Customizer.withDefaults())

				.sessionManagement(session ->
						session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

				.authorizeHttpRequests(auth -> auth
						// Liveness checks: must answer before anything else works.
						.requestMatchers("/health", "/actuator/health").permitAll()

						// Public temple pages. REQUIREMENTS.md is explicit that donors may
						// give without an account, so these cannot require authentication.
						.requestMatchers("/api/v1/public/**").permitAll()

						.anyRequest().authenticated())

				// 401 rather than a redirect to a login page: this is an API, and a browser
				// redirect would be a confusing response to a programmatic caller. 403s go
				// through a handler that records who was denied what.
				.exceptionHandling(handling -> handling
						.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
						.accessDeniedHandler(accessDeniedHandler))

				.addFilterBefore(authenticationFilter, UsernamePasswordAuthenticationFilter.class);

		return http.build();
	}

	/**
	 * Which browser origins may call the API, and how. Origins are configured per environment
	 * ({@code CORS_ALLOWED_ORIGINS}) — the frontend's dev URL locally, its real domain in
	 * production — never a wildcard, since a public API accepting any origin is an open door.
	 *
	 * <p>No credentials flag: authentication is a bearer token in a header, not a cookie, so the
	 * cross-site-cookie machinery is neither needed nor enabled. The request id is exposed so the
	 * frontend can read and correlate it.
	 */
	@Bean
	public CorsConfigurationSource corsConfigurationSource(
			@Value("${kms.cors.allowed-origins}") String allowedOrigins) {

		CorsConfiguration config = new CorsConfiguration();
		config.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
				.map(String::trim)
				.filter(origin -> !origin.isEmpty())
				.toList());
		config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-Id"));
		// Content-Disposition carries the filename of a download (the temple data export, E1-S15).
		// The web app is on a different origin from the API, so without exposing it the browser hides
		// it from JavaScript and the page has to invent a name — which is exactly what went wrong.
		config.setExposedHeaders(List.of("X-Request-Id", "Content-Disposition"));
		config.setAllowCredentials(false);
		config.setMaxAge(3600L);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", config);
		return source;
	}
}

