# AGENTS.md

If an untracked `INIT.md` exists in the repo root, read it before anything else —
it is local bootstrap context and must never be committed.

## What This Project Is

`varroa-mcp-tools` is a Jenkins plugin (MIT) that extends the official
[mcp-server-plugin](https://github.com/jenkinsci/mcp-server-plugin) with
operational tools for MCP agents: pipeline stage/step introspection, test
results, node/executor status, and later queue operations, plugin inventory,
and audit logging of agent actions. Tools are served by the official plugin's
endpoint (`/mcp-server/mcp`) and run with the permissions of the authenticated
Jenkins user.

It is developed by the Varroa project but is strictly vendor-neutral: it must
work on any Jenkins installation that has the mcp-server plugin and the
Pipeline plugins it depends on (workflow-api, pipeline-graph-analysis)
installed.

## Principles

- **Extend, never fork.** Implement `McpServerExtension` and annotate tool
  methods with `@Tool`; tools appear on the official plugin's endpoint.
  Built-in tools may be overridden by name, but prefer new, clearly-named
  tools. Never vendor or patch the server plugin itself.
  Extension-point docs: https://www.jenkins.io/doc/developer/extensions/mcp-server/
- **No authentication or authorization code.** Tools execute as the
  authenticated user; the install's security realm and authorization strategy
  decide everything. A tool must never widen access beyond what its caller
  could do through the UI or REST API. Use `ACL`/permission checks only to
  fail early with a clear message, never to grant.
- **Read-mostly by default.** Mutating tools (queue ops, rebuilds) must be
  explicit about what they change, and destructive operations need a
  confirmation-shaped argument (e.g. `confirm: true`), not implicit defaults.
- **Serialization discipline.** Tool results are consumed by LLM agents:
  return compact, stable JSON shapes; never dump raw model objects
  (`Run`, `Job`, …) through the serializer; bound every list (page or cap)
  so a tool call can't return megabytes.
- **Zero static-analysis debt.** SpotBugs/lint stay clean with no
  suppressions from the first commit. This repo is greenfield; keep it that way.

## Commands

There is no local JDK/Maven on the primary dev machine — build in a container:

```bash
docker run --rm -v "$PWD":/src -v varroa-m2-cache:/root/.m2 -w /src \
  maven:3.9-eclipse-temurin-21 mvn -B verify        # build + unit tests + analysis

docker run --rm -v "$PWD":/src -v varroa-m2-cache:/root/.m2 -w /src \
  maven:3.9-eclipse-temurin-21 mvn -B test          # tests only
```

JDK 21, Jenkins plugin parent POM, plugin BOM for dependency versions. The
`varroa-m2-cache` volume keeps the dependency cache warm — reuse it.

## Testing

- **Unit/harness**: JenkinsRule (`@Rule JenkinsRule`) for every tool — happy
  path, permission-denied path, and empty/missing-target path. Test through
  the MCP endpoint where practical, not just by invoking the Java method.
- **The Jenkins-side MCP transport is session-based**: `initialize` → capture
  `Mcp-Session-Id` → call with the header → `DELETE` to close. Any e2e helper
  must speak this handshake; a bare POST of a `tools/call` will not work.
- **e2e matrix**: CI must exercise the supported Jenkins LTS lines, not just
  the parent-POM baseline.

## Docs Rules

- Docs ship with tools: every new tool lands with reference docs (arguments,
  result shape, required permissions, an example call) in the same PR.
- Prose must read human-written: no emoji headers, no filler enthusiasm.
  Match the register of jenkinsci and kubernetes-sigs READMEs.
- The README carries a short "About Varroa" footer; Varroa is otherwise not
  referenced in user docs — this plugin stands alone.

## Keeping This Doc Current

This file is the working contract for the repo. When commands, principles,
tool scope, or verification change, update it in the same PR.
