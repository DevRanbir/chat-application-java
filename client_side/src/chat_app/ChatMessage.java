package chat_app;

public class ChatMessage {
    public enum MessageType {
        CHAT,
        SYSTEM
    }

    private final MessageType type;
    private final String sender;
    private final String content;
    private final long timestampMillis;
    private final boolean localSender;

    public ChatMessage(MessageType type, String sender, String content, long timestampMillis, boolean localSender) {
        this.type = type;
        this.sender = sender;
        this.content = content;
        this.timestampMillis = timestampMillis;
        this.localSender = localSender;
    }

    public MessageType getType() {
        return type;
    }

    public String getSender() {
        return sender;
    }

    public String getContent() {
        return content;
    }

    public long getTimestampMillis() {
        return timestampMillis;
    }

    public boolean isLocalSender() {
        return localSender;
    }
}
