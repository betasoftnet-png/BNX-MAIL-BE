package com.btctech.mailapp.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

import java.util.Collections;

@Slf4j
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtUtil jwtUtil;

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
        ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setPoolSize(1);
        taskScheduler.setThreadNamePrefix("ws-heartbeat-thread-");
        taskScheduler.initialize();

        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[]{10000, 10000})
                .setTaskScheduler(taskScheduler);

        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(50 * 1024 * 1024); // 50MB
        registration.setSendBufferSizeLimit(50 * 1024 * 1024); // 50MB
        registration.setSendTimeLimit(20000); // 20s
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
                    String authHeader = accessor.getFirstNativeHeader("Authorization");
                    if (authHeader != null && authHeader.startsWith("Bearer ")) {
                        String token = authHeader.substring(7);
                        try {
                            String email = jwtUtil.extractEmail(token);
                            if (email != null && jwtUtil.validateToken(token, email)) {
                                UsernamePasswordAuthenticationToken auth =
                                        new UsernamePasswordAuthenticationToken(email, null, Collections.emptyList());
                                accessor.setUser(auth);
                                log.debug("✓ WebSocket authenticated for user: {}", email);
                            }
                        } catch (Exception e) {
                            log.warn("WebSocket STOMP authentication failed: {}", e.getMessage());
                        }
                    }
                }
                return message;
            }
        });
    }
}
