package com.bilpark.backend.auth;

import com.bilpark.backend.model.User;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import org.springframework.stereotype.Component;

/**
 * JPA listener on {@link User}: any insert, update or delete of a users row through JPA evicts that user from
 * {@link AuthUserCache}, so a password reset, deactivation or role change is seen by the next request.
 * Hibernate gets this instance from Spring (SpringBeanContainer), hence the constructor injection.
 * The device-trace UPDATE (last_seen_at etc.) bypasses JPA on purpose; those columns do not affect auth.
 */
@Component
public class UserCacheEvictionListener {

    private final AuthUserCache cache;

    public UserCacheEvictionListener(AuthUserCache cache) {
        this.cache = cache;
    }

    @PostPersist
    @PostUpdate
    @PostRemove
    public void evict(User user) {
        if (user != null && user.getUsername() != null) cache.evict(user.getUsername());
    }
}
