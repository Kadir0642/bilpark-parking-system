package com.bilpark.backend.controller;

import com.bilpark.backend.service.VehicleTypeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class VehicleTypeController {

    private final VehicleTypeService vehicleTypeService;

    public VehicleTypeController(VehicleTypeService vehicleTypeService) {
        this.vehicleTypeService = vehicleTypeService;
    }

    // Public: active types only
    @GetMapping("/api/vehicle-types")
    public List<Map<String, Object>> active() {
        return vehicleTypeService.activePublicList();
    }

    // ADMIN: every type with its active flag
    @GetMapping("/api/admin/vehicle-types")
    public List<Map<String, Object>> all() {
        return vehicleTypeService.all().stream().map(t -> VehicleTypeService.toMap(t, true)).toList();
    }
}
