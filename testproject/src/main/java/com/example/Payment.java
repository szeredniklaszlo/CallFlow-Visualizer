package com.example;

import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.OneToMany;
import java.util.List;

@Entity
public class Payment {
    private String id;
    private double amount;
    private String status;

    @OneToMany(fetch = FetchType.EAGER)
    private List<String> auditEntries;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public double getAmount() {
        return amount;
    }

    public void setAmount(double amount) {
        this.amount = amount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public List<String> getAuditEntries() {
        return auditEntries;
    }

    public void setAuditEntries(List<String> auditEntries) {
        this.auditEntries = auditEntries;
    }
}
