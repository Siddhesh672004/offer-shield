package com.offershield.submission;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.offershield.common.web.ConflictException;
import com.offershield.common.web.TooManyRequestsException;
import io.github.bucket4j.Bucket;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-user protection of the PDF reader (P13 security review S1), in memory like the request limiter (single
 * instance, D-01): one PDF read at a time per user (409 {@code UPLOAD_IN_PROGRESS}), and at most
 * {@value #MAX_FAILED_READS} failed reads per user in {@link #FAILED_READ_WINDOW} (429 {@code UPLOAD_ATTEMPT_LIMIT}
 * with Retry-After). A rejected file creates no submission row, so the D-12 upload count never sees it; this bucket
 * does. The bucket refills one read every minute.
 */
final class PdfReadGuard {

    static final int MAX_FAILED_READS = 10;
    static final Duration FAILED_READ_WINDOW = Duration.ofMinutes(10);
    private static final long MAX_TRACKED_USERS = 100_000;

    private final Set<UUID> reading = ConcurrentHashMap.newKeySet();
    private final Cache<UUID, Bucket> failedReads = Caffeine.newBuilder()
            .expireAfterAccess(FAILED_READ_WINDOW)
            .maximumSize(MAX_TRACKED_USERS)
            .build();

    /**
     * @throws TooManyRequestsException the user's recent PDFs failed to read too often
     * @throws ConflictException        another PDF of the user is being read
     */
    void startReading(UUID userId) {
        Bucket bucket = failedReads.getIfPresent(userId);
        if (bucket != null && bucket.getAvailableTokens() < 1) {
            Duration wait = Duration.ofNanos(bucket.estimateAbilityToConsume(1).getNanosToWaitForRefill());
            throw new TooManyRequestsException("UPLOAD_ATTEMPT_LIMIT",
                    "Too many letters that couldn't be read. Try again in a few minutes.", wait);
        }
        if (!reading.add(userId)) {
            throw new ConflictException("UPLOAD_IN_PROGRESS", "Another letter is still being read. Wait for it to finish.");
        }
    }

    /** Ends the read started by {@link #startReading}; {@code failed} when the file could not be read. */
    void finishReading(UUID userId, boolean failed) {
        if (failed) {
            failedReads.get(userId, id -> newBucket()).tryConsume(1);
        }
        reading.remove(userId);
    }

    private static Bucket newBucket() {
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(MAX_FAILED_READS).refillGreedy(MAX_FAILED_READS, FAILED_READ_WINDOW))
                .build();
    }
}
