package chat_app;

import java.util.List;

public class Server {
    public static void main(String[] args) throws Exception {
        int port = args.length >= 1 ? Integer.parseInt(args[0]) : 5055;

        ChatServerCore server = new ChatServerCore(port);
        server.addListener(new ConsoleServerLogger());
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                server.shutdown("Server stopped.");
            }
        }));

        System.out.println("Server is running. Press Ctrl+C to stop.");
    }

    private static class ConsoleServerLogger implements ServerEventListener {
        @Override
        public void onServerStarted(int port, List<String> localAddresses) {
            System.out.println("Server started on port " + port + ".");
            System.out.println("Clients can connect using:");
            for (String localAddress : localAddresses) {
                System.out.println("- " + localAddress + ":" + port);
            }
        }

        @Override
        public void onClientConnected(ClientSessionInfo client) {
            System.out.println("Client connected: " + client);
        }

        @Override
        public void onClientUpdated(ClientSessionInfo client) {
            System.out.println("Client updated: " + client);
        }

        @Override
        public void onClientDisconnected(ClientSessionInfo client, String reason) {
            System.out.println("Client disconnected: " + client + " | reason=" + reason);
        }

        @Override
        public void onChatMessage(String sender, String content, long timestampMillis) {
            System.out.println("[CHAT] " + sender + ": " + content);
        }

        @Override
        public void onSystemMessage(String content, long timestampMillis) {
            System.out.println("[SYSTEM] " + content);
        }

        @Override
        public void onServerStopped(String reason) {
            System.out.println("Server stopped. reason=" + reason);
        }

        @Override
        public void onError(String message, Exception exception) {
            System.out.println("[ERROR] " + message + " | " + exception.getMessage());
        }
    }
}

