package com.btctech.mailapp.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * WebMvcConfigurer for static resource handling.
 * CORS is centrally handled by SecurityConfig and CorsFilter with Ordered.HIGHEST_PRECEDENCE
 * to ensure preflight OPTIONS, error dispatches, and API calls receive consistent CORS headers
 * without duplicate header conflicts.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:uploads/");
    }
}
