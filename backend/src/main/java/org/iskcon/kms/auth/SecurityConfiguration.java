package org.iskcon.kms.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

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
	public SecurityFilterChain filterChain(HttpSecurity http, AuthenticationFilter authenticationFilter)
			throws Exception {

		http
				// No cookies, no sessions — every request carries its own bearer token, so
				// there is no session for a forged cross-site request to ride on.
				.csrf(AbstractHttpConfigurer::disable)
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
				// redirect would be a confusing response to a programmatic caller.
				.exceptionHandling(handling ->
						handling.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))

				.addFilterBefore(authenticationFilter, UsernamePasswordAuthenticationFilter.class);

		return http.build();
	}
}
