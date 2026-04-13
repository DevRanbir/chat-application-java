# Chat Application (Java Sockets + Full Desktop GUI)

This project now includes both:

1. Legacy console chat client and server.
2. Full desktop GUI client and GUI admin server dashboard.

The networking layer was refactored for better thread safety and smoother behavior while preserving compatibility with legacy clients.

## Modules

1. `server_side`
: Contains a refactored server core, console launcher, and JavaFX admin dashboard.
2. `client_side`
: Contains legacy console client plus JavaFX GUI client with modern UX.

## Key Improvements Implemented

1. Sender-only quit behavior:
: `quit` now disconnects only the sender instead of forcing all clients out.
2. Join/leave system notifications:
: Server emits system notices when users join, rename, or leave.
3. Connected users support:
: Server tracks active users and broadcasts user lists to GUI clients.
4. Server admin controls:
: Admin GUI can disconnect selected clients and perform controlled global shutdown.
5. Client auto-reconnect:
: GUI client retries automatically on dropped connection.
6. Chat export:
: GUI client can export timeline to `.txt`.
7. Theme switcher:
: GUI client and server dashboard provide light/dark theme toggle.

## Runtime Entry Points

1. Console server:
: `chat_app.Server`
2. Console client:
: `chat_app.Client`
3. GUI server admin:
: `chat_app.ServerAdminApp`
4. GUI client:
: `chat_app.ClientGuiApp`

## Protocol Notes

The server supports both legacy and GUI framing.

1. Legacy client format:
: `sender content...`
2. GUI client frames:
: `CLIENT_HELLO|<encoded-name>`, `CLIENT_CHAT|<encoded-content>`, `CLIENT_QUIT`
3. GUI server frames:
: `SERVER_CHAT|...`, `SERVER_SYSTEM|...`, `SERVER_USERS|...`, `SERVER_SHUTDOWN|...`
4. Legacy shutdown compatibility:
: Server still emits `quited` for legacy clients on global shutdown.

## Requirements

1. Java JDK 21+ (tested with JDK 25).
2. JavaFX SDK installed locally (required for GUI launch and GUI compile).

Check Java:

```bash
javac -version
java -version
```

## JavaFX Setup (Windows PowerShell)

Set this environment variable to your JavaFX SDK `lib` folder:

```powershell
$env:JAVAFX_LIB="C:\path\to\javafx-sdk-23\lib"
```

## Build

### Server core (console-compatible)

```powershell
cd server_side
javac -d bin src/chat_app/ClientSessionInfo.java src/chat_app/ServerEventListener.java src/chat_app/ChatServerCore.java src/chat_app/Server.java
```

### Client core (console-compatible)

```powershell
cd client_side
javac -d bin src/chat_app/Client.java src/chat_app/ConnectionState.java src/chat_app/ChatMessage.java src/chat_app/ChatClientListener.java src/chat_app/ChatClientService.java
```

### Server GUI build

```powershell
cd server_side
javac --module-path "$env:JAVAFX_LIB" --add-modules javafx.controls,javafx.graphics -d bin src/chat_app/*.java
```

### Client GUI build

```powershell
cd client_side
javac --module-path "$env:JAVAFX_LIB" --add-modules javafx.controls,javafx.graphics -d bin src/chat_app/*.java
```

## Run

### Console server

```powershell
cd server_side
java -cp bin chat_app.Server 5055
```

### Console client

```powershell
cd client_side
java -cp bin chat_app.Client Alice 127.0.0.1 5055
```

### GUI server admin dashboard

```powershell
cd server_side
java --module-path "$env:JAVAFX_LIB" --add-modules javafx.controls,javafx.graphics -cp bin chat_app.ServerAdminApp
```

### GUI client

```powershell
cd client_side
java --module-path "$env:JAVAFX_LIB" --add-modules javafx.controls,javafx.graphics -cp bin chat_app.ClientGuiApp
```

## Seamless Mixed Usage

You can run GUI and console clients together against the same server core.

1. GUI users get user list, themed bubbles, status updates, and export.
2. Console users continue using plain `name message` behavior.
3. Global shutdown from admin dashboard informs GUI clients and remains backward-compatible for legacy clients.

## Known Remaining Limitations

1. No authentication.
2. No encryption (plaintext TCP).
3. No file transfer.
4. No database persistence; chat history is in-memory during runtime.

## Troubleshooting

1. `package javafx... does not exist`
: JavaFX SDK is not configured. Set `JAVAFX_LIB` correctly and rebuild.
2. `Address already in use: bind`
: Another process is using the server port. Choose a different port.
3. GUI cannot connect
: Check host, port, firewall, and server status label in admin dashboard.

## License

No license file is currently included.
