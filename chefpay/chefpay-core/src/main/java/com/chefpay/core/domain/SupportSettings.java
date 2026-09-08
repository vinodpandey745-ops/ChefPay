package com.chefpay.core.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Singleton row (exactly one per deployment, same "there's only ever one" convention as
 * {@code Restaurant}/{@code ThemeSettings}) holding the Help popup's configurable content:
 * "Add a small 'Help' icon in the top-right corner of the application page... Support Phone
 * Number, Support Email ID, Terms &amp; Conditions link, Privacy/Policy link... configurable from
 * the BistroDesk Admin Panel."
 *
 * <p>{@code termsAndConditions}/{@code privacyPolicy} are stored as plain admin-edited text (the
 * Help popup renders them as preformatted text, not HTML), same {@code @Lob TEXT} shape as {@link
 * ThemeSettings#getThemeJson()} for an arbitrary-length blob.
 *
 * <p>Read is open to any authenticated user (every terminal's Help popup needs it - same posture
 * as {@link ThemeSettings}); write is gated by the platform-owner key, same as {@code
 * PlatformOwnerController#updateTheme} - see {@code SupportController}/{@code
 * PlatformOwnerController}'s own javadoc.
 */
@Entity
@Table(name = "support_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class SupportSettings extends BaseEntity {

    @Column(name = "support_phone")
    private String supportPhone;

    @Column(name = "support_email")
    private String supportEmail;

    @Lob
    @Column(name = "terms_and_conditions", columnDefinition = "TEXT")
    private String termsAndConditions;

    @Lob
    @Column(name = "privacy_policy", columnDefinition = "TEXT")
    private String privacyPolicy;
}
