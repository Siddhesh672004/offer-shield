package com.offershield.submission;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Synthetic PDFs built in memory for tests (PRD §18: fake letters only; no binary fixtures in the repository). */
public final class TestPdfs {

    /** A fictional, genuine-style offer letter body (no real company, person, phone or UPI ID). */
    public static final String LETTER = "Offer of Employment. Dear Candidate, we are pleased to offer you the role of "
            + "Graduate Engineer Trainee at Example Fictional Systems Pvt Ltd, Pune. Your joining date is 1 July. "
            + "No fee is charged at any stage of our hiring process.";

    private TestPdfs() {
    }

    public static byte[] onePage() {
        return withPages(1);
    }

    /** {@code pages} pages, each carrying the letter text. */
    public static byte[] withPages(int pages) {
        return withPages(pages, 1);
    }

    /** {@code pages} pages with {@code linesPerPage} lines of letter text each (more lines = slower extraction). */
    public static byte[] withPages(int pages, int linesPerPage) {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                addTextPage(document, linesPerPage);
            }
            return save(document);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Opening needs a user password. */
    public static byte[] encrypted() {
        return protectedPdf("user-secret");
    }

    /** Opens without a password but is still encrypted (owner password only). */
    public static byte[] ownerPasswordOnly() {
        return protectedPdf("");
    }

    /** A scan: one page holding an image and no text layer. */
    public static byte[] imageOnly() {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            BufferedImage image = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 200, 100);
            graphics.setColor(Color.BLACK);
            graphics.drawString("Offer letter scan", 10, 50);
            graphics.dispose();
            PDImageXObject xObject = LosslessFactory.createFromImage(document, image);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawImage(xObject, 50, 500, 400, 200);
            }
            return save(document);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * A decompression bomb: one page whose content stream is {@code megabytes} MB of spaces, Flate-compressed to a few
     * KB, followed by the letter text (so without a limit it would be read normally).
     */
    public static byte[] flateBomb(int megabytes) {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            PDStream contents = new PDStream(document);
            try (OutputStream out = contents.createOutputStream(COSName.FLATE_DECODE)) {
                byte[] spaces = new byte[1024 * 1024];
                Arrays.fill(spaces, (byte) ' ');
                for (int i = 0; i < megabytes; i++) {
                    out.write(spaces);
                }
                out.write(("BT /F1 8 Tf 20 760 Td (" + LETTER.substring(0, 100) + ") Tj ET")
                        .getBytes(StandardCharsets.US_ASCII));
            }
            page.setContents(contents);
            page.setResources(new PDResources());
            page.getResources().put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            return save(document);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** {@code pages} pages packed with text: about 9,000 characters each. */
    public static byte[] denseText(int pages) {
        return withPages(pages, 40);
    }

    /**
     * One page whose single {@code Tj} draws {@code glyphs} letters A on top of each other (Helvetica's A is 667/1000
     * em wide, so a character spacing of -6.67 at size 10 cancels every advance): the text layer is one letter, however
     * many glyphs are drawn.
     */
    public static byte[] overlappingGlyphs(int glyphs) {
        return oneTextOperator("BT /F1 10 Tf -6.67 Tc 100 700 Td (", glyphs, ") Tj ET");
    }

    /** A "text bomb" (P13 S1): one page with one {@code Tj} of {@code glyphs} letters, a few KB once compressed. */
    public static byte[] textBomb(int glyphs) {
        return oneTextOperator("BT /F1 1 Tf 20 760 Td (", glyphs, ") Tj ET");
    }

    private static byte[] oneTextOperator(String before, int glyphs, String after) {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            PDStream contents = new PDStream(document);
            try (OutputStream out = contents.createOutputStream(COSName.FLATE_DECODE)) {
                out.write(before.getBytes(StandardCharsets.US_ASCII));
                byte[] letters = new byte[64 * 1024];
                Arrays.fill(letters, (byte) 'A');
                for (int left = glyphs; left > 0; left -= letters.length) {
                    out.write(letters, 0, Math.min(left, letters.length));
                }
                out.write(after.getBytes(StandardCharsets.US_ASCII));
            }
            page.setContents(contents);
            page.setResources(new PDResources());
            page.getResources().put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            return save(document);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Starts like a PDF (so magic-byte detection says PDF) but is not one. */
    public static byte[] corrupt() {
        return "%PDF-1.7\n%garbage that is not a PDF body\n".getBytes(StandardCharsets.US_ASCII);
    }

    /** A Windows executable header, as if {@code tool.exe} had been renamed to {@code offer.pdf}. */
    public static byte[] exeRenamed() {
        byte[] bytes = new byte[1024];
        bytes[0] = 'M';
        bytes[1] = 'Z';
        bytes[2] = (byte) 0x90;
        bytes[0x3C] = (byte) 0x80;
        bytes[0x80] = 'P';
        bytes[0x81] = 'E';
        return bytes;
    }

    /** {@code size} bytes that start with the PDF magic number. */
    public static byte[] pdfHeaderOfSize(int size) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, (byte) ' ');
        byte[] header = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(header, 0, bytes, 0, header.length);
        return bytes;
    }

    private static byte[] protectedPdf(String userPassword) {
        try (PDDocument document = new PDDocument()) {
            addTextPage(document, 1);
            StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-secret", userPassword, new AccessPermission());
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            return save(document);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void addTextPage(PDDocument document, int lines) throws IOException {
        PDPage page = new PDPage();
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.beginText();
            content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 8);
            content.setLeading(9);
            content.newLineAtOffset(20, 760);
            for (int i = 0; i < lines; i++) {
                for (int start = 0; start < LETTER.length(); start += 110) {
                    content.showText(LETTER.substring(start, Math.min(LETTER.length(), start + 110)));
                    content.newLine();
                }
            }
            content.endText();
        }
    }

    private static byte[] save(PDDocument document) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        document.save(out);
        return out.toByteArray();
    }
}
