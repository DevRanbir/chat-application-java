package chat_app;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

public class ServerAdminApp extends Application implements ServerEventListener {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ObservableList<ClientSessionInfo> connectedClients = FXCollections.observableArrayList();
    private final ObservableList<String> eventLogs = FXCollections.observableArrayList();

    private ChatServerCore server;

    private Label statusLabel;
    private Label addressesLabel;
    private Label uptimeLabel;
    private Label clientsCountLabel;
    private Label messagesCountLabel;

    private TextField portField;
    private Button startButton;
    private Button stopButton;
    private Button disconnectButton;
    private Button shutdownButton;
    private Button exportLogsButton;

    private ListView<ClientSessionInfo> clientsView;
    private ListView<String> logsView;

    private ComboBox<String> themeSelector;
    private Timeline uptimeTicker;
    private Scene scene;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        stage.setTitle("Chat Server Admin Dashboard");

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(16));

        VBox top = buildTopPanel();
        SplitPane center = buildCenterPanel();
        HBox bottom = buildBottomPanel();

        root.setTop(top);
        root.setCenter(center);
        root.setBottom(bottom);

        scene = new Scene(root, 1200, 760);
        applyTheme(scene, "Light Editorial");
        stage.setScene(scene);
        stage.show();

        startUptimeTicker();
        appendLog("Dashboard ready. Configure port and start the server.");
    }

    @Override
    public void stop() {
        if (uptimeTicker != null) {
            uptimeTicker.stop();
        }
        if (server != null && server.isRunning()) {
            server.shutdown("Server stopped from admin dashboard.");
        }
    }

    @Override
    public void onServerStarted(int port, List<String> localAddresses) {
        Platform.runLater(new Runnable() {
            @Override
            public void run() {
                statusLabel.setText("Running");
                addressesLabel.setText("Addresses: " + String.join(", ", localAddresses) + " | Port: " + port);
                startButton.setDisable(true);
                stopButton.setDisable(false);
                disconnectButton.setDisable(false);
                shutdownButton.setDisable(false);
                appendLog("Server started on port " + port + ".");
            }
        });
    }

    @Override
    public void onServerStopped(String reason) {
        Platform.runLater(new Runnable() {
            @Override
            public void run() {
                statusLabel.setText("Stopped");
                addressesLabel.setText("Addresses: -");
                startButton.setDisable(false);
                stopButton.setDisable(true);
                disconnectButton.setDisable(true);
                shutdownButton.setDisable(true);
                connectedClients.clear();
                clientsCountLabel.setText("Connected Clients: 0");
                appendLog("Server stopped. Reason: " + reason);
            }
        });
    }

    @Override
    public void onClientConnected(ClientSessionInfo client) {
        Platform.runLater(new Runnable() {
            @Override
            public void run() {
                upsertClient(client);
                appendLog("Connected: " + client.toString());
            }
        });
    }

    @Override
    public void onClientUpdated(ClientSessionInfo client) {
        Platform.runLater(new Runnable() {
            @Override
            public void run() {
                upsertClient(client);
                appendLog("Updated: " + client.toString());
            }
        });
    }

    @Override
    public void onClientDisconnected(ClientSessionInfo client, String reason) {
        Platform.runLater(new Runnable() {
            @Override
            public void run() {
                removeClient(client.getId());
                appendLog("Disconnected: " + client.toString() + " | reason=" + reason);
            }
        });
    }

    @Override
    public void onChatMessage(String sender, String content, long timestampMillis) {
        Platform.runLater(new Runnable() {
            @Override
            public void run() {
                appendLog(formatTime(timestampMillis) + " | " + sender + ": " + content);
                refreshCounters();
            }
        });
    }

    @Override
    public void onSystemMessage(String content, long timestampMillis) {
        Platform.runLater(new Runnable() {
            @Override
            public void run() {
                appendLog(formatTime(timestampMillis) + " | SYSTEM: " + content);
            }
        });
    }

    @Override
    public void onError(String message, Exception exception) {
        Platform.runLater(new Runnable() {
            @Override
            public void run() {
                appendLog("ERROR: " + message + " | " + exception.getMessage());
            }
        });
    }

    private VBox buildTopPanel() {
        Label title = new Label("Server Control Center");
        title.setStyle("-fx-font-size: 28px; -fx-font-weight: bold;");

        Label subtitle = new Label("Monitor sessions, logs, and operations in real time.");
        subtitle.setStyle("-fx-font-size: 13px;");

        HBox controlsRow = new HBox(10);
        controlsRow.setAlignment(Pos.CENTER_LEFT);

        portField = new TextField("5055");
        portField.setPrefWidth(100);

        startButton = new Button("Start Server");
        stopButton = new Button("Stop Server");
        disconnectButton = new Button("Disconnect Selected");
        shutdownButton = new Button("Shutdown All");
        exportLogsButton = new Button("Export Logs");

        stopButton.setDisable(true);
        disconnectButton.setDisable(true);
        shutdownButton.setDisable(true);

        themeSelector = new ComboBox<String>();
        themeSelector.getItems().addAll("Light Editorial", "Midnight Slate");
        themeSelector.getSelectionModel().select(0);

        Label portLabel = new Label("Port");
        Label themeLabel = new Label("Theme");

        controlsRow.getChildren().addAll(
                portLabel,
                portField,
                startButton,
                stopButton,
                disconnectButton,
                shutdownButton,
                exportLogsButton,
                new Region(),
                themeLabel,
                themeSelector);
            HBox.setHgrow(controlsRow.getChildren().get(7), Priority.ALWAYS);

        HBox metricsRow = new HBox(18);
        metricsRow.setAlignment(Pos.CENTER_LEFT);
        statusLabel = new Label("Stopped");
        addressesLabel = new Label("Addresses: -");
        uptimeLabel = new Label("Uptime: 00:00:00");
        clientsCountLabel = new Label("Connected Clients: 0");
        messagesCountLabel = new Label("Messages: 0");
        metricsRow.getChildren().addAll(statusLabel, addressesLabel, uptimeLabel, clientsCountLabel, messagesCountLabel);

        startButton.setOnAction(event -> startServer());
        stopButton.setOnAction(event -> stopServer());
        disconnectButton.setOnAction(event -> disconnectSelectedClient());
        shutdownButton.setOnAction(event -> shutdownServer());
        exportLogsButton.setOnAction(event -> exportLogs());
        themeSelector.setOnAction(event -> {
            Scene scene = themeSelector.getScene();
            if (scene != null) {
                applyTheme(scene, themeSelector.getValue());
            }
        });

        VBox top = new VBox(8);
        top.getChildren().addAll(title, subtitle, controlsRow, metricsRow);
        top.setPadding(new Insets(0, 0, 14, 0));
        return top;
    }

    private SplitPane buildCenterPanel() {
        clientsView = new ListView<ClientSessionInfo>(connectedClients);
        clientsView.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);

        logsView = new ListView<String>(eventLogs);

        VBox clientsBox = new VBox(8);
        Label clientsTitle = new Label("Connected Users");
        clientsTitle.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        clientsBox.getChildren().addAll(clientsTitle, clientsView);
        VBox.setVgrow(clientsView, Priority.ALWAYS);

        VBox logsBox = new VBox(8);
        Label logsTitle = new Label("Live Event Stream");
        logsTitle.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
        logsBox.getChildren().addAll(logsTitle, logsView);
        VBox.setVgrow(logsView, Priority.ALWAYS);

        SplitPane splitPane = new SplitPane();
        splitPane.getItems().addAll(clientsBox, logsBox);
        splitPane.setDividerPositions(0.32);
        return splitPane;
    }

    private HBox buildBottomPanel() {
        Label footer = new Label("Operational tip: use Disconnect Selected for moderation and Shutdown All only for planned maintenance.");
        footer.setStyle("-fx-font-size: 12px;");

        HBox bottom = new HBox(footer);
        bottom.setAlignment(Pos.CENTER_LEFT);
        bottom.setPadding(new Insets(12, 0, 0, 0));
        return bottom;
    }

    private void startServer() {
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException ex) {
            appendLog("Invalid port. Please enter a number.");
            return;
        }

        if (server != null && server.isRunning()) {
            appendLog("Server is already running.");
            return;
        }

        try {
            server = new ChatServerCore(port);
            server.addListener(this);
            server.start();
            refreshCounters();
        } catch (Exception ex) {
            appendLog("Failed to start server: " + ex.getMessage());
        }
    }

    private void stopServer() {
        if (server == null || !server.isRunning()) {
            appendLog("Server is not running.");
            return;
        }
        if (!confirmAction("Stop Server", "Stop server and disconnect all clients?")) {
            return;
        }
        server.stop("Server stopped by admin.");
    }

    private void shutdownServer() {
        if (server == null || !server.isRunning()) {
            appendLog("Server is not running.");
            return;
        }
        if (!confirmAction("Shutdown All", "Broadcast global shutdown to all clients?")) {
            return;
        }
        server.shutdown("Global shutdown requested by admin.");
    }

    private void disconnectSelectedClient() {
        if (server == null || !server.isRunning()) {
            appendLog("Server is not running.");
            return;
        }

        ClientSessionInfo selected = clientsView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            appendLog("Select a client to disconnect.");
            return;
        }

        if (!confirmAction("Disconnect Client", "Disconnect user " + selected.getDisplayName() + "?")) {
            return;
        }

        server.disconnectClient(selected.getId(), "Disconnected by admin.");
    }

    private void exportLogs() {
        if (scene == null) {
            appendLog("UI not ready for export.");
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export Server Logs");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text Files", "*.txt"));
        chooser.setInitialFileName("server-log.txt");

        File targetFile = chooser.showSaveDialog(scene.getWindow());
        if (targetFile == null) {
            return;
        }

        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(targetFile), StandardCharsets.UTF_8))) {
            for (String line : eventLogs) {
                writer.write(line);
                writer.newLine();
            }
            appendLog("Logs exported to: " + targetFile.getAbsolutePath());
        } catch (Exception ex) {
            appendLog("Failed to export logs: " + ex.getMessage());
        }
    }

    private boolean confirmAction(String title, String question) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(question);
        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }

    private void upsertClient(ClientSessionInfo info) {
        int existingIndex = -1;
        for (int index = 0; index < connectedClients.size(); index++) {
            if (connectedClients.get(index).getId() == info.getId()) {
                existingIndex = index;
                break;
            }
        }

        if (existingIndex >= 0) {
            connectedClients.set(existingIndex, info);
        } else {
            connectedClients.add(info);
        }

        List<ClientSessionInfo> sorted = new ArrayList<ClientSessionInfo>(connectedClients);
        Collections.sort(sorted, (left, right) -> Integer.compare(left.getId(), right.getId()));
        connectedClients.setAll(sorted);

        refreshCounters();
    }

    private void removeClient(int clientId) {
        for (int index = 0; index < connectedClients.size(); index++) {
            if (connectedClients.get(index).getId() == clientId) {
                connectedClients.remove(index);
                break;
            }
        }
        refreshCounters();
    }

    private void refreshCounters() {
        clientsCountLabel.setText("Connected Clients: " + connectedClients.size());
        long messages = server == null ? 0L : server.getMessageCount();
        messagesCountLabel.setText("Messages: " + messages);
    }

    private void appendLog(String message) {
        String line = formatTime(System.currentTimeMillis()) + " | " + message;
        eventLogs.add(line);
        if (eventLogs.size() > 1000) {
            eventLogs.remove(0);
        }
        logsView.scrollTo(eventLogs.size() - 1);
    }

    private static String formatTime(long timestampMillis) {
        return TIME_FORMATTER.format(Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault()));
    }

    private void startUptimeTicker() {
        uptimeTicker = new Timeline(new KeyFrame(javafx.util.Duration.seconds(1), event -> {
            if (server == null || !server.isRunning()) {
                uptimeLabel.setText("Uptime: 00:00:00");
                return;
            }
            Duration elapsed = Duration.between(
                    Instant.ofEpochMilli(server.getStartedAtMillis()),
                    Instant.ofEpochMilli(System.currentTimeMillis()));
            long hours = elapsed.toHours();
            long minutes = elapsed.toMinutes() % 60;
            long seconds = elapsed.getSeconds() % 60;
            uptimeLabel.setText(String.format("Uptime: %02d:%02d:%02d", hours, minutes, seconds));
        }));
        uptimeTicker.setCycleCount(Timeline.INDEFINITE);
        uptimeTicker.play();
    }

    private void applyTheme(Scene scene, String selectedTheme) {
        String lightTheme = ""
                + "-fx-font-family: 'Segoe UI', 'Calibri';"
                + "-fx-base: #f8fafc;"
                + "-fx-background-color: linear-gradient(to bottom right, #f8fbff, #edf3ff);"
                + "-fx-control-inner-background: #ffffff;"
                + "-fx-accent: #0b6ef6;"
                + "-fx-focus-color: #0b6ef6;"
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

        if ("Midnight Slate".equals(selectedTheme)) {
            scene.getRoot().setStyle(darkTheme);
        } else {
            scene.getRoot().setStyle(lightTheme);
        }
    }
}
