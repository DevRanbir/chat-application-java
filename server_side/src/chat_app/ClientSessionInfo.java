package chat_app;

public class ClientSessionInfo {
    private final int id;
    private final String displayName;
    private final String remoteAddress;
    private final boolean guiClient;
    private final long connectedAtMillis;

    public ClientSessionInfo(
            int id,
            String displayName,
            String remoteAddress,
            boolean guiClient,
            long connectedAtMillis) {
        this.id = id;
        this.displayName = displayName;
        this.remoteAddress = remoteAddress;
        this.guiClient = guiClient;
        this.connectedAtMillis = connectedAtMillis;
    }

    public int getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getRemoteAddress() {
        return remoteAddress;
    }

    public boolean isGuiClient() {
        return guiClient;
    }

    public long getConnectedAtMillis() {
        return connectedAtMillis;
    }

    @Override
    public String toString() {
        String name = (displayName == null || displayName.trim().isEmpty())
                ? "Guest-" + id
                : displayName;
        return "[" + id + "] " + name + " @ " + remoteAddress + (guiClient ? " (GUI)" : " (Legacy)");
    }
}
