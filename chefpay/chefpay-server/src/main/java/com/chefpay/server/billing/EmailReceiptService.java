package com.chefpay.server.billing;

import com.chefpay.core.domain.Restaurant;
import com.chefpay.core.repository.RestaurantRepository;
import com.chefpay.server.common.ApiException;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.Properties;

/**
 * Round 11: "give whatsapp and email receipt option." This is the email half - WhatsApp needs no
 * server support at all (see {@code ReceiptPrinter}'s sibling client-side {@code
 * WhatsAppSender}, which just opens a {@code wa.me} deep link with the receipt text pre-filled,
 * since ChefPay has no WhatsApp Business API account/credentials to send through automatically).
 *
 * <p>Deliberately builds a fresh {@link JavaMailSenderImpl} on every send from {@link
 * Restaurant}'s own {@code smtp*} fields, rather than relying on Spring Boot's {@code
 * spring.mail.*}-property autoconfiguration (this app sets none) - the SMTP account is
 * per-restaurant config a user enters in Settings and can change at runtime, not a fixed
 * deployment-time property. A restaurant with no SMTP configured (the default - {@code
 * smtpHost} blank) simply can't use this feature yet; {@link #isConfigured} lets {@code
 * BillingController} return a clear "not configured" error rather than a confusing SMTP stack
 * trace.
 */
@Service
public class EmailReceiptService {

    private final RestaurantRepository restaurantRepository;

    public EmailReceiptService(RestaurantRepository restaurantRepository) {
        this.restaurantRepository = restaurantRepository;
    }

    private Restaurant restaurant() {
        return restaurantRepository.findAll().stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Restaurant is not configured yet"));
    }

    public boolean isConfigured() {
        Restaurant restaurant = restaurant();
        return restaurant.getSmtpHost() != null && !restaurant.getSmtpHost().isBlank()
                && restaurant.getSmtpFromAddress() != null && !restaurant.getSmtpFromAddress().isBlank();
    }

    /** Sends {@code receiptText} as a plain-text email to {@code toEmail}. Throws {@link
     * ApiException} (never a raw {@code MessagingException}) on any failure - "SMTP not
     * configured", a bad recipient address, and an unreachable/rejecting SMTP server all surface
     * as one clear, catchable error type the way every other action in this app already reports
     * failures to the client. */
    public void sendReceipt(String toEmail, String orderNumber, String receiptText) {
        sendDocument(toEmail, "Receipt - " + orderNumber, receiptText);
    }

    /** Round 12 §19 - generalizes the send below for the Purchase Order email channel (subject
     * "Purchase Order - PO-..." instead of "Receipt - ORD-..."), reusing every bit of SMTP
     * plumbing rather than duplicating this class for one different subject line. */
    public void sendDocument(String toEmail, String subject, String bodyText) {
        Restaurant restaurant = restaurant();
        if (restaurant.getSmtpHost() == null || restaurant.getSmtpHost().isBlank()
                || restaurant.getSmtpFromAddress() == null || restaurant.getSmtpFromAddress().isBlank()) {
            throw ApiException.badRequest("SMTP_NOT_CONFIGURED",
                    "Email sending isn't configured yet - set an SMTP host and From address under Settings.");
        }
        if (toEmail == null || toEmail.isBlank()) {
            throw ApiException.badRequest("EMAIL_REQUIRED", "Enter a recipient email address.");
        }

        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(restaurant.getSmtpHost());
        mailSender.setPort(restaurant.getSmtpPort() == null ? 587 : restaurant.getSmtpPort());
        if (restaurant.getSmtpUsername() != null && !restaurant.getSmtpUsername().isBlank()) {
            mailSender.setUsername(restaurant.getSmtpUsername());
            mailSender.setPassword(restaurant.getSmtpPassword());
        }
        Properties props = mailSender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", restaurant.getSmtpUsername() != null && !restaurant.getSmtpUsername().isBlank());
        props.put("mail.smtp.starttls.enable", restaurant.isSmtpUseTls());
        // Port 465 is implicit-SSL, not STARTTLS - flip the socket factory when the restaurant's
        // configured port is the classic SSL port rather than requiring a separate UI toggle for it.
        if (restaurant.getSmtpPort() != null && restaurant.getSmtpPort() == 465) {
            props.put("mail.smtp.socketFactory.port", "465");
            props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setTo(toEmail);
            helper.setFrom(restaurant.getSmtpFromAddress(), restaurant.getName());
            helper.setSubject(subject);
            helper.setText(bodyText, false);
            mailSender.send(message);
        } catch (MessagingException | java.io.UnsupportedEncodingException e) {
            throw ApiException.badRequest("EMAIL_SEND_FAILED", "Could not prepare the receipt email: " + e.getMessage());
        } catch (org.springframework.mail.MailException e) {
            throw ApiException.badRequest("EMAIL_SEND_FAILED", "Could not send the email - check the SMTP settings under "
                    + "Settings and that the SMTP server is reachable from this server. (" + e.getMostSpecificCause().getMessage() + ")");
        }
    }
}
