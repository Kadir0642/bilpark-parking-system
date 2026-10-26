package com.bilpark.backend.auth;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.time.Instant;
import java.util.Collection;

/** Spring Security user plus the last password change (tokens issued before it are rejected). */
public class BilparkUserDetails extends User {

    private final Instant passwordChangedAt;

    public BilparkUserDetails(String username, String password, boolean enabled,
                              Collection<? extends GrantedAuthority> authorities, Instant passwordChangedAt) {
        super(username, password, enabled, true, true, true, authorities);
        this.passwordChangedAt = passwordChangedAt;
    }

    public Instant getPasswordChangedAt() {
        return passwordChangedAt;
    }
}
