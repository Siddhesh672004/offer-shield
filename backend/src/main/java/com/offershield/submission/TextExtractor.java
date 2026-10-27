package com.offershield.submission;

import com.offershield.common.web.ApiException;
import com.offershield.common.web.ServiceUnavailableException;
import com.offershield.common.web.UnprocessableException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Writer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reads the text layer of an already type-checked PDF with PDFBox (PRD §14.4): text only, never rendered. Guards
 * against hostile files:
 * <ul>
 *   <li>a decompression budget ({@link PdfDecodeBudget}: 16 MB per stream, 64 MB per file), which is the memory
 *       limit, since PDFBox itself would inflate a "PDF bomb" without bound;</li>
 *   <li>at most {@value #MAX_GLYPHS} glyphs per file (P13, a "text bomb": one small compressed text operator can
 *       show millions of glyphs, and PDFBox keeps an object per glyph until the page ends), counted as each glyph is
 *       shown;</li>
 *   <li>a 10 s deadline, checked while decompressing, between content-stream operators and every
 *       {@value #GLYPHS_PER_DEADLINE_CHECK} glyphs, plus a bounded pool so the request thread always gets its answer
 *       within the timeout;</li>
 *   <li>at most {@value #MAX_TEXT_CHARS} characters of text.</li>
 * </ul>
 * A broken file is the sender's problem (422, INFO); an unexpected exception inside the reader is still a 422 but is
 * warned with its class; an {@link Error} (out of memory, a missing class) is ours (503 {@code PDF_READ_FAILED},
 * ERROR). Only class names and error codes are logged, never anything from the document or an exception message.
 */
@Component
public class TextExtractor implements DisposableBean {

    static final int MAX_PAGES = 10;
    static final int MIN_TEXT_CHARS = 50;
    /** About 10 dense pages; also bounds what later steps (regexes, AI prompts, storage) ever see. */
    static final int MAX_TEXT_CHARS = 50_000;
    /** Twice the text limit: room for overlapping "fake bold" text, far below what could exhaust the heap. */
    static final int MAX_GLYPHS = 2 * MAX_TEXT_CHARS;
    static final int GLYPHS_PER_DEADLINE_CHECK = 1_000;
    private static final int WORKERS = 2;
    private static final int QUEUE_CAPACITY = 8;
    private static final Logger log = LoggerFactory.getLogger(TextExtractor.class);

    private final Duration timeout;
    private final ThreadPoolExecutor pool;

    public TextExtractor(@Value("${offershield.submission.pdf-timeout:10s}") Duration timeout) {
        PdfDecodeBudget.install();
        this.timeout = timeout;
        AtomicInteger threadNumber = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(WORKERS, WORKERS, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY), runnable -> {
                    Thread thread = new Thread(runnable, "pdf-text-" + threadNumber.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * @throws UnprocessableException      encrypted, more than 10 pages, unreadable, too large to decompress, too slow,
     *                                     under 50 or over 50,000 characters of text, over {@value #MAX_GLYPHS} glyphs
     * @throws ServiceUnavailableException every worker is busy, or reading failed on our side
     */
    public ExtractedPdf extract(byte[] pdf) {
        long deadline = System.nanoTime() + timeout.toNanos();
        Future<ExtractedPdf> task;
        try {
            task = pool.submit(() -> PdfDecodeBudget.run(deadline, () -> parse(pdf, deadline)));
        } catch (RejectedExecutionException ex) {
            log.warn("pdf read refused: every reader is busy errorCode=SERVER_BUSY");
            throw new ServiceUnavailableException("SERVER_BUSY",
                    "Too many files are being read right now. Please try again in a minute.");
        }
        try {
            return task.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            task.cancel(true);
            throw tooSlow();
        } catch (InterruptedException ex) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            log.warn("pdf read interrupted errorCode=SERVER_BUSY");
            throw new ServiceUnavailableException("SERVER_BUSY", "Reading the file was interrupted. Please try again.");
        } catch (ExecutionException ex) {
            throw translate(ex.getCause());
        }
    }

    private static ExtractedPdf parse(byte[] pdf, long deadline) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf, "")) {
            if (document.isEncrypted()) {
                throw encrypted();
            }
            int pages = document.getNumberOfPages();
            if (pages > MAX_PAGES) {
                throw new UnprocessableException("TOO_MANY_PAGES", "The PDF has more than 10 pages.");
            }
            if (pages == 0) {
                throw unreadable();
            }
            CappedWriter text = new CappedWriter(MAX_TEXT_CHARS);
            DeadlineTextStripper stripper = new DeadlineTextStripper(deadline);
            stripper.writeText(document, text);
            if (stripper.glyphLimitReached) {
                throw new TextTooLongException(); // in case PDFBox swallowed it somewhere on the way out
            }
            String cleaned = clean(text.toString());
            if (cleaned.codePoints().filter(cp -> !Character.isWhitespace(cp)).count() < MIN_TEXT_CHARS) {
                throw new UnprocessableException("TEXT_TOO_SHORT",
                        "We couldn't read enough text from this PDF (it may be a scan). Please paste the text instead.");
            }
            return new ExtractedPdf(cleaned, pages);
        }
    }

    /** What a failure on the reader thread becomes for the caller (logged by class or code only, never a message). */
    static RuntimeException translate(Throwable cause) {
        if (cause instanceof ApiException apiException) {
            return apiException;
        }
        if (cause instanceof PdfDecodeBudget.DeadlineExceededException) {
            return tooSlow();
        }
        if (cause instanceof PdfDecodeBudget.BudgetExceededException) {
            return new UnprocessableException("PDF_TOO_COMPLEX",
                    "This PDF is too large or complex to read safely. Please paste the text instead.");
        }
        if (cause instanceof TextTooLongException) {
            return new UnprocessableException("TEXT_TOO_LONG",
                    "This PDF has more text than an offer letter (over 50,000 characters). Please paste the letter text instead.");
        }
        if (cause instanceof InvalidPasswordException) {
            return encrypted();
        }
        String type = cause == null ? "unknown" : cause.getClass().getSimpleName();
        if (cause instanceof Error) {
            // Out of memory, a missing class: our problem, not the sender's file.
            log.error("pdf read failed on our side: {} errorCode=PDF_READ_FAILED", type);
            return new ServiceUnavailableException("PDF_READ_FAILED", "We couldn't read this PDF because of a problem "
                    + "on our side. Please try again in a few minutes, or paste the text instead.");
        }
        if (cause instanceof RuntimeException) {
            // PDFBox can fail this way on a malformed file, but so can a bug of ours: keep it visible.
            log.warn("pdf unreadable: unexpected {}", type);
            return unreadable();
        }
        // Never the message: PDFBox messages can quote document content.
        log.info("pdf unreadable: {}", type);
        return unreadable();
    }

    /** Drops NUL characters (MySQL and later regexes dislike them) and surrounding whitespace. */
    private static String clean(String text) {
        return text.replace("\u0000", "").strip();
    }

    private static UnprocessableException encrypted() {
        return new UnprocessableException("PDF_ENCRYPTED",
                "This PDF is password-protected or encrypted. Please paste the text instead.");
    }

    private static UnprocessableException unreadable() {
        return new UnprocessableException("PDF_UNREADABLE",
                "We couldn't read this PDF. Please paste the text instead.");
    }

    private static UnprocessableException tooSlow() {
        log.info("pdf read stopped: took too long errorCode=PDF_TIMEOUT");
        return new UnprocessableException("PDF_TIMEOUT",
                "Reading this PDF took too long. Please paste the text instead.");
    }

    @Override
    public void destroy() {
        pool.shutdownNow();
    }

    /**
     * Text of every page, stopping once the deadline passes, the thread is interrupted or the file shows more than
     * {@value #MAX_GLYPHS} glyphs.
     */
    private static final class DeadlineTextStripper extends PDFTextStripper {

        private final long deadline;
        private int glyphs;
        private boolean glyphLimitReached;

        DeadlineTextStripper(long deadline) {
            this.deadline = deadline;
        }

        @Override
        public void processPage(PDPage page) throws IOException {
            PdfDecodeBudget.checkPageContents(page);
            super.processPage(page);
        }

        @Override
        protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            PdfDecodeBudget.checkDeadline(deadline);
            super.processOperator(operator, operands);
        }

        /** Every glyph of every text operator passes here before PDFBox builds its per-glyph object. */
        @Override
        protected void showGlyph(Matrix textRenderingMatrix, PDFont font, int code, Vector displacement)
                throws IOException {
            glyphs++;
            if (glyphs > MAX_GLYPHS) {
                glyphLimitReached = true;
                // Unchecked, so PDFBox's handler for IOExceptions inside form XObjects cannot swallow it.
                throw new TextTooLongException();
            }
            if (glyphs % GLYPHS_PER_DEADLINE_CHECK == 0) {
                PdfDecodeBudget.checkDeadline(deadline);
            }
            super.showGlyph(textRenderingMatrix, font, code, displacement);
        }
    }

    /** Collects text and stops as soon as it would exceed the limit, so a huge text layer is never held in memory. */
    private static final class CappedWriter extends Writer {

        private final StringBuilder text = new StringBuilder();
        private final int maxChars;

        CappedWriter(int maxChars) {
            this.maxChars = maxChars;
        }

        @Override
        public void write(char[] chars, int offset, int length) {
            if (text.length() + length > maxChars) {
                throw new TextTooLongException();
            }
            text.append(chars, offset, length);
        }

        @Override
        public void write(String value, int offset, int length) {
            if (text.length() + length > maxChars) {
                throw new TextTooLongException();
            }
            text.append(value, offset, offset + length);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }

    /** More text (or glyphs) than an offer letter has. */
    private static final class TextTooLongException extends RuntimeException {

        TextTooLongException() {
            super("PDF text longer than the limit", null, false, false);
        }
    }

    /** The text layer and page count of a PDF. */
    public record ExtractedPdf(String text, int pages) {

        @Override
        public String toString() {
            return "ExtractedPdf[pages=" + pages + ", textLength=" + text.length() + "]";
        }
    }
}
