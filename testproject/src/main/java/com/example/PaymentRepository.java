package com.example;

import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public class PaymentRepository {
    public void save(Payment payment) {
        // Simulate save
    }

    public void saveAndFlush(Payment payment) {
        // Simulate saveAndFlush - acquires locks immediately
    }

    public Payment findById(String id) {
        return new Payment();
    }

    public List<Payment> findAllByStatus(String status) {
        // Returns entities with @OneToMany(fetch=EAGER) - triggers EAGER warning
        return List.of();
    }
}
