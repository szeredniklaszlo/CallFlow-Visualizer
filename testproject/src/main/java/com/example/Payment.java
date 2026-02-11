package com.example;

import javax.persistence.*;
import java.util.List;

/**
 * Payment entity demonstrating multiple JPA risk patterns:
 * - @Id with @GeneratedValue(IDENTITY) → early INSERT lock
 * - @OneToMany with cascade=ALL + fetch=EAGER → cascade + eager risks
 */
@Entity
public class Payment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String status;
    private double amount;

    @OneToMany(fetch = FetchType.EAGER, cascade = CascadeType.ALL, orphanRemoval = true)
    private List<AuditEntry> auditEntries;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public double getAmount() {
        return amount;
    }

    public void setAmount(double amount) {
        this.amount = amount;
    }

    public List<AuditEntry> getAuditEntries() {
        return auditEntries;
    }

    public void setAuditEntries(List<AuditEntry> auditEntries) {
        this.auditEntries = auditEntries;
    }
}
