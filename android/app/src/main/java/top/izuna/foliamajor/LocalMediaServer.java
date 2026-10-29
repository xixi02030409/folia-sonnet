package top.izuna.foliamajor;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal loopback-only HTTP server for streaming SAF documents to the
 * WebView. Binds 127.0.0.1 exclusively, supports GET with Range requests
 * (bytes=a-b / bytes=a-) so <audio> can seek without buffering whole files.
 */
class LocalMediaServer {

    private static final int CHUNK = 64 * 1024;

    private final Context context;
    private ServerSocket serverSocket;
    private ExecutorService pool;
    private int port = -1;
    private volatile boolean running = false;

    LocalMediaServer(Context context) {
        this.context = context.getApplicationContext();
    }

    synchronized void start() throws IOException {
        if (running) return;
        serverSocket = new ServerSocket(0, 64, java.net.InetAddress.getByName("127.0.0.1"));
        port = serverSocket.getLocalPort();
        running = true;
        pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "folia-local-media");
            t.setDaemon(true);
            return t;
        });
        Thread acceptor = new Thread(this::acceptLoop, "folia-local-media-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    synchronized void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        if (pool != null) pool.shutdownNow();
        running = false;
    }

    boolean isRunning() {
        return running;
    }

    int getPort() {
        return port;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                pool.execute(() -> handleConnection(socket));
            } catch (IOException e) {
                if (running) {
                    // transient accept failure; keep serving
                } else {
                    return;
                }
            }
        }
    }

    private void handleConnection(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(15000);
            InputStream rawIn = s.getInputStream();
            OutputStream out = s.getOutputStream();

            BufferedReader reader = new BufferedReader(new InputStreamReader(rawIn, StandardCharsets.US_ASCII));
            String requestLine = reader.readLine();
            if (requestLine == null || !requestLine.startsWith("GET ")) {
                writeSimple(out, 400, "Bad Request");
                return;
            }
            String target = requestLine.split(" ")[1];

            // drain headers (we only need Range)
            String rangeHeader = null;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.regionMatches(true, 0, "range:", 0, 6)) {
                    rangeHeader = line.substring(6).trim();
                }
            }

            if (!target.startsWith("/media?")) {
                writeSimple(out, 404, "Not Found");
                return;
            }
            String query = target.substring("/media?".length());
            String treeUriStr = null;
            String path = null;
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String key = URLDecoder.decode(pair.substring(0, eq), "UTF-8");
                String value = URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                if (key.equals("uri")) treeUriStr = value;
                else if (key.equals("path")) path = value;
            }
            if (treeUriStr == null || path == null || path.isEmpty()) {
                writeSimple(out, 400, "Missing params");
                return;
            }
            // normalize & reject traversal
            path = path.replace('\\', '/');
            while (path.startsWith("/")) path = path.substring(1);
            if (path.contains("..")) {
                writeSimple(out, 400, "Illegal path");
                return;
            }

            Uri treeUri = Uri.parse(treeUriStr);
            String rootDocId;
            Uri docUri;
            try {
                rootDocId = DocumentsContract.getTreeDocumentId(treeUri);
                String docId = rootDocId + ":" + path;
                docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
            } catch (Exception e) {
                writeSimple(out, 400, "Bad tree uri");
                return;
            }

            ContentResolver resolver = context.getContentResolver();
            ParcelFileDescriptor pfd;
            long total;
            try {
                pfd = resolver.openFileDescriptor(docUri, "r");
                if (pfd == null) {
                    writeSimple(out, 404, "Not Found");
                    return;
                }
                total = pfd.getStatSize();
            } catch (Exception e) {
                writeSimple(out, 404, "Not Found");
                return;
            }

            long start = 0;
            long end = total - 1;
            boolean partial = false;
            if (rangeHeader != null && rangeHeader.regionMatches(true, 0, "bytes=", 0, 6)) {
                String spec = rangeHeader.substring(6).trim();
                int dash = spec.indexOf('-');
                try {
                    if (dash == 0) {
                        // suffix range: last N bytes
                        long suffix = Long.parseLong(spec.substring(1));
                        start = Math.max(0, total - suffix);
                        end = total - 1;
                        partial = true;
                    } else if (dash > 0) {
                        start = Long.parseLong(spec.substring(0, dash).trim());
                        if (dash + 1 < spec.length()) {
                            end = Long.parseLong(spec.substring(dash + 1).trim());
                        }
                        end = Math.min(end, total - 1);
                        partial = true;
                    }
                    if (start >= total || start > end) {
                        pfd.close();
                        writeRangeNotSatisfiable(out, total);
                        return;
                    }
                } catch (NumberFormatException nfe) {
                    // fall through to full response
                    start = 0;
                    end = total - 1;
                    partial = false;
                }
            }

            String fileName = path.substring(path.lastIndexOf('/') + 1);
            String contentType = LocalMediaPlugin.guessMimeType(fileName);
            long length = end - start + 1;

            StringBuilder head = new StringBuilder();
            head.append("HTTP/1.1 ").append(partial ? "206 Partial Content" : "200 OK").append("\r\n");
            head.append("Content-Type: ").append(contentType).append("\r\n");
            head.append("Accept-Ranges: bytes\r\n");
            head.append("Cache-Control: no-store\r\n");
            if (partial) {
                head.append(String.format(Locale.US, "Content-Range: bytes %d-%d/%d\r\n", start, end, total));
            }
            head.append("Content-Length: ").append(length).append("\r\n");
            head.append("Connection: close\r\n\r\n");
            out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();

            try (FileInputStream fis = new FileInputStream(pfd.getFileDescriptor())) {
                long toSkip = start;
                while (toSkip > 0) {
                    long skipped = fis.skip(toSkip);
                    if (skipped <= 0) break;
                    toSkip -= skipped;
                }
                byte[] buffer = new byte[CHUNK];
                long remaining = length;
                while (remaining > 0) {
                    int read = fis.read(buffer, 0, (int) Math.min(CHUNK, remaining));
                    if (read <= 0) break;
                    out.write(buffer, 0, read);
                    remaining -= read;
                }
                out.flush();
            } finally {
                try {
                    pfd.close();
                } catch (IOException ignored) {
                }
            }
        } catch (Exception e) {
            // connection dropped by client mid-stream: normal for audio seeking
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void writeSimple(OutputStream out, int code, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + " " + message + "\r\n"
                + "Content-Type: text/plain\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private void writeRangeNotSatisfiable(OutputStream out, long total) throws IOException {
        byte[] body = "range not satisfiable".getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 416 Range Not Satisfiable\r\n"
                + "Content-Range: bytes */" + total + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }
}
