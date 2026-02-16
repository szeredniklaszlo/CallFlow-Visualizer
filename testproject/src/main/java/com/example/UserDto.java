package com.example;

import java.util.List;

public class UserDto {
    private String username;
    private String bio;
    private List<String> tags;
    private int orderCount;

    // ALL of these should be HIDDEN in the graph
    // because they are simple getters/setters on a non-Entity class
    // or fields without risky annotations.

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public int getOrderCount() {
        return orderCount;
    }

    public void setOrderCount(int orderCount) {
        this.orderCount = orderCount;
    }
}
