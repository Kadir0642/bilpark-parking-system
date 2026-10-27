package com.bilpark.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A vehicle type row (Flyway V5). "active" is derived from the ACTIVE tariff's enabled set
 * and is only changed by VehicleTypeService.syncActiveFrom.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "vehicle_types")
public class VehicleType {

    @Id
    @Column(length = 30)
    private String code;

    @Column(name = "display_name", nullable = false, length = 60)
    private String displayName;

    @Column(name = "icon_key", nullable = false, length = 30)
    private String iconKey;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(nullable = false)
    private boolean active;

    public VehicleType(String code, String displayName, String iconKey, int sortOrder, boolean active) {
        this.code = code;
        this.displayName = displayName;
        this.iconKey = iconKey;
        this.sortOrder = sortOrder;
        this.active = active;
    }
}
