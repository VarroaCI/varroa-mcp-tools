# getStageSteps

Returns a flat, bounded list of steps within a single stage of a Pipeline run.

## Arguments

| Argument | Type | Required | Default | Description |
|----------|------|----------|---------|-------------|
| `jobFullName` | string | yes | — | Full name of the Pipeline job (e.g. `folder/my-job`) |
| `buildNumber` | integer | yes | — | Build number |
| `stageId` | string | yes | — | Stage ID (from a previous `getPipelineStages` call) |
| `limit` | integer | no | 20 | Maximum steps to return (hard cap 100) |
| `offset` | integer | no | 0 | Zero-based starting index |

## Result

```
{
  steps: [
    {
      stepId:       string,    // stable FlowNode ID (use as stepId in getStepLog)
      name:         string,    // step descriptor name (see three-tier fallback below)
      status:       string,    // GenericStatus enum name (e.g. "SUCCESS", "NOT_EXECUTED")
      durationMs:   number,    // total wall-clock duration of the step
      timestampMs:  number,    // epoch millis when the step started
      logAvailable: boolean    // true if the step node carries a LogAction
    },
    ...
  ],
  limit:      number,     // effective limit applied
  offset:     number,     // effective offset applied
  truncated:  boolean     // true if the full list has more entries than returned
}
```

The result does not echo `jobFullName`, `buildNumber`, or `stageId`, and does not
carry a `stepCount` field.

## Step name resolution (three-tier fallback)

Each step entry's `name` field is resolved using the following priority order:

1. `StepDescriptor.getFunctionName()` — the short function-call name (e.g.
   `echo`, `dir`, `sh`), captured from the `StepNode` interface.
2. `StepDescriptor.getDisplayName()` — the human-readable display name (e.g.
   "Echo", "Dir", "Shell Script"), used when the function name is empty or
   null.
3. `FlowNode.getDisplayName()` — the raw node-level display name, used only
   when the node does not implement `StepNode` (unusual).

This resolution applies per step entry and is independent of the stage-level
`name` resolution used by `getPipelineStages`.

## Block-scoped steps (not recursed)

Block-scoped pipeline steps such as `dir`, `withEnv`, `withCredentials`, and
`container` each appear as a **single entry** — their children are not
expanded. A `dir('subdir')` containing two `echo` steps produces three
entries in the step list (dir, echo, echo), not a nested tree.

This tool does **not** recurse into nested parallel branches or stages within
the same stage graph; the step list is a flat scan of nodes belonging to the
identified `MemoryFlowChunk`.

## Required permission

`Item.READ` on the target Pipeline job. Because this tool relies solely on
`Jenkins.getItemByFullName`'s implicit ACL filtering, a caller who lacks
`Item.READ` sees the same error message as a caller targeting a nonexistent
job — the tool never discloses a job's existence to an unauthorized caller.

## Example

Request:

```json
{
  "method": "tools/call",
  "params": {
    "name": "getStageSteps",
    "arguments": {
      "jobFullName": "my-folder/my-pipeline",
      "buildNumber": 42,
      "stageId": "15",
      "limit": 10
    }
  }
}
```

Response:

```json
{
  "steps": [
    {
      "stepId": "20",
      "name": "echo",
      "status": "SUCCESS",
      "durationMs": 15,
      "timestampMs": 1737043201523,
      "logAvailable": true
    },
    {
      "stepId": "24",
      "name": "dir",
      "status": "SUCCESS",
      "durationMs": 1200,
      "timestampMs": 1737043201540,
      "logAvailable": false
    },
    {
      "stepId": "28",
      "name": "echo",
      "status": "SUCCESS",
      "durationMs": 12,
      "timestampMs": 1737043202740,
      "logAvailable": true
    }
  ],
  "limit": 10,
  "offset": 0,
  "truncated": false
}
```
