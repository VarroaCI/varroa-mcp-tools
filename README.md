# MCP Tools for Jenkins

A Jenkins plugin that extends the official
[mcp-server-plugin](https://github.com/jenkinsci/mcp-server-plugin) with
operational tools for MCP agents: pipeline stage/step introspection and
node/executor status (bounded test-result tools are planned future work).
Tools are served through the same
`/mcp-server/mcp` endpoint and run with the permissions of the
authenticated Jenkins user.

This plugin is vendor-neutral — it works on any Jenkins installation with
the mcp-server plugin installed. It ships no authentication or
authorization logic of its own; the install's security realm and
authorization strategy decide everything.

## Tools

| Tool | Description | Permission |
|------|-------------|------------|
| `getPipelineStages` | Returns a flat, bounded list of stages for a Pipeline run. | `Item.READ` |
| `getStageSteps` | Returns steps within a single stage of a Pipeline run, bounded by limit and offset. | `Item.READ` |
| `getStepLog` | Returns a byte-capped log excerpt for a single Pipeline step. | `Item.READ` |
| `getNodes` | Returns a bounded list of Jenkins nodes with basic status and cheap cached monitor data (disk and temp space). | `Jenkins.READ` |
| `getExecutorStatus` | Returns per-node executor occupancy and current-build reference. | `Jenkins.READ` |

## About Varroa

This plugin is developed by the [Varroa](https://varroa.dev) project, an
open platform for operating Jenkins with AI agents. Varroa is not a fork
of Jenkins — it layers on top, using standard extension points and
vendor-neutral APIs.
