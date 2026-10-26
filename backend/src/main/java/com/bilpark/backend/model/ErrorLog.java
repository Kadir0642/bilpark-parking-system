package com.bilpark.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** An unexpected server error (500), kept for the developer's recent-errors view. */
@Data
@NoArgsConstructor
@Entity
@Table(name = "error_logs")
public class ErrorLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(length = 10)
    private String method;

    @Column(length = 255)
    private String path;

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(name = "exception_class", length = 255)
    private String exceptionClass;

    @Column(length = 1000)
    private String message;

    @Column(name = "stack_excerpt", columnDefinition = "text")
    private String stackExcerpt;

    @Column(length = 255)
    private String username;
}
