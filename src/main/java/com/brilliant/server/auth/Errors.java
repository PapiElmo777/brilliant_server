package com.brilliant.server.auth;

import java.util.*;
import jakarta.servlet.http.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DataAccessException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class Errors {
    public static Map<String, Object> body(ApiException e, HttpServletRequest request) {
        Map<String,Object> detail = new LinkedHashMap<>();
        detail.put("code", e.code);
        detail.put("message", switch(e.code) {
            case "INVALID_CREDENTIALS" -> "Usuario o contraseña incorrectos.";
            case "USERNAME_UNAVAILABLE" -> "El nombre de usuario ya está ocupado.";
            case "CURRENT_PASSWORD_INVALID" -> "Contraseña actual incorrecta.";
            case "VALIDATION_ERROR" -> "Revisa los datos introducidos.";
            default -> "No se pudo completar la solicitud.";
        });
        if (!e.fields.isEmpty()) detail.put("fields", e.fields);
        return Map.of("error", detail, "requestId", Objects.toString(request.getAttribute("requestId"), ""));
    }
    public static void send(HttpServletRequest req, HttpServletResponse res, ApiException e) throws java.io.IOException {
        res.setStatus(e.status); res.setContentType("application/json"); res.setCharacterEncoding("UTF-8");
        if (e.retryAfter>0) res.setHeader("Retry-After", Long.toString(e.retryAfter));
        new ObjectMapper().writeValue(res.getOutputStream(), body(e, req));
    }
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> api(ApiException e, HttpServletRequest req) {
        var builder = ResponseEntity.status(e.status);
        if(e.retryAfter>0) builder.header("Retry-After", Long.toString(e.retryAfter));
        return builder.body(body(e,req));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> malformed(Exception e, HttpServletRequest req) { return api(new ApiException(400,"VALIDATION_ERROR"),req); }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<?> database(Exception e, HttpServletRequest req) { return api(new ApiException(503,"SERVICE_UNAVAILABLE"),req); }
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<?> missing(Exception e, HttpServletRequest req) { return api(new ApiException(404,"NOT_FOUND"),req); }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<?> method(Exception e, HttpServletRequest req) { return api(new ApiException(405,"METHOD_NOT_ALLOWED"),req); }
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<?> media(Exception e, HttpServletRequest req) { return api(new ApiException(415,"UNSUPPORTED_MEDIA_TYPE"),req); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception e, HttpServletRequest req) { return api(new ApiException(500,"INTERNAL_ERROR"),req); }
}
