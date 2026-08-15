package io.jenkins.plugins.varroamcptools.tools;

import hudson.Extension;
import hudson.model.Executor;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.User;
import io.jenkins.plugins.mcp.server.McpServerExtension;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import io.jenkins.plugins.varroamcptools.util.JsonResultBuilder;
import io.jenkins.plugins.varroamcptools.util.PermissionGuard;
import java.util.Map;
import jenkins.model.CauseOfInterruption;
import jenkins.model.Jenkins;

/**
 * MCP tools for operating on individual builds.
 *
 * <p>Provides {@code abortBuild}, the tool-call equivalent of the stop
 * button on a build page. It works for any {@link Run}, not only Pipeline
 * runs, and requires {@link Item#CANCEL} — the permission Jenkins already
 * associates with aborting a job's builds.
 */
@Extension
public class BuildTools implements McpServerExtension {

    // ----------------------------------------------------------------
    // Tool: abortBuild
    // ----------------------------------------------------------------

    @Tool(
            name = "abortBuild",
            description = "Aborts a running build, the tool-call equivalent of the stop button "
                    + "on its build page. Requests an interrupt and returns immediately: the "
                    + "build finishes asynchronously, so a successful call reports that the "
                    + "abort was accepted, not that the build has already stopped. Aborting "
                    + "destroys in-progress work, so confirm must be true.",
            structuredOutput = true,
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true))
    public Map<String, Object> abortBuild(
            @ToolParam(description = "Full name of the job (e.g. 'folder/my-job')") String jobFullName,
            @ToolParam(description = "Build number") int buildNumber,
            @ToolParam(description = "Must be true — aborting destroys in-progress work") Boolean confirm) {

        // The confirmation is checked before anything is resolved, so a call
        // that forgot it cannot be mistaken for one that found nothing to do.
        if (!Boolean.TRUE.equals(confirm)) {
            throw new IllegalArgumentException("abortBuild destroys in-progress work; pass confirm=true to proceed");
        }

        Item item = Jenkins.get().getItemByFullName(jobFullName);
        if (!(item instanceof Job<?, ?> job)) {
            // getItemByFullName filters by Item.READ, so a job that is
            // invisible to the caller and one that does not exist produce the
            // same message on purpose.
            throw new IllegalArgumentException("No such job '" + jobFullName + "'");
        }
        PermissionGuard.require(job, Item.CANCEL);

        Run<?, ?> run = job.getBuildByNumber(buildNumber);
        if (run == null) {
            throw new IllegalArgumentException("No such build #" + buildNumber + " for '" + jobFullName + "'");
        }

        if (!run.isBuilding()) {
            return abortResult(run, false, "build is not running");
        }

        // A flyweight task (a Pipeline run's own body, for instance) occupies a
        // one-off executor rather than a regular one.
        Executor executor = run.getExecutor();
        if (executor == null) {
            executor = run.getOneOffExecutor();
        }
        if (executor == null) {
            // A narrow window, not a stuck state: isBuilding() goes true
            // shortly before an executor is attached and stays true through
            // the post-build phase after it is released. A Pipeline paused on
            // an input step keeps its one-off executor and is aborted through
            // the path below, so it does not land here.
            return abortResult(run, false, "build is running but holds no executor");
        }

        // Re-check identity immediately before interrupting. Between resolving
        // the executor and this point the run can finish and that executor
        // pick up the next queued build — and interrupt() hits whatever the
        // executor is running now, so the tool would abort an unrelated build
        // and report success. Jenkins' own stop path re-validates the same way.
        // This narrows the window rather than closing it; the executor's lock
        // is not ours to take.
        if (executor.getCurrentExecutable() != run) {
            return abortResult(run, false, "build is not running");
        }

        executor.interrupt(Result.ABORTED, userInterruption());

        // Report what is observably true rather than that interrupt() was
        // called. The check above narrows the handoff race but cannot close
        // it, and if the run completed inside that window the interrupt
        // reached a finished executor and nothing was aborted — claiming
        // otherwise would contradict the result field in the same response.
        // An abort that did land leaves the run either still unwinding or
        // already ABORTED.
        //
        // aborted is derived from the two values that get serialized, so the
        // invariant "never aborted: true beside a *completed* non-ABORTED
        // result" holds by construction rather than by timing. No pair of
        // reads on a live Run is atomic — isBuilding() and getResult() can
        // always skew — so the contract is defined against the fields the
        // response actually carries.
        //
        // Both halves matter. A run publishes its result before it stops
        // building (anything that marks a build UNSTABLE mid-run does this),
        // so a non-ABORTED result alone does not mean the interrupt missed;
        // only a result on a run that has stopped building does.
        Result outcome = run.getResult();
        boolean building = run.isBuilding();
        boolean aborted = building || outcome == null || outcome == Result.ABORTED;
        String reason = aborted ? "abort requested" : "build finished before the abort took effect";
        return abortResult(run, aborted, reason, building, outcome);
    }

    // ----------------------------------------------------------------
    // Internal helpers
    // ----------------------------------------------------------------

    /**
     * Names the caller as the cause, so the abort is attributed in the build
     * log the same way the stop button's would be. {@link User#current()} is
     * null for an anonymous or non-user authentication, which the string
     * constructor covers.
     */
    private static CauseOfInterruption userInterruption() {
        User user = User.current();
        if (user != null) {
            return new CauseOfInterruption.UserInterruption(user);
        }
        return new CauseOfInterruption.UserInterruption(
                Jenkins.getAuthentication2().getName());
    }

    /**
     * Builds the result shape. {@code building} and {@code result} are read
     * after the interrupt request and describe the run as it stands right
     * then — an accepted abort normally still reports {@code building: true},
     * because the run has not yet unwound.
     */
    private static Map<String, Object> abortResult(Run<?, ?> run, boolean aborted, String reason) {
        return abortResult(run, aborted, reason, run.isBuilding(), run.getResult());
    }

    /**
     * Builds the result from already-read state, for the callers whose
     * decision depends on the same values the response reports. The two reads
     * are independent — nothing on a live {@link Run} makes them atomic — so
     * this exists to keep the decision and the response consistent with each
     * other, not to claim they describe one instant.
     */
    private static Map<String, Object> abortResult(
            Run<?, ?> run, boolean aborted, String reason, boolean building, Result outcome) {
        return JsonResultBuilder.create()
                .add("jobFullName", run.getParent().getFullName())
                .add("buildNumber", run.getNumber())
                .add("aborted", aborted)
                .add("reason", reason)
                .add("building", building)
                .add("result", outcome == null ? null : outcome.toString())
                .build();
    }
}
