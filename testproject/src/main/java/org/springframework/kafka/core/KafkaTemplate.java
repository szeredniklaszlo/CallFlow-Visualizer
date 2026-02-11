package org.springframework.kafka.core;

public class KafkaTemplate<K, V> {
    public void send(String topic, V data) {
    }

    public void sendDefault(V data) {
    }
}
