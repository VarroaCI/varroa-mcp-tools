package io.jenkins.plugins.varroamcptools.util;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.AccessDeniedException3;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class PermissionGuardTest {

    @Test
    void shouldPassThroughWhenPermitted(JenkinsRule j) throws Exception {
        FreeStyleProject project = j.createFreeStyleProject("test-project");

        // SYSTEM has all permissions by default in JenkinsRule.
        assertThatCode(() -> PermissionGuard.require(project, Item.READ)).doesNotThrowAnyException();
    }

    @Test
    void shouldFailEarlyWithClearMessage(JenkinsRule j) throws Exception {
        FreeStyleProject project = j.createFreeStyleProject("test-project");

        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(
                new MockAuthorizationStrategy().grant(Jenkins.READ).everywhere().toEveryone());

        User alice = User.getOrCreateByIdOrFullName("alice");

        try (ACLContext ctx = ACL.as2(alice.impersonate2())) {
            assertThatThrownBy(() -> PermissionGuard.require(project, Item.READ))
                    .isInstanceOf(AccessDeniedException3.class)
                    .hasMessageContaining("Read")
                    .hasMessageContaining("test-project");
        }
    }
}
