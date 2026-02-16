package com.example;

import jakarta.persistence.*;

import java.util.List;

@Entity
@Table(name = "users")
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String username;

    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
    private List<Order> orders;

    @Lob
    @Basic(fetch = FetchType.LAZY)
    private String bio;

    @ElementCollection
    private List<String> tags;

    // Normal getter - SHOULD BE HIDDEN
    public String getUsername() {
        return username;
    }

    // Setter - SHOULD BE HIDDEN
    public void setUsername(String username) {
        this.username = username;
    }

    // Lazy collection getter - SHOULD BE VISIBLE (Performance Risk)
    public List<Order> getOrders() {
        return orders;
    }

    // Lob getter - SHOULD BE VISIBLE (Performance Risk)
    public String getBio() {
        return bio;
    }

    // ElementCollection getter - SHOULD BE VISIBLE
    public List<String> getTags() {
        return tags;
    }

    public void setOrders(List<Order> orders) {
        this.orders = orders;
    }
}
