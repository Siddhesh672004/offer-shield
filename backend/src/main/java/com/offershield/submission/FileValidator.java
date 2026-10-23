package com.offershield.submission;

import com.offershield.common.web.BadRequestException;
import com.offershield.common.web.PayloadTooLargeException;
import com.offershield.common.web.UnsupportedMediaTypeException;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Upload checks in PRD §14.4 order: size (413), real type from magic bytes via Tika (415; the file name and the
 * declared content type are ignored), then the PDF rules in {@link TextExtractor} (422). Only a file that passes every
 * check is returned, so nothing invalid is ever stored or sent to Cloudinary.
 */
@Component
public class FileValidator {

    public static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final String PDF = "application/pdf";

    private final Tika tika = new Tika();
    private final TextExtractor textExtractor;

    public FileValidator(TextExtractor textExtractor) {
        this.textExtractor = textExtractor;
    }

    public ValidatedPdf validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw emptyFile();
        }
        checkSize(file);
        return validate(read(file));
    }

    public ValidatedPdf validate(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw emptyFile();
        }
        if (bytes.length > MAX_BYTES) {
            throw tooLarge();
        }
        if (!PDF.equals(tika.detect(bytes))) {
            throw new UnsupportedMediaTypeException("UNSUPPORTED_FILE_TYPE", "Only PDF files are accepted.");
        }
        TextExtractor.ExtractedPdf extracted = textExtractor.extract(bytes);
        return new ValidatedPdf(bytes, extracted.text(), extracted.pages());
    }

    /** Size only, so a too-large file is refused before it is read into memory or hashed. */
    public void checkSize(MultipartFile file) {
        if (file != null && file.getSize() > MAX_BYTES) {
            throw tooLarge();
        }
    }

    static byte[] read(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new BadRequestException("UPLOAD_INCOMPLETE", "The file could not be read. Please try again.");
        }
    }

    private static BadRequestException emptyFile() {
        return new BadRequestException("EMPTY_FILE", "The file is empty.");
    }

    private static PayloadTooLargeException tooLarge() {
        return new PayloadTooLargeException("FILE_TOO_LARGE", "The file is larger than 5 MB.");
    }
}
