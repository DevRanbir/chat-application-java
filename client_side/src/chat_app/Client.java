package chat_app;

import java.io.*;
import java.net.*;
import java.util.Scanner; 

	public class Client implements Runnable {
		private Socket socket;
		private DataOutputStream output;
		private DataInputStream input;
		String name;
		Client(String host, int port,String name){
			String ur_message = null;	
			this.name=name;
			try {
				socket = new Socket( host, port );
				System.out.println( "connected to "+socket );
				input = new DataInputStream( socket.getInputStream() );
				output = new DataOutputStream( socket.getOutputStream() );
                new Thread(this).start(); 
                
                @SuppressWarnings("resource")
				Scanner scan=new Scanner(System.in);
                while(true) {  
                	ur_message=scan.nextLine();
                	if(ur_message.equals("quit")) {
	                		processMessage(name+" "+ur_message);
                		System.out.println("you quited the communicaton");
	                		try {
	                			if (socket != null && !socket.isOutputShutdown()) {
	                				socket.shutdownOutput();
	                			}
	                		} catch (IOException ie) {
	                			System.out.println(ie);
	                		}
                		break;
                		}
                	else
	                		processMessage(name+" "+ur_message);
                }
                } catch( IOException ie ) {
                		System.out.println( ie );
                }
			
			}
	private void processMessage( String message ) {
		try {
			output.writeUTF( message );
			output.flush();
			} catch( IOException ie ) {
				System.out.println( ie );
			}
		}
		public void run() { 
			String sender,content = null;
			try {
		while (true) {
			String message = input.readUTF();
		    if(message.equals("quited")) {
		    	input.close();
		    	output.close();
		    	socket.close();
		    	System.exit(0);
		    	}
		    else {
		    	String[] parts = message.split(" ", 2);
		    	if (parts.length < 2) {
		    		continue;
		    	}
		    	sender = parts[0];
		    	content = parts[1];
		    	System.out.println(sender+":"+content);
		    }
			} 
		} catch( IOException ie ) {
				closeConnection();
			}
		}
		private void closeConnection() {
			try {
				if (input != null) {
					input.close();
				}
			} catch (IOException ie) {
				System.out.println(ie);
			}
			try {
				if (output != null) {
					output.close();
				}
			} catch (IOException ie) {
				System.out.println(ie);
			}
			try {
				if (socket != null && !socket.isClosed()) {
					socket.close();
				}
			} catch (IOException ie) {
				System.out.println(ie);
			}
		}
		public static void main(String args[]) {
			if (args.length < 1 || args.length > 3) {
				System.out.println("Usage: java -cp bin chat_app.Client <name> [server_host] [server_port]");
				System.out.println("Example (LAN): java -cp bin chat_app.Client Bob 192.168.1.20 5055");
				System.out.println("Example (internet): java -cp bin chat_app.Client Bob 203.0.113.10 5055");
				return;
			}

			String name=args[0];
			String host = args.length >= 2 ? args[1] : "localhost";
			int port = args.length == 3 ? Integer.parseInt(args[2]) : 5055;

			Client cl1=new Client(host,port,name);
		} 
	}
