package org.springframework.web.client;

public class RestTemplate {
    public <T> T getForObject(String url, Class<T> responseType, Object... uriVariables) {
        return null;
    }

    public <T> T postForObject(String url, Object request, Class<T> responseType, Object... uriVariables) {
        return null;
    }

    public void put(String url, Object request, Object... uriVariables) {
    }

    public void delete(String url, Object... uriVariables) {
    }
}
