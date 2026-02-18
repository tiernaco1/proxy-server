import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

// Each instance of this handles one browser connection
// reads the request, sends it to the real server, and pipes the response back

public class RequestHandler implements Runnable {

    private Socket browserSocket;
    private ConcurrentHashMap<String, byte[]> cache;
    private ConcurrentHashMap<String, Boolean> blockedHosts;
    private AtomicInteger totalRequests;
    private AtomicInteger cacheHits;
    private AtomicInteger cacheMisses;

    // shared across all handler instances - DateTimeFormatter is thread-safe
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    public RequestHandler(Socket browserSocket, ConcurrentHashMap<String, byte[]> cache,
            ConcurrentHashMap<String, Boolean> blockedHosts,
            AtomicInteger totalRequests, AtomicInteger cacheHits, AtomicInteger cacheMisses) {
        this.browserSocket  = browserSocket;
        this.cache          = cache;
        this.blockedHosts   = blockedHosts;
        this.totalRequests  = totalRequests;
        this.cacheHits      = cacheHits;
        this.cacheMisses    = cacheMisses;
    }

    @Override
    public void run() {
        Socket webServerSocket = null;

        try {
            browserSocket.setSoTimeout(10000);

            InputStream fromBrowser = browserSocket.getInputStream();
            OutputStream toBrowser = browserSocket.getOutputStream();

            // first line from the browser is something like "GET http://example.com/ HTTP/1.1"
            String firstLine = readLine(fromBrowser);
            if (firstLine == null || firstLine.isEmpty())
                return;

            // try to parse it
            HttpRequestParser req;
            try {
                req = new HttpRequestParser(firstLine);
            } catch (IllegalArgumentException e) {
                respondWithError(toBrowser, 400, "Bad Request");
                return;
            }

            // we dont handle CONNECT yet (thats for https)
            if (req.method.equals("CONNECT")) {
                respondWithError(toBrowser, 501, "CONNECT Not Implemented");
                return;
            }

            if (req.host == null) {
                respondWithError(toBrowser, 400, "No host in request");
                return;
            }

            // read the rest of the headers the browser sent
            ArrayList<String> headerLines = new ArrayList<>();
            boolean foundHost = false;
            String h;
            while ((h = readLine(fromBrowser)) != null && !h.isEmpty()) {
                // browsers send "Proxy-Connection" to proxies, swap it for regular "Connection"
                if (h.toLowerCase().startsWith("proxy-connection:")) {
                    headerLines.add("Connection: close");
                    continue;
                }
                if (h.toLowerCase().startsWith("host:")) {
                    foundHost = true;
                }
                headerLines.add(h);
            }

            // make sure theres a Host header (required by HTTP/1.1)
            if (!foundHost) {
                String hostVal = req.host;
                if (req.port != 80)
                    hostVal += ":" + req.port;
                headerLines.add("Host: " + hostVal);
            }

            // check blocklist before connecting - return 403 if blocked
            if (blockedHosts.containsKey(req.host)) {
                totalRequests.incrementAndGet();
                cacheMisses.incrementAndGet();
                logRequest(req, "403");
                respondWithError(toBrowser, 403, "Blocked");
                return;
            }

            // now connect to the actual web server
            try {
                webServerSocket = new Socket(req.host, req.port);
                webServerSocket.setSoTimeout(10000);
            } catch (IOException e) {
                System.err.println("Couldnt reach " + req.host + ":" + req.port);
                respondWithError(toBrowser, 502, "Bad Gateway");
                return;
            }

            OutputStream toServer = webServerSocket.getOutputStream();
            InputStream fromServer = webServerSocket.getInputStream();

            // send the rewritten request to the web server
            // key thing: change "GET http://example.com/page HTTP/1.1" to "GET /page HTTP/1.1"
            writeLine(toServer, req.buildRelativeRequestLine());
            for (String header : headerLines) {
                writeLine(toServer, header);
            }
            writeLine(toServer, ""); // blank line = end of headers

            // if browser sent a body (like a POST form), forward that too
            int bodyLen = findContentLength(headerLines);
            if (bodyLen > 0) {
                pipeBytes(fromBrowser, toServer, bodyLen);
            }
            toServer.flush();

            // now read the response from the web server and send it back to the browser

            // grab the status line first (like "HTTP/1.1 200 OK")
            String statusLine = readLine(fromServer);
            if (statusLine == null) {
                respondWithError(toBrowser, 502, "Empty response from server");
                return;
            }
            writeLine(toBrowser, statusLine);

            // pull the status code out of "HTTP/1.1 200 OK" -> "200"
            String[] sp = statusLine.split(" ", 3);
            String statusCode = (sp.length >= 2) ? sp[1] : "???";

            // update counters and print the log line
            totalRequests.incrementAndGet();
            cacheMisses.incrementAndGet(); // stub until caching is implemented in step 7
            logRequest(req, statusCode);

            // read response headers, figure out how long the body is
            int respBodyLen = -1;
            boolean isChunked = false;

            String respHeader;
            while ((respHeader = readLine(fromServer)) != null) {
                writeLine(toBrowser, respHeader);
                if (respHeader.isEmpty())
                    break; // blank line = headers done

                String lc = respHeader.toLowerCase();
                if (lc.startsWith("content-length:")) {
                    try {
                        respBodyLen = Integer.parseInt(respHeader.substring(15).trim());
                    } catch (NumberFormatException ex) {
                        /* ignore bad value */ }
                }
                if (lc.startsWith("transfer-encoding:") && lc.contains("chunked")) {
                    isChunked = true;
                }
            }
            toBrowser.flush();

            // relay the response body back to the browser
            if (isChunked) {
                relayChunkedBody(fromServer, toBrowser);
            } else if (respBodyLen >= 0) {
                pipeBytes(fromServer, toBrowser, respBodyLen);
            } else {
                // no content-length and not chunked - just read until connection closes
                pipeUntilDone(fromServer, toBrowser);
            }
            toBrowser.flush();

        } catch (SocketTimeoutException e) {
            System.err.println("Connection timed out: " + e.getMessage());
            try {
                respondWithError(browserSocket.getOutputStream(), 504, "Gateway Timeout");
            } catch (IOException ex) {
                /* nothing we can do */ }
        } catch (IOException e) {
            System.err.println("IO error handling request: " + e.getMessage());
        } finally {
            safeClose(webServerSocket);
            safeClose(browserSocket);
        }
    }

