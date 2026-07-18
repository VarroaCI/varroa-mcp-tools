package io.jenkins.plugins.varroamcptools.tools;

import static org.assertj.core.api.Assertions.assertThat;

import hudson.model.Computer;
import hudson.model.FreeStyleProject;
import hudson.slaves.DumbSlave;
import hudson.slaves.OfflineCause;
import hudson.tasks.Shell;
import io.jenkins.plugins.varroamcptools.testutil.McpSessionClient;
import java.util.Map;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONNull;
import net.sf.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class NodeToolsTest {

    // ----------------------------------------------------------------
    // 5.1 — getNodes happy path
    // ----------------------------------------------------------------

    @Test
    void getNodesHappyPath(JenkinsRule j) throws Exception {
        McpSessionClient client = new McpSessionClient(j.getURL());
        client.initialize();

        JSONObject response = client.callTool("getNodes", Map.of());
        client.close();

        assertThat(response.has("error")).isFalse();

        JSONObject data = getStructuredContent(response);
        JSONArray nodes = data.getJSONArray("nodes");
        // At minimum the built-in node (name: "")
        assertThat(nodes.size()).isGreaterThanOrEqualTo(1);

        JSONObject builtIn = findNodeByName(nodes, "");
        assertThat((Object) builtIn).isNotNull();
        assertThat(builtIn.getString("name")).isEqualTo("");
        assertThat(builtIn.getString("displayName")).isNotNull();
        assertThat(builtIn.has("description")).isTrue();
        assertThat(builtIn.has("labels")).isTrue();
        assertThat(builtIn.getBoolean("online")).isTrue();
        // offlineCause must be null for online nodes
        assertThat(builtIn.has("offlineCause")).isTrue();
        assertThat(builtIn.get("offlineCause")).isEqualTo(JSONNull.getInstance());
        assertThat(builtIn.getInt("numExecutors")).isGreaterThan(0);
        // diskSpaceMb/tempSpaceMb may be null or a non-negative number
        assertThat(builtIn.has("diskSpaceMb")).isTrue();
        assertThat(builtIn.has("tempSpaceMb")).isTrue();
        if (!JSONNull.getInstance().equals(builtIn.get("diskSpaceMb"))) {
            assertThat(builtIn.getLong("diskSpaceMb")).isGreaterThanOrEqualTo(0);
        }
        if (!JSONNull.getInstance().equals(builtIn.get("tempSpaceMb"))) {
            assertThat(builtIn.getLong("tempSpaceMb")).isGreaterThanOrEqualTo(0);
        }

        // Verify result shape
        assertThat(data.keySet()).containsExactlyInAnyOrder("nodes", "limit", "offset", "truncated");
        assertThat(data.getInt("limit")).isEqualTo(20);
        assertThat(data.getInt("offset")).isEqualTo(0);
        assertThat(data.getBoolean("truncated")).isFalse();
    }

    // ----------------------------------------------------------------
    // 5.2 — getNodes permission denied
    // ----------------------------------------------------------------

    @Test
    void getNodesPermissionDenied(JenkinsRule j) throws Exception {
        // Set up security: grant nothing to everyone
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(
                new MockAuthorizationStrategy().grant(Jenkins.READ).everywhere().to("nonexistent"));

        McpSessionClient client = new McpSessionClient(j.getURL());
        client.initialize();

        JSONObject response = client.callTool("getNodes", Map.of());
        client.close();

        String error = getToolError(response);
        assertThat(error).isNotNull();
        assertThat(error).contains("Read");
    }

    // ----------------------------------------------------------------
    // 5.3 — getNodes empty/missing-target (no configured agents)
    // ----------------------------------------------------------------

    @Test
    void getNodesOnlyBuiltInNode(JenkinsRule j) throws Exception {
        McpSessionClient client = new McpSessionClient(j.getURL());
        client.initialize();

        JSONObject response = client.callTool("getNodes", Map.of());
        client.close();

        assertThat(response.has("error")).isFalse();

        JSONObject data = getStructuredContent(response);
        JSONArray nodes = data.getJSONArray("nodes");
        // Exactly one node (the built-in) because no agents were created
        assertThat(nodes.size()).isEqualTo(1);
        JSONObject builtIn = nodes.getJSONObject(0);
        assertThat(builtIn.getString("name")).isEqualTo("");
        assertThat(data.getBoolean("truncated")).isFalse();
    }

    // ----------------------------------------------------------------
    // 5.4 — getNodes bounding
    // ----------------------------------------------------------------

    @Test
    void getNodesBounding(JenkinsRule j) throws Exception {
        // Create 3 agents so total is 4 nodes (built-in + 3 agents)
        for (int i = 0; i < 3; i++) {
            j.createSlave("agent-" + i, "", null);
        }

        McpSessionClient client = new McpSessionClient(j.getURL());
        client.initialize();

        // limit=2 → should return 2 nodes, truncated=true
        JSONObject r1 = client.callTool("getNodes", Map.of("limit", 2));
        assertThat(r1.has("error")).isFalse();
        JSONObject d1 = getStructuredContent(r1);
        assertThat(d1.getJSONArray("nodes").size()).isEqualTo(2);
        assertThat(d1.getBoolean("truncated")).isTrue();
        assertThat(d1.getInt("limit")).isEqualTo(2);

        // offset=2, limit=2 → should return the remaining 2 nodes but truncated=true
        // (4 total nodes > 2 returned, so count-based truncated is true)
        JSONObject r2 = client.callTool("getNodes", Map.of("offset", 2, "limit", 2));
        assertThat(r2.has("error")).isFalse();
        JSONObject d2 = getStructuredContent(r2);
        assertThat(d2.getJSONArray("nodes").size()).isEqualTo(2);
        assertThat(d2.getBoolean("truncated")).isTrue();

        client.close();
    }

    // ----------------------------------------------------------------
    // 5.5 — getNodes offline node fields
    // ----------------------------------------------------------------

    @Test
    void getNodesOfflineNode(JenkinsRule j) throws Exception {
        DumbSlave slave = j.createSlave("offline-agent", "", null);
        Computer c = slave.getComputer();
        assertThat(c).isNotNull();
        c.setTemporarilyOffline(true, new OfflineCause.ByCLI("offline for testing"));

        McpSessionClient client = new McpSessionClient(j.getURL());
        client.initialize();

        JSONObject response = client.callTool("getNodes", Map.of());
        client.close();

        assertThat(response.has("error")).isFalse();

        JSONObject data = getStructuredContent(response);
        JSONArray nodes = data.getJSONArray("nodes");
        JSONObject offlineNode = findNodeByName(nodes, "offline-agent");
        assertThat((Object) offlineNode).isNotNull();
        assertThat(offlineNode.getBoolean("online")).isFalse();
        assertThat(JSONNull.getInstance().equals(offlineNode.get("offlineCause")))
                .isFalse();
        assertThat(offlineNode.getString("offlineCause")).isNotEmpty();
        // Offline nodes have null diskSpaceMb/tempSpaceMb
        assertThat(offlineNode.get("diskSpaceMb")).isEqualTo(JSONNull.getInstance());
        assertThat(offlineNode.get("tempSpaceMb")).isEqualTo(JSONNull.getInstance());
    }

    // ----------------------------------------------------------------
    // 6.1 — getExecutorStatus happy path, idle
    // ----------------------------------------------------------------

    @Test
    void getExecutorStatusIdle(JenkinsRule j) throws Exception {
        McpSessionClient client = new McpSessionClient(j.getURL());
        client.initialize();

        JSONObject response = client.callTool("getExecutorStatus", Map.of());
        client.close();

        assertThat(response.has("error")).isFalse();

        JSONObject data = getStructuredContent(response);
        JSONArray nodes = data.getJSONArray("nodes");
        assertThat(nodes.size()).isGreaterThanOrEqualTo(1);

        JSONObject builtIn = findNodeByName(nodes, "");
        assertThat((Object) builtIn).isNotNull();
        JSONArray executors = builtIn.getJSONArray("executors");
        // At least one executor on the built-in node
        assertThat(executors.size()).isGreaterThan(0);

        for (Object obj : executors) {
            JSONObject exec = (JSONObject) obj;
            assertThat(exec.getInt("index")).isGreaterThanOrEqualTo(0);
            assertThat(exec.getBoolean("busy")).isFalse();
            assertThat(exec.has("currentBuild")).isTrue();
            assertThat(exec.get("currentBuild")).isEqualTo(JSONNull.getInstance());
        }

        assertThat(data.keySet()).containsExactlyInAnyOrder("nodes", "limit", "offset", "truncated");
        assertThat(data.getInt("limit")).isEqualTo(20);
        assertThat(data.getInt("offset")).isEqualTo(0);
        assertThat(data.getBoolean("truncated")).isFalse();
    }

    // ----------------------------------------------------------------
    // 6.2 — getExecutorStatus busy executor
    // ----------------------------------------------------------------

    @Test
    void getExecutorStatusBusy(JenkinsRule j) throws Exception {
        // Create a FreeStyleProject and schedule a build to occupy an executor
        FreeStyleProject p = j.createFreeStyleProject("busy-project");
        p.getBuildersList().add(new Shell("echo running && sleep 5"));

        // Schedule the build
        j.jenkins.getQueue().schedule(p);

        // Poll until an executor becomes busy (or timeout after 10s)
        long deadline = System.currentTimeMillis() + 10_000;
        boolean busyFound = false;
        while (System.currentTimeMillis() < deadline) {
            Computer builtIn = j.jenkins.getComputer("");
            if (builtIn != null && builtIn.getExecutors().stream().anyMatch(e -> e.isBusy())) {
                busyFound = true;
                break;
            }
            Thread.sleep(100);
        }
        assertThat(busyFound).as("An executor should become busy within 10s").isTrue();

        McpSessionClient client = new McpSessionClient(j.getURL());
        client.initialize();

        JSONObject response = client.callTool("getExecutorStatus", Map.of());
        client.close();

        assertThat(response.has("error")).isFalse();

        JSONObject data = getStructuredContent(response);
        JSONArray nodes = data.getJSONArray("nodes");
        JSONObject builtIn = findNodeByName(nodes, "");
        assertThat((Object) builtIn).isNotNull();

        JSONArray executors = builtIn.getJSONArray("executors");
        JSONObject busyExec = null;
        for (Object obj : executors) {
            JSONObject exec = (JSONObject) obj;
            if (exec.getBoolean("busy")) {
                busyExec = exec;
                break;
            }
        }
        assertThat((Object) busyExec).isNotNull();

        // currentBuild should be present
        assertThat(JSONNull.getInstance().equals(busyExec.get("currentBuild"))).isFalse();
        JSONObject cb = busyExec.getJSONObject("currentBuild");
        assertThat(cb.getString("jobFullName")).isEqualTo("busy-project");
        assertThat(cb.getInt("buildNumber")).isGreaterThan(0);
        // progressPercent can be -1 (unknown) or a valid percentage
        assertThat(cb.has("progressPercent")).isTrue();
        // durationMs should be >= 0
        assertThat(cb.getLong("durationMs")).isGreaterThanOrEqualTo(0);

        // Let the build finish
        j.waitUntilNoActivity();
    }

    // ----------------------------------------------------------------
    // 6.3 — getExecutorStatus permission denied
    // ----------------------------------------------------------------

    @Test
    void getExecutorStatusPermissionDenied(JenkinsRule j) throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(
                new MockAuthorizationStrategy().grant(Jenkins.READ).everywhere().to("nonexistent"));

        McpSessionClient client = new McpSessionClient(j.getURL());
        client.initialize();

        JSONObject response = client.callTool("getExecutorStatus", Map.of());
        client.close();

        String error = getToolError(response);
        assertThat(error).isNotNull();
        assertThat(error).contains("Read");
    }

    // ----------------------------------------------------------------
    // 6.4 — getExecutorStatus offline node
    // ----------------------------------------------------------------

    @Test
    void getExecutorStatusOfflineNode(JenkinsRule j) throws Exception {
        DumbSlave slave = j.createSlave("offline-agent", "", null);
        Computer c = slave.getComputer();
        assertThat(c).isNotNull();
        c.setTemporarilyOffline(true, new OfflineCause.ByCLI("offline for testing"));

        McpSessionClient client = new McpSessionClient(j.getURL());
        client.initialize();

        JSONObject response = client.callTool("getExecutorStatus", Map.of("nodeName", "offline-agent"));
        client.close();

        assertThat(response.has("error")).isFalse();

        JSONObject data = getStructuredContent(response);
        JSONArray nodes = data.getJSONArray("nodes");
        assertThat(nodes.size()).isEqualTo(1);
        JSONObject node = nodes.getJSONObject(0);
        assertThat(node.getString("name")).isEqualTo("offline-agent");
        JSONArray executors = node.getJSONArray("executors");
        assertThat(executors.size()).isEqualTo(0);
        assertThat(data.getBoolean("truncated")).isFalse();
    }

    // ----------------------------------------------------------------
    // 6.5 — getExecutorStatus unknown node
    // ----------------------------------------------------------------

    @Test
    void getExecutorStatusUnknownNode(JenkinsRule j) throws Exception {
        McpSessionClient client = new McpSessionClient(j.getURL());
        client.initialize();

        JSONObject response = client.callTool("getExecutorStatus", Map.of("nodeName", "nonexistent-node"));
        client.close();

        String error = getToolError(response);
        assertThat(error).isNotNull();
        assertThat(error).contains("No such node 'nonexistent-node'");
    }

    // ----------------------------------------------------------------
    // 6.6 — getExecutorStatus bounding
    // ----------------------------------------------------------------

    @Test
    void getExecutorStatusBounding(JenkinsRule j) throws Exception {
        // Create 3 agents so total is 4 nodes
        for (int i = 0; i < 3; i++) {
            j.createSlave("agent-" + i, "", null);
        }

        McpSessionClient client = new McpSessionClient(j.getURL());
        client.initialize();

        // limit=2 → 2 nodes, truncated=true
        JSONObject r1 = client.callTool("getExecutorStatus", Map.of("limit", 2));
        assertThat(r1.has("error")).isFalse();
        JSONObject d1 = getStructuredContent(r1);
        assertThat(d1.getJSONArray("nodes").size()).isEqualTo(2);
        assertThat(d1.getBoolean("truncated")).isTrue();

        // offset=2, limit=2 → remaining 2 nodes but truncated=true
        // (4 total nodes > 2 returned, so count-based truncated is true)
        JSONObject r2 = client.callTool("getExecutorStatus", Map.of("offset", 2, "limit", 2));
        assertThat(r2.has("error")).isFalse();
        JSONObject d2 = getStructuredContent(r2);
        assertThat(d2.getJSONArray("nodes").size()).isEqualTo(2);
        assertThat(d2.getBoolean("truncated")).isTrue();

        // nodeName without bounding — truncated must be false even with limit
        JSONObject r3 = client.callTool("getExecutorStatus", Map.of("nodeName", "agent-0", "limit", 1));
        assertThat(r3.has("error")).isFalse();
        JSONObject d3 = getStructuredContent(r3);
        assertThat(d3.getJSONArray("nodes").size()).isEqualTo(1);
        assertThat(d3.getBoolean("truncated")).isFalse();

        client.close();
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private static String getToolError(JSONObject response) {
        JSONObject result = response.getJSONObject("result");
        if (!result.optBoolean("isError", false)) {
            return null;
        }
        String text = result.getJSONArray("content").getJSONObject(0).getString("text");
        return JSONObject.fromObject(text).getString("message");
    }

    private static JSONObject getStructuredContent(JSONObject response) {
        JSONObject result = response.getJSONObject("result");
        String text = result.getJSONArray("content").getJSONObject(0).getString("text");
        return JSONObject.fromObject(text).getJSONObject("result");
    }

    /** Finds the first node entry with the given name, or {@code null}. */
    private static JSONObject findNodeByName(JSONArray nodes, String name) {
        for (Object obj : nodes) {
            JSONObject node = (JSONObject) obj;
            if (name.equals(node.getString("name"))) {
                return node;
            }
        }
        return null;
    }
}
