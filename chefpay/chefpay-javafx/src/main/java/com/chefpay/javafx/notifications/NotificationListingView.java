package com.chefpay.javafx.notifications;

import com.chefpay.javafx.client.ApiClient;
import com.chefpay.javafx.client.ApiException;
import com.chefpay.javafx.client.dto.NotificationDtos;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Alerts / notification inbox - Round 8's Notification screen. The "Unread only" checkbox drives
 * which query is issued in {@link #reload()} ({@code unreadOnly=true} vs {@code unreadOnly=false});
 * marking a row read PATCHes it and then reloads under the same filter, same "re-render straight
 * from the server's response" discipline as every other screen here.
 */
public class NotificationListingView {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy hh:mm a");

    private final BorderPane root = new BorderPane();
    private final ApiClient apiClient;
    private final VBox notificationsBox = new VBox(8);
    private final Label statusLabel = new Label();
    private final CheckBox unreadOnly = new CheckBox("Unread only");

    public NotificationListingView(ApiClient apiClient) {
        this.apiClient = apiClient;

        root.setTop(buildHeader());
        ScrollPane scroll = new ScrollPane(notificationsBox);
        scroll.setFitToWidth(true);
        notificationsBox.setPadding(new Insets(16, 24, 24, 24));
        root.setCenter(scroll);
    }

    private HBox buildHeader() {
        Label title = new Label("Alerts");
        title.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        statusLabel.setStyle("-fx-text-fill: #666;");

        unreadOnly.setOnAction(e -> reload());

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button refresh = new Button("Refresh");
        refresh.setOnAction(e -> reload());

        HBox header = new HBox(16, title, unreadOnly, spacer, statusLabel, refresh);
        header.setPadding(new Insets(16, 24, 16, 24));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setStyle("-fx-background-color: #f4f5f7; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");
        return header;
    }

    public void reload() {
        String path = "/api/notifications?unreadOnly=" + (unreadOnly.isSelected() ? "true" : "false");
        Thread worker = new Thread(() -> {
            try {
                var data = apiClient.get(path);
                List<NotificationDtos.NotificationDto> notifications =
                        apiClient.convertList(data, NotificationDtos.NotificationDto.class);
                Platform.runLater(() -> render(notifications));
            } catch (ApiException ex) {
                Platform.runLater(() -> statusLabel.setText("Could not load alerts: " + ex.getMessage()));
            }
        }, "chefpay-notifications-load");
        worker.setDaemon(true);
        worker.start();
    }

    private void render(List<NotificationDtos.NotificationDto> notifications) {
        long unreadCount = notifications.stream().filter(n -> !n.read()).count();
        statusLabel.setText(unreadCount + " unread of " + notifications.size() + " total");

        notificationsBox.getChildren().clear();
        if (notifications.isEmpty()) {
            notificationsBox.getChildren().add(new Label("No alerts to show."));
            return;
        }
        for (NotificationDtos.NotificationDto notification : notifications) {
            notificationsBox.getChildren().add(buildRow(notification));
        }
    }

    private HBox buildRow(NotificationDtos.NotificationDto notification) {
        Label tag = new Label(notification.category());
        tag.setStyle("-fx-font-weight: bold; -fx-font-size: 11px; -fx-text-fill: white; -fx-padding: 2 8 2 8; "
                + "-fx-background-radius: 4; -fx-background-color: " + categoryColor(notification.category()) + ";");
        tag.setPrefWidth(140);

        Label message = new Label(notification.message());
        message.setWrapText(true);
        message.setPrefWidth(420);
        message.setStyle(notification.read() ? "-fx-font-size: 13px;" : "-fx-font-size: 13px; -fx-font-weight: bold;");

        Label timestamp = new Label(notification.createdAt() == null ? "-" : notification.createdAt().format(TIMESTAMP_FORMAT));
        timestamp.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");
        timestamp.setPrefWidth(150);

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button markRead = new Button("Mark Read");
        markRead.setDisable(notification.read());
        markRead.setVisible(!notification.read());
        markRead.setOnAction(e -> markRead(notification));

        HBox row = new HBox(12, tag, message, timestamp, spacer, markRead);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(8, 12, 8, 12));
        row.setStyle(notification.read()
                ? "-fx-border-color: #eee; -fx-border-width: 0 0 1 0;"
                : "-fx-border-color: #eee; -fx-border-width: 0 0 1 0; -fx-background-color: #fff8e1;");
        return row;
    }

    private String categoryColor(String category) {
        if (category == null) {
            return "#888888";
        }
        return switch (category) {
            case "LOW_STOCK" -> "#c0392b";
            case "ORDER_CANCELLED" -> "#e67e22";
            default -> "#7f8c8d";
        };
    }

    private void markRead(NotificationDtos.NotificationDto notification) {
        Thread worker = new Thread(() -> {
            try {
                apiClient.patch("/api/notifications/" + notification.id() + "/read", null);
                Platform.runLater(this::reload);
            } catch (ApiException ex) {
                Platform.runLater(() -> new javafx.scene.control.Alert(
                        javafx.scene.control.Alert.AlertType.ERROR, ex.getMessage()).showAndWait());
            }
        }, "chefpay-notification-mark-read");
        worker.setDaemon(true);
        worker.start();
    }

    public Parent view() {
        return root;
    }
}
