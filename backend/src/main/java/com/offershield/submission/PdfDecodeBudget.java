package com.offershield.submission;

import com.offershield.common.web.ApiException;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.filter.DecodeOptions;
import org.apache.pdfbox.filter.DecodeResult;
import org.apache.pdfbox.filter.Filter;
import org.apache.pdfbox.filter.FilterFactory;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.pdmodel.PDPage;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.Map;

/**
 * The "PDFBox memory limit" of PRD §14.4 against PDF bombs. PDFBox 3 decompresses every stream (page content, fonts,
 * object and xref streams) into an unbounded heap buffer and has no setting to cap it, so a 5 MB file can expand to
 * gigabytes. At startup each PDFBox decoder is therefore wrapped once; while a thread runs inside
 * {@link #run(long, Extraction)} the wrapper counts what the decoders write and stops at 16 MB per stream, 64 MB per
 * document, or the extraction deadline. Threads outside an extraction (nothing else in the app reads PDFs; tests that
 * build PDFs) are not limited. If the decoders cannot be wrapped (a PDFBox upgrade changed its internals), the
 * application refuses to start rather than read PDFs unprotected.
 */
final class PdfDecodeBudget {

    static final long MAX_STREAM_BYTES = 16L * 1024 * 1024;
    static final long MAX_DOCUMENT_BYTES = 64L * 1024 * 1024;

    private static final ThreadLocal<Budget> CURRENT = new ThreadLocal<>();
    private static boolean installed;

    private PdfDecodeBudget() {
    }

    /** Wraps PDFBox's decoders (idempotent). */
    static synchronized void install() {
        if (installed) {
            return;
        }
        try {
            Field field = FilterFactory.class.getDeclaredField("filters");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<COSName, Filter> filters = (Map<COSName, Filter>) field.get(FilterFactory.INSTANCE);
            if (filters.isEmpty()) {
                throw new IllegalStateException("no PDFBox filters registered");
            }
            filters.replaceAll((name, filter) -> filter instanceof BudgetedFilter ? filter : new BudgetedFilter(filter));
        } catch (ReflectiveOperationException | RuntimeException ex) {
            throw new IllegalStateException("Could not install the PDF decompression limit ("
                    + ex.getClass().getSimpleName() + "); refusing to read PDFs without it.");
        }
        installed = true;
    }

    /**
     * Runs {@code work} with a fresh budget on this thread. Exceptions thrown by the budget may be swallowed inside
     * PDFBox (it skips a broken font, for example), so the budget is checked again afterwards. PDFBox may also catch
     * them and throw its own exception instead (P13), so a failure is reported as the limit that was hit, if one was;
     * our own {@link ApiException}s (too many pages, encrypted, ...) are kept as they are.
     *
     * @throws BudgetExceededException   more was decompressed than allowed
     * @throws DeadlineExceededException the deadline passed while decompressing, or before the work failed
     */
    static <T> T run(long deadlineNanos, Extraction<T> work) throws IOException {
        Budget budget = new Budget(deadlineNanos);
        CURRENT.set(budget);
        try {
            T result;
            try {
                result = work.run();
            } catch (IOException | RuntimeException ex) {
                if (!(ex instanceof ApiException)) {
                    if (budget.exceeded) {
                        throw new BudgetExceededException();
                    }
                    if (System.nanoTime() - deadlineNanos > 0) {
                        throw new DeadlineExceededException();
                    }
                }
                throw ex;
            }
            if (budget.exceeded) {
                throw new BudgetExceededException();
            }
            return result;
        } finally {
            CURRENT.remove();
        }
    }

    /** Work that reads a PDF. */
    @FunctionalInterface
    interface Extraction<T> {
        T run() throws IOException;
    }

    /** Thrown when a PDF decompresses to more than the budget allows. */
    static final class BudgetExceededException extends IOException {

        BudgetExceededException() {
            super("PDF decompression budget exceeded");
        }
    }

    /** Thrown when PDF processing runs past its deadline (or its thread is interrupted). */
    static final class DeadlineExceededException extends IOException {

        DeadlineExceededException() {
            super("PDF processing deadline exceeded");
        }
    }

    /**
     * Decodes a page's content stream(s) once, inside the budget, and discards the result. Needed because
     * {@code PDPage} streams a single Flate content stream through its own decoder, bypassing the wrapped ones; once
     * this passes, that stream is known to fit the per-stream limit.
     */
    static void checkPageContents(PDPage page) throws IOException {
        COSBase contents = page.getCOSObject().getDictionaryObject(COSName.CONTENTS);
        if (contents instanceof COSStream stream) {
            decodeAndDiscard(stream);
        } else if (contents instanceof COSArray array) {
            for (int i = 0; i < array.size(); i++) {
                if (array.getObject(i) instanceof COSStream stream) {
                    decodeAndDiscard(stream);
                }
            }
        }
    }

    private static void decodeAndDiscard(COSStream stream) throws IOException {
        try (RandomAccessRead ignored = stream.createView()) {
            // createView() runs every filter of the stream; the decoded bytes are counted and then released.
        }
    }

    static void checkDeadline(long deadlineNanos) throws DeadlineExceededException {
        if (System.nanoTime() - deadlineNanos > 0 || Thread.currentThread().isInterrupted()) {
            throw new DeadlineExceededException();
        }
    }

    private static final class Budget {

        private final long deadlineNanos;
        private long documentBytes;
        private boolean exceeded;

        Budget(long deadlineNanos) {
            this.deadlineNanos = deadlineNanos;
        }
    }

    /** Counts the decoded bytes of one stream against the current thread's budget. */
    private static final class LimitedOutputStream extends FilterOutputStream {

        private final Budget budget;
        private long streamBytes;

        LimitedOutputStream(OutputStream out, Budget budget) {
            super(out);
            this.budget = budget;
        }

        @Override
        public void write(int b) throws IOException {
            charge(1);
            out.write(b);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            charge(length);
            out.write(bytes, offset, length);
        }

        private void charge(int length) throws IOException {
            checkDeadline(budget.deadlineNanos);
            streamBytes += length;
            budget.documentBytes += length;
            if (budget.exceeded || streamBytes > MAX_STREAM_BYTES || budget.documentBytes > MAX_DOCUMENT_BYTES) {
                budget.exceeded = true;
                throw new BudgetExceededException();
            }
        }
    }

    /** A PDFBox decoder whose output is counted while an extraction runs on the current thread. */
    private static final class BudgetedFilter extends Filter {

        private final Filter delegate;

        BudgetedFilter(Filter delegate) {
            this.delegate = delegate;
        }

        @Override
        public DecodeResult decode(InputStream encoded, OutputStream decoded, COSDictionary parameters, int index)
                throws IOException {
            return delegate.decode(encoded, limit(decoded), parameters, index);
        }

        @Override
        public DecodeResult decode(InputStream encoded, OutputStream decoded, COSDictionary parameters, int index,
                                   DecodeOptions options) throws IOException {
            return delegate.decode(encoded, limit(decoded), parameters, index, options);
        }

        @Override
        protected void encode(InputStream input, OutputStream encoded, COSDictionary parameters) throws IOException {
            delegate.encode(input, encoded, parameters, 0);
        }

        private static OutputStream limit(OutputStream decoded) {
            Budget budget = CURRENT.get();
            return budget == null ? decoded : new LimitedOutputStream(decoded, budget);
        }
    }
}
