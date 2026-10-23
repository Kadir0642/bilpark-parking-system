package com.bilpark.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A street managed by the company. "code" is the StreetLocation enum name (e.g. TEVFIK_BEY),
 * which stays the stable key used by zones, spots and records.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "streets")
public class Street {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    public Street(String code, String displayName, int sortOrder) {
        this.code = code;
        this.displayName = displayName;
        this.sortOrder = sortOrder;
    }
}
