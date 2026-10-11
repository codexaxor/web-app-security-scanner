package com.company.secscan.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Minimal security for M0 (D-010): health is public, everything else needs HTTP Basic against {@code app_user}.
 * No roles, no form login, no Spring default generated password. Real authorization is M10.
 *
 * Only loaded in the servlet (api) process; the worker runs non-web and has no {@code HttpSecurity}.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                // No server-side session: every request re-authenticates via HTTP Basic (STATELESS). Because no
                // session cookie is issued, there is no CSRF vector, so CSRF protection is disabled on that basis
                // alone — not for convenience. No CORS is configured, so cross-origin browser calls are blocked
                // by default (the UI is same-origin via the nginx/Vite proxy); revisit if that changes.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
