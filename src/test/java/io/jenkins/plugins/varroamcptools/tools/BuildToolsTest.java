package io.jenkins.plugins.varroamcptools.tools;

import static org.assertj.core.api.Assertions.assertThat;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.Result;
import hudson.tasks.Shell;
import io.jenkins.plugins.varroamcptools.testutil.McpSessionClient;
import java.util.Map;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class BuildToolsTest {

    // ----------------------------------------------------------------
    // Happy path
    // ----------------------------------------------------------------

    @Test
    void abortBuildStopsARunningBuild(JenkinsRule j) throws Exception {
        FreeStyleProject project = j.createFreeStyleProject("long-running");
        // Only has to outlive the abort call, which lands in well under a
        // second. Kept short so a silently-failing abort surfaces as a fast
        // failure instead of burning the sleep before waitForCompletion returns.
        project.getBuildersList().add(new Shell("sleep 60"));

        FreeStyleBuild build = project.scheduleBuild2(0).waitForStart();
        waitForExecutor(build);

        McpSessionClient client = new McpSessionClient(j.getURL());
        client.initialize();
        JSONObject response = client.callTool(
                "abortBuild", Map.of("jobFullName", "long-running", "buildNumber", build.getNumber(), "confirm", true));
        client.close();

        assertThat(getToolError(response)).isNull();

        JSONObject data = getStructuredContent(response);
        assertThat(data.keySet())
                .containsExactlyInAnyOrder("jobFullName", "buildNumber", "aborted", "reason", "building", "result");
        assertThat(data.getString("jobFullName")).isEqualTo("long-running");
        assertThat(data.getInt("buildNumber")).isEqualTo(build.getNumber());
        assertThat(data.getBoolean("aborted")).isTrue();
        assertThat(data.getString("reason")).isEqualTo("abort requested");

        // The tool only requests the interrupt; the run unwinds afterwards.
        j.waitForCompletion(build);
        j.assertBuildStatus(Result.ABORTED, build);
    }

    @Test
    void abortBuildStopsAPipelinePausedForInput(JenkinsRule j) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, "paused");
        job.setDefinition(new CpsFlowDefinition("input message: 'proceed?'", true));
        WorkflowRun run = job.scheduleBuild2(0).waitForStart();

        // Wait for the prompt in the log, not just for an execution to exist:
        // the execution and the executor are both attached at startup, so
        // polling for them would let this pass without ever reaching input.
        j.waitForMessage("proceed?", run);

        // A run paused for input keeps its one-off executor — measured, not
        // assumed, because "waiting for input holds no executor" is a common
        // belief and would put this case in the no-executor branch instead.
        assertThat(run.getOneOffExecutor()).isNotNull();
        assertThat(run.isBuilding()).isTrue();

        McpSessionClient client = new McpSessionClient(j.getURL());
        client.initialize();
        JSONObject response = client.callTool(
                "abortBuild", Map.of("jobFullName", "paused", "buildNumber", run.getNumber(), "confirm", true));
        client.close();

        assertThat(getToolError(response)).isNull();
        JSONObject data = getStructuredContent(response);
        assertThat(data.getBoolean("aborted")).isTrue();
        assertThat(data.getString("reason")).isEqualTo("abort requested");

        j.waitForCompletion(run);
        j.assertBuildStatus(Result.ABORTED, run);
    }

    // ----------------------------------------------------------------
    // Confirmation
    // ----------------------------------------------------------------

    @Test
    void abortBuildRequiresConfirmation(JenkinsRule j) throws Exception {
        FreeStyleProject project = j.createFreeStyleProject("needs-confirm");
        // Only has to outlive the abort call, which lands in well under a
        // second. Kept short so a silently-failing abort surfaces as a fast
        // failure instead of burning the sleep before waitForCompletion returns.
        project.getBuildersList().add(new Shell("sleep 60"));

        FreeStyleBuild build = project.scheduleBuild2(0).waitForStart();
        waitForExecutor(build);

        McpSessionClient client = new McpSessionClient(j.getURL());
        client.initialize();
        JSONObject response =
                client.callTool("abortBuild", Map.of("jobFullName", "needs-confirm", "buildNumber", build.getNumber()));
        client.close();

        assertThat(getToolError(response)).contains("confirm=true");
        // The build must be untouched by a call that never confirmed.
        assertThat(build.isBuilding()).isTrue();

        build.getExecutor().interrupt(Result.ABORTED);
        j.waitForCompletion(build);
    }

    // ----------------------------------------------------------------
    // Permission denied
    // ----------------------------------------------------------------

    @Test
    void abortBuildDeniedWithoutCancelPermission(JenkinsRule j) throws Exception {
        j.createFreeStyleProject("guarded");
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        // Read but not Cancel: the job resolves, and the guard is what stops
        // the call — not a "no such job" masking the real reason.
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Item.READ)
                .everywhere()
                .toEveryone());

        McpSessionClient client = new McpSessionClient(j.getURL());
        client.initialize();
        JSONObject response =
                client.callTool("abortBuild", Map.of("jobFullName", "guarded", "buildNumber", 1, "confirm", true));
        client.close();

        String error = getToolError(response);
        assertThat(error).isNotNull();
        assertThat(error).contains("Cancel");
        assertThat(error).contains("guarded");
    }

    @Test
    void abortBuildHidesJobsTheCallerCannotRead(JenkinsRule j) throws Exception {
        j.createFreeStyleProject("secret");
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        // Jenkins.READ but no Item.READ: an existing job must be reported
        // exactly like a nonexistent one, or the error leaks its existence.
        j.jenkins.setAuthorizationStrategy(
                new MockAuthorizationStrategy().grant(Jenkins.READ).everywhere().toEveryone());

        McpSessionClient client = new McpSessionClient(j.getURL());
        client.initialize();
        JSONObject response =
                client.callTool("abortBuild", Map.of("jobFullName", "secret", "buildNumber", 1, "confirm", true));
        client.close();

        String error = getToolError(response);
        assertThat(error).contains("No such job 'secret'");
        // Nothing may hint that the job exists but is off limits.
        assertThat(error).doesNotContain("Cancel");
    }

    // ----------------------------------------------------------------
    // Missing targets
    // ----------------------------------------------------------------

    @Test
    void abortBuildUnknownJob(JenkinsRule j) throws Exception {
        McpSessionClient client = new McpSessionClient(j.getURL());
        client.initialize();
        JSONObject response = client.callTool(
                "abortBuild", Map.of("jobFullName", "does-not-exist", "buildNumber", 1, "confirm", true));
        client.close();

        assertThat(getToolError(response)).contains("No such job 'does-not-exist'");
    }

    @Test
    void abortBuildUnknownBuildNumber(JenkinsRule j) throws Exception {
        j.createFreeStyleProject("no-builds");

        McpSessionClient client = new McpSessionClient(j.getURL());
        client.initialize();
        JSONObject response =
                client.callTool("abortBuild", Map.of("jobFullName", "no-builds", "buildNumber", 42, "confirm", true));
        client.close();

        assertThat(getToolError(response)).contains("No such build #42");
    }

    // ----------------------------------------------------------------
    // Already finished
    // ----------------------------------------------------------------

    @Test
    void abortBuildOnFinishedBuildReportsNotRunning(JenkinsRule j) throws Exception {
        FreeStyleProject project = j.createFreeStyleProject("already-done");
        FreeStyleBuild build = j.buildAndAssertSuccess(project);

        McpSessionClient client = new McpSessionClient(j.getURL());
        client.initialize();
        JSONObject response = client.callTool(
                "abortBuild", Map.of("jobFullName", "already-done", "buildNumber", build.getNumber(), "confirm", true));
        client.close();

        assertThat(getToolError(response)).isNull();

        JSONObject data = getStructuredContent(response);
        assertThat(data.getBoolean("aborted")).isFalse();
        assertThat(data.getString("reason")).isEqualTo("build is not running");
        assertThat(data.getBoolean("building")).isFalse();
        assertThat(data.getString("result")).isEqualTo("SUCCESS");
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    /**
     * isBuilding() goes true a moment before an executor is attached, so a
     * test that calls straight after waitForStart() can race the tool into
     * the "holds no executor" branch.
     */
    private static void waitForExecutor(FreeStyleBuild build) throws InterruptedException {
        for (int i = 0; i < 200 && build.getExecutor() == null; i++) {
            Thread.sleep(50);
        }
        assertThat(build.getExecutor()).isNotNull();
    }

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
}
