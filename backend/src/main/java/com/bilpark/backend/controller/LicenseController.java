package com.bilpark.backend.controller;

import com.bilpark.backend.license.LicenseService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Any logged-in role (SecurityConfig). The developer changes the license via /api/developer/license. */
@RestController
@RequestMapping("/api/license")
public class LicenseController {

    private final LicenseService licenseService;

    public LicenseController(LicenseService licenseService) {
        this.licenseService = licenseService;
    }

    @GetMapping("/status")
    public LicenseService.LicenseStatus status() {
        return licenseService.status();
    }
}
