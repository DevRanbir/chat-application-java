package chat_app;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class ChatServerCore {
    private static final String CLIENT_HELLO_PREFIX = "CLIENT_HELLO|";
    private static final String CLIENT_CHAT_PREFIX = "CLIENT_CHAT|";
    private static final String CLIENT_QUIT = "CLIENT_QUIT";

    private static final String SERVER_CHAT_PREFIX = "SERVER_CHAT|";
    private static final String SERVER_SYSTEM_PREFIX = "SERVER_SYSTEM|";
    private static final String SERVER_USERS_PREFIX = "SERVER_USERS|";
    private static final String SERVER_SHUTDOWN_PREFIX = "SERVER_SHUTDOWN|";

    private final int port;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger nextClientId = new AtomicInteger(1);
    private final AtomicLong messageCount = new AtomicLong(0);

    private final Map<Integer, ClientConnection> clientsById = new ConcurrentHashMap<Integer, ClientConnection>();
    private final List<ServerEventListener> listeners = new CopyOnWriteArrayList<ServerEventListener>();

    private final Object lifecycleLock = new Object();
    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;
    private volatile ExecutorService clientExecutor;
    private volatile long startedAtMillis;

    public ChatServerCore(int port) {
        this.port = port;
    }

    public int getPort() {
        return port;
    }

    public boolean isRunning() {
        return running.get();
    }

    public long getStartedAtMillis() {
        return startedAtMillis;
    }

    public long getUptimeMillis() {
        if (!isRunning()) {
            return 0;
        }
        return Math.max(0L, System.currentTimeMillis() - startedAtMillis);
    }

    public long getMessageCount() {
        return messageCount.get();
    }

    public void addListener(ServerEventListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(ServerEventListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    public List<ClientSessionInfo> getConnectedClients() {
        List<ClientSessionInfo> snapshots = new ArrayList<ClientSessionInfo>();
        for (ClientConnection client : clientsById.values()) {
            snapshots.add(toSessionInfo(client));
        }
        Collections.sort(snapshots, new Comparator<ClientSessionInfo>() {
            @Override
            public int compare(ClientSessionInfo left, ClientSessionInfo right) {
                return Integer.compare(left.getId(), right.getId());
            }
        });
        return snapshots;
    }

    public List<String> getCurrentUserNames() {
        List<String> users = new ArrayList<String>();
        for (ClientConnection client : clientsById.values()) {
            users.add(displayNameOrGuest(client));
        }
        Collections.sort(users);
        return users;
    }

    public List<String> getLocalIpv4Addresses() {
        List<String> addresses = new ArrayList<String>();
        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback() || networkInterface.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
                while (inetAddresses.hasMoreElements()) {
                    InetAddress address = inetAddresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        addresses.add(address.getHostAddress());
                    }
                }
            }
        } catch (SocketException ex) {
            fireError("Could not enumerate network addresses.", ex);
        }
        if (addresses.isEmpty()) {
            addresses.add("127.0.0.1");
        }
        Collections.sort(addresses);
        return addresses;
    }

    public void start() throws IOException {
        synchronized (lifecycleLock) {
            if (running.get()) {
                return;
            }
            serverSocket = new ServerSocket(port);
            clientExecutor = Executors.newCachedThreadPool();
            running.set(true);
            startedAtMillis = System.currentTimeMillis();
            messageCount.set(0L);
        }

        fireServerStarted(port, getLocalIpv4Addresses());

        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "chat-server-accept");
        acceptThread.start();
    }

    public void disconnectClient(int clientId, String reason) {
        removeClient(clientId, reason, true, false);
    }

    public void shutdown(String reason) {
        stopInternal(reason, true);
    }

    public void stop(String reason) {
        stopInternal(reason, true);
    }

    private void stopInternal(String reason, boolean broadcastShutdown) {
        String shutdownReason = normalizeSystemMessage(reason, "Server stopped.");
        if (!running.compareAndSet(true, false)) {
            return;
        }

        if (broadcastShutdown) {
            String frame = buildShutdownFrame(shutdownReason);
            broadcastToAll(frame, "quited");
            fireSystemMessage(shutdownReason, System.currentTimeMillis());
        }

        closeServerSocket();

        List<Integer> clientIds = new ArrayList<Integer>(clientsById.keySet());
        for (Integer clientId : clientIds) {
            removeClient(clientId.intValue(), shutdownReason, true, true);
        }

        ExecutorService executorSnapshot = clientExecutor;
        if (executorSnapshot != null) {
            executorSnapshot.shutdownNow();
        }

        fireServerStopped(shutdownReason);
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                socket.setKeepAlive(true);

                ClientConnection connection = new ClientConnection(nextClientId.getAndIncrement(), socket);
                clientsById.put(Integer.valueOf(connection.getId()), connection);

                fireClientConnected(toSessionInfo(connection));
                fireUserListUpdated(getCurrentUserNames());
                broadcastUserListFrame();

                clientExecutor.submit(new Runnable() {
                    @Override
                    public void run() {
                        readClientLoop(connection);
                    }
                });
            } catch (SocketException ex) {
                if (running.get()) {
                    fireError("Socket error in accept loop.", ex);
                }
            } catch (IOException ex) {
                if (running.get()) {
                    fireError("I/O error while accepting client.", ex);
                }
            }
        }
    }

    private void readClientLoop(ClientConnection connection) {
        try {
            while (running.get() && !connection.isClosed()) {
                String payload = connection.getInput().readUTF();
                handleIncomingPayload(connection, payload);
            }
        } catch (EOFException ex) {
            removeClient(connection.getId(), "Client disconnected.", true, false);
        } catch (SocketException ex) {
            removeClient(connection.getId(), "Client disconnected.", true, false);
        } catch (IOException ex) {
            removeClient(connection.getId(), "Connection lost.", true, false);
        }
    }

    private void handleIncomingPayload(ClientConnection connection, String payload) {
        if (payload == null || payload.trim().isEmpty()) {
            return;
        }

        if (payload.startsWith(CLIENT_HELLO_PREFIX)) {
            connection.setGuiClient(true);
            String encodedName = payload.substring(CLIENT_HELLO_PREFIX.length());
            String requestedName = normalizeName(decodeField(encodedName), connection.getId());
            registerDisplayName(connection, requestedName);
            fireClientUpdated(toSessionInfo(connection));
            sendSystemToConnection(connection, "Welcome " + requestedName + "!");
            return;
        }

        if (payload.startsWith(CLIENT_CHAT_PREFIX)) {
            String content = decodeField(payload.substring(CLIENT_CHAT_PREFIX.length())).trim();
            if (content.isEmpty()) {
                return;
            }
            if ("quit".equalsIgnoreCase(content) || "/quit".equalsIgnoreCase(content)) {
                removeClient(connection.getId(), "User left the chat.", true, false);
                return;
            }
            String sender = ensureDisplayName(connection);
            broadcastChat(sender, content);
            return;
        }

        if (CLIENT_QUIT.equals(payload)) {
            removeClient(connection.getId(), "User left the chat.", true, false);
            return;
        }

        handleLegacyPayload(connection, payload);
    }

    private void handleLegacyPayload(ClientConnection connection, String payload) {
        String[] parts = payload.split(" ", 2);
        if (parts.length < 2) {
            return;
        }

        String parsedSender = normalizeName(parts[0], connection.getId());
        String content = parts[1].trim();
        if (content.isEmpty()) {
            return;
        }

        if (connection.getDisplayName() == null || connection.getDisplayName().trim().isEmpty()) {
            registerDisplayName(connection, parsedSender);
            fireClientUpdated(toSessionInfo(connection));
        }

        String sender = ensureDisplayName(connection);

        if ("quit".equalsIgnoreCase(content)) {
            removeClient(connection.getId(), "User left the chat.", true, false);
            return;
        }

        broadcastChat(sender, content);
    }

    private void registerDisplayName(ClientConnection connection, String requestedName) {
        String currentName = connection.getDisplayName();
        if (currentName != null && currentName.equals(requestedName)) {
            return;
        }

        if (currentName == null || currentName.trim().isEmpty()) {
            connection.setDisplayName(requestedName);
            broadcastSystem(requestedName + " joined the chat.");
            broadcastUserListFrame();
            fireUserListUpdated(getCurrentUserNames());
            return;
        }

        connection.setDisplayName(requestedName);
        broadcastSystem(currentName + " is now known as " + requestedName + ".");
        broadcastUserListFrame();
        fireUserListUpdated(getCurrentUserNames());
    }

    private String ensureDisplayName(ClientConnection connection) {
        String displayName = connection.getDisplayName();
        if (displayName != null && !displayName.trim().isEmpty()) {
            return displayName;
        }
        String generatedName = normalizeName("Guest-" + connection.getId(), connection.getId());
        registerDisplayName(connection, generatedName);
        return generatedName;
    }

    private void sendSystemToConnection(ClientConnection connection, String content) {
        try {
            if (connection.isGuiClient()) {
                connection.send(buildSystemFrame(content));
            } else {
                connection.send("Server " + content);
            }
        } catch (IOException ex) {
            removeClient(connection.getId(), "Connection lost.", true, false);
        }
    }

    private void broadcastChat(String sender, String content) {
        long timestamp = System.currentTimeMillis();
        messageCount.incrementAndGet();

        String guiFrame = buildChatFrame(sender, timestamp, content);
        String legacyFrame = sender + " " + content;
        broadcastToAll(guiFrame, legacyFrame);

        fireChatMessage(sender, content, timestamp);
    }

    private void broadcastSystem(String content) {
        long timestamp = System.currentTimeMillis();
        String guiFrame = buildSystemFrame(content);
        String legacyFrame = "Server " + content;
        broadcastToAll(guiFrame, legacyFrame);

        fireSystemMessage(content, timestamp);
    }

    private void broadcastUserListFrame() {
        List<String> users = getCurrentUserNames();
        String usersFrame = buildUsersFrame(users);

        List<Integer> failedClients = new ArrayList<Integer>();
        for (ClientConnection client : clientsById.values()) {
            if (!client.isGuiClient()) {
                continue;
            }
            try {
                client.send(usersFrame);
            } catch (IOException ex) {
                failedClients.add(Integer.valueOf(client.getId()));
            }
        }

        for (Integer failedClientId : failedClients) {
            removeClient(failedClientId.intValue(), "Connection lost.", true, false);
        }
    }

    private void broadcastToAll(String guiFrame, String legacyFrame) {
        List<Integer> failedClients = new ArrayList<Integer>();
        for (ClientConnection client : clientsById.values()) {
            String frame = client.isGuiClient() ? guiFrame : legacyFrame;
            try {
                client.send(frame);
            } catch (IOException ex) {
                failedClients.add(Integer.valueOf(client.getId()));
            }
        }

        for (Integer failedClientId : failedClients) {
            removeClient(failedClientId.intValue(), "Connection lost.", true, false);
        }
    }

    private void removeClient(int clientId, String reason, boolean fireEvent, boolean stopping) {
        ClientConnection removed = clientsById.remove(Integer.valueOf(clientId));
        if (removed == null) {
            return;
        }

        if (!removed.markClosed()) {
            return;
        }

        removed.closeQuietly();
        ClientSessionInfo snapshot = toSessionInfo(removed);

        if (fireEvent) {
            fireClientDisconnected(snapshot, reason);
        }

        if (!stopping) {
            String displayName = snapshot.getDisplayName();
            if (displayName != null && !displayName.trim().isEmpty()) {
                broadcastSystem(displayName + " left the chat.");
            }
            broadcastUserListFrame();
            fireUserListUpdated(getCurrentUserNames());
        }
    }

    private void closeServerSocket() {
        ServerSocket socketSnapshot = serverSocket;
        serverSocket = null;
        if (socketSnapshot == null) {
            return;
        }
        try {
            socketSnapshot.close();
        } catch (IOException ex) {
            fireError("Error while closing server socket.", ex);
        }
    }

    private ClientSessionInfo toSessionInfo(ClientConnection connection) {
        return new ClientSessionInfo(
                connection.getId(),
                displayNameOrGuest(connection),
                connection.getRemoteAddress(),
                connection.isGuiClient(),
                connection.getConnectedAtMillis());
    }

    private String displayNameOrGuest(ClientConnection connection) {
        String displayName = connection.getDisplayName();
        if (displayName == null || displayName.trim().isEmpty()) {
            return "Guest-" + connection.getId();
        }
        return displayName;
    }

    private void fireServerStarted(int serverPort, List<String> localAddresses) {
        for (ServerEventListener listener : listeners) {
            try {
                listener.onServerStarted(serverPort, localAddresses);
            } catch (RuntimeException ex) {
                // Listener failures should not stop server operation.
            }
        }
    }

    private void fireServerStopped(String reason) {
        for (ServerEventListener listener : listeners) {
            try {
                listener.onServerStopped(reason);
            } catch (RuntimeException ex) {
                // Listener failures should not stop server operation.
            }
        }
    }

    private void fireClientConnected(ClientSessionInfo info) {
        for (ServerEventListener listener : listeners) {
            try {
                listener.onClientConnected(info);
            } catch (RuntimeException ex) {
                // Listener failures should not stop server operation.
            }
        }
    }

    private void fireClientUpdated(ClientSessionInfo info) {
        for (ServerEventListener listener : listeners) {
            try {
                listener.onClientUpdated(info);
            } catch (RuntimeException ex) {
                // Listener failures should not stop server operation.
            }
        }
    }

    private void fireClientDisconnected(ClientSessionInfo info, String reason) {
        for (ServerEventListener listener : listeners) {
            try {
                listener.onClientDisconnected(info, reason);
            } catch (RuntimeException ex) {
                // Listener failures should not stop server operation.
            }
        }
    }

    private void fireChatMessage(String sender, String content, long timestampMillis) {
        for (ServerEventListener listener : listeners) {
            try {
                listener.onChatMessage(sender, content, timestampMillis);
            } catch (RuntimeException ex) {
                // Listener failures should not stop server operation.
            }
        }
    }

    private void fireSystemMessage(String content, long timestampMillis) {
        for (ServerEventListener listener : listeners) {
            try {
                listener.onSystemMessage(content, timestampMillis);
            } catch (RuntimeException ex) {
                // Listener failures should not stop server operation.
            }
        }
    }

    private void fireUserListUpdated(List<String> users) {
        for (ServerEventListener listener : listeners) {
            try {
                listener.onUserListUpdated(users);
            } catch (RuntimeException ex) {
                // Listener failures should not stop server operation.
            }
        }
    }

    private void fireError(String message, Exception exception) {
        for (ServerEventListener listener : listeners) {
            try {
                listener.onError(message, exception);
            } catch (RuntimeException ex) {
                // Listener failures should not stop server operation.
            }
        }
    }

    private static String normalizeName(String requestedName, int clientId) {
        if (requestedName == null) {
            return "Guest-" + clientId;
        }
        String cleaned = requestedName.trim();
        if (cleaned.isEmpty()) {
            return "Guest-" + clientId;
        }

        cleaned = cleaned.replaceAll("\\s+", "_");
        cleaned = cleaned.replace('|', '_');
        cleaned = cleaned.replace(',', '_');

        if (cleaned.length() > 24) {
            return cleaned.substring(0, 24);
        }
        return cleaned;
    }

    private static String normalizeSystemMessage(String reason, String fallback) {
        if (reason == null || reason.trim().isEmpty()) {
            return fallback;
        }
        return reason.trim();
    }

    private static String buildChatFrame(String sender, long timestamp, String content) {
        return SERVER_CHAT_PREFIX
                + encodeField(sender)
                + "|"
                + timestamp
                + "|"
                + encodeField(content);
    }

    private static String buildSystemFrame(String content) {
        long timestamp = System.currentTimeMillis();
        return SERVER_SYSTEM_PREFIX + timestamp + "|" + encodeField(content);
    }

    private static String buildUsersFrame(List<String> users) {
        StringBuilder builder = new StringBuilder(SERVER_USERS_PREFIX);
        for (int index = 0; index < users.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(encodeField(users.get(index)));
        }
        return builder.toString();
    }

    private static String buildShutdownFrame(String reason) {
        return SERVER_SHUTDOWN_PREFIX + System.currentTimeMillis() + "|" + encodeField(reason);
    }

    private static String encodeField(String value) {
        String safeValue = value == null ? "" : value;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(safeValue.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeField(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return "";
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(encoded);
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return encoded;
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

    private static final class ClientConnection {
        private final int id;
        private final Socket socket;
        private final DataInputStream input;
        private final DataOutputStream output;
        private final long connectedAtMillis;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final Object writeLock = new Object();

        private volatile String displayName;
        private volatile boolean guiClient;

        private ClientConnection(int id, Socket socket) throws IOException {
            this.id = id;
            this.socket = socket;
            this.input = new DataInputStream(socket.getInputStream());
            this.output = new DataOutputStream(socket.getOutputStream());
            this.connectedAtMillis = Instant.now().toEpochMilli();
        }

        private int getId() {
            return id;
        }

        private DataInputStream getInput() {
            return input;
        }

        private String getDisplayName() {
            return displayName;
        }

        private void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        private boolean isGuiClient() {
            return guiClient;
        }

        private void setGuiClient(boolean guiClient) {
            this.guiClient = guiClient;
        }

        private long getConnectedAtMillis() {
            return connectedAtMillis;
        }

        private String getRemoteAddress() {
            return socket.getRemoteSocketAddress() == null
                    ? "unknown"
                    : socket.getRemoteSocketAddress().toString();
        }

        private boolean isClosed() {
            return closed.get();
        }

        private boolean markClosed() {
            return closed.compareAndSet(false, true);
        }

        private void send(String payload) throws IOException {
            synchronized (writeLock) {
                output.writeUTF(payload);
                output.flush();
            }
        }

        private void closeQuietly() {
            ChatServerCore.closeQuietly(input);
            ChatServerCore.closeQuietly(output);
            try {
                socket.close();
            } catch (IOException ex) {
                // no-op
            }
        }
    }
}
