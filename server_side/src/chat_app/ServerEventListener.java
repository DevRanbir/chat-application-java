package chat_app;

import java.util.List;

public interface ServerEventListener {
    default void onServerStarted(int port, List<String> localAddresses) {
    }

    default void onServerStopped(String reason) {
    }

    default void onClientConnected(ClientSessionInfo client) {
    }

    default void onClientUpdated(ClientSessionInfo client) {
    }

    default void onClientDisconnected(ClientSessionInfo client, String reason) {
    }

    default void onChatMessage(String sender, String content, long timestampMillis) {
    }

    default void onSystemMessage(String content, long timestampMillis) {
    }

    default void onUserListUpdated(List<String> users) {
    }

    default void onError(String message, Exception exception) {
    }
}
