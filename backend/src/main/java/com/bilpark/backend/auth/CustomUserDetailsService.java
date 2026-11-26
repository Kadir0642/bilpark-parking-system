package com.bilpark.backend.auth;

import com.bilpark.backend.config.ClockConfig;
import com.bilpark.backend.model.User;
import com.bilpark.backend.repository.UserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final AuthUserCache cache;

    public CustomUserDetailsService(UserRepository userRepository, AuthUserCache cache) {
        this.userRepository = userRepository;
        this.cache = cache;
    }

    /** Fresh read with the password hash: login (DaoAuthenticationProvider) and token issuing. */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return toDetails(find(username), true);
    }

    /**
     * The user a valid JWT / SSE ticket belongs to, from {@link AuthUserCache} (short TTL, evicted on every
     * users row change). No password hash in the result. UsernameNotFoundException is not cached; any other
     * exception (database unreachable, pool timeout) propagates so the caller can answer 503 instead of 401.
     */
    public UserDetails loadForToken(String username) throws UsernameNotFoundException {
        return cache.get(username, u -> toDetails(find(u), false));
    }

    private User find(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }

    private static UserDetails toDetails(User user, boolean withPassword) {
        return new BilparkUserDetails(
                user.getUsername(),
                withPassword ? user.getPassword() : "",
                user.isActive(),
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())),
                user.getPasswordChangedAt() != null
                        ? user.getPasswordChangedAt().atZone(ClockConfig.ZONE).toInstant() : null);
    }
}
