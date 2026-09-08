package com.chefpay.server.invoices;

import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

/**
 * F2.3's on-premise/offline-friendly extraction engine (Tess4J - a Java binding for Tesseract
 * OCR), matching the SRS's technical-notes recommendation. Deliberately defensive: Tesseract needs
 * a native library plus trained-language data files (tessdata) actually present on the machine
 * running the server, neither of which this application ships or installs itself - a store that
 * hasn't set those up must see a clear "OCR unavailable, enter items manually" message, never a
 * broken screen or a 500. Every call is wrapped in a catch of {@link Throwable} (not just {@code
 * Exception}) because a missing native library surfaces as {@link UnsatisfiedLinkError}, an
 * {@code Error}, not an exception.
 *
 * <p>{@code Tesseract} is instantiated fresh per call (not held as a field/bean) so a startup-time
 * failure can never happen - the constructor itself is cheap and side-effect-free; only {@link
 * ITesseract#doOCR} actually touches the native library.
 */
@Service
@Slf4j
public class OcrService {

    /** Optional override for where tessdata (trained language files) live; Tess4J's default
     * lookup (bundled resources, then TESSDATA_PREFIX) is used when this is unset. Configure via
     * the {@code TESSDATA_PATH} environment variable on the server if Tesseract is installed to a
     * non-standard location. */
    private static final String TESSDATA_PATH_ENV = "TESSDATA_PATH";

    public OcrExtractionResult extractText(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            return OcrExtractionResult.unavailable("No image data was provided.");
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (image == null) {
                return OcrExtractionResult.unavailable("Could not decode the uploaded file as an image "
                        + "(check it's a JPG/PNG photo of the invoice, not a PDF).");
            }
            ITesseract tesseract = new Tesseract();
            String tessdataPath = System.getenv(TESSDATA_PATH_ENV);
            if (tessdataPath != null && !tessdataPath.isBlank()) {
                tesseract.setDatapath(tessdataPath);
            }
            String text = tesseract.doOCR(image);
            if (text == null || text.isBlank()) {
                return OcrExtractionResult.unavailable("Tesseract ran but could not read any text from this image - "
                        + "try a clearer/higher-resolution photo, or enter items manually.");
            }
            return OcrExtractionResult.success(text, "TESSERACT");
        } catch (Throwable t) {
            log.warn("Tesseract OCR unavailable/failed on this terminal: {}", t.toString());
            return OcrExtractionResult.unavailable("OCR engine (Tesseract) is not available on this terminal "
                    + "(native library or trained-language data not installed) - enter items manually below, "
                    + "or ask an admin to install Tesseract and set the TESSDATA_PATH environment variable.");
        }
    }
}
