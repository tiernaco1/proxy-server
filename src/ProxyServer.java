import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

            // create one pool of 50 reusable threads - shared across all connections
            ExecutorService threadPool = Executors.newFixedThreadPool(50);

            ConcurrentHashMap<String, byte[]> cache = new ConcurrentHashMap<>();
            ConcurrentHashMap<String, Boolean> blockedHosts = new ConcurrentHashMap<>();

            // clean up threads when Ctrl+C is pressed
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                threadPool.shutdown();
            }));

            // sit here forever accepting new connections
            while (true) {
                Socket client = listener.accept();
                System.out.println("New connection from " + client.getInetAddress());
                threadPool.execute(new RequestHandler(client, cache, blockedHosts));
            }
        } catch (IOException e) {
            System.err.println("Failed to start proxy: " + e.getMessage());
        }
    }
}
