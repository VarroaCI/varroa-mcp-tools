package io.jenkins.plugins.varroamcptools.tools;

import hudson.Extension;
import hudson.console.AnnotatedLargeText;
import hudson.model.Item;
import io.jenkins.plugins.mcp.server.McpServerExtension;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import io.jenkins.plugins.varroamcptools.util.BoundedResult;
import io.jenkins.plugins.varroamcptools.util.JsonResultBuilder;
import io.jenkins.plugins.varroamcptools.util.ListBounding;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.actions.LogAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graph.StepNode;
import org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner;
import org.jenkinsci.plugins.workflow.graphanalysis.ForkScanner;
import org.jenkinsci.plugins.workflow.graphanalysis.MemoryFlowChunk;
import org.jenkinsci.plugins.workflow.graphanalysis.StandardChunkVisitor;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.pipelinegraphanalysis.GenericStatus;
import org.jenkinsci.plugins.workflow.pipelinegraphanalysis.StageChunkFinder;
import org.jenkinsci.plugins.workflow.pipelinegraphanalysis.StatusAndTiming;
import org.jenkinsci.plugins.workflow.pipelinegraphanalysis.TimingInfo;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;

/**
 * MCP tools for Pipeline stage/step introspection.
 *
 * <p>All three tools share a single {@link #resolveRun} method that
 * resolves {@code jobFullName}+{@code buildNumber} to a
 * {@link WorkflowRun}, relying solely on
 * {@link Jenkins#getItemByFullName}'s implicit ACL filtering — invisible
 * and nonexistent jobs produce the same uniform error message.
 */
@Extension
public class PipelineTools implements McpServerExtension {

    // ----------------------------------------------------------------
    // Tool: getPipelineStages
    // ----------------------------------------------------------------

