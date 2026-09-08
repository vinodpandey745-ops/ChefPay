package com.chefpay.javafx.common;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

/**
 * Loads the restaurant-provided ChefPay mark once and hands out {@link ImageView} copies sized for
 * wherever it's used - the login screen's brand panel, the shell header, and the app window/taskbar
 * icon ({@code ChefPayDesktopApp}). Packaged at {@code chefpay-javafx/src/main/resources/images/
 * chefpay-logo.png} rather than the placeholder 🍽 emoji this screen used before.
 */
public final class AppLogo {

    private static final String RESOURCE_PATH = "/images/chefpay-logo.png";
    private static final Image IMAGE = loadImage();

    private AppLogo() {
    }

    private static Image loadImage() {
        var stream = AppLogo.class.getResourceAsStream(RESOURCE_PATH);
        if (stream == null) {
            return null;
        }
        return new Image(stream);
    }

    /** True as long as the packaged logo file loaded correctly - callers can fall back to text/emoji if not. */
    public static boolean isAvailable() {
        return IMAGE != null;
    }

    public static Image image() {
        return IMAGE;
    }

    /** An {@code ImageView} square-fitted to {@code size}, or a text fallback if the resource is missing. */
    public static Node imageView(double size) {
        if (IMAGE == null) {
            Label fallback = new Label("🍽");
            fallback.setStyle("-fx-font-size: " + size + "px;");
            return fallback;
        }
        ImageView view = new ImageView(IMAGE);
        view.setFitWidth(size);
        view.setFitHeight(size);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        return view;
    }
}
