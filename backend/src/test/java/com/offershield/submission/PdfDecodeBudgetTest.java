package com.offershield.submission;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.filter.FilterFactory;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The PDF decompression budget applies only inside an extraction, and every PDFBox decoder is wrapped. */
class PdfDecodeBudgetTest {

    @BeforeAll
    static void install() {
        PdfDecodeBudget.install();
        PdfDecodeBudget.install(); // idempotent
    }

    @Test
    void decodersAreWrapped() throws IOException {
        assertThat(FilterFactory.INSTANCE.getFilter(COSName.FLATE_DECODE).getClass().getName())
                .endsWith("PdfDecodeBudget$BudgetedFilter");
    }

    @Test
    void insideAnExtraction_bigStreamStops() throws IOException {
        try (PDDocument document = Loader.loadPDF(TestPdfs.flateBomb(20))) {
            COSStream contents = document.getPage(0).getCOSObject().getCOSStream(COSName.CONTENTS);

            assertThatThrownBy(() -> PdfDecodeBudget.run(System.nanoTime() + 10_000_000_000L, () -> {
                try (RandomAccessRead view = contents.createView()) {
                    return view.length();
                }
            })).isInstanceOf(PdfDecodeBudget.BudgetExceededException.class);
        }
    }

    @Test
    void outsideAnExtraction_unlimited() throws IOException {
        try (PDDocument document = Loader.loadPDF(TestPdfs.flateBomb(20))) {
            COSStream contents = document.getPage(0).getCOSObject().getCOSStream(COSName.CONTENTS);
            try (RandomAccessRead view = contents.createView()) {
                assertThat(view.length()).isGreaterThan(PdfDecodeBudget.MAX_STREAM_BYTES);
            }
        }
    }

    @Test
    void pastTheDeadline_stops() throws IOException {
        try (PDDocument document = Loader.loadPDF(TestPdfs.flateBomb(1))) {
            COSStream contents = document.getPage(0).getCOSObject().getCOSStream(COSName.CONTENTS);

            assertThatThrownBy(() -> PdfDecodeBudget.run(System.nanoTime() - 1, () -> {
                try (RandomAccessRead view = contents.createView()) {
                    return view.length();
                }
            })).isInstanceOf(PdfDecodeBudget.DeadlineExceededException.class);
        }
    }

    @Test
    void budgetHitButPdfBoxThrowsItsOwnException_stillReportedAsTheBudget() throws IOException {
        // P13: PDFBox can catch our exception (e.g. while loading an object stream) and throw its own instead.
        try (PDDocument document = Loader.loadPDF(TestPdfs.flateBomb(20))) {
            COSStream contents = document.getPage(0).getCOSObject().getCOSStream(COSName.CONTENTS);

            assertThatThrownBy(() -> PdfDecodeBudget.run(System.nanoTime() + 10_000_000_000L, () -> {
                try (RandomAccessRead view = contents.createView()) {
                    return view.length();
                } catch (IOException ex) {
                    throw new IOException("PDFBox: could not read the object stream");
                }
            })).isInstanceOf(PdfDecodeBudget.BudgetExceededException.class);
        }
    }

    @Test
    void failureAfterTheDeadline_reportedAsTooSlow() {
        assertThatThrownBy(() -> PdfDecodeBudget.run(System.nanoTime() - 1, () -> {
            throw new IOException("PDFBox: missing root object");
        })).isInstanceOf(PdfDecodeBudget.DeadlineExceededException.class);
    }

    @Test
    void ourOwnAnswer_keptEvenAfterTheDeadline() {
        com.offershield.common.web.UnprocessableException tooManyPages =
                new com.offershield.common.web.UnprocessableException("TOO_MANY_PAGES", "The PDF has more than 10 pages.");

        assertThatThrownBy(() -> PdfDecodeBudget.run(System.nanoTime() - 1, () -> {
            throw tooManyPages;
        })).isSameAs(tooManyPages);
    }

    @Test
    void failureWithinTheLimits_unchanged() {
        IOException broken = new IOException("PDFBox: missing root object");

        assertThatThrownBy(() -> PdfDecodeBudget.run(System.nanoTime() + 10_000_000_000L, () -> {
            throw broken;
        })).isSameAs(broken);
    }
}
