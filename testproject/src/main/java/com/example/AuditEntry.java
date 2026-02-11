package com.example;

import javax.persistence.*;

/**
 * Simple audit entry entity referenced by Payment.
 */
@Entity
public class AuditEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String action;
    private String timestamp;

    public Long getId() {
        return id;
    }

    public String getAction() {
        return action;
    }

    public String getTimestamp() {
        return timestamp;
    }
}
