Set JavaFX SDK path once per terminal session:

$env:JAVAFX_LIB="C:\path\to\javafx-sdk-23\lib"

Server core compile (console + networking core):
cd server_side
javac -d bin src/chat_app/ClientSessionInfo.java src/chat_app/ServerEventListener.java src/chat_app/ChatServerCore.java src/chat_app/Server.java

Server GUI compile:
cd server_side
javac --module-path "$env:JAVAFX_LIB" --add-modules javafx.controls,javafx.graphics -d bin src/chat_app/*.java

Server console run:
java -cp bin chat_app.Server 5055

Server admin GUI run:
java --module-path "$env:JAVAFX_LIB" --add-modules javafx.controls,javafx.graphics -cp bin chat_app.ServerAdminApp

Client core compile (legacy console + new networking service):
cd client_side
javac -d bin src/chat_app/Client.java src/chat_app/ConnectionState.java src/chat_app/ChatMessage.java src/chat_app/ChatClientListener.java src/chat_app/ChatClientService.java

Client GUI compile:
cd client_side
javac --module-path "$env:JAVAFX_LIB" --add-modules javafx.controls,javafx.graphics -d bin src/chat_app/*.java

Client console run:
java -cp bin chat_app.Client Ranbir 10.24.109.81 5055

Client GUI run:
java --module-path "$env:JAVAFX_LIB" --add-modules javafx.controls,javafx.graphics -cp bin chat_app.ClientGuiApp