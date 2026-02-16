package com.example;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;

@Service
public class AsyncRiskService {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final ExecutorService executor = Executors.newFixedThreadPool(10);

    public AsyncRiskService(KafkaTemplate<String, String> kafkaTemplate, ApplicationEventPublisher eventPublisher) {
        this.kafkaTemplate = kafkaTemplate;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Feature 12: Detect MQ Publisher
     */
    public void publishMessage(String payload) {
        System.out.println("Preparing to publish...");
        kafkaTemplate.send("my-topic", payload);
    }

    /**
     * Feature 12: Detect Spring Event Publisher
     */
    public void publishEvent(Object event) {
        eventPublisher.publishEvent(event);
    }

    /**
     * Feature 13: Async inside Transaction (High Risk)
     * This uses ExecutorService.submit() directly.
     */
    @Transactional
    public void riskyAsyncTransaction() {
        System.out.println("Inside transaction...");

        // This should be flagged via dashed line to 'asyncLogic'
        executor.submit(() -> {
            System.out.println("Processing in separate thread - lost transaction context!");
            asyncLogic();
        });

        saveData();
    }

    /**
     * Feature 13: Async inside Transaction (High Risk)
     * This uses new Thread().
     */
    @Transactional
    public void riskyThreadTransaction() {
        new Thread(() -> {
            System.out.println("Manual thread - dangerous!");
            asyncLogic();
        }).start();
    }

    /**
     * Feature 13: Async inside Transaction (High Risk)
     */
    @Transactional
    public void riskyFutureTransaction() {
        CompletableFuture.supplyAsync(() -> {
            asyncLogic();
            return "Async Result";
        });
    }

    /**
     * Feature 13: Calling @Async method from Transactional (High Risk)
     */
    @Transactional
    public void callAsyncMethodInTx() {
        // This call edge should be dashed and flagged
        asyncMethod();
    }

    @Async
    public void asyncMethod() {
        System.out.println("Running asynchronously via Spring annotation");
    }

    private void saveData() {
        // DB operation
    }

    private void asyncLogic() {
        System.out.println("Executing async business logic");
    }
}
