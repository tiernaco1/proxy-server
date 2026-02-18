import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

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

            ConcurrentHashMap<String, CachedResponse> cache = new ConcurrentHashMap<>();
            ConcurrentHashMap<String, Boolean> blockedHosts = new ConcurrentHashMap<>();

            // counters for tracking request stats - shared with RequestHandler and console
            AtomicInteger totalRequests = new AtomicInteger(0);
            AtomicInteger cacheHits = new AtomicInteger(0);
            AtomicInteger cacheMisses = new AtomicInteger(0);

            // start the management console on its own thread so it can read stdin
            // while the main thread stays in the accept loop
            // daemon thread so it doesn't block the JVM from exiting on Ctrl+C
            ManagementConsole console = new ManagementConsole(
                    blockedHosts, cache, totalRequests, cacheHits, cacheMisses);
            Thread consoleThread = new Thread(console, "management-console");
            consoleThread.setDaemon(true);
            consoleThread.start();

            // clean up threads when Ctrl+C is pressed
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                threadPool.shutdown();
            }));

            // sit here forever accepting new connections
            while (true) {
                Socket client = listener.accept();
                threadPool.execute(new RequestHandler(
                        client, cache, blockedHosts, totalRequests, cacheHits, cacheMisses));
            }
        } catch (IOException e) {
            System.err.println("Failed to start proxy: " + e.getMessage());
        }
    }
}
