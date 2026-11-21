package io.r2c.net;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A very small HTTP/1.1 server built directly on sockets.
 *
 * <p>Why not {@code com.sun.net.httpserver} or a framework? Those are unavailable
 * on Android. This server implements only what the proxy needs: request line and
 * headers, {@code Content-Length} and chunked request bodies, fixed-length bodies,
 * chunked responses (required for SSE streaming), and {@code Connection: close}
 * semantics.
 *
 * <p>Each connection gets one thread. SSE responses hold their thread for the
 * duration of the stream, which is acceptable because a proxy serves a handful of
 * long lived streams rather than thousands of short requests.
 */
public final class NanoHttpServer {

    /** Handles one decoded request and writes the response. */
    public interface Handler {
        void handle(Exchange exchange) throws IOException;
    }

    /** One request/response pair, exposing just enough HTTP to write the proxy. */
    public static final class Exchange {
        public final String method;
        public final String path;
        public final Map<String, String> query;
        public final Headers requestHeaders;
        private final Socket socket;
        private final InputStream in;

        private boolean responded;
        private OutputStream bodyOut;

        Exchange(String method, String path, Map<String, String> query,
                 Headers requestHeaders, Socket socket, InputStream in) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.requestHeaders = requestHeaders;
            this.socket = socket;
            this.in = in;
        }

