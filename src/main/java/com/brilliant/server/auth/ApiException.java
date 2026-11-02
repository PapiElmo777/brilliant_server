package com.brilliant.server.auth;
import java.util.Map;
public class ApiException extends RuntimeException {
    public final int status;
    public final String code;
    public final Map<String, String> fields;
    public final long retryAfter;
    public ApiException(int status, String code) { this(status, code, Map.of(), 0); }
    public ApiException(int status, String code, Map<String, String> fields, long retryAfter) {
        super(code); this.status=status; this.code=code; this.fields=fields; this.retryAfter=retryAfter;
    }
}
