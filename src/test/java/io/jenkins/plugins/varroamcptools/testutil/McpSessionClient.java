package io.jenkins.plugins.varroamcptools.testutil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import net.sf.json.JSONObject;

/**
 * Minimal {@link HttpURLConnection}-based MCP session client for
 * {@code JenkinsRule}-driven tests.
 *
 * <p>Drives the full MCP session-based transport handshake that the
 * {@code mcp-server-plugin} endpoint requires:
 * <ol>
 *   <li>{@link #initialize()} — POST an MCP {@code initialize} request,
 *       capture the {@code Mcp-Session-Id} response header.</li>
 *   <li>{@link #callTool(String, Map)} — POST a {@code tools/call} with
 *       the captured session header.</li>
 *   <li>{@link #close()} — DELETE the session.</li>
 * </ol>
 *
 * <p>This class is {@code final}: downstream tool changes (C3–C5) consume
 * it by composition, not subclassing.
 */
public final class McpSessionClient {

    private final URL mcpEndpoint;
    private String sessionId;
    private boolean initialized;
    private boolean closed;
    private int requestId;

    /**
     * @param jenkinsBaseUrl the {@code JenkinsRule} base URL (e.g.
     *                       {@code j.getURL()})
     */
    public McpSessionClient(URL jenkinsBaseUrl) {
        this.mcpEndpoint = buildEndpoint(jenkinsBaseUrl);
        this.requestId = 1;
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Issues the MCP {@code initialize} POST to {@code /mcp-server/mcp}
     * and captures the {@code Mcp-Session-Id} response header for use by
     * subsequent calls.
     *
     * @throws IOException if the HTTP request fails
     */
    public void initialize() throws IOException {
        JSONObject params = new JSONObject();
        params.put("protocolVersion", "2025-06-18");
        params.put("capabilities", new JSONObject());
        JSONObject clientInfo = new JSONObject();
        clientInfo.put("name", "mcp-session-test-client");
        clientInfo.put("version", "1.0");
        params.put("clientInfo", clientInfo);

        JSONObject request = buildRequest("initialize", params);

        HttpURLConnection conn = postJson(request);
        readAndDiscard(conn);
        this.sessionId = conn.getHeaderField("Mcp-Session-Id");
        conn.disconnect();
        this.initialized = true;
    }

    /**
     * Issues a {@code tools/call} POST for the named tool with the given
     * arguments, including the captured {@code Mcp-Session-Id} header.
     *
     * @param name the MCP tool name (e.g. {@code "whoAmI"})
     * @param args the tool arguments (may be empty, never {@code null})
     * @return the parsed JSON-RPC response
     * @throws IllegalStateException if called before {@link #initialize()}
     *         or after {@link #close()}
     * @throws IOException          if the HTTP request fails
     */
    public JSONObject callTool(String name, Map<String, Object> args) throws IOException {
        if (!initialized) {
            throw new IllegalStateException("initialize() must be called before callTool()");
        }
        if (closed) {
            throw new IllegalStateException(
                    "close() has already been called; cannot call callTool()" + " on a closed session");
        }

        JSONObject params = new JSONObject();
        params.put("name", name);
        params.put("arguments", args == null ? new JSONObject() : args);
        JSONObject request = buildRequest("tools/call", params);

        HttpURLConnection conn = postJson(request, sessionId);
        JSONObject response = readResponse(conn);
        conn.disconnect();
        return response;
    }

    /**
     * Issues an HTTP {@code DELETE} to {@code /mcp-server/mcp} with the
     * captured session header, ending the session. Once closed, subsequent
     * {@link #callTool(String, Map)} calls will throw
     * {@link IllegalStateException}.
     *
     * <p>Errors during the DELETE are silently consumed; callers can rely
     * on the closed-state guard alone.
     */
    public void close() {
        if (!initialized || closed) {
            closed = true;
            return;
        }
        try {
            HttpURLConnection conn = (HttpURLConnection) mcpEndpoint.openConnection();
            conn.setRequestMethod("DELETE");
            conn.setRequestProperty("Mcp-Session-Id", sessionId);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.getResponseCode();
            conn.disconnect();
        } catch (IOException ignored) {
            // best-effort — the session is terminated from the client's
            // perspective regardless
        }
        closed = true;
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private static URL buildEndpoint(URL baseUrl) {
        try {
            return new URL(baseUrl, "mcp-server/mcp");
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot construct MCP endpoint URL from base: " + baseUrl, e);
        }
    }

    private JSONObject buildRequest(String method, JSONObject params) {
        JSONObject req = new JSONObject();
        req.put("jsonrpc", "2.0");
        req.put("id", String.valueOf(requestId++));
        req.put("method", method);
        req.put("params", params);
        return req;
    }

    /**
     * Opens a POST connection, writes the JSON body, and returns the
     * (still-open) connection for header inspection and body reading.
     * No session header is sent (used by {@link #initialize()}).
     */
    private HttpURLConnection postJson(JSONObject body) throws IOException {
        return postJson(body, null);
    }

    /**
     * Same as {@link #postJson(JSONObject)} but includes the
     * {@code Mcp-Session-Id} header when {@code sessionId} is non-null.
     */
    private HttpURLConnection postJson(JSONObject body, String sessionId) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) mcpEndpoint.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoInput(true);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json, text/event-stream");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        if (sessionId != null) {
            conn.setRequestProperty("Mcp-Session-Id", sessionId);
        }
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
            os.flush();
        }
        return conn;
    }

    /**
     * Reads and discards the response body (used by {@link #initialize()}
     * which only needs the header).
     */
    private static void readAndDiscard(HttpURLConnection conn) throws IOException {
        int status = conn.getResponseCode();
        if (status < 200 || status >= 300) {
            String errorBody = readErrorBody(conn);
            throw new IOException("MCP endpoint returned HTTP " + status + " for "
                    + conn.getRequestMethod() + " " + conn.getURL()
                    + ": " + errorBody);
        }
        drain(conn);
    }

    /**
     * Reads the response body into a {@link JSONObject}.
     *
     * <p>When the response content type is {@code text/event-stream}
     * (SSE), the Streamable HTTP framing is stripped and only the
     * JSON-RPC payload is returned.  Otherwise the entire body is
     * parsed as JSON directly.
     */
    private static JSONObject readResponse(HttpURLConnection conn) throws IOException {
        int status = conn.getResponseCode();
        if (status < 200 || status >= 300) {
            String errorBody = readErrorBody(conn);
            throw new IOException("MCP endpoint returned HTTP " + status + " for "
                    + conn.getRequestMethod() + " " + conn.getURL()
                    + ": " + errorBody);
        }
        String contentType = conn.getContentType();
        if (contentType != null && contentType.contains("text/event-stream")) {
            return parseSsePayload(conn.getInputStream());
        }
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return JSONObject.fromObject(sb.toString());
        }
    }

    // ------------------------------------------------------------------
    // Response helpers
    // ------------------------------------------------------------------

    /** Reads the error stream fully; returns {@code "<no body>"} if
     *  the stream is null. */
    private static String readErrorBody(HttpURLConnection conn) {
        try {
            InputStream err = conn.getErrorStream();
            if (err == null) {
                return "<no body>";
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(err, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line);
                }
                return sb.toString();
            }
        } catch (IOException ignored) {
            return "<unreadable>";
        }
    }

    /** Drains (and discards) the response body. */
    private static void drain(HttpURLConnection conn) throws IOException {
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            while (reader.readLine() != null) {
                // discard
            }
        }
    }

    /**
     * Parses a {@code text/event-stream} (SSE) body, collecting the
     * JSON-RPC payload from {@code data:} lines.
     */
    private static JSONObject parseSsePayload(InputStream in) throws IOException {
        StringBuilder data = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data:")) {
                    String payload = line.substring(5);
                    if (payload.startsWith(" ")) {
                        payload = payload.substring(1);
                    }
                    data.append(payload);
                }
                // ignore event:, id:, blank lines
            }
        }
        return JSONObject.fromObject(data.toString());
    }
}