    // prints one structured log line per request:
    // [14:32:07] 127.0.0.1   GET   http://example.com/   200
    private void logRequest(HttpRequestParser req, String statusCode) {
        String clientIP = browserSocket.getInetAddress().getHostAddress();
        String url = "http://" + req.host + (req.port == 80 ? "" : ":" + req.port) + req.path;
        System.out.printf("[%s] %-20s %-8s %-50s %s%n",
                LocalTime.now().format(TIME_FMT), clientIP, req.method, url, statusCode);
    }

    // reads one line from an input stream, byte by byte, looking for \r\n
    // we cant use BufferedReader here because it would read ahead into the body
    // and mess up the binary data (images etc)
    private String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream();
        int previous = -1;
        int current;
        while ((current = in.read()) != -1) {
            if (current == '\n' && previous == '\r') {
                // got \r\n, chop off the \r we already added and return
                byte[] raw = lineBuffer.toByteArray();
                return new String(raw, 0, raw.length - 1, "ISO-8859-1");
            }
            lineBuffer.write(current);
            previous = current;
        }
        // hit end of stream
        if (lineBuffer.size() > 0) {
            return new String(lineBuffer.toByteArray(), "ISO-8859-1");
        }
        return null;
    }

    // writes a line with \r\n ending (HTTP requires this)
    private void writeLine(OutputStream out, String line) throws IOException {
        out.write((line + "\r\n").getBytes("ISO-8859-1"));
    }

    // copies exactly numBytes from one stream to another
    private void pipeBytes(InputStream src, OutputStream dest, int numBytes) throws IOException {
        byte[] buf = new byte[4096];
        int left = numBytes;
        while (left > 0) {
            int got = src.read(buf, 0, Math.min(buf.length, left));
            if (got == -1)
                break;
            dest.write(buf, 0, got);
            left -= got;
        }
    }

    // copies everything until the source stream ends
    private void pipeUntilDone(InputStream src, OutputStream dest) throws IOException {
        byte[] buf = new byte[4096];
        int got;
        while ((got = src.read(buf)) != -1) {
            dest.write(buf, 0, got);
        }
    }

    // handles chunked transfer encoding
    // each chunk starts with a hex size, then the data, then \r\n
    // a chunk of size 0 means we're done
    private void relayChunkedBody(InputStream in, OutputStream out) throws IOException {
        while (true) {
            String sizeLine = readLine(in);
            if (sizeLine == null)
                break;
            writeLine(out, sizeLine);

            // the size is in hex, might have ";extension" after it
            String hexPart = sizeLine.split(";")[0].trim();
            int chunkSize;
            try {
                chunkSize = Integer.parseInt(hexPart, 16);
            } catch (NumberFormatException e) {
                break;
            }

            if (chunkSize == 0) {
                // last chunk, read the final \r\n
                String end = readLine(in);
                if (end != null)
                    writeLine(out, end);
                break;
            }

            // read the actual chunk data
            pipeBytes(in, out, chunkSize);

            // each chunk ends with \r\n
            String afterChunk = readLine(in);
            if (afterChunk != null)
                writeLine(out, afterChunk);
        }
        out.flush();
    }

    // looks through headers for Content-Length and returns its value, or -1
    private int findContentLength(ArrayList<String> headers) {
        for (String header : headers) {
            if (header.toLowerCase().startsWith("content-length:")) {
                try {
                    return Integer.parseInt(header.substring(15).trim());
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    // sends a basic error page back to the browser
    private void respondWithError(OutputStream out, int statusCode, String msg) throws IOException {
        String page = "<html><body><h1>" + statusCode + " " + msg + "</h1></body></html>";
        String resp = "HTTP/1.1 " + statusCode + " " + msg + "\r\n"
                + "Content-Type: text/html\r\n"
                + "Content-Length: " + page.length() + "\r\n"
                + "Connection: close\r\n"
                + "\r\n"
                + page;
        out.write(resp.getBytes("ISO-8859-1"));
        out.flush();
    }

    private void safeClose(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException e) {
            }
        }
    }
}
