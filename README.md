# Burp Suite MCP Server Extension +++

## Overview

This is an enhanced fork of the original Burp MCP Server that adds:

- **Advanced history browsing** — Reverse chronological ordering and regex-based filtering for HTTP/WebSocket history
- **History counting tools** — Quick metrics without loading full history
- **Capture Sessions** — Isolate HTTP traffic per automated flow during AI-driven penetration testing

Instead of digging through entire proxy history, create named sessions that record only traffic from specific test flows.

## New Fork improvements

### Enhanced history navigation and querying capabilities:

Ordering Improvements:

Added newestFirst parameter (default: false) to enable reverse chronological browsing in:

- `GetProxyHttpHistory` - browse HTTP history newest first
- `GetProxyHttpHistoryRegex` - filter and reverse HTTP history
- `GetProxyWebsocketHistory` - browse WebSocket history newest first
- `GetProxyWebsocketHistoryRegex` - filter and reverse WebSocket history

New Counting Tools:

- `GetProxyHttpHistoryCount` - returns total HTTP history count with optional regex filtering
- `GetProxyWebsocketHistoryCount`  - returns total WebSocket history count with optional regex filtering
Both tools support permission checks and optional regex patterns for targeted counting

## Capture Sessions

Isolate HTTP traffic per flow during AI-driven pentest automation. Instead of digging through the entire proxy history, create named sessions that record only the traffic generated during a specific action.

### MCP Functions Example 

```
capture_start("login-flow")       # start recording
→ browser navigates/interacts     # traffic goes through Burp proxy
capture_stop("login-flow")        # stop recording
capture_summary("login-flow", excludeStaticAssets=true)   # structured overview
capture_get("login-flow", entryIndex=3)                   # fetch specific request
```

## MCP Tools

### Session Management

| Tool | Params | Description |
|------|--------|-------------|
| `capture_start` | `sessionId`, `excludeStaticAssets?`, `inScopeOnly?`, `maxRequestBytes?`, `maxResponseBytes?`, `maxEntries?` | Start or resume recording with a capture policy |
| `capture_stop` | `sessionId` | Stop recording, returns entry count |
| `capture_list` | — | List all sessions with status and count |
| `capture_delete` | `sessionId` | Delete session and its data |

### Analysis

| Tool | Params | Description |
|------|--------|-------------|
| `capture_summary` | `sessionId`, `excludeStaticAssets?` | Structured overview: endpoints with #index, methods, hosts, status codes. No raw data. |
| `capture_count` | `sessionId`, `excludeStaticAssets?` | Entry count only |
| `capture_get` | `sessionId`, `count?`, `offset?`, `excludeStaticAssets?`, `entryIndex?` | Fetch raw request/response data |
| `capture_stats` | `sessionId?` | Per-session counts, bytes, policy and truncation, or global disk usage; no request/response payloads |
| `capture_storage_cleanup` | `dryRun?` | Inspect orphaned payloads; defaults to `true` (no deletion) |

## Key Parameters

- **`excludeStaticAssets`** — Defaults to `true` on `capture_start`: static assets are not stored. On analysis tools it defaults to `false` and only filters already stored entries.
- **`inScopeOnly`** (default `false`) — Store only traffic matching Burp's target scope when enabled.
- **`maxRequestBytes` / `maxResponseBytes`** — Defaults: 2 MiB / 10 MiB. Larger messages are truncated in storage, not on the network. Accepted values: 1 KiB–100 MiB per message.
- **`maxEntries`** (default `100000`, range `1`–`1000000`) — Stop storing additional entries at this limit; traffic continues normally. Stats report filtered, dropped and truncated entries.
- **`entryIndex`** — Zero-based index shown in `capture_summary`; bypasses pagination. `capture_get` otherwise defaults to `count=10`, `offset=0`; count must be between `1` and `100`, and offset/index nonnegative.

## `capture_summary` Output Example

```
Session: login-flow (stopped, 4 entries, 4 relevant)
Storage: raw=8.00 KiB, stored=8.00 KiB, truncated=0, filtered=34, dropped=0
Policy: static=exclude, scope=all, requestLimit=2.00 MiB, responseLimit=10.00 MiB, entryLimit=100000

Endpoints:
  #0 GET /login → 200
  #1 POST /login → 303 (has body)
  #2 GET /dashboard → 200
  #3 GET /api/user/me → 200

Hosts: example.test:443 (4)
Methods: GET (3), POST (1)
Status codes: 200 (3), 303 (1)
```

The summary lists at most 1,000 endpoint entries, retaining original indices; an omission line reports additional entries. Counts and groupings still cover all matching metadata. MCP summary output is additionally truncated after 5,000 characters with `... (truncated)`. Use `capture_count`, `capture_stats` and paginated/indexed `capture_get` when the summary is incomplete.

## UI

The **Capture Sessions** tab appears under the MCP tab in Burp with:

- Session list with active/stopped indicator and entry count
- Sortable request table (#, Host, Method, URL, Status, Time)
- Native Burp request/response editors with syntax highlighting
- Right-click: Send to Repeater, Send to Intruder, Delete Request
- Live table updates during active capture
- Persistence across Burp restarts

<img width="2914" height="1612" alt="image" src="https://github.com/user-attachments/assets/076f98c5-0669-4eb1-b97e-dd8a0c1fd717" />

## Capture Storage and Backups

The Burp project contains a small v2 manifest. Entry metadata lives in external JSONL indexes; request/response pairs are SHA-256 content-addressed, gzip-compressed blobs. Identical stored pairs share a blob, but every capture entry remains recorded and network requests are never deduplicated. Payloads are loaded only when requested or selected in the UI; the side-by-side request/response view is preserved.

Storage defaults to `~/.BurpSuite/mcp-captures/<store-id>/`. Set the JVM property `-Dburp.mcp.capture.dir=/path/to/captures` before loading the extension to override the base directory. Keep existing store directories available when changing this setting.

**Back up the external storage together with the Burp project.** A `.burp` file, including **Project > Save copy**, does not embed the external indexes or payloads. Missing payloads cannot be reconstructed from the manifest. Treat the storage as sensitive: compression and hashing are not encryption.

Only one open project may own a given external store. A copied `.burp` file retains the same store ID and points to the same external data; it is not an independent capture snapshot. Opening another manager for that store is refused until the first owner closes. Close the active owner before backing up a matched project and external store.

Legacy in-project captures migrate automatically; the legacy data is removed only after the external data and v2 manifest have been saved successfully. Keep a pre-migration backup: reverting to the legacy extension does not automatically convert external storage back. Save copy may reclaim old project space after migration, but it does not replace an external backup.

Deleting an entry/session leaves shared blobs intact. Use `capture_storage_cleanup()` to inspect orphaned blobs without deletion. Run with `dryRun=false` only when reclamation is needed, after making a backup; routine destructive cleanup is unnecessary.

## Build

```bash
JAVA_HOME=$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home ./gradlew embedProxyJar
```

Output: `build/libs/burp-mcp-all.jar` → load in Burp via Extensions → Add.
