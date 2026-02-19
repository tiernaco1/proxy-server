import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

// runs on its own thread and lets you control the proxy while its running
// reads commands from stdin so you can block/unblock hosts, check stats etc
// without having to restart the proxy

public class ManagementConsole implements Runnable {

    // ConcurrentHashMap means multiple threads can read/write safely at the same
    // time
    private final ConcurrentHashMap<String, Boolean> blockedHosts;
    private final ConcurrentHashMap<String, CachedResponse> cache;

    // atomic integers are used here instead of regular ints because
    // RequestHandler threads increment these from different threads concurrently
    private final AtomicInteger totalRequests;
    private final AtomicInteger cacheHits;
    private final AtomicInteger cacheMisses;

    // running totals for calculating average response times per category
    private final AtomicLong totalHitTimeMs;
    private final AtomicLong totalMissTimeMs;

    private final Scanner scanner = new Scanner(System.in);

    public ManagementConsole(ConcurrentHashMap<String, Boolean> blockedHosts,
            ConcurrentHashMap<String, CachedResponse> cache,
            AtomicInteger totalRequests, AtomicInteger cacheHits, AtomicInteger cacheMisses,
            AtomicLong totalHitTimeMs, AtomicLong totalMissTimeMs) {
        this.blockedHosts    = blockedHosts;
        this.cache           = cache;
        this.totalRequests   = totalRequests;
        this.cacheHits       = cacheHits;
        this.cacheMisses     = cacheMisses;
        this.totalHitTimeMs  = totalHitTimeMs;
        this.totalMissTimeMs = totalMissTimeMs;
    }

    @Override
    public void run() {
        printHelp();
        System.out.print("> ");

        // keep reading input until stdin closes (e.g. Ctrl+D / end of pipe)
        while (scanner.hasNextLine()) {
            String input = scanner.nextLine().trim();
            if (!input.isEmpty()) {
                dispatch(input);
            }
            System.out.print("> ");
        }
    }

    // figures out which command was typed and calls the right handler
    private void dispatch(String line) {
        // split into at most 2 parts so "block example.com" gives ["block",
        // "example.com"]
        // and "list blocked" gives ["list", "blocked"]
        String[] parts = line.split("\\s+", 2);
        String verb = parts[0].toLowerCase();
        String arg = (parts.length > 1) ? parts[1].trim() : "";

        switch (verb) {
            case "block":
                handleBlock(arg);
                break;
            case "unblock":
                handleUnblock(arg);
                break;
            case "list":
                handleList(arg);
                break;
            case "stats":
                handleStats();
                break;
            case "help":
                printHelp();
                break;
            default:
                System.out.println("Unknown command: " + verb + "  (type 'help')");
        }
    }

    // adds a host to the blocklist map
    // RequestHandler checks this map before forwarding each request
    private void handleBlock(String host) {
        if (host.isEmpty()) {
            System.out.println("Usage: block <host>");
            return;
        }
        blockedHosts.put(host, Boolean.TRUE);
        System.out.println("Blocked: " + host);
    }

    // removes a host from the blocklist so requests to it go through again
    // remove(key, value) is the atomic version - only removes if the value still
    // matches
    private void handleUnblock(String host) {
        if (host.isEmpty()) {
            System.out.println("Usage: unblock <host>");
            return;
        }
        boolean removed = blockedHosts.remove(host, Boolean.TRUE);
        if (removed) {
            System.out.println("Unblocked: " + host);
        } else {
            System.out.println("Host not in block list: " + host);
        }
    }

    // handles both "list blocked" and "list cache" subcommands
    private void handleList(String arg) {
        switch (arg.toLowerCase()) {
            case "blocked":
                if (blockedHosts.isEmpty()) {
                    System.out.println("No hosts are blocked.");
                } else {
                    System.out.println("Blocked hosts:");
                    // just iterate the keys since the value (Boolean.TRUE) doesnt matter
                    blockedHosts.keySet().forEach(h -> System.out.println("  " + h));
                }
                break;
            case "cache":
                if (cache.isEmpty()) {
                    System.out.println("Cache is empty.");
                } else {
                    System.out.println("Cached URLs:");
                    // show the url, size, and how many seconds until the entry expires
                    long now = System.currentTimeMillis();
                    cache.forEach((url, cr) -> {
                        long secsLeft = (cr.expiry - now) / 1000;
                        String expiryTag = secsLeft > 0 ? "expires in " + secsLeft + "s" : "EXPIRED";
                        System.out.println("  " + url + "  (" + cr.body.length + " bytes, " + expiryTag + ")");
                    });
                }
                break;
            default:
                System.out.println("Usage: list blocked | list cache");
        }
    }

    // prints request stats - useful for showing the cache is actually saving time
    private void handleStats() {
        int total  = totalRequests.get();
        int hits   = cacheHits.get();
        int misses = cacheMisses.get();

        double hitRate   = (total  > 0) ? (100.0 * hits / total) : 0.0;
        long avgHitMs    = (hits   > 0) ? totalHitTimeMs.get()  / hits   : 0;
        long avgMissMs   = (misses > 0) ? totalMissTimeMs.get() / misses : 0;

        System.out.println("--- Proxy Stats ---");
        System.out.printf("  Total requests : %d%n",    total);
        System.out.printf("  Cache hits     : %d%n",    hits);
        System.out.printf("  Cache misses   : %d%n",    misses);
        System.out.printf("  Hit rate       : %.1f%%%n", hitRate);
        System.out.printf("  Avg hit time   : %dms%n",  avgHitMs);
        System.out.printf("  Avg miss time  : %dms%n",  avgMissMs);
    }

    private void printHelp() {
        System.out.println("Commands:");
        System.out.println("  block <host>     Block a host");
        System.out.println("  unblock <host>   Unblock a host");
        System.out.println("  list blocked     Show all blocked hosts");
        System.out.println("  list cache       Show all cached URLs and sizes");
        System.out.println("  stats            Show request and cache statistics");
        System.out.println("  help             Show this message");
    }
}
