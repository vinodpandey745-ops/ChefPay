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
 * {@code Restaurant}) holding the web client's appearance configuration as a JSON blob rather
 * than one column per token. Deliberately schemaless: chefpay-web's theme shape (primary/
 * secondary/background/sidebar/card/button/typography tokens - see chefpay-web/src/lib/theme.ts)
 * is expected to grow, and every prior "add a settings field" round in this project has meant
 * touching a giant positional-record DTO (see {@code RestaurantDto}) in four places per field.
 * A JSON blob lets the frontend's theme shape evolve without a server round-trip for every new
 * token - the server just stores and version-guards the blob, it never parses or validates its
 * contents.
 *
 * <p>Read is open to any authenticated user (every terminal needs the current theme to render);
 * write requires {@code RESTAURANT_MANAGE}, same gate as every other restaurant-wide setting.
 */
@Entity
@Table(name = "theme_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@EqualsAndHashCode(callSuper = false)
public class ThemeSettings extends BaseEntity {

    /** Raw JSON, opaque to the server. Null/absent means "no customization yet - client uses its
     * own built-in default palette." */
    @Lob
    @Column(name = "theme_json", columnDefinition = "TEXT")
    private String themeJson;
}
