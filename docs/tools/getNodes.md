# getNodes

Returns a bounded list of Jenkins nodes with basic status and cheap cached
monitor data (disk and temporary space).

## Arguments

| Argument | Type | Required | Default | Description |
|----------|------|----------|---------|-------------|
| `limit` | integer | no | 20 | Maximum nodes to return (hard cap 100) |
| `offset` | integer | no | 0 | Zero-based starting index |

## Result

```
{
  nodes: [
    {
      name:          string,     // Computer.getName()
      displayName:   string,     // Computer.getDisplayName()
      description:   string,     // Node.getNodeDescription() ("" when unavailable)
      labels:        string[],   // sorted LabelAtom names ([] when node unavailable)
      online:        boolean,    // Computer.isOnline()
      offlineCause:  string|null,// null when online, reason text when offline
      numExecutors:  number,     // Computer.getNumExecutors()
      diskSpaceMb:   number|null,// cached DiskSpaceMonitor reading in MB, null when offline or unavailable
      tempSpaceMb:   number|null // cached TemporarySpaceMonitor reading in MB, null when offline or unavailable
    },
    ...
  ],
  limit:      number,     // effective limit applied
  offset:     number,     // effective offset applied
  truncated:  boolean     // true if the full node list has more entries than returned
}
```

## Disk and temp space staleness

The `diskSpaceMb` and `tempSpaceMb` fields are read from the cached monitor
state only — the tool never triggers a blocking remote round-trip to the
agent. The cached values can lag actual disk state by up to the monitor's
configured interval (default approximately one hour for `DiskSpaceMonitor`).
A `null` value means no cached data is available yet (the agent may have just
connected, or the periodic monitor thread has not yet run).

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
    "name": "getNodes",
    "arguments": {
      "limit": 5
    }
  }
}
```

Response:

```json
{
  "nodes": [
    {
      "name": "",
      "displayName": "built-in node",
      "description": "the Jenkins controller",
      "labels": ["built-in", "master"],
      "online": true,
      "offlineCause": null,
      "numExecutors": 2,
      "diskSpaceMb": 51200,
      "tempSpaceMb": 102400
    },
    {
      "name": "linux-agent-1",
      "displayName": "Linux Agent 1",
      "description": "",
      "labels": ["linux"],
      "online": true,
      "offlineCause": null,
      "numExecutors": 4,
      "diskSpaceMb": 204800,
      "tempSpaceMb": 409600
    }
  ],
  "limit": 5,
  "offset": 0,
  "truncated": false
}
```
