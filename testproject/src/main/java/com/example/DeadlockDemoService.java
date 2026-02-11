package com.example;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestTemplate;
import java.util.List;

/**
 * Demonstration service showcasing ALL deadlock and performance risk patterns.
 * Use as a test target for the CallFlow Visualizer plugin.
 *
 * Patterns from Features 1-4:
 * 1. Read-only TX (@Tx(RO) — green)
 * 2. REQUIRES_NEW (!TX(NEW)! — red)
 * 3. TX + MQ send (danger combo)
 * 4. TX + HTTP call (danger combo)
 * 5. saveAndFlush (FLUSH!)
 * 6. Eager fetch (EAGER!)
 * 7. Loop-enclosed calls (thick edge)
 *
 * Patterns from Features 5-8:
 * 8. TABLE SCAN RISK (deleteByEmail on non-indexed column)
 * 9. CASCADE OPERATION (save/delete entity with cascade=ALL)
 * 10. EARLY INSERT LOCK (save entity with IDENTITY strategy)
 * 11. Critical path (combined worst-case)
 */
@Service
public class DeadlockDemoService {

    private final PaymentRepository paymentRepo;
    private final OrderRepository orderRepo;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final RestTemplate restTemplate;

    public DeadlockDemoService(PaymentRepository paymentRepo,
            OrderRepository orderRepo,
            KafkaTemplate<String, String> kafkaTemplate,
            RestTemplate restTemplate) {
        this.paymentRepo = paymentRepo;
        this.orderRepo = orderRepo;
        this.kafkaTemplate = kafkaTemplate;
        this.restTemplate = restTemplate;
    }

    // ============================================================
    // Pattern 1: Read-only transaction — safe, green badge
    // ============================================================
    @Transactional(readOnly = true)
    public List<Payment> listPayments(String status) {
        return paymentRepo.findAllByStatus(status);
    }

    // ============================================================
    // Pattern 2: REQUIRES_NEW — red badge, deadlock risk
    // ============================================================
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processInNewTransaction(String paymentId) {
        Payment payment = paymentRepo.findById(paymentId);
        payment.setStatus("PROCESSING");
        paymentRepo.save(payment);
    }

    // ============================================================
    // Pattern 3: Standard TX + MQ send = danger combo
    // ============================================================
    @Transactional
    public void processAndNotify(String paymentId) {
        Payment payment = paymentRepo.findById(paymentId);
        payment.setStatus("COMPLETED");
        paymentRepo.save(payment);
        kafkaTemplate.send("payment-events", "Payment " + paymentId + " completed");
    }

    // ============================================================
    // Pattern 4: Standard TX + HTTP call = danger combo
    // ============================================================
    @Transactional
    public void processWithExternalVerification(String paymentId) {
        Payment payment = paymentRepo.findById(paymentId);
        String result = restTemplate.getForObject(
                "https://api.external.com/verify/" + paymentId, String.class);
        payment.setStatus(result);
        paymentRepo.save(payment);
    }

    // ============================================================
    // Pattern 5: saveAndFlush — FLUSH! warning badge
    // ============================================================
    @Transactional
    public void urgentSave(Payment payment) {
        payment.setStatus("URGENT");
        paymentRepo.saveAndFlush(payment);
    }

    // ============================================================
    // Pattern 6: Eager fetch via repository — EAGER! badge
    // ============================================================
    @Transactional
    public List<Payment> loadAllWithAudit(String status) {
        return paymentRepo.findAllByStatus(status);
    }

    // ============================================================
    // Pattern 7: Loop-enclosed call — thick edge
    // ============================================================
    @Transactional
    public void batchProcess(List<String> paymentIds) {
        for (String id : paymentIds) {
            processInNewTransaction(id);
        }
    }

    // ============================================================
    // Feature 5: TABLE SCAN RISK — delete by non-indexed column
    // ============================================================
    @Transactional
    public void purgeOrdersByEmail(String email) {
        // 'email' is not indexed on Order entity → TABLE SCAN RISK!
        orderRepo.deleteByEmail(email);
    }

    // ============================================================
    // Feature 6: CASCADE OPERATION — save entity with cascade=ALL
    // Payment has @OneToMany(cascade = CascadeType.ALL)
    // ============================================================
    @Transactional
    public void savePaymentWithCascade(Payment payment) {
        // Saving Payment triggers cascade to all AuditEntry children
        paymentRepo.save(payment);
    }

    // ============================================================
    // Feature 6b: CASCADE + DELETE — delete parent with cascade
    // ============================================================
    @Transactional
    public void deletePaymentCascade(String paymentId) {
        Payment payment = paymentRepo.findById(paymentId);
        // Deleting Payment cascades delete to all AuditEntry children
        paymentRepo.delete(payment);
    }

    // ============================================================
    // Feature 7: EARLY INSERT LOCK — save entity with IDENTITY ID
    // Payment uses @GeneratedValue(strategy = IDENTITY)
    // ============================================================
    @Transactional
    public void createNewPayment(Payment newPayment) {
        newPayment.setStatus("NEW");
        // IDENTITY strategy: this triggers an immediate INSERT + DB lock
        paymentRepo.save(newPayment);
        // ... rest of long-running transaction holds the lock ...
        String verified = restTemplate.getForObject(
                "https://api.external.com/verify/new", String.class);
        newPayment.setStatus(verified);
        paymentRepo.save(newPayment);
    }

    // ============================================================
    // Feature 8: Critical Path — combined worst-case scenario
    // This method chains: TABLE SCAN + CASCADE + EARLY LOCK +
    // REQUIRES_NEW + HTTP call + MQ send + loop
    // ============================================================
    @Transactional
    public void apocalypsePipeline(List<String> emails) {
        for (String email : emails) {
            // Table scan risk
            orderRepo.deleteByEmail(email);

            // Create new payment (IDENTITY → early lock)
            Payment payment = new Payment();
            payment.setStatus("PENDING");
            paymentRepo.save(payment); // early INSERT lock + cascade

            // Flush immediately
            paymentRepo.saveAndFlush(payment);

            // HTTP call inside TX inside loop
            String status = restTemplate.getForObject(
                    "https://api.external.com/status/" + email, String.class);
            payment.setStatus(status);

            // MQ send inside TX inside loop
            kafkaTemplate.send("order-events", "Processed: " + email);

            // REQUIRES_NEW inside loop inside TX
            processInNewTransaction(payment.getId().toString());
        }
    }
}
