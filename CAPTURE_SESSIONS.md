# Capture Sessions

Isolate HTTP traffic per flow during AI-driven pentest automation. Instead of digging through the entire proxy history, create named sessions that record only the traffic generated during a specific action.

## Quick Start

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
| `capture_stats` | `sessionId?` | Session counts, bytes, policy and truncation, or global disk usage; no request/response payloads |
| `capture_storage_cleanup` | `dryRun?` | Inspect orphaned blobs; `true` by default, so nothing is deleted |

### Proxy History Extensions

| Tool | Params | Description |
|------|--------|-------------|
| `get_proxy_http_history_count` | — | Total items in Burp HTTP history |
| `get_proxy_http_history` | `count`, `offset`, `reverse?` | Fetch history (set `reverse=true` for most recent first) |

## Key Parameters

- **`excludeStaticAssets`** — Defaults to `true` for `capture_start`, excluding static assets before storage. Analysis tools default to `false`; their filter only hides already stored JS, CSS, images, fonts and other recognized static paths.
- **`inScopeOnly`** (default `false`) — Only store traffic matching Burp's target scope when enabled.
- **`maxRequestBytes` / `maxResponseBytes`** — Default to 2 MiB / 10 MiB. Each accepts 1 KiB–100 MiB. Larger messages are truncated in storage; network traffic is unchanged.
- **`maxEntries`** — Default `100000`, range `1`–`1000000`. Additional entries are dropped from storage at this limit. `capture_stats` exposes filtered, dropped and truncated counts.
- **`entryIndex`** — Zero-based index from `capture_summary`, bypassing pagination. Otherwise `capture_get` defaults to `count=10`, `offset=0`; count must be between `1` and `100`, and offset/index nonnegative. Missing payloads are reported as errors, never silently skipped.

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

Summary output lists at most 1,000 endpoint entries with their original indices; an omission line reports any additional entries. Counts and groupings cover all matching metadata. The MCP tool also truncates the summary after 5,000 characters, adding `... (truncated)`. Use `capture_count`, `capture_stats` and paginated/indexed `capture_get` to inspect sessions beyond those output limits.

## UI

The **Capture Sessions** tab appears under the MCP tab in Burp with:

- Session list with active/stopped indicator and entry count
- Sortable request table (#, Host, Method, URL, Status, Time)
- Native Burp request/response editors with syntax highlighting
- Right-click: Send to Repeater, Send to Intruder, Delete Request
- Live table updates during active capture
- Persistence across Burp restarts

<img width="2914" height="1612" alt="image" src="https://github.com/user-attachments/assets/076f98c5-0669-4eb1-b97e-dd8a0c1fd717" />

## External Storage, Migration and Backups

Only a small v2 manifest stays in the Burp project. Metadata is stored in external JSONL indexes, and request/response payload pairs are stored as SHA-256-addressed gzip blobs. Identical stored pairs share a blob; this does not deduplicate capture entries or network requests. Summary/count tools use metadata; the UI loads payloads on selection and retains the side-by-side request/response editors.

The default location is `~/.BurpSuite/mcp-captures/<store-id>/`. Override the base directory with the JVM property `-Dburp.mcp.capture.dir=/path/to/captures` before loading the extension. Preserve the existing store directories when moving storage.

**External backups are mandatory if you need to retain captures.** Back up the `.burp` project and its external store together. **Project > Save copy does not include external storage.** The project manifest alone cannot restore missing indexes or payloads. Store backups securely: gzip compression and SHA-256 addressing do not encrypt traffic.

Each external store permits only one open project owner. Copies of a `.burp` project retain its store ID and reference the same external files, not an independent snapshot. A second manager is refused until the first owner closes. Close the active owner before taking a matched backup of the project and its external store.

Existing in-project captures migrate automatically. Legacy data is deleted only after external storage and the v2 manifest have been saved successfully; migration failures are explicit and retain the original legacy data. Keep a pre-migration project backup. Downgrading to the legacy extension does not automatically migrate external captures back into the project. Save copy can reclaim old project space after successful migration, but still requires a separate external backup.

Deleting entries or sessions may leave orphaned blobs because other entries can share payloads. `capture_storage_cleanup()` is a safe dry-run by default. Review its report and back up storage before using `dryRun=false`; do not run destructive cleanup unless disk reclamation is actually needed.


## Build

```bash
JAVA_HOME=$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home ./gradlew embedProxyJar
```

Output: `build/libs/burp-mcp-all.jar` → load in Burp via Extensions → Add.

## Playwright Proxy Setup

CLI flags don't work for localhost. Use `--config` with a JSON file:

```json
{
  "browser": {
    "browserName": "chromium",
    "launchOptions": {
      "proxy": { "server": "http://127.0.0.1:8080" }
    }
  },
  "ignoreHttpsErrors": true
}
```

```bash
npx -y @playwright/mcp@latest --config /path/to/playwright-mcp.config.json
```
