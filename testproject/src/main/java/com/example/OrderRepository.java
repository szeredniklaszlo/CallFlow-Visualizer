package com.example;

import org.springframework.stereotype.Repository;
import java.util.List;

/**
 * Repository for Order entity.
 * Demonstrates TABLE_SCAN_RISK: deleteByEmail queries a non-indexed field.
 * findByOrderNumber is safe because orderNumber has @Column(unique=true).
 */
@Repository
public interface OrderRepository {
    /** DANGEROUS: 'email' is not indexed → full table scan + table lock */
    void deleteByEmail(String email);

    /** SAFE: 'orderNumber' has @Column(unique=true) */
    Order findByOrderNumber(String orderNumber);

    /** DANGEROUS: 'status' is not indexed */
    List<Order> findByStatus(String status);

    Order save(Order order);

    void delete(Order order);
}
