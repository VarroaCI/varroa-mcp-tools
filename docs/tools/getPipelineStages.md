# getPipelineStages

Returns a flat, bounded list of stages for a Pipeline run.

## Arguments

| Argument | Type | Required | Default | Description |
|----------|------|----------|---------|-------------|
| `jobFullName` | string | yes | — | Full name of the Pipeline job (e.g. `folder/my-job`) |
| `buildNumber` | integer | yes | — | Build number |
| `limit` | integer | no | 20 | Maximum stages to return (hard cap 100) |
| `offset` | integer | no | 0 | Zero-based starting index |

## Result

```
{
  stages: [
    {
      stageId:     string,    // stable FlowNode ID (use as stageId in getStageSteps)
      name:        string,    // stage LabelAction display name
      status:      string,    // GenericStatus enum name (e.g. "SUCCESS", "FAILED")
      durationMs:  number,    // total wall-clock duration of the stage
      timestampMs: number     // epoch millis when the stage started
    },
    ...
  ],
  limit:      number,     // effective limit applied
  offset:     number,     // effective offset applied
  truncated:  boolean     // true if the full list has more entries than returned
}
```

The result does not echo `jobFullName` or `buildNumber`, and does not carry a
`stageCount` field.

## Stage ordering and parallel branches

Stages are returned as a flat list in execution/document order (the order
`ForkScanner` visits the flow graph, depth-first over recorded nodes — not
necessarily wall-clock order for parallel branches). Stages nested inside
`parallel` blocks appear as peer entries with no parent/child or
branch-membership field. An agent that needs to know which parallel branch a
stage belongs to must infer it from the stage name or from the ordering
relative to other known stages.

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
    "name": "getPipelineStages",
    "arguments": {
      "jobFullName": "my-folder/my-pipeline",
      "buildNumber": 42,
      "limit": 5
    }
  }
}
```

Response:

```json
{
  "stages": [
    {
      "stageId": "12",
      "name": "Checkout",
      "status": "SUCCESS",
      "durationMs": 1523,
      "timestampMs": 1737043200000
    },
    {
      "stageId": "15",
      "name": "Build",
      "status": "SUCCESS",
      "durationMs": 45120,
      "timestampMs": 1737043201523
    }
  ],
  "limit": 5,
  "offset": 0,
  "truncated": false
}
```
