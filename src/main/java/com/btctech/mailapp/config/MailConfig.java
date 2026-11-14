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

    @Value("${spring.mail.host:${mail.smtp.host:localhost}}")
    private String host;

    @Value("${spring.mail.port:${mail.smtp.port:587}}")
    private int port;

    @Value("${spring.mail.username:${mail.smtp.username:}}")
    private String username;

    @Value("${spring.mail.password:${mail.smtp.password:}}")
    private String password;

    @Value("${spring.mail.properties.mail.smtp.auth:${mail.smtp.auth:true}}")
    private boolean auth;

    @Value("${spring.mail.properties.mail.smtp.starttls.enable:${mail.smtp.starttls.enable:true}}")
    private boolean starttls;

    @Value("${spring.mail.properties.mail.smtp.starttls.required:false}")
    private boolean starttlsRequired;

    @Value("${spring.mail.properties.mail.smtp.ssl.enable:${mail.smtp.ssl.enable:false}}")
    private boolean ssl;

    @Value("${spring.mail.protocol:smtp}")
    private String protocol;

    @Bean
    @Primary
    public JavaMailSender javaMailSender() {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(host != null ? host.trim() : "localhost");
        mailSender.setPort(port > 0 ? port : 587);
        mailSender.setProtocol(protocol != null ? protocol.trim() : "smtp");

        boolean hasCredentials = username != null && !username.trim().isEmpty();
        if (hasCredentials) {
            mailSender.setUsername(username.trim());
        }
        if (password != null && !password.trim().isEmpty()) {
            mailSender.setPassword(password.trim());
        }

        boolean isSslPort = (port == 465);
        boolean useSsl = ssl || isSslPort;
        boolean useStarttls = starttls && !isSslPort;

        Properties props = mailSender.getJavaMailProperties();
        props.put("mail.transport.protocol", protocol);
        props.put("mail.smtp.auth", String.valueOf(auth && hasCredentials));
        props.put("mail.smtp.starttls.enable", String.valueOf(useStarttls));
        props.put("mail.smtp.starttls.required", String.valueOf(starttlsRequired));
        props.put("mail.smtp.ssl.enable", String.valueOf(useSsl));
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");

        log.info("✓ JavaMailSender initialized: host={}, port={}, auth={}, starttls={}, ssl={}",
                mailSender.getHost(), mailSender.getPort(), auth && hasCredentials, useStarttls, useSsl);

        return mailSender;
    }
}

