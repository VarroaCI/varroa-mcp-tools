# MCP Tools for Jenkins

A Jenkins plugin that extends the official
[mcp-server-plugin](https://github.com/jenkinsci/mcp-server-plugin) with
operational tools for MCP agents: pipeline stage/step introspection and
node/executor status (bounded test-result tools are planned future work).
Tools are served through the same
`/mcp-server/mcp` endpoint and run with the permissions of the
authenticated Jenkins user.

This plugin is vendor-neutral — it works on any Jenkins installation with the
mcp-server plugin and the Pipeline plugins it builds on (see Installing). It
ships no authentication or authorization logic of its own; the install's
security realm and authorization strategy decide everything.

## Tools

| Tool | Description | Permission |
|------|-------------|------------|
| `getPipelineStages` | Returns a flat, bounded list of stages for a Pipeline run. | `Item.READ` |
| `getStageSteps` | Returns steps within a single stage of a Pipeline run, bounded by limit and offset. | `Item.READ` |
| `getStepLog` | Returns a byte-capped log excerpt for a single Pipeline step. | `Item.READ` |
| `getNodes` | Returns a bounded list of Jenkins nodes with basic status and cheap cached monitor data (disk and temp space). | `Jenkins.READ` |
| `getExecutorStatus` | Returns per-node executor occupancy and current-build reference. | `Jenkins.READ` |
| `abortBuild` | Aborts a running build, the tool-call equivalent of the stop button on its build page. | `Item.CANCEL` |

## Installing

Download the latest `varroa-mcp-tools.hpi` from the
[GitHub Releases](https://github.com/varroaci/varroa-mcp-tools/releases) page,
then install it through the Jenkins UI: Manage Jenkins → Plugins → Advanced
settings → Deploy Plugin → upload the `.hpi` file, and restart Jenkins if
prompted.

Install the required plugins first. A manual `.hpi` upload does not resolve
dependencies, so Jenkins refuses to load the plugin if any are missing. This
plugin depends on:

- **mcp-server** — serves the tools and owns the `/mcp-server/mcp` endpoint.
- **pipeline-graph-analysis** and **workflow-api** — back the Pipeline
  introspection tools (`getPipelineStages`, `getStageSteps`, `getStepLog`).

Install each from Manage Jenkins → Plugins → Available before deploying the
`.hpi`; each in turn pulls its own dependencies through the update center. On a
Jenkins that already runs Pipeline jobs, `pipeline-graph-analysis` and
`workflow-api` are typically present already.

`.hpi` and `.jpi` are the same archive format; if your tooling expects a `.jpi`
extension, rename the downloaded file.

This is an interim distribution channel. Once the plugin is published to the
Jenkins update center, installation will move to Manage Jenkins → Plugins →
Available.

## Versioning

Releases follow semantic versioning (`MAJOR.MINOR.PATCH`), derived
automatically from [Conventional Commits](https://www.conventionalcommits.org/)
on `main`: `fix:` yields a patch, `feat:` a minor, and `feat!:` or a
`BREAKING CHANGE:` footer a major. A release is cut by merging the rolling
release pull request that automation keeps open.

The compatibility surface is the MCP tool contract and the platform floor:

- **Major** — removing or renaming a tool; removing a result field or changing
  a field's meaning, type, or units; changing a tool's required permissions;
  raising the Jenkins baseline in a way that drops supported LTS lines.
- **Minor** — new tools; new optional arguments; new result fields; routine
  baseline or BOM bumps that keep the current LTS support window.
- **Patch** — bug fixes, documentation fixes, and dependency updates with no
  change to the tool contract.

Result shapes evolve additively wherever possible, since agents parse them.

## About Varroa

This plugin is developed by the [Varroa](https://varroa.dev) project, an
open platform for operating Jenkins with AI agents. Varroa is not a fork
of Jenkins — it layers on top, using standard extension points and
vendor-neutral APIs.
