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
| `capture_start` | `sessionId` | Start recording HTTP traffic under this ID |
| `capture_stop` | `sessionId` | Stop recording, returns entry count |
| `capture_list` | — | List all sessions with status and count |
| `capture_delete` | `sessionId` | Delete session and its data |

### Analysis

| Tool | Params | Description |
|------|--------|-------------|
| `capture_summary` | `sessionId`, `excludeStaticAssets?` | Structured overview: endpoints with #index, methods, hosts, status codes. No raw data. |
| `capture_count` | `sessionId`, `excludeStaticAssets?` | Entry count only |
| `capture_get` | `sessionId`, `count?`, `offset?`, `excludeStaticAssets?`, `entryIndex?` | Fetch raw request/response data |

### Proxy History Extensions

| Tool | Params | Description |
|------|--------|-------------|
| `get_proxy_http_history_count` | — | Total items in Burp HTTP history |
| `get_proxy_http_history` | `count`, `offset`, `reverse?` | Fetch history (set `reverse=true` for most recent first) |

## Key Parameters

- **`excludeStaticAssets`** (default `false`) — Filters out JS, CSS, images, fonts, webmanifest, favicon, robots.txt. Typically reduces 40+ requests to 3-5 relevant ones.
- **`entryIndex`** — Fetch a specific entry by its index (as shown in `capture_summary` output). Bypasses pagination.

## `capture_summary` Output Example

```
Session: login-flow (stopped, 38 entries, 4 relevant)

Endpoints:
  #0 GET /login → 200
  #12 POST /login → 303 (has body)
  #13 GET /dashboard → 200
  #14 GET /api/user/me → 200 (has body)

Hosts: localhost:7331 (4)
Methods: GET (3), POST (1)
Status codes: 200 (3), 303 (1)
```

## UI

The **Capture Sessions** tab appears under the MCP tab in Burp with:

- Session list with active/stopped indicator and entry count
- Sortable request table (#, Host, Method, URL, Status, Time)
- Native Burp request/response editors with syntax highlighting
- Right-click: Send to Repeater, Send to Intruder, Delete Request
- Live table updates during active capture
- Persistence across Burp restarts

<img width="2914" height="1612" alt="image" src="https://github.com/user-attachments/assets/076f98c5-0669-4eb1-b97e-dd8a0c1fd717" />


## Build

```bash
JAVA_HOME=$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home ./gradlew shadowJar
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