        /** Reads the full request body as UTF-8 text. */
        public String readBody() throws IOException {
            String te = requestHeaders.get("Transfer-Encoding");
            if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
                return readChunked(in);
            }
            String cl = requestHeaders.get("Content-Length");
            if (cl == null) return "";
            int length;
            try {
                length = Integer.parseInt(cl.trim());
            } catch (NumberFormatException e) {
                return "";
            }
            if (length <= 0) return "";
            byte[] buf = new byte[length];
            int read = 0;
            while (read < length) {
                int n = in.read(buf, read, length - read);
                if (n < 0) break;
                read += n;
            }
            return new String(buf, 0, read, StandardCharsets.UTF_8);
        }

        /** Sends a complete response with a fixed body. */
        public void sendJson(int status, String body) throws IOException {
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            Headers h = new Headers();
            h.set("Content-Type", "application/json; charset=utf-8");
            send(status, h, bytes);
        }

        /** Sends headers for a body of known length. */
        public void send(int status, Headers headers, byte[] body) throws IOException {
            if (responded) return;
            responded = true;
            OutputStream out = new java.io.BufferedOutputStream(socket.getOutputStream(), 8192);
            writeStatusAndHeaders(out, status, headers, String.valueOf(body.length));
            out.write(body);
            out.flush();
            this.bodyOut = null;
        }

        /**
         * Starts a chunked streaming response. Used for SSE where the total length
         * is unknown up front.
         */
        public void beginChunked(int status, Headers headers) throws IOException {
            if (responded) return;
            responded = true;
            headers.set("Transfer-Encoding", "chunked");
            OutputStream out = new java.io.BufferedOutputStream(socket.getOutputStream(), 4096);
            writeStatusAndHeaders(out, status, headers, null);
            out.flush();
            this.bodyOut = out;
        }

        /** Writes one chunk to a streaming response. */
        public void writeChunk(byte[] data) throws IOException {
            if (bodyOut == null) throw new IOException("response is not chunked");
            if (data.length == 0) return;
            bodyOut.write((Integer.toHexString(data.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            bodyOut.write(data);
            bodyOut.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            bodyOut.flush();
        }

        /** Writes one chunk from a UTF-8 string. */
        public void writeChunk(String text) throws IOException {
            writeChunk(text.getBytes(StandardCharsets.UTF_8));
        }

        /** Terminates a chunked response. Safe to call more than once. */
        public void endChunked() {
            if (bodyOut == null) return;
            try {
                bodyOut.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                bodyOut.flush();
            } catch (IOException ignored) {
                // Client already gone.
            } finally {
                bodyOut = null;
            }
        }

        public boolean responded() {
            return responded;
        }

        private void writeStatusAndHeaders(OutputStream out, int status, Headers headers,
                                           String contentLength) throws IOException {
            StringBuilder sb = new StringBuilder(256);
            sb.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
            if (contentLength != null && headers.get("Content-Length") == null) {
                headers.set("Content-Length", contentLength);
            }
            // The proxy has no use for keep-alive and closing simplifies framing.
            headers.set("Connection", "close");
            for (Map.Entry<String, String> e : headers.asMap().entrySet()) {
                sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
            sb.append("\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Ordered, case-insensitive header map. */
    public static final class Headers {
        private final Map<String, String> values = new LinkedHashMap<>();
        private final Map<String, String> caseIndex = new LinkedHashMap<>();

        public void set(String name, String value) {
            String key = name.toLowerCase(Locale.ROOT);
            String existing = caseIndex.get(key);
            if (existing != null) {
                values.remove(existing);
            }
            caseIndex.put(key, name);
            values.put(name, value);
        }

        public void add(String name, String value) {
            String key = name.toLowerCase(Locale.ROOT);
            String existing = caseIndex.get(key);
            if (existing != null && values.containsKey(existing)) {
                values.put(existing, values.get(existing) + ", " + value);
                return;
            }
            set(name, value);
        }

        public String get(String name) {
            return values.get(caseIndex.get(name.toLowerCase(Locale.ROOT)));
        }

        public Map<String, String> asMap() {
            return values;
        }
    }

    private final String bindAddress;
    private final int port;
    private final Handler handler;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong connections = new AtomicLong();
    private ServerSocket serverSocket;
    private ExecutorService workers;
    private Thread acceptThread;

    public NanoHttpServer(String bindAddress, int port, Handler handler) {
        this.bindAddress = bindAddress;
        this.port = port;
        this.handler = handler;
    }

    /** Binds and starts accepting connections. Throws if the port is taken. */
    public void start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        bindListeningSocket();
        running.set(true);

        workers = Executors.newCachedThreadPool(new ThreadFactory() {
            private final AtomicLong n = new AtomicLong();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "r2c-http-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });

        acceptThread = new Thread(this::acceptLoop, "r2c-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    /**
     * Binds the listening socket.
     *
     * <p>Kept deliberately dumb: a plain {@code bind} to the requested address and
     * port. Earlier attempts to pin the address family by hand caused the socket to
     * accept a few connections and then stop answering on some Android devices, so
     * the address is passed through as-is and the platform decides.
     */
    private void bindListeningSocket() throws IOException {
        String host = bindAddress == null ? "" : bindAddress.trim();
        if (host.isEmpty() || host.equals("0.0.0.0") || host.equals("*")) {
            // Wildcard: equivalent to binding every interface, and the form Android
            // handles most reliably.
            serverSocket.bind(new InetSocketAddress(port), 64);
            return;
        }
        serverSocket.bind(new InetSocketAddress(host, port), 64);
    }

    /** The port actually bound (useful when 0 was requested). */
    public int boundPort() {
        return serverSocket == null ? port : serverSocket.getLocalPort();
    }

    public boolean isRunning() {
        return running.get() && serverSocket != null && !serverSocket.isClosed();
    }

    public long connectionCount() {
        return connections.get();
    }

    /** Stops accepting and closes the listener. In-flight handlers finish on their own. */
    public void stop() {
        running.set(false);
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // already closed
            }
            serverSocket = null;
        }
        if (workers != null) {
            workers.shutdown();
            workers = null;
        }
        acceptThread = null;
    }

    private void acceptLoop() {
        while (running.get()) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                if (!running.get()) return;
                // Accepting can fail transiently (fd pressure, socket closed by the
                // runtime). Without a short back-off this becomes a busy loop that
                // pegs a core and stops answering entirely.
                Log.warn("accept failed: " + e);
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            } catch (RuntimeException e) {
                if (!running.get()) return;
                Log.warn("accept crashed: " + e);
                return;
            }
            connections.incrementAndGet();
            ExecutorService pool = workers;
            if (pool == null) {
                closeQuietly(socket);
                return;
            }
            try {
                pool.execute(() -> serve(socket));
            } catch (RuntimeException e) {
                // Pool already shut down mid-accept.
                Log.warn("cannot dispatch connection: " + e);
                closeQuietly(socket);
            }
        }
        Log.info("accept loop exited");
    }

    private void serve(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(120_000);
            s.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(s.getInputStream(), 8192);

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0].toUpperCase(Locale.ROOT);
            String target = parts[1];

            Headers headers = new Headers();
            String line;
            int headerCount = 0;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                headers.add(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
                if (++headerCount > 200) return;
            }

            String path = target;
            Map<String, String> query = new LinkedHashMap<>();
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                for (String pair : target.substring(q + 1).split("&")) {
                    if (pair.isEmpty()) continue;
                    int eq = pair.indexOf('=');
                    if (eq > 0) {
                        query.put(urlDecode(pair.substring(0, eq)), urlDecode(pair.substring(eq + 1)));
                    } else {
                        query.put(urlDecode(pair), "");
                    }
                }
            }

            Exchange exchange = new Exchange(method, urlDecode(path), query, headers, s, in);
            handler.handle(exchange);

            // A handler that bailed before responding still needs a reply, otherwise
            // the client hangs until its own timeout.
            if (!exchange.responded()) {
                exchange.sendJson(500, "{\"error\":{\"message\":\"handler produced no response\","
                        + "\"type\":\"server_error\"}}");
            }
        } catch (SocketException e) {
            // Client disconnected (very common for cancelled SSE streams).
            io.r2c.util.Log.debug("connection closed: " + e.getMessage());
        } catch (IOException e) {
            // Malformed request or broken pipe: nothing useful to do.
            io.r2c.util.Log.debug("connection error: " + e);
        } catch (Throwable e) {
            // A handler bug must never take down the accept loop silently; this is
            // the difference between "requests fail" and "requests hang forever".
            io.r2c.util.Log.error("handler failure", e);
        }
    }

    // -------------------------------------------------------------------- helpers

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }

    /** Reads a CRLF (or LF) terminated line; returns null at end of stream. */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        int c;
        boolean any = false;
        while ((c = in.read()) != -1) {
            any = true;
            if (c == '\n') break;
            if (c != '\r') buf.write(c);
            if (buf.size() > 16 * 1024) throw new IOException("header line too long");
        }
        if (!any && buf.size() == 0) return null;
        return buf.toString(StandardCharsets.UTF_8);
    }

    /** Decodes a chunked request body into text. */
    private static String readChunked(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            String sizeLine = readLine(in);
            if (sizeLine == null) break;
            String hex = sizeLine.trim();
            int semi = hex.indexOf(';');
            if (semi >= 0) hex = hex.substring(0, semi);
            int size;
            try {
                size = Integer.parseInt(hex.trim(), 16);
            } catch (NumberFormatException e) {
                break;
            }
            if (size == 0) {
                // Consume the trailing CRLF and any trailers.
                String trailer;
                while ((trailer = readLine(in)) != null && !trailer.isEmpty()) {
                    // ignore
                }
                break;
            }
            byte[] chunk = new byte[size];
            int read = 0;
            while (read < size) {
                int n = in.read(chunk, read, size - read);
                if (n < 0) break;
                read += n;
            }
            sb.append(new String(chunk, 0, read, StandardCharsets.UTF_8));
            readLine(in); // trailing CRLF after each chunk
        }
        return sb.toString();
    }

    static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return s;
        }
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 408: return "Request Timeout";
            case 413: return "Payload Too Large";
            case 429: return "Too Many Requests";
            case 499: return "Client Closed Request";
            case 500: return "Internal Server Error";
            case 502: return "Bad Gateway";
            case 503: return "Service Unavailable";
            default: return "Status";
        }
    }
}