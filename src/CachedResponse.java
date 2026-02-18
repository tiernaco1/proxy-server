import java.util.ArrayList;

// holds everything needed to replay a cached HTTP response
// all fields are final - safe to read from multiple threads without any locking

public class CachedResponse {

    public final String statusLine;            // e.g. "HTTP/1.1 200 OK"
    public final ArrayList<String> headers;    // response header lines (no blank line included)
    public final byte[] body;                  // raw response body bytes
    public final long timestamp;               // System.currentTimeMillis() when this was stored
    public final long expiry;                  // System.currentTimeMillis() value when this entry expires

    public CachedResponse(String statusLine, ArrayList<String> headers,
            byte[] body, long timestamp, long expiry) {
        this.statusLine = statusLine;
        this.headers    = headers;
        this.body       = body;
        this.timestamp  = timestamp;
        this.expiry     = expiry;
    }
}
