package com.workforce.backend.auth;

import com.workforce.backend.employee.AppUserDetails;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentUser {
    private CurrentUser() {}

    public static AppUserDetails details() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AppUserDetails details)) {
            throw new org.springframework.security.access.AccessDeniedException("Unauthenticated");
        }
        return details;
    }
}
