package io.jenkins.plugins.varroamcptools.tools;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.labels.LabelAtom;
import hudson.node_monitors.DiskSpaceMonitor;
import hudson.node_monitors.DiskSpaceMonitorDescriptor;
import hudson.node_monitors.TemporarySpaceMonitor;
import io.jenkins.plugins.mcp.server.McpServerExtension;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import io.jenkins.plugins.varroamcptools.util.BoundedResult;
import io.jenkins.plugins.varroamcptools.util.JsonResultBuilder;
import io.jenkins.plugins.varroamcptools.util.ListBounding;
import io.jenkins.plugins.varroamcptools.util.PermissionGuard;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import jenkins.model.Jenkins;

/**
 * MCP tools for node and executor status introspection.
 *
 * <p>Provides {@code getNodes} (bounded list of nodes with basic status and
 * cheap cached monitor data) and {@code getExecutorStatus} (per-node
 * executor occupancy and current-build reference). Both tools require
 * {@link Jenkins#READ} and fail early when the caller lacks it.
 */
@Extension
public class NodeTools implements McpServerExtension {

    // ----------------------------------------------------------------
    // Tool: getNodes
    // ----------------------------------------------------------------

    @Tool(description = "List Jenkins nodes with basic status and cheap cached monitor data")
    public Map<String, Object> getNodes(
            @ToolParam(description = "Max nodes to return (default 20, max 100)") Integer limit,
            @ToolParam(description = "Offset into the node list (default 0)") Integer offset) {

        PermissionGuard.require(Jenkins.get(), Jenkins.READ);

        List<Computer> computers = Arrays.asList(Jenkins.get().getComputers());
        BoundedResult<Computer> bounded = ListBounding.bound(computers, limit, offset);

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Computer c : bounded.getItems()) {
            nodes.add(buildNodeEntry(c));
        }

