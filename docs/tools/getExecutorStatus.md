# getExecutorStatus

Returns per-node executor occupancy and current-build reference data.

## Arguments

| Argument | Type | Required | Default | Description |
|----------|------|----------|---------|-------------|
| `nodeName` | string | no | — | Restrict to a single node by name; omit for all nodes |
| `limit` | integer | no | 20 | Maximum nodes to return when `nodeName` is omitted (hard cap 100) |
| `offset` | integer | no | 0 | Zero-based starting index when `nodeName` is omitted |

When `nodeName` is provided, the result contains exactly one node entry
(if found) and `truncated` is always `false`; `limit` and `offset` are echoed
but not applied as bounding parameters.

When `nodeName` is omitted, the result is a bounded page across all computers.

## Result

```
{
  nodes: [
    {
      name:      string,    // Computer.getName()
      executors: [
        {
          index:         number,             // 0-based position in Computer.getExecutors()
          busy:          boolean,            // Executor.isBusy()
          currentBuild:  object|null         // null when idle or when the current executable is not a Run
          // currentBuild (present when busy and running a Run):
          //   jobFullName:      string    // Run.getParent().getFullName()
          //   buildNumber:      number    // Run.getNumber()
          //   progressPercent:  number    // Executor.getProgress() (-1 when unknown)
          //   durationMs:       number    // Executor.getElapsedTime()
        },
        ...
      ]
    },
    ...
  ],
  limit:      number,     // effective limit applied
  offset:     number,     // effective offset applied
  truncated:  boolean     // true when nodeName is omitted and more nodes exist
}
```

## OneOffExecutor exclusion

The tool reports only `Computer.getExecutors()` — regular (persistent)
executors. `Computer.getOneOffExecutors()` (one-shot executors used by
Pipeline flyweight tasks and similar short-lived operations) are not
included. This is by design: one-off executors are transient, do not
represent a long-running build slot, and are outside the scope of this tool.

## Required permission

`Jenkins.READ`. The check is performed once per call against the root Jenkins
object; no per-node permission check is applied. A caller who holds
`Jenkins.READ` sees every node (the same set as `/computer/api/json`).

## Example

Request:

```json
{
  "method": "tools/call",
  "params": {
    "name": "getExecutorStatus",
    "arguments": {
      "nodeName": "linux-agent-1"
    }
  }
}
```

Response:

```json
{
  "nodes": [
    {
      "name": "linux-agent-1",
      "executors": [
        {
          "index": 0,
          "busy": true,
          "currentBuild": {
            "jobFullName": "my-folder/my-job",
            "buildNumber": 42,
            "progressPercent": 65,
            "durationMs": 1234000
          }
        },
        {
          "index": 1,
          "busy": false,
          "currentBuild": null
        }
      ]
    }
  ],
  "limit": 20,
  "offset": 0,
  "truncated": false
}
```
