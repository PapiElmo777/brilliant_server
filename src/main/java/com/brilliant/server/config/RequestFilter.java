package com.brilliant.server.config;

import com.brilliant.server.auth.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.UUID;
import org.slf4j.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(RequestFilter.class);
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id=UUID.randomUUID().toString(); request.setAttribute("requestId",id);
        response.setHeader("X-Request-Id",id); response.setHeader("Cache-Control","no-store");
        response.setHeader("X-Content-Type-Options","nosniff"); response.setHeader("X-Frame-Options","DENY");
        response.setHeader("Content-Security-Policy","default-src 'none'; frame-ancestors 'none'");
        response.setHeader("Referrer-Policy","no-referrer");
        try {
            if(request.getContentLengthLong()>8192) { Errors.send(request,response,new ApiException(413,"PAYLOAD_TOO_LARGE")); return; }
            if (java.util.Set.of("POST","PATCH","DELETE","PUT").contains(request.getMethod())) {
                byte[] body=request.getInputStream().readNBytes(8193);
                if(body.length>8192) { Errors.send(request,response,new ApiException(413,"PAYLOAD_TOO_LARGE")); return; }
                HttpServletRequestWrapper wrapped = new HttpServletRequestWrapper(request) {
                    @Override public ServletInputStream getInputStream() {
                        ByteArrayInputStream input = new ByteArrayInputStream(body);
                        return new ServletInputStream() {
                            public int read() { return input.read(); }
                            public boolean isFinished() { return input.available()==0; }
                            public boolean isReady() { return true; }
                            public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                        };
                    }
                    @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8)); }
                };
                chain.doFilter(wrapped,response);
            } else chain.doFilter(request,response);
        } finally {
            // No URL, IP, nombre, cabeceras, cuerpo, excepción ni secretos.
            LOG.info("event=http result={} requestId={}",response.getStatus(),id);
        }
    }
}
