package com.chefpay.javafx.common;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import javafx.scene.image.Image;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

/**
 * Generates a "scan to pay" QR code encoding a standard UPI deep link
 * ({@code upi://pay?pa=...&pn=...&am=...&cu=INR&...}) - the exact same URI format every UPI app in
 * India (PhonePe, Google Pay, Paytm, BHIM, a bank's own app, etc.) already knows how to open,
 * pre-filling the payee and amount for the customer to confirm and pay from their own app.
 *
 * <p><b>What this deliberately is NOT.</b> There is no payment gateway, PSP (payment service
 * provider), or bank API integration here - this class does exactly one thing, encode a URI as a
 * QR image. It cannot tell you whether the customer actually completed the payment; UPI's
 * "collect confirmation" flow requires a registered PSP merchant integration with a webhook/status
 * API, which is a materially different (and much larger - PCI-adjacent, needs a real payment
 * aggregator account) project than generating a QR code. Staff still confirms the payment arrived
 * (checking their own UPI app / bank SMS) and marks it received in {@code BillingView} the same
 * way a CARD or WALLET payment is recorded today - this QR only saves the customer from typing a
 * VPA and amount by hand.
 */
public final class UpiQrGenerator {

    private UpiQrGenerator() {
    }

    /** Builds the {@code upi://pay} deep link for one payment request. {@code note} and
     * {@code transactionRef} are both optional and, if blank, simply omitted from the URI - most
     * UPI apps handle a link with only {@code pa}/{@code pn}/{@code am}/{@code cu} present. */
    public static String buildUpiUri(String vpa, String payeeName, BigDecimal amount, String note, String transactionRef) {
        StringBuilder sb = new StringBuilder("upi://pay?");
        sb.append("pa=").append(encode(vpa));
        sb.append("&pn=").append(encode(payeeName));
        sb.append("&cu=INR");
        if (amount != null) {
            sb.append("&am=").append(encode(amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()));
        }
        if (note != null && !note.isBlank()) {
            sb.append("&tn=").append(encode(note));
        }
        if (transactionRef != null && !transactionRef.isBlank()) {
            sb.append("&tr=").append(encode(transactionRef));
        }
        return sb.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Renders the given UPI URI as a square QR code image, ready to drop into a JavaFX
     * {@code ImageView}. Throws {@link IllegalStateException} (wrapping the checked
     * {@link WriterException}) on the rare case zxing can't encode the input - callers should show
     * an error rather than let this propagate as an unhandled exception onto the FX thread. */
    public static Image generate(String upiUri, int sizePx) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix matrix = new QRCodeWriter().encode(upiUri, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
            WritableImage image = new WritableImage(sizePx, sizePx);
            PixelWriter writer = image.getPixelWriter();
            for (int x = 0; x < sizePx; x++) {
                for (int y = 0; y < sizePx; y++) {
                    writer.setColor(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return image;
        } catch (WriterException ex) {
            throw new IllegalStateException("Could not generate UPI QR code: " + ex.getMessage(), ex);
        }
    }
}
