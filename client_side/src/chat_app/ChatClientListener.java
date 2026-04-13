package chat_app;

import java.util.List;

public interface ChatClientListener {
    void onConnectionStateChanged(ConnectionState state, String detail);

    void onChatMessage(ChatMessage message);

    void onUsersUpdated(List<String> users);

    void onDisconnected(String reason);

    void onError(String message, Exception exception);
}
