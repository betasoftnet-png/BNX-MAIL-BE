package com.btctech.mailapp.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

@Configuration
@Slf4j
public class MailConfig {

    @Value("${spring.mail.host:${SPRING_MAIL_HOST:${mail.smtp.host:${SMTP_HOST:localhost}}}}")
    private String host;

    @Value("${spring.mail.port:${SPRING_MAIL_PORT:${mail.smtp.port:${SMTP_PORT:587}}}}")
    private int port;

    @Value("${spring.mail.username:${SPRING_MAIL_USERNAME:${mail.smtp.username:${SMTP_USERNAME:}}}}")
    private String username;

    @Value("${spring.mail.password:${SPRING_MAIL_PASSWORD:${mail.smtp.password:${SMTP_PASSWORD:}}}}")
    private String password;

    @Value("${spring.mail.properties.mail.smtp.auth:${SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH:${mail.smtp.auth:true}}}")
    private boolean auth;

    @Value("${spring.mail.properties.mail.smtp.starttls.enable:${SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE:${mail.smtp.starttls.enable:true}}}")
    private boolean starttls;

    @Value("${spring.mail.properties.mail.smtp.starttls.required:${SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_REQUIRED:false}}")
    private boolean starttlsRequired;

    @Value("${spring.mail.properties.mail.smtp.ssl.enable:${SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_ENABLE:${mail.smtp.ssl.enable:false}}}")
    private boolean ssl;

    @Value("${spring.mail.properties.mail.smtp.ssl.trust:${SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_TRUST:*}}")
    private String sslTrust;

    @Value("${spring.mail.protocol:smtp}")
    private String protocol;

    @Bean
    @Primary
    public JavaMailSender javaMailSender() {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        String resolvedHost = (host != null && !host.trim().isEmpty()) ? host.trim() : "localhost";
        int resolvedPort = port > 0 ? port : 587;
        String resolvedProtocol = (protocol != null && !protocol.trim().isEmpty()) ? protocol.trim() : "smtp";

        mailSender.setHost(resolvedHost);
        mailSender.setPort(resolvedPort);
        mailSender.setProtocol(resolvedProtocol);
        mailSender.setDefaultEncoding("UTF-8");

        boolean hasCredentials = username != null && !username.trim().isEmpty();
        if (hasCredentials) {
            mailSender.setUsername(username.trim());
        }
        if (password != null && !password.trim().isEmpty()) {
            mailSender.setPassword(password.trim());
        }

        boolean isSslPort = (resolvedPort == 465);
        boolean useSsl = ssl || isSslPort;
        boolean useStarttls = starttls && !isSslPort;

        Properties props = mailSender.getJavaMailProperties();
        props.put("mail.transport.protocol", resolvedProtocol);
        props.put("mail.smtp.auth", String.valueOf(auth && hasCredentials));
        props.put("mail.smtp.starttls.enable", String.valueOf(useStarttls));
        props.put("mail.smtp.starttls.required", String.valueOf(starttlsRequired));
        props.put("mail.smtp.ssl.enable", String.valueOf(useSsl));
        props.put("mail.smtp.ssl.trust", (sslTrust != null && !sslTrust.trim().isEmpty()) ? sslTrust.trim() : "*");
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");

        if (useSsl) {
            props.put("mail.smtp.socketFactory.port", String.valueOf(resolvedPort));
            props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
            props.put("mail.smtp.socketFactory.fallback", "false");
        }

        if ("localhost".equalsIgnoreCase(resolvedHost) || "127.0.0.1".equals(resolvedHost)) {
            log.warn("⚠️ JavaMailSender is configured for '{}' (port {}). In production, configure SPRING_MAIL_HOST / SMTP_HOST to an external SMTP provider.", resolvedHost, resolvedPort);
        } else {
            log.info("✓ JavaMailSender configured: host={}, port={}, auth={}, starttls={}, ssl={}",
                    resolvedHost, resolvedPort, auth && hasCredentials, useStarttls, useSsl);
        }

        return mailSender;
    }
}

