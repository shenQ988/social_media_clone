package com.pixframe.util;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Ports pixframe/api/api_utils.py's error_400()/error_403()/error_404()/error_409(). */
public final class ApiError {

    private ApiError() {
    }

    private static ResponseEntity<Map<String, Object>> build(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        body.put("status_code", status.value());
        return ResponseEntity.status(status).body(body);
    }

    public static ResponseEntity<Map<String, Object>> badRequest() {
        return build(HttpStatus.BAD_REQUEST, "Bad Request");
    }

    public static ResponseEntity<Map<String, Object>> forbidden() {
        return build(HttpStatus.FORBIDDEN, "Forbidden");
    }

    public static ResponseEntity<Map<String, Object>> notFound() {
        return build(HttpStatus.NOT_FOUND, "Not Found");
    }

    public static ResponseEntity<Map<String, Object>> conflict() {
        return build(HttpStatus.CONFLICT, "Conflict");
    }
}
