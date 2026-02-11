package org.springframework.transaction.annotation;

public enum Propagation {
    REQUIRED,
    REQUIRES_NEW,
    SUPPORTS,
    NOT_SUPPORTED,
    MANDATORY,
    NEVER,
    NESTED
}
