// Breaks down an HTTP request line like "GET http://example.com/page HTTP/1.1"
// into the individual pieces we need to forward the request properly

public class HttpRequestParser {

    public String method;   // GET, POST, CONNECT, etc
    public String host;
    public int port;
    public String path;     // everything after the host:port
    public String httpVersion;

    public HttpRequestParser(String reqLine) {
        // split "GET http://example.com/page HTTP/1.1" into 3 tokens
        String[] tokens = reqLine.split(" ");
        if (tokens.length < 3) {
            throw new IllegalArgumentException("bad request line: " + reqLine);
        }

        method = tokens[0].toUpperCase();
        httpVersion = tokens[2];
        String url = tokens[1];

        // CONNECT is special - used for HTTPS tunnelling
        // format is just "host:port" with no http:// prefix
        if (method.equals("CONNECT")) {
            extractHostAndPort(url);
            path = "";
            return;
        }

        // normal request - browser sends full url to proxy like http://example.com/stuff
        if (url.startsWith("http://")) {
            // chop off the "http://" part
            String withoutScheme = url.substring("http://".length());

            // find where the host ends and the path begins
            int slash = withoutScheme.indexOf('/');
            String hostPart;
            if (slash == -1) {
                // no path given, just the host
                hostPart = withoutScheme;
                path = "/";
            } else {
                hostPart = withoutScheme.substring(0, slash);
                path = withoutScheme.substring(slash); // keeps the leading /
            }
            extractHostAndPort(hostPart);
        } else {
            // relative url - shouldn't happen with a properly configured proxy client
            // but handle it anyway
            path = url;
            host = null;
            port = 80;
        }
    }

    // pulls apart "example.com:8080" into host and port
    // if no port specified, defaults to 80
    private void extractHostAndPort(String hp) {
        int colon = hp.lastIndexOf(':');
        if (colon == -1) {
            host = hp;
            port = 80;
        } else {
            host = hp.substring(0, colon);
            try {
                port = Integer.parseInt(hp.substring(colon + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("bad port in: " + hp);
            }
        }
    }

    // rebuilds the request line but with a relative path instead of the full url
    // so "GET http://example.com/page HTTP/1.1" becomes "GET /page HTTP/1.1"
    public String buildRelativeRequestLine() {
        return method + " " + path + " " + httpVersion;
    }
}
