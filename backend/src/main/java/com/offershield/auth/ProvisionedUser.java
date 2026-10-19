package com.offershield.auth;

import java.util.UUID;

public record ProvisionedUser(UUID userId, Role role) {
}
