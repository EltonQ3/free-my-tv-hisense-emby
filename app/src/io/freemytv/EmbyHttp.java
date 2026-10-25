package io.freemytv;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** HTTPS normally; explicitly configured LAN HTTP uses a bounded, pinned private-address connection. */
final class EmbyHttp {
    static String server(String value) throws Exception {
        URI u = new URI(value.trim());
        if (!("http".equals(u.getScheme()) || "https".equals(u.getScheme())) || u.getHost() == null
                || u.getRawUserInfo() != null || u.getRawQuery() != null || u.getRawFragment() != null)
            throw new IOException("Invalid server address");
        if ("http".equals(u.getScheme())) privateAddress(u.getHost());
        String s = u.toASCIIString().replaceAll("/+$", "");
        return u.getPath() == null || u.getPath().isEmpty() || u.getPath().equals("/") ? s + "/emby" : s;
    }

    private static InetAddress privateAddress(String host) throws IOException {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses.length == 0) throw new IOException("Server unavailable");
        for (InetAddress a : addresses) {
            byte[] b = a.getAddress();
            if (!(a.isLoopbackAddress() || a.isSiteLocalAddress() || b.length == 16 && (b[0] & 0xfe) == 0xfc))
                throw new IOException("HTTPS required outside the local network");
        }
        return addresses[0];
    }

    static byte[] get(String address, String token, int limit) throws Exception {
        return request(address, token, limit, null);
    }

    /** Only explicit user playback uses POST; credentials remain in a header and redirects stay disabled. */
    static byte[] post(String address, String token, byte[] body, int limit) throws Exception {
        if (body == null || body.length > 16384) throw new IOException("Invalid request body");
        return request(address, token, limit, body);
    }

    private static byte[] request(String address, String token, int limit, byte[] body) throws Exception {
        if (token == null || !token.matches("[A-Za-z0-9_-]{16,256}")) throw new IOException("Invalid API key");
        URL url = new URL(address);
        if (url.getUserInfo() != null || url.getRef() != null) throw new IOException("Invalid server address");
        if ("https".equals(url.getProtocol())) {
            HttpURLConnection c = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(6000); c.setReadTimeout(10000);
            c.setRequestProperty("X-Emby-Token", token);
            c.setRequestProperty("Accept-Encoding", "identity");
            try {
                if (body != null) {
                    c.setRequestMethod("POST"); c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json");
                    c.setFixedLengthStreamingMode(body.length);
                    try (OutputStream out = c.getOutputStream()) { out.write(body); }
                }
                int status = c.getResponseCode();
                if (body == null ? status != 200 : status != 200 && status != 204)
                    throw new IOException("Emby returned HTTP " + status);
                if (status == 204) return new byte[0];
                try (InputStream in = c.getInputStream()) { return read(in, -1, limit); }
            } finally { c.disconnect(); }
        }
        if (!"http".equals(url.getProtocol())) throw new IOException("Invalid server address");
        InetAddress ip = privateAddress(url.getHost());
        int port = url.getPort() < 0 ? 80 : url.getPort();
        String path = url.getFile().isEmpty() ? "/" : url.getFile();
        if (path.indexOf('\r') >= 0 || path.indexOf('\n') >= 0) throw new IOException("Invalid request");
        try (Socket socket = new Socket()) {
            // Connect to the checked IP itself; DNS cannot change between validation and the connection.
            socket.connect(new InetSocketAddress(ip, port), 6000);
            socket.setSoTimeout(10000);
            String host = url.getHost();
            if (host.indexOf(':') >= 0 && !host.startsWith("[")) host = "[" + host + "]";
            String request = (body == null ? "GET " : "POST ") + path + " HTTP/1.1\r\nHost: " + host + ":" + port
                    + "\r\nX-Emby-Token: " + token + "\r\nAccept-Encoding: identity\r\nConnection: close\r\n"
                    + (body == null ? "" : "Content-Type: application/json\r\nContent-Length: " + body.length + "\r\n") + "\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            if (body != null) socket.getOutputStream().write(body);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            String status = line(in);
            if (!status.matches(body == null ? "HTTP/1\\.[01] 200(?: .*|)" : "HTTP/1\\.[01] (?:200|204)(?: .*|)"))
                throw new IOException("Emby request failed");
            if (status.matches("HTTP/1\\.[01] 204(?: .*|)")) return new byte[0];
            Map<String, String> headers = new HashMap<>();
            int size = 0;
            for (String h; !(h = line(in)).isEmpty();) {
                if ((size += h.length()) > 65536) throw new IOException("Response too large");
                int colon = h.indexOf(':');
                if (colon > 0) headers.put(h.substring(0, colon).trim().toLowerCase(Locale.ROOT), h.substring(colon + 1).trim());
            }
            if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                while (true) {
                    String chunk = line(in).split(";", 2)[0].trim();
                    long count = Long.parseLong(chunk, 16);
                    if (count < 0 || count > limit - out.size()) throw new IOException("Response too large");
                    if (count == 0) return out.toByteArray();
                    out.write(read(in, count, limit - out.size()));
                    if (!line(in).isEmpty()) throw new IOException("Invalid response");
                }
            }
            long count = headers.containsKey("content-length") ? Long.parseLong(headers.get("content-length")) : -1;
            return read(in, count, limit);
        }
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int b; (b = in.read()) != -1;) {
            if (b == '\n') return new String(out.toByteArray(), StandardCharsets.ISO_8859_1).replaceAll("\\r$", "");
            if (out.size() >= 8192) throw new IOException("Invalid response");
            out.write(b);
        }
        throw new EOFException();
    }

    private static byte[] read(InputStream in, long count, int limit) throws IOException {
        if (count > limit || count < -1) throw new IOException("Response too large");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[16384];
        while (count < 0 || out.size() < count) {
            int n = in.read(b, 0, count < 0 ? b.length : (int) Math.min(b.length, count - out.size()));
            if (n < 0) { if (count >= 0) throw new EOFException(); break; }
            if (out.size() + n > limit) throw new IOException("Response too large");
            out.write(b, 0, n);
        }
        return out.toByteArray();
    }
}
