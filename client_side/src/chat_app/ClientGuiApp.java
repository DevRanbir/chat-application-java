package chat_app;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

public class ClientGuiApp extends Application implements ChatClientListener {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final List<String> NAME_COLORS = Arrays.asList(
            "#2563eb",
            "#0f766e",
            "#b45309",
            "#9333ea",
            "#be123c",
            "#1d4ed8",
            "#0e7490");

    private final ChatClientService clientService = new ChatClientService();
    private final ObservableList<ChatMessage> messages = FXCollections.observableArrayList();
    private final ObservableList<String> users = FXCollections.observableArrayList();

    private TextField nameField;
    private TextField hostField;
    private TextField portField;

    private Button connectButton;
    private Button disconnectButton;
    private Button sendButton;
    private Button exportButton;

    private ComboBox<String> themeSelector;

    private Label statusLabel;
    private Label usersCountLabel;
    private Label connectionHintLabel;

    private TextArea composeArea;
    private ListView<ChatMessage> messageListView;
    private ListView<String> usersListView;

    private Scene scene;
    private String currentTheme = "Light Editorial";

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        clientService.addListener(this);

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(16));

        root.setTop(buildTopSection());
        root.setCenter(buildCenterSection());

        scene = new Scene(root, 1220, 760);
        applyTheme("Light Editorial");

        stage.setTitle("Chat Application - Premium Client");
        stage.setScene(scene);
        stage.show();

        appendSystemMessage("Welcome. Connect to your server to start chatting.");
    }

    @Override
    public void stop() {
        clientService.disconnect();
    }

    @Override
    public void onConnectionStateChanged(ConnectionState state, String detail) {
        Platform.runLater(new Runnable() {
            @Override
            public void run() {
                statusLabel.setText("Status: " + state.name() + " | " + detail);
                boolean connected = state == ConnectionState.CONNECTED;
                boolean busy = state == ConnectionState.CONNECTING || state == ConnectionState.RECONNECTING;
                sendButton.setDisable(!connected);
                composeArea.setDisable(!connected);
                connectButton.setDisable(connected || busy);
                disconnectButton.setDisable(!connected && !busy);

                nameField.setDisable(connected || busy);
                hostField.setDisable(connected || busy);
                portField.setDisable(connected || busy);

                if (connected) {
                    connectionHintLabel.setText("Live chat is active. Press Enter to send and Shift+Enter for a new line.");
                } else if (busy) {
                    connectionHintLabel.setText("Network transition in progress. Please wait...");
                } else {
                    connectionHintLabel.setText("You are offline. Update host/port and connect.");
                }
            }
        });
    }

    @Override
    public void onChatMessage(ChatMessage message) {
        Platform.runLater(new Runnable() {
            @Override
            public void run() {
                messages.add(message);
                if (messages.size() > 2000) {
                    messages.remove(0);
                }
                messageListView.scrollTo(messages.size() - 1);
            }
        });
    }

    @Override
    public void onUsersUpdated(List<String> updatedUsers) {
        Platform.runLater(new Runnable() {
            @Override
            public void run() {
                users.setAll(updatedUsers);
                usersCountLabel.setText("Connected Users: " + users.size());
            }
        });
    }

    @Override
    public void onDisconnected(String reason) {
        Platform.runLater(new Runnable() {
            @Override
            public void run() {
                appendSystemMessage(reason);
            }
        });
    }

    @Override
    public void onError(String message, Exception exception) {
        Platform.runLater(new Runnable() {
            @Override
            public void run() {
                appendSystemMessage("Error: " + message + " " + safeErrorMessage(exception));
            }
        });
    }

    private VBox buildTopSection() {
        Label title = new Label("Group Chat Studio");
        title.setStyle("-fx-font-size: 30px; -fx-font-weight: 800;");

        Label subtitle = new Label("A polished real-time conversation workspace with seamless reconnect and export-ready history.");
        subtitle.setStyle("-fx-font-size: 13px;");

        HBox controls = new HBox(10);
        controls.setAlignment(Pos.CENTER_LEFT);

        nameField = new TextField("Guest");
        nameField.setPrefWidth(140);

        hostField = new TextField("localhost");
        hostField.setPrefWidth(160);

        portField = new TextField("5055");
        portField.setPrefWidth(90);

        connectButton = new Button("Connect");
        disconnectButton = new Button("Disconnect");
        disconnectButton.setDisable(true);

        exportButton = new Button("Export Chat");

        themeSelector = new ComboBox<String>();
        themeSelector.getItems().addAll("Light Editorial", "Midnight Slate");
        themeSelector.getSelectionModel().select(0);

        Label nameLabel = new Label("Name");
        Label hostLabel = new Label("Host");
        Label portLabel = new Label("Port");
        Label themeLabel = new Label("Theme");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        controls.getChildren().addAll(
                nameLabel,
                nameField,
                hostLabel,
                hostField,
                portLabel,
                portField,
                connectButton,
                disconnectButton,
                exportButton,
                spacer,
                themeLabel,
                themeSelector);

        statusLabel = new Label("Status: DISCONNECTED | Not connected");
        statusLabel.setStyle("-fx-font-size: 12px;");

        connectionHintLabel = new Label("You are offline. Update host/port and connect.");
        connectionHintLabel.setStyle("-fx-font-size: 12px; -fx-font-style: italic;");

        connectButton.setOnAction(event -> connectToServer());
        disconnectButton.setOnAction(event -> clientService.disconnect());
        exportButton.setOnAction(event -> exportChatHistory());
        themeSelector.setOnAction(event -> applyTheme(themeSelector.getValue()));

        VBox top = new VBox(8);
        top.getChildren().addAll(title, subtitle, controls, statusLabel, connectionHintLabel);
        top.setPadding(new Insets(0, 0, 12, 0));
        return top;
    }

    private SplitPane buildCenterSection() {
        messageListView = new ListView<ChatMessage>(messages);
        messageListView.setCellFactory(list -> new MessageCell());

        composeArea = new TextArea();
        composeArea.setPromptText("Write your message...");
        composeArea.setWrapText(true);
        composeArea.setPrefRowCount(3);
        composeArea.setDisable(true);

        composeArea.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER && !event.isShiftDown()) {
                event.consume();
                sendCurrentMessage();
            }
        });

        sendButton = new Button("Send");
        sendButton.setDisable(true);
        sendButton.setOnAction(event -> sendCurrentMessage());

        HBox composeActions = new HBox(sendButton);
        composeActions.setAlignment(Pos.CENTER_RIGHT);

        VBox composeBox = new VBox(8);
        composeBox.getChildren().addAll(composeArea, composeActions);
        composeBox.setPadding(new Insets(10, 0, 0, 0));

        BorderPane chatPane = new BorderPane();
        chatPane.setCenter(messageListView);
        chatPane.setBottom(composeBox);

        Label usersTitle = new Label("Connected Users");
        usersTitle.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        usersCountLabel = new Label("Connected Users: 0");
        usersCountLabel.setStyle("-fx-font-size: 12px;");

        usersListView = new ListView<String>(users);

        VBox usersPane = new VBox(8);
        usersPane.getChildren().addAll(usersTitle, usersCountLabel, usersListView);
        VBox.setVgrow(usersListView, Priority.ALWAYS);

        SplitPane splitPane = new SplitPane();
        splitPane.getItems().addAll(chatPane, usersPane);
        splitPane.setDividerPositions(0.75);
        return splitPane;
    }

    private void connectToServer() {
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException ex) {
            appendSystemMessage("Port must be a valid number.");
            return;
        }

        String host = hostField.getText().trim().isEmpty() ? "localhost" : hostField.getText().trim();
        String name = nameField.getText().trim().isEmpty() ? "Guest" : nameField.getText().trim();

        clientService.connect(host, port, name);
        appendSystemMessage("Connecting to " + host + ":" + port + " as " + name + "...");
    }

    private void sendCurrentMessage() {
        String content = composeArea.getText();
        if (content == null || content.trim().isEmpty()) {
            return;
        }
        clientService.sendChat(content);
        composeArea.clear();
    }

    private void exportChatHistory() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export Chat History");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text Files", "*.txt"));
        chooser.setInitialFileName("chat-history.txt");

        File targetFile = chooser.showSaveDialog(scene.getWindow());
        if (targetFile == null) {
            return;
        }

        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(targetFile), StandardCharsets.UTF_8))) {
            for (ChatMessage message : messages) {
                String timestamp = formatTime(message.getTimestampMillis());
                if (message.getType() == ChatMessage.MessageType.SYSTEM) {
                    writer.write("[" + timestamp + "] [SYSTEM] " + message.getContent());
                } else {
                    writer.write("[" + timestamp + "] " + message.getSender() + ": " + message.getContent());
                }
                writer.newLine();
            }
            appendSystemMessage("Chat exported to: " + targetFile.getAbsolutePath());
        } catch (Exception ex) {
            appendSystemMessage("Failed to export chat: " + ex.getMessage());
        }
    }

    private void appendSystemMessage(String content) {
        messages.add(new ChatMessage(
                ChatMessage.MessageType.SYSTEM,
                "System",
                content,
                System.currentTimeMillis(),
                false));
        if (messageListView != null) {
            messageListView.scrollTo(messages.size() - 1);
        }
    }

    private void applyTheme(String selectedTheme) {
        if (scene == null) {
            return;
        }

        currentTheme = selectedTheme == null ? "Light Editorial" : selectedTheme;

        String lightTheme = ""
                + "-fx-font-family: 'Segoe UI', 'Calibri';"
                + "-fx-base: #f8fafc;"
                + "-fx-background-color: linear-gradient(to bottom right, #f8fbff, #edf3ff);"
                + "-fx-control-inner-background: #ffffff;"
                + "-fx-accent: #2563eb;"
                + "-fx-focus-color: #2563eb;"
                + "-fx-faint-focus-color: transparent;";

        String darkTheme = ""
                + "-fx-font-family: 'Segoe UI', 'Calibri';"
                + "-fx-base: #111827;"
                + "-fx-background-color: linear-gradient(to bottom right, #111827, #1f2937);"
                + "-fx-control-inner-background: #1f2937;"
                + "-fx-text-fill: #e5e7eb;"
                + "-fx-accent: #38bdf8;"
                + "-fx-focus-color: #38bdf8;"
                + "-fx-faint-focus-color: transparent;";

        if ("Midnight Slate".equals(currentTheme)) {
            scene.getRoot().setStyle(darkTheme);
        } else {
            scene.getRoot().setStyle(lightTheme);
        }

        messageListView.refresh();
    }

    private String formatTime(long timestampMillis) {
        return TIME_FORMATTER.format(Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault()));
    }

    private String colorForSender(String sender) {
        int colorIndex = Math.abs(sender.hashCode()) % NAME_COLORS.size();
        return NAME_COLORS.get(colorIndex);
    }

    private static String safeErrorMessage(Exception exception) {
        if (exception == null || exception.getMessage() == null || exception.getMessage().trim().isEmpty()) {
            return "Unexpected network error.";
        }
        return exception.getMessage();
    }

    private class MessageCell extends ListCell<ChatMessage> {
        @Override
        protected void updateItem(ChatMessage item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }

            if (item.getType() == ChatMessage.MessageType.SYSTEM) {
                Label systemLabel = new Label(formatTime(item.getTimestampMillis()) + "  |  " + item.getContent());
                systemLabel.setWrapText(true);
                if ("Midnight Slate".equals(currentTheme)) {
                    systemLabel.setStyle("-fx-font-style: italic; -fx-text-fill: #dbeafe; -fx-background-color: #334155; -fx-padding: 6 10 6 10; -fx-background-radius: 10;");
                } else {
                    systemLabel.setStyle("-fx-font-style: italic; -fx-text-fill: #475569; -fx-background-color: #e2e8f0; -fx-padding: 6 10 6 10; -fx-background-radius: 10;");
                }

                HBox wrapper = new HBox(systemLabel);
                wrapper.setAlignment(Pos.CENTER);
                wrapper.setPadding(new Insets(6, 10, 6, 10));
                setGraphic(wrapper);
                return;
            }

            Label sender = new Label(item.getSender());
            sender.setStyle("-fx-font-size: 11px; -fx-font-weight: 700; -fx-text-fill: " + colorForSender(item.getSender()) + ";");

            Label body = new Label(item.getContent());
            body.setWrapText(true);
            body.setStyle("-fx-font-size: 14px;");

            Label meta = new Label(formatTime(item.getTimestampMillis()));
            if ("Midnight Slate".equals(currentTheme)) {
                meta.setStyle("-fx-font-size: 10px; -fx-text-fill: #93c5fd;");
            } else {
                meta.setStyle("-fx-font-size: 10px; -fx-text-fill: #64748b;");
            }

            VBox bubble = new VBox(4, sender, body, meta);
            bubble.setMaxWidth(620);

            if ("Midnight Slate".equals(currentTheme)) {
                if (item.isLocalSender()) {
                    bubble.setStyle("-fx-background-color: #1d4ed8; -fx-padding: 9 12 9 12; -fx-background-radius: 14;");
                    body.setStyle("-fx-font-size: 14px; -fx-text-fill: #e0f2fe;");
                } else {
                    bubble.setStyle("-fx-background-color: #334155; -fx-padding: 9 12 9 12; -fx-background-radius: 14;");
                    body.setStyle("-fx-font-size: 14px; -fx-text-fill: #e2e8f0;");
                }
            } else {
                if (item.isLocalSender()) {
                    bubble.setStyle("-fx-background-color: #dbeafe; -fx-padding: 9 12 9 12; -fx-background-radius: 14;");
                } else {
                    bubble.setStyle("-fx-background-color: #f1f5f9; -fx-padding: 9 12 9 12; -fx-background-radius: 14;");
                }
            }

            HBox wrapper = new HBox(bubble);
            wrapper.setPadding(new Insets(6, 10, 6, 10));
            wrapper.setAlignment(item.isLocalSender() ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
            setGraphic(wrapper);
        }
    }
}
