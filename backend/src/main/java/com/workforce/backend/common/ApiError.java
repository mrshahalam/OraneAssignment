package com.workforce.backend.common;

import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;

public record ApiError(String timestamp, int status, String error, String message,
                       String path, List<String> details) {
    public static ApiError of(HttpStatus status, String message, String path, List<String> details) {
        return new ApiError(Instant.now().toString(), status.value(),
                status.getReasonPhrase(), message, path, details);
    }
}