package com.offershield.submission;

import java.util.UUID;

/**
 * A submission whose 30 days are over (D-06) that the retention job still has work on: its row is not wiped yet, or
 * its private PDF is still to be deleted ({@code cloudinaryPublicId} is {@code null} for pasted text or once deleted).
 */
public record ExpiredSubmission(UUID id, String cloudinaryPublicId) {
}
