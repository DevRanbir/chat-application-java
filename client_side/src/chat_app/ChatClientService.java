package chat_app;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

public class ChatClientService {
    private static final String CLIENT_HELLO_PREFIX = "CLIENT_HELLO|";
    private static final String CLIENT_CHAT_PREFIX = "CLIENT_CHAT|";
    private static final String CLIENT_QUIT = "CLIENT_QUIT";

    private static final String SERVER_CHAT_PREFIX = "SERVER_CHAT|";
    private static final String SERVER_SYSTEM_PREFIX = "SERVER_SYSTEM|";
    private static final String SERVER_USERS_PREFIX = "SERVER_USERS|";
    private static final String SERVER_SHUTDOWN_PREFIX = "SERVER_SHUTDOWN|";

    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int MAX_RECONNECT_ATTEMPTS = 8;
    private static final long BASE_RECONNECT_DELAY_MS = 1500L;

    private final List<ChatClientListener> listeners = new CopyOnWriteArrayList<ChatClientListener>();
    private final BlockingQueue<String> outboundQueue = new LinkedBlockingQueue<String>();
    private final Object connectionLock = new Object();
    private final Object writeLock = new Object();

    private volatile Socket socket;
    private volatile DataInputStream input;
    private volatile DataOutputStream output;

    private volatile String host = "localhost";
    private volatile int port = 5055;
    private volatile String userName = "User";

    private volatile ConnectionState state = ConnectionState.DISCONNECTED;
    private volatile Thread connectionThread;
    private volatile boolean shouldRun;
    private volatile boolean manualDisconnect;

