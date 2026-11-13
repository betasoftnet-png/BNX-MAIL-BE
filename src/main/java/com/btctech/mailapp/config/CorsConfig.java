package com.btctech.mailapp.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(
                        "https://www.bnxmail.com",
                        "https://bnxmail.com",
                        "https://www.b2auth.com",
                        "https://b2auth.com",
                        "https://z7ibjggnrtzynrguut8nerqr.151.185.41.212.sslip.io",
                        "http://a11atsvnjorf7fwsxsfxvkwf.151.185.41.212.sslip.io",
                        "https://a11atsvnjorf7fwsxsfxvkwf.151.185.41.212.sslip.io",
                        "http://z8m99lkvh7u6ncfsumfwcrdl.151.185.41.212.sslip.io",
                        "https://z8m99lkvh7u6ncfsumfwcrdl.151.185.41.212.sslip.io",
                        "http://z7m99lkvh7u6ncfsumfwcrdl.151.185.41.212.sslip.io",
                        "https://z7m99lkvh7u6ncfsumfwcrdl.151.185.41.212.sslip.io",
                        "https://bit-tool.com",
                        "https://www.bit-tool.com",
                        "http://localhost:3000",
                        "http://localhost:5173" // for local frontend
                )
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    @Override
    public void addResourceHandlers(org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:uploads/");
    }
}
