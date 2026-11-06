package com.brilliant.server.config;

import com.brilliant.server.auth.*;
import com.brilliant.server.users.Accounts;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.*;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class Security {
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, Accounts accounts, Settings.Values settings, Environment env) throws Exception {
        if(settings.production() && !env.getProperty("server.ssl.enabled",Boolean.class,false))
            throw new IllegalStateException("Producción requiere TLS integrado (server.ssl.enabled=true)");
        http.csrf(c -> c.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(c -> c.disable()).cors(c -> c.configurationSource(cors(settings)))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api/v1/health/live","/api/v1/health/ready").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/v1/auth/register","/api/v1/auth/login","/api/v1/auth/refresh").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req,res,ex) -> Errors.send(req,res,new ApiException(401,"UNAUTHORIZED")))
                .accessDeniedHandler((req,res,ex) -> Errors.send(req,res,new ApiException(403,"FORBIDDEN"))))
            .oauth2ResourceServer(o -> o.jwt(j -> {}).authenticationEntryPoint((req,res,ex) -> {
                boolean expired=false;
                for(Throwable t=ex;t!=null;t=t.getCause())
                    if(t instanceof JwtValidationException v)
                        expired=v.getErrors().stream().anyMatch(error -> error.getErrorCode().equals("access_token_expired"));
                Errors.send(req,res,new ApiException(401,expired?"ACCESS_TOKEN_EXPIRED":"UNAUTHORIZED"));
            }));
        http.addFilterAfter(new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws IOException,ServletException {
                var auth=SecurityContextHolder.getContext().getAuthentication();
                if(auth instanceof JwtAuthenticationToken token) {
                    boolean logout=req.getMethod().equals("POST") && req.getRequestURI().equals("/api/v1/auth/logout");
                    try {
                        if(!accounts.active(token.getToken().getSubject(),token.getToken().getClaimAsString("sid"),logout))
                            throw new ApiException(401,"SESSION_INVALID");
                    } catch(ApiException e) { Errors.send(req,res,e); return; }
                    catch(org.springframework.dao.DataAccessException e) { Errors.send(req,res,new ApiException(503,"SERVICE_UNAVAILABLE")); return; }
                }
                chain.doFilter(req,res);
            }
        },BearerTokenAuthenticationFilter.class);
        return http.build();
    }
    private CorsConfigurationSource cors(Settings.Values settings) {
        CorsConfiguration config=new CorsConfiguration();
        config.setAllowedOrigins(settings.allowedOrigins()==null || settings.allowedOrigins().isBlank()? List.of():Arrays.stream(settings.allowedOrigins().split(",")).map(String::strip).toList());
        if(config.getAllowedOrigins().contains("*")) throw new IllegalArgumentException("CORS exige orígenes explícitos");
        config.setAllowedMethods(List.of("GET","POST","PATCH","PUT","DELETE","OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization","Content-Type"));
        config.setExposedHeaders(List.of("X-Request-Id","Retry-After"));
        config.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source=new UrlBasedCorsConfigurationSource();source.registerCorsConfiguration("/api/v1/**",config);return source;
    }
}
