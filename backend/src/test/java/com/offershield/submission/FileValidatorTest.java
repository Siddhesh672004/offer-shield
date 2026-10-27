package com.offershield.submission;

import com.offershield.common.web.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PRD §14.4 upload controls: size, magic bytes, pages, encryption, extraction timeout, image-only (P5-02). */
class FileValidatorTest {

    private final TextExtractor extractor = new TextExtractor(Duration.ofSeconds(10));
    private final FileValidator validator = new FileValidator(extractor);

    @AfterEach
    void stopPool() {
        extractor.destroy();
    }

    @Test
    void validOnePage_ok() {
        byte[] original = TestPdfs.onePage();

        ValidatedPdf pdf = validator.validate(file(original));

        assertThat(pdf.pages()).isEqualTo(1);
        assertThat(pdf.text()).contains("Graduate Engineer Trainee").contains("No fee is charged");
        assertThat(pdf.bytes()).isEqualTo(original);
    }

    @Test
    void tenPages_ok() {
        assertThat(validator.validate(file(TestPdfs.withPages(10))).pages()).isEqualTo(10);
    }

    @Test
    void exeRenamed_415() {
        assertProblem(() -> validator.validate(file(TestPdfs.exeRenamed())),
                HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FILE_TYPE");
    }

    @Test
    void plainTextRenamed_415() {
        assertProblem(() -> validator.validate(file("Dear Candidate, this is not a PDF at all.".getBytes())),
                HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FILE_TYPE");
    }

    @Test
    void oversize_413() {
        assertProblem(() -> validator.validate(file(TestPdfs.pdfHeaderOfSize((int) FileValidator.MAX_BYTES + 1))),
                HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE");
    }

    @Test
    void exactlyFiveMegabytes_notRejectedForSize() {
        // Passes the size check, then fails later because it is not a real PDF.
        assertProblem(() -> validator.validate(file(TestPdfs.pdfHeaderOfSize((int) FileValidator.MAX_BYTES))),
                HttpStatus.UNPROCESSABLE_CONTENT, "PDF_UNREADABLE");
    }

    @Test
    void empty_400() {
        assertProblem(() -> validator.validate(file(new byte[0])), HttpStatus.BAD_REQUEST, "EMPTY_FILE");
    }

    @Test
    void elevenPages_422() {
        assertProblem(() -> validator.validate(file(TestPdfs.withPages(11))),
                HttpStatus.UNPROCESSABLE_CONTENT, "TOO_MANY_PAGES");
    }

    @Test
    void encrypted_422() {
        assertProblem(() -> validator.validate(file(TestPdfs.encrypted())),
                HttpStatus.UNPROCESSABLE_CONTENT, "PDF_ENCRYPTED");
    }

    @Test
    void ownerPasswordOnly_422() {
        assertProblem(() -> validator.validate(file(TestPdfs.ownerPasswordOnly())),
                HttpStatus.UNPROCESSABLE_CONTENT, "PDF_ENCRYPTED");
    }

    @Test
    void corrupt_422() {
        assertProblem(() -> validator.validate(file(TestPdfs.corrupt())),
                HttpStatus.UNPROCESSABLE_CONTENT, "PDF_UNREADABLE");
    }

    @Test
    void timeout_422() {
        TextExtractor slow = new TextExtractor(Duration.ofNanos(1));
        try {
            assertProblem(() -> new FileValidator(slow).validate(file(TestPdfs.withPages(10, 80))),
                    HttpStatus.UNPROCESSABLE_CONTENT, "PDF_TIMEOUT");
        } finally {
            slow.destroy();
        }
    }

    @Test
    void flateBomb_422_withoutReadingItIntoMemory() {
        byte[] bomb = TestPdfs.flateBomb(40);
        assertThat(bomb.length).as("a few KB on disk").isLessThan(200_000);

        assertProblem(() -> validator.validate(file(bomb)), HttpStatus.UNPROCESSABLE_CONTENT, "PDF_TOO_COMPLEX");
    }

    @Test
    void compressedPageWithinBudget_ok() {
        ValidatedPdf pdf = validator.validate(file(TestPdfs.flateBomb(2)));

        assertThat(pdf.text()).contains("Offer of Employment");
    }

    @Test
    void moreTextThanALetter_422() {
        assertThat(validator.validate(file(TestPdfs.denseText(5))).text().length())
                .isLessThanOrEqualTo(TextExtractor.MAX_TEXT_CHARS);
        assertProblem(() -> validator.validate(file(TestPdfs.denseText(6))),
                HttpStatus.UNPROCESSABLE_CONTENT, "TEXT_TOO_LONG");
    }

    @Test
    void manyGlyphsInOneTextOperator_422TextTooLong() {
        // P13 (S1): glyphs are counted while a page is read (at most 2 × 50,000 per file). These 150,000 are drawn on
        // top of each other, so the text layer itself is one letter: only the glyph count can stop this file.
        assertProblem(() -> validator.validate(file(TestPdfs.overlappingGlyphs(150_000))),
                HttpStatus.UNPROCESSABLE_CONTENT, "TEXT_TOO_LONG");
    }

    @Test
    void textBombOf15MillionGlyphs_422TextTooLong_quickly() {
        // P13 (S1) regression: one compressed Tj of 15 million glyphs passes the decompression budget (15 MB < 16 MB)
        // and has one operator only; before the glyph cap PDFBox built an object per glyph (gigabytes of heap).
        byte[] bomb = TestPdfs.textBomb(15_000_000);
        assertThat(bomb.length).as("a few KB on disk").isLessThan(200_000);

        long start = System.nanoTime();
        assertProblem(() -> validator.validate(file(bomb)), HttpStatus.UNPROCESSABLE_CONTENT, "TEXT_TOO_LONG");

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void imageOnly_422() {
        assertThatThrownBy(() -> validator.validate(file(TestPdfs.imageOnly())))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                    assertThat(ex.errorCode()).isEqualTo("TEXT_TOO_SHORT");
                    assertThat(ex.getMessage()).containsIgnoringCase("paste the text");
                });
    }

    private static MockMultipartFile file(byte[] bytes) {
        return new MockMultipartFile("file", "offer.pdf", "application/pdf", bytes);
    }

    private static void assertProblem(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, HttpStatus status,
                                      String errorCode) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.status()).isEqualTo(status);
            assertThat(ex.errorCode()).isEqualTo(errorCode);
        });
    }
}
