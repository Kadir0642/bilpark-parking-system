package com.bilpark.backend.controller;

import com.bilpark.backend.common.ApiException;
import com.bilpark.backend.common.ErrorCodes;
import com.bilpark.backend.settings.SettingsService;
import com.bilpark.backend.settings.SettingsService.PerformanceWeights;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Admin settings: personnel score weights and the yearly rent. ADMIN only (/api/admin/**); audited by the service. */
@RestController
@RequestMapping("/api/admin/settings")
public class SettingsController {

    public record RentRequest(Integer year, Double annualRent) {}

    private final SettingsService settings;

    public SettingsController(SettingsService settings) {
        this.settings = settings;
    }

    @GetMapping("/performance-weights")
    public PerformanceWeights getWeights() {
        return settings.getPerformanceWeights();
    }

    @PutMapping("/performance-weights")
    public PerformanceWeights putWeights(@RequestBody PerformanceWeights body) {
        return settings.updatePerformanceWeights(body);
    }

    @GetMapping("/rent")
    public Map<String, Object> getRent() {
        List<Map<String, Object>> years = new ArrayList<>();
        settings.annualRents().forEach((year, amount) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("year", year);
            m.put("annualRent", amount);
            m.put("monthlyShare", amount / 12.0);
            years.add(m);
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("years", years);
        return out;
    }

    @PutMapping("/rent")
    public Map<String, Object> putRent(@RequestBody RentRequest body) {
        if (body == null || body.year() == null || body.annualRent() == null) {
            throw ApiException.badRequest(ErrorCodes.INVALID_SETTINGS, "Yıl ve yıllık kira bedeli gerekli.");
        }
        return settings.setAnnualRent(body.year(), body.annualRent());
    }
}
