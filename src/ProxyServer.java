import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

// main class - starts the proxy and listens for browser connections

public class ProxyServer {

    public static final int PORT = 4000;

    public static void main(String[] args) {
        int port = PORT;

        // allow overriding the port from command line
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.out.println("Bad port number, using default " + PORT);
            }
        }

        try {
            ServerSocket listener = new ServerSocket();
            listener.setReuseAddress(true);
            listener.bind(new java.net.InetSocketAddress(port));
            System.out.println("Proxy running on port " + port);

            // sit here forever accepting new connections
            while (true) {
                Socket client = listener.accept();
                System.out.println("New connection from " + client.getInetAddress());

                // handle each connection in its own thread so we can serve multiple at once
                Thread t = new Thread(new RequestHandler(client));
                t.start();
            }
        } catch (IOException e) {
            System.err.println("Failed to start proxy: " + e.getMessage());
        }
    }
}
