package com.bilpark.backend.auth;

import com.bilpark.backend.model.User;
import com.bilpark.backend.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final CustomUserDetailsService userDetailsService;
    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    public AuthController(AuthenticationManager authenticationManager, 
                          CustomUserDetailsService userDetailsService, 
                          JwtUtil jwtUtil,
                          UserRepository userRepository) {
        this.authenticationManager = authenticationManager;
        this.userDetailsService = userDetailsService;
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        try {
            // Authenticate the user
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password())
            );
        } catch (AuthenticationException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid username or password"));
        }

        // Generate token
        final UserDetails userDetails = userDetailsService.loadUserByUsername(request.username());
        final String token = jwtUtil.generateToken(userDetails);
        
        // Fetch user from DB to get the assigned street and role
        User user = userRepository.findByUsername(request.username()).orElseThrow();
        
        user.setLastLoginTime(java.time.LocalDateTime.now());
        userRepository.save(user);

        return ResponseEntity.ok(new LoginResponse(
                token, 
                user.getUsername(), 
                user.getRole().name(), 
                user.getAssignedZone()
        ));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(java.security.Principal principal) {
        if (principal != null) {
            User user = userRepository.findByUsername(principal.getName()).orElse(null);
            if (user != null) {
                user.setLastLoginTime(null);
                userRepository.save(user);
            }
        }
        return ResponseEntity.ok(Map.of("message", "Logged out and shift ended."));
    }
}