        return JsonResultBuilder.create()
                .add("nodes", nodes)
                .add("limit", effectiveLimit(limit))
                .add("offset", effectiveOffset(offset))
                .add("truncated", bounded.isTruncated())
                .build();
    }

    // ----------------------------------------------------------------
    // Tool: getExecutorStatus
    // ----------------------------------------------------------------

    @Tool(description = "Per-node executor occupancy and current-build reference")
    public Map<String, Object> getExecutorStatus(
            @ToolParam(description = "Restrict to a single node by name; omit for all nodes") String nodeName,
            @ToolParam(description = "Max nodes to return when nodeName is omitted (default 20, max 100)")
                    Integer limit,
            @ToolParam(description = "Offset into the node list when nodeName is omitted (default 0)") Integer offset) {

        PermissionGuard.require(Jenkins.get(), Jenkins.READ);

        List<Computer> computers;
        boolean truncated;
        int effectiveLimit;
        int effectiveOffset;

        if (nodeName != null) {
            Computer c = Jenkins.get().getComputer(nodeName);
            if (c == null) {
                throw new IllegalArgumentException("No such node '" + nodeName + "'");
            }
            computers = List.of(c);
            truncated = false;
            effectiveLimit = limit == null ? ListBounding.DEFAULT_LIMIT : limit;
            effectiveOffset = offset == null ? 0 : offset;
        } else {
            List<Computer> allComputers = Arrays.asList(Jenkins.get().getComputers());
            BoundedResult<Computer> bounded = ListBounding.bound(allComputers, limit, offset);
            computers = bounded.getItems();
            truncated = bounded.isTruncated();
            effectiveLimit = effectiveLimit(limit);
            effectiveOffset = effectiveOffset(offset);
        }

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Computer c : computers) {
            nodes.add(buildExecutorNodeEntry(c));
        }

        return JsonResultBuilder.create()
                .add("nodes", nodes)
                .add("limit", effectiveLimit)
                .add("offset", effectiveOffset)
                .add("truncated", truncated)
                .build();
    }

    // ----------------------------------------------------------------
    // Shared helpers
    // ----------------------------------------------------------------

    /**
     * Converts a {@link DiskSpaceMonitorDescriptor.DiskSpace} to whole
     * megabytes, or returns {@code null} when the value is {@code null}.
     */
    private static Long toMb(DiskSpaceMonitorDescriptor.DiskSpace space) {
        if (space == null) {
            return null;
        }
        return Math.round(space.size / (1024.0 * 1024.0));
    }

    /**
     * Builds the shared node-description fields used by both tools.
     *
     * <p>Degrades gracefully when {@link Computer#getNode()} returns
     * {@code null} (a race during concurrent node deletion).
     */
    private static Map<String, Object> describeNode(Computer c) {
        Node node = c.getNode();
        return JsonResultBuilder.create()
                .add("name", c.getName())
                .add("displayName", c.getDisplayName())
                .add("description", node == null ? "" : Objects.toString(node.getNodeDescription(), ""))
                .add(
                        "labels",
                        node == null
                                ? List.of()
                                : node.getAssignedLabels().stream()
                                        .map(LabelAtom::getName)
                                        .sorted()
                                        .toList())
                .add("online", c.isOnline())
                .add("offlineCause", c.isOnline() ? null : emptyToNull(c.getOfflineCauseReason()))
                .add("numExecutors", c.getNumExecutors())
                .build();
    }

    // ----------------------------------------------------------------
    // Internal helpers
    // ----------------------------------------------------------------

    /** Builds a full {@code getNodes} entry from a {@link Computer}. */
    private static Map<String, Object> buildNodeEntry(Computer c) {
        Map<String, Object> entry = new LinkedHashMap<>(describeNode(c));
        Long diskSpaceMb = c.isOffline() ? null : toMb(DiskSpaceMonitor.DESCRIPTOR.get(c));
        Long tempSpaceMb = c.isOffline() ? null : toMb(new TemporarySpaceMonitor().getFreeSpace(c));
        entry.put("diskSpaceMb", diskSpaceMb);
        entry.put("tempSpaceMb", tempSpaceMb);
        return entry;
    }

    /** Builds a {@code getExecutorStatus} node entry from a {@link Computer}. */
    private static Map<String, Object> buildExecutorNodeEntry(Computer c) {
        List<Map<String, Object>> executors = new ArrayList<>();
        if (!c.isOffline()) {
            List<Executor> executorList = c.getExecutors();
            for (int i = 0; i < executorList.size(); i++) {
                executors.add(buildExecutorEntry(i, executorList.get(i)));
            }
        }
        return JsonResultBuilder.create()
                .add("name", c.getName())
                .add("executors", executors)
                .build();
    }

    /** Builds a single executor entry from its index and reference. */
    private static Map<String, Object> buildExecutorEntry(int index, Executor e) {
        boolean busy = e.isBusy();
        Map<String, Object> entry = JsonResultBuilder.create()
                .add("index", index)
                .add("busy", busy)
                .add("currentBuild", buildCurrentBuild(busy, e))
                .build();
        return entry;
    }

    /**
     * Returns the {@code currentBuild} object for a busy executor, or
     * {@code null} when idle or when the current executable is not a
     * {@link Run}.
     */
    private static Map<String, Object> buildCurrentBuild(boolean busy, Executor e) {
        if (!busy) {
            return null;
        }
        if (e.getCurrentExecutable() instanceof Run<?, ?> run) {
            return JsonResultBuilder.create()
                    .add("jobFullName", run.getParent().getFullName())
                    .add("buildNumber", run.getNumber())
                    .add("progressPercent", e.getProgress())
                    .add("durationMs", e.getElapsedTime())
                    .build();
        }
        return null;
    }

    private static int effectiveLimit(Integer limit) {
        if (limit == null) {
            return ListBounding.DEFAULT_LIMIT;
        }
        return Math.min(Math.max(1, limit), ListBounding.HARD_CAP);
    }

    private static int effectiveOffset(Integer offset) {
        return offset == null ? 0 : Math.max(0, offset);
    }

    /** Returns {@code null} when the string is null or empty. */
    private static String emptyToNull(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        return s;
    }
}
