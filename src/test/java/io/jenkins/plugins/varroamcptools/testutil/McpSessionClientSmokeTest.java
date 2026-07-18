package io.jenkins.plugins.varroamcptools.testutil;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import net.sf.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class McpSessionClientSmokeTest {

    @Test
    void shouldCompleteWhoAmIRoundTrip(JenkinsRule j) throws Exception {
        McpSessionClient client = new McpSessionClient(j.getURL());
        client.initialize();

        JSONObject response = client.callTool("whoAmI", Map.of());

        assertThat(response.has("error"))
                .as("MCP response must not contain an error key")
                .isFalse();
        assertThat(response.has("result"))
                .as("MCP response must contain a result key")
                .isTrue();

        assertThatCode(client::close).doesNotThrowAnyException();
    }

    @Test
    void shouldThrowWhenCallToolBeforeInitialize(JenkinsRule j) throws Exception {
        McpSessionClient client = new McpSessionClient(j.getURL());

        assertThatThrownBy(() -> client.callTool("whoAmI", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("initialize()");
    }

    @Test
    void shouldThrowWhenCallToolAfterClose(JenkinsRule j) throws Exception {
        McpSessionClient client = new McpSessionClient(j.getURL());
        client.initialize();
        client.close();

        assertThatThrownBy(() -> client.callTool("whoAmI", Map.of())).isInstanceOf(IllegalStateException.class);
    }
}
