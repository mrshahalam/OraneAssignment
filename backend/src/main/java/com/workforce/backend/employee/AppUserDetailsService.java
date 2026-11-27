package com.workforce.backend.employee;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import com.workforce.backend.employee.model.UserEntity;

@Service
public class AppUserDetailsService implements UserDetailsService {
    private final com.workforce.backend.employee.repository.UserRepository userRepository;

    public AppUserDetailsService(com.workforce.backend.employee.repository.UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return userRepository.findByUsername(username)
                .map(this::toUserDetails)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }

    private AppUserDetails toUserDetails(UserEntity user) {
        return new AppUserDetails(user.getEmployee().getId(), user.getUsername(),
                user.getPasswordHash(), user.getRole(),
                "ACTIVE".equals(user.getEmployee().getEmploymentStatus()));
    }
}