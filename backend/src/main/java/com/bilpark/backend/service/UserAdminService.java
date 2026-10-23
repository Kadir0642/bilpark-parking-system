package com.bilpark.backend.service;

import com.bilpark.backend.common.ApiException;
import com.bilpark.backend.common.ErrorCodes;
import com.bilpark.backend.model.SyncEventType;
import com.bilpark.backend.model.User;
import com.bilpark.backend.repository.UserRepository;
import com.bilpark.backend.sync.SyncService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** Admin user management: listing and activating / deactivating accounts. */
@Service
public class UserAdminService {

    private final UserRepository userRepository;
    private final ShiftService shiftService;
    private final SyncService syncService;

    public UserAdminService(UserRepository userRepository, ShiftService shiftService, SyncService syncService) {
        this.userRepository = userRepository;
        this.shiftService = shiftService;
        this.syncService = syncService;
    }

    @Transactional(readOnly = true)
    public List<User> listUsers() {
        return userRepository.findAll();
    }

    @Transactional
    public User setActive(Long id, boolean active, String adminUsername) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "Kullanıcı bulunamadı."));
        if (!active && user.getUsername().equals(adminUsername)) {
            throw ApiException.badRequest(ErrorCodes.BAD_REQUEST, "Kendi hesabınızı pasife alamazsınız.");
        }
        boolean wasActive = user.isActive();
        user.setActive(active);
        userRepository.save(user);
        if (!active && wasActive) {
            shiftService.closeAllOpenShifts(user);
            syncService.userEvent(SyncEventType.USER_DEACTIVATED, user.getId(),
                    Map.of("userId", user.getId(), "username", user.getUsername()));
            syncService.closeUserStreams(user.getId());
        }
        return user;
    }
}
