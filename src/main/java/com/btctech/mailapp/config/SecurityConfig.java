package com.btctech.mailapp.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;

import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Slf4j
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtFilter jwtFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Public endpoints (no authentication)
                        .requestMatchers("/api/users/profile-picture/**").permitAll()
                        .requestMatchers("/api/auth/register", "/api/auth/login/**", "/api/auth/refresh", "/api/auth/forgot-password/**", "/api/auth/reset-password", "/api/auth/username-suggestions", "/api/auth/child/**", "/api/auth/system-status", "/api/auth/appeal", "/api/auth/verify-business", "/api/auth/send-mobile-otp", "/api/auth/verify-mobile-otp", "/api/auth/fetch-gstins").permitAll()
                        .requestMatchers("/api/verification/status/**", "/api/verification/webhook").permitAll()
                        .requestMatchers("/api/oauth/token").permitAll() // ✅ Public token exchange
                        .requestMatchers("/api/webhooks/incoming-mail").permitAll() // ✅ Webhook
                        .requestMatchers("/api/mail/public/send", "/api/mail/public/schedule", "/api/mail/public/schedule/**").permitAll() // ✅ Public email sending/scheduling
                        .requestMatchers("/", "/index.html", "/error").permitAll()
                        .requestMatchers(
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/swagger-resources/**",
                                "/webjars/**",
                                "/ws/**"
                        ).permitAll()

                        // Protected endpoints (require JWT)
                        .requestMatchers("/api/admin/**").authenticated() // ✅ Admin API
                        .requestMatchers("/api/oauth/authorize").authenticated() // ✅ Protected authorize
                        .requestMatchers("/api/emails/**").authenticated()
                        .requestMatchers("/api/mail/**").authenticated()
                        .requestMatchers("/api/chat/**").authenticated() // ✅ Added for chat system
                        .requestMatchers("/api/templates/**").authenticated() // ✅ Added for custom email templates
                        .requestMatchers("/api/blocked-contacts/**").authenticated() // ✅ Blocked contacts API
                        .requestMatchers("/api/casbox/**").authenticated()
                        .requestMatchers("/api/contact-aliases/**").authenticated()

                        // Any other request requires authentication
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            log.error("✗ Unauthorized access attempt to {}: {}", request.getRequestURI(), authException.getMessage());
                            response.sendError(401, "Unauthorized: " + authException.getMessage());
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            log.error("✗ Access denied for {}: {}", request.getRequestURI(), accessDeniedException.getMessage());
                            response.sendError(403, "Access Denied: " + accessDeniedException.getMessage());
                        })
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // ✅ Allow production domains and local dev servers
        configuration.setAllowedOriginPatterns(List.of(
            "http://localhost:3000",
            "http://localhost:5173",
            "https://www.kinsword.com",
            "https://www.b2auth.com",
            "https://b2auth.com",
            "https://cliks.beta-softnet.com",
            "https://cliksbusiness.com",
            "https://www.bnxmail.com",
            "https://bnxmail.com",
            "https://account.beta-softnet.com",
            "https://www.beta-softnet.com",
            "https://beta-softnet.com",
            "https://admin.bnxmail.com/",
            "https://admin.bnxmail.com",
            "https://bit-tool.com/",
            "https://bit-tool.com",
            "https://www.bit-tool.com",
            "https://www.bit-tool.com/"
    
        ));

        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true); // ✅ Changed to true
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}