    @Tool(
            name = "getPipelineStages",
            description = "Returns a flat list of stages for a Pipeline run, "
                    + "bounded by limit and offset. Stages nested inside parallel "
                    + "branches appear as peer entries with no parent/child field.",
            structuredOutput = true,
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false))
    public Map<String, Object> getPipelineStages(
            @ToolParam(description = "Full name of the Pipeline job (e.g. 'folder/my-job')") String jobFullName,
            @ToolParam(description = "Build number") int buildNumber,
            @ToolParam(description = "Maximum stages to return (default 20, hard cap 100)", required = false)
                    Integer limit,
            @ToolParam(description = "Zero-based starting index (default 0)", required = false) Integer offset) {

        WorkflowRun run = resolveRun(jobFullName, buildNumber);
        FlowExecution execution = run.getExecution();
        if (execution == null) {
            return emptyStagesResult(limit, offset);
        }

        List<Map<String, Object>> stages = new ArrayList<>();
        try {
            ForkScanner.visitSimpleChunks(
                    execution.getCurrentHeads(),
                    new StandardChunkVisitor() {
                        @Override
                        protected void handleChunkDone(MemoryFlowChunk chunk) {
                            stages.add(buildStageEntry(run, chunk));
                        }
                    },
                    new StageChunkFinder());
        } catch (Exception e) {
            // If the graph walk itself fails (malformed execution, plugin
            // missing), surface the error rather than swallowing it.
            throw new RuntimeException(
                    "Failed to walk pipeline stages for '" + jobFullName + "' build #" + buildNumber, e);
        }

        Collections.reverse(stages);

        BoundedResult<Map<String, Object>> bounded = ListBounding.bound(stages, limit, offset);
        return JsonResultBuilder.create()
                .add("stages", bounded.getItems())
                .add("limit", effectiveLimit(limit))
                .add("offset", effectiveOffset(offset))
                .add("truncated", bounded.isTruncated())
                .build();
    }

    // ----------------------------------------------------------------
    // Tool: getStageSteps
    // ----------------------------------------------------------------

    @Tool(
            name = "getStageSteps",
            description = "Returns steps within a single stage of a Pipeline run, "
                    + "bounded by limit and offset. Both leaf steps and block-scoped "
                    + "steps (dir, withEnv, etc.) each appear as one entry.",
            structuredOutput = true,
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false))
    public Map<String, Object> getStageSteps(
            @ToolParam(description = "Full name of the Pipeline job (e.g. 'folder/my-job')") String jobFullName,
            @ToolParam(description = "Build number") int buildNumber,
            @ToolParam(description = "Stage ID (from a previous getPipelineStages call)") String stageId,
            @ToolParam(description = "Maximum steps to return (default 20, hard cap 100)", required = false)
                    Integer limit,
            @ToolParam(description = "Zero-based starting index (default 0)", required = false) Integer offset) {

        WorkflowRun run = resolveRun(jobFullName, buildNumber);
        FlowExecution execution = run.getExecution();
        if (execution == null) {
            return emptyStepsResult(limit, offset);
        }

        MemoryFlowChunk targetChunk = findStageChunk(execution, stageId, buildNumber);
        if (targetChunk == null) {
            throw new IllegalArgumentException("No such stage '" + stageId + "' in build #" + buildNumber + "'");
        }

        List<Map<String, Object>> steps = new ArrayList<>();
        DepthFirstScanner scanner = new DepthFirstScanner();
        scanner.setup(targetChunk.getLastNode());
        FlowNode firstNode = targetChunk.getFirstNode();
        for (FlowNode node : scanner) {
            if (node instanceof StepNode) {
                steps.add(buildStepEntry(run, node));
            }
            if (node.equals(firstNode)) {
                break;
            }
        }

        Collections.reverse(steps);

        BoundedResult<Map<String, Object>> bounded = ListBounding.bound(steps, limit, offset);
        return JsonResultBuilder.create()
                .add("steps", bounded.getItems())
                .add("limit", effectiveLimit(limit))
                .add("offset", effectiveOffset(offset))
                .add("truncated", bounded.isTruncated())
                .build();
    }

    // ----------------------------------------------------------------
    // Tool: getStepLog
    // ----------------------------------------------------------------

    private static final int DEFAULT_MAX_BYTES = 50 * 1024; // 50 KB
    private static final int HARD_MAX_BYTES = 1_048_576; // 1 MiB

    @Tool(
            name = "getStepLog",
            description = "Returns a byte-capped log excerpt for a single Pipeline "
                    + "step. Default maxBytes is 50 KB, hard cap is 1 MiB. Values "
                    + "<= 0 are rejected; values above 1 MiB are silently clamped.",
            structuredOutput = true,
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false))
    public Map<String, Object> getStepLog(
            @ToolParam(description = "Full name of the Pipeline job (e.g. 'folder/my-job')") String jobFullName,
            @ToolParam(description = "Build number") int buildNumber,
            @ToolParam(description = "Step ID (from a previous getStageSteps call)") String stepId,
            @ToolParam(description = "Maximum bytes to read (default 51200, hard cap 1048576)", required = false)
                    Integer maxBytes) {

        if (maxBytes != null && maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive, got " + maxBytes);
        }

        int appliedCap = maxBytes == null ? DEFAULT_MAX_BYTES : maxBytes;
        if (appliedCap > HARD_MAX_BYTES) {
            appliedCap = HARD_MAX_BYTES;
        }

        WorkflowRun run = resolveRun(jobFullName, buildNumber);
        FlowExecution execution = run.getExecution();
        if (execution == null) {
            throw new IllegalArgumentException("No such step '" + stepId + "' in build #" + buildNumber + "'");
        }

        FlowNode node;
        try {
            node = execution.getNode(stepId);
        } catch (IOException e) {
            throw new RuntimeException("Failed to look up step '" + stepId + "' in build #" + buildNumber + "'", e);
        }
        if (node == null) {
            throw new IllegalArgumentException("No such step '" + stepId + "' in build #" + buildNumber + "'");
        }

        LogAction log = node.getPersistentAction(LogAction.class);
        if (log == null) {
            return JsonResultBuilder.create()
                    .add("stepId", stepId)
                    .add("logText", "")
                    .add("byteLength", 0)
                    .add("truncated", false)
                    .add("logAvailable", false)
                    .build();
        }

        AnnotatedLargeText<? extends FlowNode> text = log.getLogText();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            text.writeLogTo(0, new BoundedOutputStream(buffer, appliedCap));
        } catch (IOException e) {
            throw new RuntimeException(
                    "Failed to read log for step '" + stepId + "' in build #" + buildNumber + "'", e);
        }

        byte[] bytes = buffer.toByteArray();
        CharsetDecoder decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        String logText;
        try {
            logText = decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (IOException e) {
            // CodingErrorAction.REPLACE means decode never throws
            throw new RuntimeException("Unexpected decode failure", e);
        }

        boolean truncated = text.length() > appliedCap;

        return JsonResultBuilder.create()
                .add("stepId", stepId)
                .add("logText", logText)
                .add("byteLength", bytes.length)
                .add("truncated", truncated)
                .add("logAvailable", true)
                .build();
    }

    // ----------------------------------------------------------------
    // Run/Job resolution (package-private, local)
    // ----------------------------------------------------------------

    /**
     * Resolves {@code jobFullName}+{@code buildNumber} to a
     * {@link WorkflowRun}, relying solely on
     * {@link Jenkins#getItemByFullName}'s implicit ACL filtering.
     *
     * <p>Invisible-to-the-caller jobs and nonexistent jobs both resolve
     * to {@code null} from {@code getItemByFullName}, so this method
     * produces one uniform message for both cases.
     *
     * @throws IllegalArgumentException if the job does not exist, is not a
     *         {@link WorkflowJob}, or is invisible to the caller
     * @throws IllegalArgumentException if the build number does not exist
     */
    WorkflowRun resolveRun(String jobFullName, int buildNumber) {
        Item item = Jenkins.get().getItemByFullName(jobFullName);
        if (!(item instanceof WorkflowJob job)) {
            throw new IllegalArgumentException("No such pipeline job '" + jobFullName + "'");
        }
        WorkflowRun run = job.getBuildByNumber(buildNumber);
        if (run == null) {
            throw new IllegalArgumentException("No such build #" + buildNumber + " for '" + jobFullName + "'");
        }
        return run;
    }

    // ----------------------------------------------------------------
    // Internal helpers
    // ----------------------------------------------------------------

    /** Builds a single stage entry map from a {@link MemoryFlowChunk}. */
    private static Map<String, Object> buildStageEntry(WorkflowRun run, MemoryFlowChunk chunk) {
        FlowNode firstNode = chunk.getFirstNode();
        String stageId = firstNode.getId();
        LabelAction la = firstNode.getPersistentAction(LabelAction.class);
        String name = (la != null) ? la.getDisplayName() : firstNode.getDisplayName();
        String status;
        GenericStatus gs = StatusAndTiming.computeChunkStatus(run, chunk);
        status = (gs != null) ? gs.name() : null;
        TimingInfo t = StatusAndTiming.computeChunkTiming(run, 0L, chunk);
        long durationMs = (t != null) ? t.getTotalDurationMillis() : 0L;
        long timestampMs = (t != null) ? t.getStartTimeMillis() : 0L;

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("stageId", stageId);
        entry.put("name", name);
        entry.put("status", status);
        entry.put("durationMs", durationMs);
        entry.put("timestampMs", timestampMs);
        return entry;
    }

    /** Builds a single step entry map from a {@link FlowNode}. */
    private static Map<String, Object> buildStepEntry(WorkflowRun run, FlowNode node) {
        String stepId = node.getId();

        // Three-tier name fallback
        String name;
        StepDescriptor sd = getStepDescriptor(node);
        if (sd != null) {
            name = sd.getFunctionName();
            if (name == null || name.isEmpty()) {
                name = sd.getDisplayName();
            }
        } else {
            name = node.getDisplayName();
        }

        // Wrap as single-node chunk for StatusAndTiming reuse
        MemoryFlowChunk singleChunk = new MemoryFlowChunk();
        singleChunk.setFirstNode(node);
        singleChunk.setLastNode(node);

        String status;
        GenericStatus gs = StatusAndTiming.computeChunkStatus(run, singleChunk);
        status = (gs != null) ? gs.name() : null;
        TimingInfo t = StatusAndTiming.computeChunkTiming(run, 0L, singleChunk);
        long durationMs = (t != null) ? t.getTotalDurationMillis() : 0L;
        long timestampMs = (t != null) ? t.getStartTimeMillis() : 0L;
        boolean logAvailable = node.getPersistentAction(LogAction.class) != null;

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("stepId", stepId);
        entry.put("name", name);
        entry.put("status", status);
        entry.put("durationMs", durationMs);
        entry.put("timestampMs", timestampMs);
        entry.put("logAvailable", logAvailable);
        return entry;
    }

    /** Extracts the {@link StepDescriptor} from a node, handling both
     *  atom steps and block-start steps via the {@link StepNode} interface. */
    private static StepDescriptor getStepDescriptor(FlowNode node) {
        if (node instanceof StepNode) {
            return ((StepNode) node).getDescriptor();
        }
        return null;
    }

    /**
     * Re-walks the flow graph with {@link ForkScanner} and
     * {@link StageChunkFinder} to find the chunk whose first node's ID
     * matches {@code stageId}.
     *
     * @return the matching chunk, or {@code null} if none matches
     */
    private static MemoryFlowChunk findStageChunk(FlowExecution execution, String stageId, int buildNumber) {
        MemoryFlowChunk[] found = new MemoryFlowChunk[1];
        try {
            ForkScanner.visitSimpleChunks(
                    execution.getCurrentHeads(),
                    new StandardChunkVisitor() {
                        @Override
                        protected void handleChunkDone(MemoryFlowChunk chunk) {
                            if (found[0] == null
                                    && stageId.equals(chunk.getFirstNode().getId())) {
                                MemoryFlowChunk snapshot = new MemoryFlowChunk();
                                snapshot.setFirstNode(chunk.getFirstNode());
                                snapshot.setLastNode(chunk.getLastNode());
                                found[0] = snapshot;
                            }
                        }
                    },
                    new StageChunkFinder());
        } catch (Exception e) {
            throw new RuntimeException("Failed to walk pipeline stages for build #" + buildNumber, e);
        }
        return found[0];
    }

    private static Map<String, Object> emptyStagesResult(Integer limit, Integer offset) {
        return JsonResultBuilder.create()
                .add("stages", List.of())
                .add("limit", effectiveLimit(limit))
                .add("offset", effectiveOffset(offset))
                .add("truncated", false)
                .build();
    }

    private static Map<String, Object> emptyStepsResult(Integer limit, Integer offset) {
        return JsonResultBuilder.create()
                .add("steps", List.of())
                .add("limit", effectiveLimit(limit))
                .add("offset", effectiveOffset(offset))
                .add("truncated", false)
                .build();
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

    // ----------------------------------------------------------------
    // BoundedOutputStream
    // ----------------------------------------------------------------

    /**
     * An {@link OutputStream} that forwards at most {@code maxBytes}
     * bytes to the delegate, silently discarding the rest.
     */
    private static final class BoundedOutputStream extends OutputStream {

        private final OutputStream delegate;
        private final long maxBytes;
        private long written;

        BoundedOutputStream(OutputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public void write(int b) throws IOException {
            if (written >= maxBytes) {
                return;
            }
            delegate.write(b);
            written++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (written >= maxBytes) {
                return;
            }
            int allowed = (int) Math.min(len, maxBytes - written);
            delegate.write(b, off, allowed);
            written += allowed;
        }
    }
}
