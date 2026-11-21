package com.btctech.mailapp.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final String[] ALLOWED_ORIGIN_PATTERNS = {
        "https://www.bnxmail.com",
        "https://bnxmail.com",
        "https://api.bnxmail.com",
        "https://admin.bnxmail.com",
        "https://www.b2auth.com",
        "https://b2auth.com",
        "https://cliks.beta-softnet.com",
        "https://cliksbusiness.com",
        "https://account.beta-softnet.com",
        "https://www.beta-softnet.com",
        "https://beta-softnet.com",
        "https://bit-tool.com",
        "https://www.bit-tool.com",
        "https://app2.bit-tool.com",
        "http://app2.bit-tool.com",
        "https://storage.beta-softnet.com",
        "https://www.kinsword.com",
        "https://*.bnxmail.com",
        "https://*.bit-tool.com",
        "http://*.bit-tool.com",
        "https://*.beta-softnet.com",
        "http://localhost:3000",
        "http://localhost:5173",
        "http://localhost:[*]",
        "http://127.0.0.1:[*]"
    };

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns(ALLOWED_ORIGIN_PATTERNS);
        registry.addEndpoint("/ws").setAllowedOriginPatterns(ALLOWED_ORIGIN_PATTERNS).withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureWebSocketTransport(org.springframework.web.socket.config.annotation.WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(50 * 1024 * 1024); // 50MB
        registration.setSendBufferSizeLimit(50 * 1024 * 1024); // 50MB
        registration.setSendTimeLimit(20000); // 20s
    }
}