    public void addListener(ChatClientListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(ChatClientListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    public ConnectionState getState() {
        return state;
    }

    public String getUserName() {
        return userName;
    }

    public void connect(String host, int port, String userName) {
        synchronized (connectionLock) {
            this.host = (host == null || host.trim().isEmpty()) ? "localhost" : host.trim();
            this.port = port;
            this.userName = normalizeUserName(userName);
            this.manualDisconnect = false;
            this.shouldRun = true;

            if (connectionThread != null && connectionThread.isAlive()) {
                return;
            }

            connectionThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    runConnectionLoop();
                }
            }, "chat-client-connection");
            connectionThread.setDaemon(true);
            connectionThread.start();
        }
    }

    public void sendChat(String content) {
        if (content == null) {
            return;
        }
        String trimmed = content.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        outboundQueue.offer(CLIENT_CHAT_PREFIX + encodeField(trimmed));
    }

    public void disconnect() {
        manualDisconnect = true;
        shouldRun = false;

        try {
            sendRaw(CLIENT_QUIT);
        } catch (IOException ex) {
            // no-op
        }

        closeSocketQuietly();
        setState(ConnectionState.DISCONNECTED, "Disconnected.");
        fireDisconnected("Disconnected.");
    }

    private void runConnectionLoop() {
        int reconnectAttempts = 0;

        while (shouldRun) {
            boolean reconnecting = reconnectAttempts > 0;
            if (reconnecting) {
                setState(ConnectionState.RECONNECTING, "Reconnect attempt " + reconnectAttempts + "...");
            } else {
                setState(ConnectionState.CONNECTING, "Connecting to " + host + ":" + port + "...");
            }

            Thread senderThread = null;
            try {
                openConnection();
                reconnectAttempts = 0;
                setState(ConnectionState.CONNECTED, "Connected to " + host + ":" + port + ".");

                sendRaw(CLIENT_HELLO_PREFIX + encodeField(userName));
                senderThread = startSenderLoop();
                readIncomingLoop();
            } catch (IOException ex) {
                if (shouldRun && !manualDisconnect) {
                    fireError("Connection issue.", ex);
                }
            } finally {
                closeSocketQuietly();
                if (senderThread != null) {
                    senderThread.interrupt();
                    try {
                        senderThread.join(500L);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            if (!shouldRun || manualDisconnect) {
                break;
            }

            reconnectAttempts++;
            if (reconnectAttempts > MAX_RECONNECT_ATTEMPTS) {
                shouldRun = false;
                setState(ConnectionState.FAILED, "Unable to reconnect.");
                fireDisconnected("Unable to reconnect to server.");
                break;
            }

            long delay = reconnectDelayMs(reconnectAttempts);
            setState(ConnectionState.RECONNECTING, "Reconnecting in " + (delay / 1000.0) + "s...");
            try {
                Thread.sleep(delay);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (manualDisconnect) {
            return;
        }

        if (state != ConnectionState.FAILED) {
            setState(ConnectionState.DISCONNECTED, "Connection closed.");
        }
    }

    private void openConnection() throws IOException {
        Socket newSocket = new Socket();
        newSocket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        newSocket.setKeepAlive(true);
        newSocket.setTcpNoDelay(true);

        socket = newSocket;
        input = new DataInputStream(newSocket.getInputStream());
        output = new DataOutputStream(newSocket.getOutputStream());
    }

    private Thread startSenderLoop() {
        Thread senderThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (shouldRun && !Thread.currentThread().isInterrupted()) {
                    try {
                        String payload = outboundQueue.take();
                        sendRaw(payload);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (IOException ex) {
                        fireError("Send failed.", ex);
                        closeSocketQuietly();
                        break;
                    }
                }
            }
        }, "chat-client-sender");

        senderThread.setDaemon(true);
        senderThread.start();
        return senderThread;
    }

    private void readIncomingLoop() throws IOException {
        while (shouldRun) {
            String payload;
            try {
                payload = input.readUTF();
            } catch (EOFException ex) {
                throw new SocketException("Server closed the connection.");
            }
            handleServerPayload(payload);
        }
    }

    private void handleServerPayload(String payload) {
        if (payload == null || payload.trim().isEmpty()) {
            return;
        }

        if (payload.startsWith(SERVER_CHAT_PREFIX)) {
            String[] parts = payload.split("\\|", 4);
            if (parts.length < 4) {
                return;
            }

            String sender = decodeField(parts[1]);
            long timestamp = parseLong(parts[2], System.currentTimeMillis());
            String content = decodeField(parts[3]);
            boolean localSender = sender.equals(userName);

            fireChatMessage(new ChatMessage(ChatMessage.MessageType.CHAT, sender, content, timestamp, localSender));
            return;
        }

        if (payload.startsWith(SERVER_SYSTEM_PREFIX)) {
            String[] parts = payload.split("\\|", 3);
            if (parts.length < 3) {
                return;
            }

            long timestamp = parseLong(parts[1], System.currentTimeMillis());
            String content = decodeField(parts[2]);
            fireChatMessage(new ChatMessage(ChatMessage.MessageType.SYSTEM, "System", content, timestamp, false));
            return;
        }

        if (payload.startsWith(SERVER_USERS_PREFIX)) {
            String data = payload.substring(SERVER_USERS_PREFIX.length());
            List<String> users = new ArrayList<String>();
            if (!data.trim().isEmpty()) {
                String[] encodedUsers = data.split(",");
                for (String encodedUser : encodedUsers) {
                    users.add(decodeField(encodedUser));
                }
            }
            Collections.sort(users);
            fireUsersUpdated(users);
            return;
        }

        if (payload.startsWith(SERVER_SHUTDOWN_PREFIX)) {
            String[] parts = payload.split("\\|", 3);
            if (parts.length >= 3) {
                long timestamp = parseLong(parts[1], System.currentTimeMillis());
                String reason = decodeField(parts[2]);
                fireChatMessage(new ChatMessage(ChatMessage.MessageType.SYSTEM, "System", reason, timestamp, false));
                shouldRun = false;
                manualDisconnect = true;
                setState(ConnectionState.DISCONNECTED, "Server shutdown.");
                fireDisconnected(reason);
            }
            return;
        }

        if ("quited".equalsIgnoreCase(payload.trim())) {
            fireChatMessage(new ChatMessage(
                    ChatMessage.MessageType.SYSTEM,
                    "System",
                    "Server initiated a global shutdown.",
                    System.currentTimeMillis(),
                    false));
            shouldRun = false;
            manualDisconnect = true;
            setState(ConnectionState.DISCONNECTED, "Server shutdown.");
            fireDisconnected("Server initiated a global shutdown.");
            return;
        }

        String[] legacyParts = payload.split(" ", 2);
        if (legacyParts.length < 2) {
            return;
        }

        fireChatMessage(new ChatMessage(
                ChatMessage.MessageType.CHAT,
                legacyParts[0],
                legacyParts[1],
                System.currentTimeMillis(),
                legacyParts[0].equals(userName)));
    }

    private void sendRaw(String payload) throws IOException {
        synchronized (writeLock) {
            if (output == null) {
                throw new IOException("Not connected.");
            }
            output.writeUTF(payload);
            output.flush();
        }
    }

    private void closeSocketQuietly() {
        closeQuietly(input);
        closeQuietly(output);
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ex) {
                // no-op
            }
        }

        socket = null;
        input = null;
        output = null;
    }

    private void setState(ConnectionState newState, String detail) {
        state = newState;
        for (ChatClientListener listener : listeners) {
            try {
                listener.onConnectionStateChanged(newState, detail);
            } catch (RuntimeException ex) {
                // Listener failures should not break networking.
            }
        }
    }

    private void fireChatMessage(ChatMessage message) {
        for (ChatClientListener listener : listeners) {
            try {
                listener.onChatMessage(message);
            } catch (RuntimeException ex) {
                // Listener failures should not break networking.
            }
        }
    }

    private void fireUsersUpdated(List<String> users) {
        for (ChatClientListener listener : listeners) {
            try {
                listener.onUsersUpdated(users);
            } catch (RuntimeException ex) {
                // Listener failures should not break networking.
            }
        }
    }

    private void fireDisconnected(String reason) {
        for (ChatClientListener listener : listeners) {
            try {
                listener.onDisconnected(reason);
            } catch (RuntimeException ex) {
                // Listener failures should not break networking.
            }
        }
    }

    private void fireError(String message, Exception exception) {
        for (ChatClientListener listener : listeners) {
            try {
                listener.onError(message, exception);
            } catch (RuntimeException ex) {
                // Listener failures should not break networking.
            }
        }
    }

    private static long reconnectDelayMs(int attempt) {
        long delay = BASE_RECONNECT_DELAY_MS * attempt;
        return Math.min(delay, 8000L);
    }

    private static long parseLong(String rawValue, long fallback) {
        try {
            return Long.parseLong(rawValue);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String normalizeUserName(String rawName) {
        if (rawName == null || rawName.trim().isEmpty()) {
            return "Guest";
        }

        String cleaned = rawName.trim();
        cleaned = cleaned.replaceAll("\\s+", "_");
        cleaned = cleaned.replace('|', '_');
        cleaned = cleaned.replace(',', '_');

        if (cleaned.length() > 24) {
            return cleaned.substring(0, 24);
        }
        return cleaned;
    }

    private static String encodeField(String value) {
        String safeValue = value == null ? "" : value;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(safeValue.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeField(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        try {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return value;
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ex) {
            // no-op
        }
    }
}
