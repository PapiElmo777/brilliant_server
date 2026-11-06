package com.brilliant.server.rooms;

import com.brilliant.server.config.Settings;
import java.util.Arrays;
import org.springframework.context.annotation.*;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class RoomSocketConfiguration implements WebSocketConfigurer {
    private final RoomSocket socket;
    private final Settings.Values settings;
    public RoomSocketConfiguration(RoomSocket socket,Settings.Values settings) {this.socket=socket;this.settings=settings;}
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        var registration=registry.addHandler(socket,"/api/v1/rooms/*/events").addInterceptors(socket);
        if(settings.allowedOrigins()!=null && !settings.allowedOrigins().isBlank())
            registration.setAllowedOrigins(Arrays.stream(settings.allowedOrigins().split(",")).map(String::strip).toArray(String[]::new));
    }
    @Bean ServletServerContainerFactoryBean socketContainer() {
        var container=new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(256);container.setMaxBinaryMessageBufferSize(256);
        container.setMaxSessionIdleTimeout(25000L);container.setAsyncSendTimeout(1500L);return container;
    }
}
