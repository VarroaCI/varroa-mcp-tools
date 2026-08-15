# abortBuild

Aborts a running build — the tool-call equivalent of the stop button on a
build page. Works for any build, not only Pipeline runs.

## Arguments

| Argument | Type | Required | Default | Description |
|----------|------|----------|---------|-------------|
| `jobFullName` | string | yes | — | Full name of the job (e.g. `folder/my-job`) |
| `buildNumber` | integer | yes | — | Build number |
| `confirm` | boolean | yes | — | Must be `true`; aborting destroys in-progress work |

`confirm` is checked before anything is resolved, so a call that forgot it
fails with a message about the confirmation rather than one about the job.

## Result

```
{
  jobFullName: string,        // Run.getParent().getFullName()
  buildNumber: number,        // Run.getNumber()
  aborted:     boolean,       // whether an interrupt was accepted; false in the
                              // three no-op cases listed below
  reason:      string,        // why, in the false case
  building:    boolean,       // Run.isBuilding(), read after the request
  result:      string|null    // Run.getResult(), read alongside it
}
```

## The abort is asynchronous

The tool requests an interrupt and returns immediately. `aborted: true` means
the abort was **accepted**, not that the build has stopped: the run still has
to unwind its steps and post-build actions. A step that catches the interruption and
returns normally can still finish the run at its own result — the interrupt is
a request to the running code, and the tool does not override what that code
decides. Expect `building: true` and
`result: null` in the same response that reports `aborted: true`. Poll the
build afterwards if you need to see it reach `ABORTED`.

`aborted` is `false`, with no error, in three cases:

| `reason` | Meaning |
|----------|---------|
| `build is not running` | The build already finished — including the case where it finished while the call was in flight. `result` carries its outcome. |
| `build is running but holds no executor` | The run is between states — started but not yet attached to an executor, or already past its last step. Both are brief; retry shortly. |
| `build finished before the abort took effect` | The build completed on its own in the moment between being resolved and the interrupt landing. `result` carries the outcome it reached. |

A Pipeline paused on an `input` step keeps its executor and is aborted
normally, so it does not produce the second case.

When an interrupt is issued, `aborted` is computed from the `building` and
`result` fields the response carries, so those three cannot disagree however the
build and the call interleave: it reports what the build did, not merely that an
interrupt was sent. In particular `aborted` is never `true` alongside a
**completed** non-`ABORTED` `result`.

The three `reason` cases above are the exception, because no interrupt is issued
in any of them: they always report `aborted: false`. `build is running but holds
no executor` is the one where that is visible — it can return `aborted: false`
with `building: true`, which is the honest answer, since nothing was asked to
stop.

Note that a result alone does not mean the build is over — a run marked
`UNSTABLE` mid-flight still has work to do, and aborting it there is a real
abort.

## Required permission

`Item.CANCEL` (shown as **Job/Cancel** in the matrix), the permission Jenkins
already requires for the stop button. The check runs against the job before
the build is resolved, so a caller who lacks it gets a permission error rather
than a build-not-found one.

A job the caller cannot read is reported as `No such job`, the same as a job
that does not exist — the tool never reveals the existence of jobs the caller
cannot see.

## Example

Request:

```json
{
  "method": "tools/call",
  "params": {
    "name": "abortBuild",
    "arguments": {
      "jobFullName": "my-folder/my-job",
      "buildNumber": 42,
      "confirm": true
    }
  }
}
```

Response:

```json
{
  "jobFullName": "my-folder/my-job",
  "buildNumber": 42,
  "aborted": true,
  "reason": "abort requested",
  "building": true,
  "result": null
}
```
