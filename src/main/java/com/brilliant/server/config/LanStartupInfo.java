package com.brilliant.server.config;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/** Shows the address to enter in the devices; never logs request data. */
@Component
public class LanStartupInfo implements ApplicationListener<WebServerInitializedEvent> {
    private static final Logger LOG = LoggerFactory.getLogger(LanStartupInfo.class);
    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        int port = event.getWebServer().getPort();
        var environment = event.getApplicationContext().getEnvironment();
        String bind = environment.getProperty("server.address", "127.0.0.1");
        String scheme = Boolean.parseBoolean(environment.getProperty("server.ssl.enabled")) ? "https" : "http";
        var message = new StringBuilder("\n\n========== CONEXIÓN A BRILLIANT ==========\n")
                .append("Puerto: ").append(port).append('\n');
        try {
            var boundAddress = InetAddress.getByName(bind);
            var interfaces = NetworkInterface.getNetworkInterfaces();
            boolean found = false;
            for (var network : interfaces == null ? Collections.<NetworkInterface>emptyList() : Collections.list(interfaces)) {
                if (!network.isUp() || network.isLoopback()) continue;
                for (var address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()
                            && !address.isLinkLocalAddress() && !address.isMulticastAddress()) {
                        found = true;
                        message.append("IP actual (").append(network.getName()).append("): ")
                                .append(address.getHostAddress()).append('\n');
                        if (boundAddress.isAnyLocalAddress() || boundAddress.equals(address)) {
                            message.append("En el teléfono → Servidor: ").append(scheme).append("://")
                                    .append(address.getHostAddress()).append(':').append(port).append('\n');
                        }
                    }
                }
            }
            if (!found) message.append("No se detectó una IP de red. Revisa la conexión Wi-Fi de esta computadora.\n");
            if (boundAddress.isLoopbackAddress()) {
                message.append("Solo acepta conexiones locales. Para conectar teléfonos, arranca con --spring.profiles.active=lan.\n");
            }
            if (boundAddress.isLoopbackAddress() || boundAddress.isAnyLocalAddress()) {
                message.append("Emulador Android en esta computadora → Servidor: ")
                        .append(scheme).append("://10.0.2.2:").append(port).append('\n');
            }
        } catch (IOException e) {
            message.append("No se pudo consultar la IP. Revisa la dirección Wi-Fi de esta computadora.\n");
        }
        message.append("Teléfono y computadora deben estar en la misma red Wi-Fi.\n")
                .append("Copia la dirección en la app y pulsa Guardar y conectar.\n")
                .append("========================================\n");
        LOG.info("{}", message);
    }
}
