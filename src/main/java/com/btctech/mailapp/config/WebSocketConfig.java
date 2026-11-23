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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
        "https://t2da8odkjkrmgtktzvcjidwy.151.185.41.212.sslip.io",
        "http://t2da8odkjkrmgtktzvcjidwy.151.185.41.212.sslip.io",
        "https://*.sslip.io",
        "http://*.sslip.io",
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
        taskScheduler.setPoolSize(2);
        taskScheduler.setThreadNamePrefix("ws-heartbeat-");
        taskScheduler.initialize();

        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[]{20000, 20000})
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
                if (accessor != null) {
                    StompCommand command = accessor.getCommand();

                    if (StompCommand.CONNECT.equals(command)) {
                        String authHeader = accessor.getFirstNativeHeader("Authorization");
                        if (authHeader == null) {
                            authHeader = accessor.getFirstNativeHeader("authorization");
                        }
                        if (authHeader == null) {
                            authHeader = accessor.getFirstNativeHeader("token");
                        }
                        if (authHeader == null) {
                            authHeader = accessor.getFirstNativeHeader("passcode");
                        }

                        if (authHeader != null && authHeader.startsWith("Bearer ")) {
                            authHeader = authHeader.substring(7).trim();
                        }

                        if (authHeader != null && !authHeader.isEmpty()) {
                            try {
                                String email = jwtUtil.extractEmail(authHeader);
                                if (email != null && Boolean.TRUE.equals(jwtUtil.validateToken(authHeader, email))) {
                                    UsernamePasswordAuthenticationToken auth =
                                            new UsernamePasswordAuthenticationToken(
                                                    email,
                                                    null,
                                                    Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"))
                                            );
                                    accessor.setUser(auth);
                                    log.info("✓ WebSocket STOMP authenticated for: {}", email);
                                } else {
                                    log.warn("✗ WebSocket STOMP rejected: Invalid or expired token for {}", email);
                                }
                            } catch (Exception e) {
                                log.warn("✗ WebSocket STOMP token validation error: {}", e.getMessage());
                            }
                        } else {
                            log.warn("✗ WebSocket STOMP CONNECT without Authorization header (session={})", accessor.getSessionId());
                        }
                    } else if (StompCommand.SUBSCRIBE.equals(command)) {
                        log.debug("WebSocket client subscribing to: {} (session={})", accessor.getDestination(), accessor.getSessionId());
                    } else if (StompCommand.DISCONNECT.equals(command)) {
                        log.debug("WebSocket client disconnecting: session={}", accessor.getSessionId());
                    }
                }
                return message;
            }
        });
    }
}
