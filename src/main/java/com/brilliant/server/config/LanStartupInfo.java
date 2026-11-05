package com.brilliant.server.config;

import java.net.*;
import java.util.Collections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Shows the address to enter in the devices; never logs request data. */
@Component
@Profile("lan")
public class LanStartupInfo implements ApplicationListener<WebServerInitializedEvent> {
    private static final Logger LOG = LoggerFactory.getLogger(LanStartupInfo.class);
    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        int port = event.getWebServer().getPort();
        LOG.info("Servidor listo en el puerto {}. Conecta los dispositivos a la misma red Wi-Fi.", port);
        try {
            var interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return;
            for (var network : Collections.list(interfaces)) {
                if (!network.isUp() || network.isLoopback()) continue;
                for (var address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        LOG.info("Dirección para la app: http://{}:{}", address.getHostAddress(), port);
                    }
                }
            }
        } catch (SocketException e) {
            LOG.warn("No se pudo obtener la IP local. Consulta la dirección Wi-Fi de esta computadora.");
        }
    }
}
