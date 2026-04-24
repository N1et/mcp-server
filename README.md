# Burp Suite MCP Server Extension

## Overview

Integrate Burp Suite with AI Clients using the Model Context Protocol (MCP).

For more information about the protocol visit: [modelcontextprotocol.io](https://modelcontextprotocol.io/)

## Features

- Connect Burp Suite to AI clients through MCP
- Automatic installation for Claude Desktop
- Comes with packaged Stdio MCP proxy server

## Usage

- Install the extension in Burp Suite
- Configure your Burp MCP server in the extension settings
- Configure your MCP client to use the Burp SSE MCP server or stdio proxy
- Interact with Burp through your client!

## Installation

### Prerequisites

Ensure that the following prerequisites are met before building and installing the extension:

1. **Java**: Java must be installed and available in your system's PATH. You can verify this by running `java --version` in your terminal.
2. **jar Command**: The `jar` command must be executable and available in your system's PATH. You can verify this by running `jar --version` in your terminal. This is required for building and installing the extension.

### Building the Extension

1. **Clone the Repository**: Obtain the source code for the MCP Server Extension.
   ```
   git clone https://github.com/PortSwigger/mcp-server.git
   ```

2. **Navigate to the Project Directory**: Move into the project's root directory.
   ```
   cd mcp-server
   ```

3. **Build the JAR File**: Use Gradle to build the extension.
   ```
   ./gradlew embedProxyJar
   ```

   This command compiles the source code and packages it into a JAR file located in `build/libs/burp-mcp-all.jar`.

### Loading the Extension into Burp Suite

1. **Open Burp Suite**: Launch your Burp Suite application.
2. **Access the Extensions Tab**: Navigate to the `Extensions` tab.
3. **Add the Extension**:
    - Click on `Add`.
    - Set `Extension Type` to `Java`.
    - Click `Select file ...` and choose the JAR file built in the previous step.
    - Click `Next` to load the extension.

Upon successful loading, the MCP Server Extension will be active within Burp Suite.

## Configuration

### Configuring the Extension
Configuration for the extension is done through the Burp Suite UI in the `MCP` tab.
- **Toggle the MCP Server**: The `Enabled` checkbox controls whether the MCP server is active.
- **Enable config editing**: The `Enable tools that can edit your config` checkbox allows the MCP server to expose tools which can edit Burp configuration files.
- **Advanced options**: You can configure the port and host for the MCP server. By default, it listens on `http://127.0.0.1:9876`.

### Claude Desktop Client

To fully utilize the MCP Server Extension with Claude, you need to configure your Claude client settings appropriately.
The extension has an installer which will automatically configure the client settings for you.

1. Currently, Claude Desktop only support STDIO MCP Servers
   for the service it needs.
   This approach isn't ideal for desktop apps like Burp, so instead, Claude will start a proxy server that points to the
   Burp instance,  
   which hosts a web server at a known port (`localhost:9876`).

2. **Configure Claude to use the Burp MCP server**  
   You can do this in one of two ways:

    - **Option 1: Run the installer from the extension**
      This will add the Burp MCP server to the Claude Desktop config.

    - **Option 2: Manually edit the config file**  
      Open the file located at `~/Library/Application Support/Claude/claude_desktop_config.json`,
      and replace or update it with the following:
      ```json
      {
        "mcpServers": {
          "burp": {
            "command": "<path to Java executable packaged with Burp>",
            "args": [
                "-jar",
                "/path/to/mcp/proxy/jar/mcp-proxy-all.jar",
                "--sse-url",
                "<your Burp MCP server URL configured in the extension>"
            ]
          }
        }
      }
      ```

3. **Restart Claude Desktop** - assuming Burp is running with the extension loaded.

## Manual installations
If you want to install the MCP server manually you can either use the extension's SSE server directly or the packaged
Stdio proxy server.

### SSE MCP Server
In order to use the SSE server directly you can just provide the url for the server in your client's configuration. Depending
on your client and your configuration in the extension this may be with or without the `/sse` path.
```
http://127.0.0.1:9876
```
or
```
http://127.0.0.1:9876/sse
```

### Stdio MCP Proxy Server
The source code for the proxy server can be found here: [MCP Proxy Server](https://github.com/PortSwigger/mcp-proxy)

In order to support MCP Clients which only support Stdio MCP Servers, the extension comes packaged with a proxy server for
passing requests to the SSE MCP server extension.

If you want to use the Stdio proxy server you can use the extension's installer option to extract the proxy server jar.
Once you have the jar you can add the following command and args to your client configuration:
```
/path/to/packaged/burp/java -jar /path/to/proxy/jar/mcp-proxy-all.jar --sse-url http://127.0.0.1:9876
```

### Creating / modifying tools

Tools are defined in `src/main/kotlin/net/portswigger/mcp/tools/Tools.kt`. To define new tools, create a new serializable
data class with the required parameters which will come from the LLM.

The tool name is auto-derived from its parameters data class. A description is also needed for the LLM. You can return
a string (or richer PromptMessageContents) to provide data back to the LLM.

Extend the Paginated interface to add auto-pagination support.

# New Fork improvements

## Enhanced history navigation and querying capabilities:

Ordering Improvements:

Added newestFirst parameter (default: false) to enable reverse chronological browsing in:

GetProxyHttpHistory - browse HTTP history newest first
GetProxyHttpHistoryRegex - filter and reverse HTTP history
GetProxyWebsocketHistory - browse WebSocket history newest first
GetProxyWebsocketHistoryRegex - filter and reverse WebSocket history

New Counting Tools:

GetProxyHttpHistoryCount - returns total HTTP history count with optional regex filtering
GetProxyWebsocketHistoryCount - returns total WebSocket history count with optional regex filtering
Both tools support permission checks and optional regex patterns for targeted counting

# Capture Sessions

Isolate HTTP traffic per flow during AI-driven pentest automation. Instead of digging through the entire proxy history, create named sessions that record only the traffic generated during a specific action.

## MCP Functions Example 

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
