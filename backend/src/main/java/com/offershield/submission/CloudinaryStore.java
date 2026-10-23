package com.offershield.submission;

import com.cloudinary.Cloudinary;
import com.offershield.common.Hashing;
import com.offershield.common.web.ServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Original PDFs as private ("authenticated" delivery type) Cloudinary assets (PRD §9.1, D-05). Uploads happen server-side and only after
 * validation; nothing is publicly addressable, and reviewers get short-lived signed download links (P10). When
 * {@code CLOUDINARY_URL} is blank the store is "not configured": PDFs are simply not kept and links are unavailable.
 * The URL holds the API secret, so it is never logged or put into an exception message.
 */
@Component
public class CloudinaryStore {

    static final int TIMEOUT_SECONDS = 15;
    private static final String PUBLIC_ID_PREFIX = "submissions/";
    private static final String RESOURCE_TYPE = "image";
    // "authenticated", not "private": a private asset's derived versions (e.g. a page rendered as JPG) are served without a
    // signature unless the account enables Strict transformations (found live 2026-11-18); authenticated assets need a
    // signature for the original and every derived version.
    private static final String DELIVERY_TYPE = "authenticated";
    private static final Logger log = LoggerFactory.getLogger(CloudinaryStore.class);

    private final Cloudinary cloudinary;

    @Autowired
    public CloudinaryStore(@Value("${offershield.cloudinary.url:}") String cloudinaryUrl) {
        this(create(cloudinaryUrl));
        if (cloudinary == null) {
            // WARN (P13): in production this is a misconfiguration; reviewers then get no PDF link (D-05).
            log.warn("pdf storage not configured (CLOUDINARY_URL blank): original PDFs will not be kept");
        }
    }

    CloudinaryStore(Cloudinary cloudinary) {
        this.cloudinary = cloudinary;
    }

    public boolean isConfigured() {
        return cloudinary != null;
    }

    /**
     * {@code submissions/} + sha256(owner:idempotencyKey:requestSha256). A byte-identical retry overwrites the same
     * asset; a request that reuses the key for other content gets its own asset, so it can never replace the PDF of
     * the submission that was actually saved.
     */
    public static String publicIdFor(UUID ownerUserId, String idempotencyKey, String requestSha256) {
        return PUBLIC_ID_PREFIX + Hashing.sha256Hex(ownerUserId + ":" + idempotencyKey + ":" + requestSha256);
    }

    /** @throws ServiceUnavailableException when Cloudinary fails or times out (the caller saves nothing) */
    public void upload(String publicId, byte[] pdf) {
        Cloudinary client = requireConfigured();
        try {
            client.uploader().upload(pdf, Map.of(
                    "public_id", publicId,
                    "type", DELIVERY_TYPE,
                    "resource_type", RESOURCE_TYPE,
                    "overwrite", true,
                    "invalidate", true));
            log.info("pdf stored");
        } catch (Exception ex) {
            log.warn("pdf upload failed: {}", ex.getClass().getSimpleName());
            throw new ServiceUnavailableException("STORAGE_UNAVAILABLE", "Upload failed. Please try again.");
        }
    }

    /** Idempotent: an asset that is already gone counts as deleted. */
    public void delete(String publicId) {
        Cloudinary client = requireConfigured();
        Map<?, ?> result;
        try {
            result = client.uploader().destroy(publicId, Map.of(
                    "type", DELIVERY_TYPE,
                    "resource_type", RESOURCE_TYPE,
                    "invalidate", true));
        } catch (Exception ex) {
            log.warn("pdf delete failed: {}", ex.getClass().getSimpleName());
            throw new IllegalStateException("pdf delete failed: " + ex.getClass().getSimpleName());
        }
        Object outcome = result == null ? null : result.get("result");
        if (!"ok".equals(outcome) && !"not found".equals(outcome)) {
            log.warn("pdf delete failed: unexpected result");
            throw new IllegalStateException("pdf delete failed: unexpected result");
        }
    }

    /** A signed link that stops working after {@code ttl}; empty when storage is not configured. */
    public Optional<String> downloadLink(String publicId, Duration ttl) {
        return downloadLink(publicId, Instant.now().plus(ttl));
    }

    /**
     * A signed link that stops working at {@code expiresAt} (whole seconds); empty when storage is not configured.
     * Signing happens locally (no call to Cloudinary). The link is a credential for the file: never log it.
     */
    public Optional<String> downloadLink(String publicId, Instant expiresAt) {
        if (cloudinary == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(cloudinary.privateDownload(publicId, "pdf", Map.of(
                    "type", DELIVERY_TYPE,
                    "resource_type", RESOURCE_TYPE,
                    "expires_at", expiresAt.getEpochSecond(),
                    "attachment", true)));
        } catch (Exception ex) {
            log.warn("pdf link failed: {}", ex.getClass().getSimpleName());
            throw new ServiceUnavailableException("STORAGE_UNAVAILABLE", "The PDF link could not be created.");
        }
    }

    Cloudinary client() {
        return cloudinary;
    }

    private Cloudinary requireConfigured() {
        if (cloudinary == null) {
            throw new IllegalStateException("pdf storage is not configured");
        }
        return cloudinary;
    }

    private static Cloudinary create(String cloudinaryUrl) {
        if (cloudinaryUrl == null || cloudinaryUrl.isBlank()) {
            return null;
        }
        Cloudinary client;
        try {
            client = new Cloudinary(cloudinaryUrl.trim());
        } catch (RuntimeException ex) {
            // The SDK's message can repeat the URL, which contains the API secret: drop it.
            throw invalidUrl();
        }
        if (client.config.cloudName == null || client.config.apiKey == null || client.config.apiSecret == null) {
            throw invalidUrl();
        }
        client.config.secure = true;
        client.config.timeout = TIMEOUT_SECONDS;
        return client;
    }

    private static IllegalStateException invalidUrl() {
        return new IllegalStateException(
                "CLOUDINARY_URL must look like cloudinary://<api_key>:<api_secret>@<cloud_name> (or be left empty).");
    }
}
