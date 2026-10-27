package com.offershield.submission;

import com.cloudinary.Cloudinary;
import com.cloudinary.Uploader;
import com.offershield.common.web.ServiceUnavailableException;
import com.offershield.support.LogCapture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Private Cloudinary storage for original PDFs (PRD §9.1, D-05), against a mocked SDK. Fake credentials only. */
class CloudinaryStoreTest {

    private static final String FAKE_URL = "cloudinary://123456789012345:fake-secret-for-tests@demo-cloud";
    private static final byte[] PDF = TestPdfs.onePage();

    private final Cloudinary cloudinary = mock(Cloudinary.class);
    private final Uploader uploader = mock(Uploader.class);
    private final CloudinaryStore store = new CloudinaryStore(cloudinary);

    @Test
    @SuppressWarnings("unchecked")
    void upload_authenticatedType_derivedVersionsNeedASignatureToo() throws Exception {
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.upload(any(), anyMap())).thenReturn(Map.of("public_id", "submissions/abc", "type", "authenticated"));

        store.upload("submissions/abc", PDF);

        ArgumentCaptor<Map<String, Object>> options = ArgumentCaptor.forClass(Map.class);
        verify(uploader).upload(eq(PDF), options.capture());
        assertThat(options.getValue())
                .containsEntry("public_id", "submissions/abc")
                .containsEntry("type", "authenticated")
                .containsEntry("resource_type", "image")
                .containsEntry("overwrite", true);
    }

    @Test
    void upload_failure_503WithoutDetails() throws Exception {
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.upload(any(), anyMap())).thenThrow(new IOException("Invalid Signature fake-secret-for-tests"));

        try (LogCapture logs = LogCapture.of(CloudinaryStore.class)) {
            assertThatThrownBy(() -> store.upload("submissions/abc", PDF))
                    .isInstanceOfSatisfying(ServiceUnavailableException.class, ex -> {
                        assertThat(ex.errorCode()).isEqualTo("STORAGE_UNAVAILABLE");
                        assertThat(ex.getMessage()).doesNotContain("fake-secret");
                    });
            assertThat(logs.events()).extracting(event -> event.getFormattedMessage())
                    .anySatisfy(line -> assertThat(line).contains("pdf upload failed").contains("IOException"))
                    .allSatisfy(line -> assertThat(line).doesNotContain("fake-secret"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void delete_notFoundOk() throws Exception {
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.destroy(eq("submissions/gone"), anyMap())).thenReturn(Map.of("result", "not found"));

        store.delete("submissions/gone");

        ArgumentCaptor<Map<String, Object>> options = ArgumentCaptor.forClass(Map.class);
        verify(uploader).destroy(eq("submissions/gone"), options.capture());
        assertThat(options.getValue()).containsEntry("type", "authenticated").containsEntry("resource_type", "image");
    }

    @Test
    void delete_ok() throws Exception {
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.destroy(eq("submissions/x"), anyMap())).thenReturn(Map.of("result", "ok"));

        store.delete("submissions/x");

        verify(uploader).destroy(eq("submissions/x"), anyMap());
    }

    @Test
    void delete_otherResult_throws() throws Exception {
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.destroy(eq("submissions/x"), anyMap())).thenReturn(Map.of("result", "error"));

        assertThatThrownBy(() -> store.delete("submissions/x")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void link_hasExpiry() {
        CloudinaryStore real = new CloudinaryStore(FAKE_URL);
        long before = Instant.now().getEpochSecond();

        String link = real.downloadLink("submissions/abc", Duration.ofMinutes(5)).orElseThrow();

        URI uri = URI.create(link);
        assertThat(uri.getScheme()).isEqualTo("https");
        assertThat(uri.getHost()).isEqualTo("api.cloudinary.com");
        assertThat(uri.getPath()).isEqualTo("/v1_1/demo-cloud/image/download");
        assertThat(link).contains("type=authenticated").contains("format=pdf").contains("signature=")
                .doesNotContain("fake-secret-for-tests");
        Matcher expiresAt = Pattern.compile("expires_at=(\\d+)").matcher(link);
        assertThat(expiresAt.find()).isTrue();
        assertThat(Long.parseLong(expiresAt.group(1))).isBetween(before + 300, Instant.now().getEpochSecond() + 300);
    }

    @Test
    void timeoutsAreFifteenSeconds() {
        assertThat(new CloudinaryStore(FAKE_URL).client().config.timeout).isEqualTo(15);
        assertThat(new CloudinaryStore(FAKE_URL).client().config.secure).isTrue();
    }

    @Test
    void notConfigured_skipsQuietly() {
        CloudinaryStore unconfigured = new CloudinaryStore("  ");

        assertThat(unconfigured.isConfigured()).isFalse();
        assertThat(unconfigured.downloadLink("submissions/abc", Duration.ofMinutes(5))).isEmpty();
        assertThatThrownBy(() -> unconfigured.upload("submissions/abc", PDF)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void notConfigured_warnedOnceAtStartup() {
        // P13 (silent failure 14): without storage no original PDF is kept for reviewers; say so loudly at startup.
        java.util.List<ch.qos.logback.classic.spi.ILoggingEvent> events;
        try (LogCapture logs = LogCapture.of(CloudinaryStore.class)) {
            new CloudinaryStore("  ");
            events = logs.events();
        }

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getLevel().toString()).isEqualTo("WARN");
            assertThat(event.getFormattedMessage()).contains("pdf storage not configured");
        });
    }

    @Test
    void invalidUrl_failsStartupWithoutEchoingIt() {
        assertThatThrownBy(() -> new CloudinaryStore("https://user:fake-secret-for-tests@example.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLOUDINARY_URL")
                .hasMessageNotContaining("fake-secret-for-tests")
                .hasNoCause();
    }

    @Test
    void publicId_isSha256OfOwnerKeyAndContent() {
        UUID owner = UUID.fromString("0190c9a0-0000-7000-8000-000000000001");
        String content = "a".repeat(64);

        String id = CloudinaryStore.publicIdFor(owner, "key-1", content);

        // A byte-identical retry reuses the asset; anything else gets its own, so a racing request with the same key
        // but another file can never overwrite the PDF of the submission that won.
        assertThat(id).matches("submissions/[0-9a-f]{64}")
                .isEqualTo(CloudinaryStore.publicIdFor(owner, "key-1", content))
                .isNotEqualTo(CloudinaryStore.publicIdFor(owner, "key-2", content))
                .isNotEqualTo(CloudinaryStore.publicIdFor(owner, "key-1", "b".repeat(64)))
                .isNotEqualTo(CloudinaryStore.publicIdFor(UUID.randomUUID(), "key-1", content));
        // printf '%s' "0190c9a0-0000-7000-8000-000000000001:key-1:aaaa…(64)" | sha256sum
        assertThat(id).isEqualTo("submissions/79c2564d2d7af9926a2811bd2a95c0817b64ee5ea600a8062b6968e264145c6e");
    }
}
