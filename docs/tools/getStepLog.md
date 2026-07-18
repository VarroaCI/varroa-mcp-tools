# getStepLog

Returns a byte-capped log excerpt for a single Pipeline step.

## Arguments

| Argument | Type | Required | Default | Description |
|----------|------|----------|---------|-------------|
| `jobFullName` | string | yes | — | Full name of the Pipeline job (e.g. `folder/my-job`) |
| `buildNumber` | integer | yes | — | Build number |
| `stepId` | string | yes | — | Step ID (from a previous `getStageSteps` call) |
| `maxBytes` | integer | no | 51200 | Maximum bytes to read (hard cap 1048576) |

## Result

```
{
  stepId:       string,    // echoed from the request
  logText:      string,    // the log excerpt (UTF-8, malformed bytes replaced)
  byteLength:   number,    // actual byte count of logText
  truncated:    boolean,   // true if the full log is longer than byteLength
  logAvailable: boolean    // true if the step node carries a LogAction
}
```

## Byte capping semantics

| Condition | Behaviour |
|-----------|-----------|
| `maxBytes` not set | Defaults to 51200 (50 KB) |
| `maxBytes <= 0` | Rejected with `IllegalArgumentException` |
| `maxBytes > 1048576` (1 MiB) | Silently clamped to 1048576 |
| `maxBytes` between 1 and 1048576 | Applied as-is |

The hard cap of 1048576 bytes (1 MiB) is a safety limit: any request larger
than this is clamped before reading, not rejected. The tool never reads more
than the clamped value from the underlying `AnnotatedLargeText` stream,
regardless of the actual log size.

## Behaviour when no LogAction is present

When the resolved `FlowNode` exists but carries no `LogAction` (typical for
block-scoped steps such as `dir` or `withEnv`, or for alternative
step-atom implementations that do not emit a log), the tool returns a
**non-error** response with the following shape:

```json
{
  "stepId": "<stepId>",
  "logText": "",
  "byteLength": 0,
  "truncated": false,
  "logAvailable": false
}
```

This is not an error condition — the step simply produced no log output.
Callers should check `logAvailable` before treating `logText` as meaningful.

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
    "name": "getStepLog",
    "arguments": {
      "jobFullName": "my-folder/my-pipeline",
      "buildNumber": 42,
      "stepId": "20",
      "maxBytes": 1024
    }
  }
}
```

Response:

```json
{
  "stepId": "20",
  "logText": "+ echo 'Hello from the Build stage'\nHello from the Build stage\n",
  "byteLength": 55,
  "truncated": false,
  "logAvailable": true
}
```
