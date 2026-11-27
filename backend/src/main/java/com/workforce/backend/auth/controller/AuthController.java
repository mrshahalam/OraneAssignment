package com.workforce.backend.auth.controller;

import com.workforce.backend.employee.AppUserDetails;
import com.workforce.backend.employee.JwtService;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    public AuthController(AuthenticationManager authenticationManager, JwtService jwtService) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
    }

    public record LoginRequest(String username, String password) {}
    public record LoginResponse(String token, long expiresIn, String role, long employeeId) {}

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        Authentication auth = authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        AppUserDetails details = (AppUserDetails) auth.getPrincipal();
        return new LoginResponse(jwtService.generateToken(details),
            jwtService.getExpirationSeconds(), details.getRole().name(), details.getEmployeeId());
    }
}
