package com.offershield.auth;

public enum Role {
    USER,
    TPO,
    ADMIN;

    public String authority() {
        return "ROLE_" + name();
    }
}
