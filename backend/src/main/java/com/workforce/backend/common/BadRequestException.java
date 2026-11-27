package com.workforce.backend.common;

public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) { super(message); }
}
