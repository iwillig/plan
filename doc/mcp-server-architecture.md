# MCP Server Architecture

This document describes the capabilities-based MCP server design for the planning tool.

## Overview

The planning tool MCP server uses an innovative **two-tool design** that prioritizes discoverability and elegance over the traditional "one tool per operation" approach.

## Design Philosophy

### Traditional MCP Design (5+ tools)

```
list_facts, get_fact, create_fact, update_fact, delete_fact...
```

**Problems:**
- Tool clutter in MCP client UI
- Hard to extend without client changes
- No self-discovery mechanism

### Capabilities-Based Design (2 tools)

```
get_capabilities + execute
```

**Benefits:**
- Clean, minimal tool interface
- Self-documenting via capabilities document
- Dynamic operation discovery
- Easy to extend (just add to capabilities)
- Natural RPC pattern LLMs understand

## Architecture Diagram

```plantuml
@startuml
!theme plain
skinparam backgroundColor #FEFEFE
skinparam componentStyle rectangle

package "MCP Client" {
    [Claude/Cursor/etc] as Client
}

package "Planning Tool MCP Server" {
    [get_capabilities] as CapTool
    [execute] as ExecTool
    [RPC Router] as Router
    
    package "Operation Handlers" {
        [fact/list] as ListOp
        [fact/get] as GetOp
        [fact/create] as CreateOp
        [fact/update] as UpdateOp
        [fact/delete] as DeleteOp
    }
    
    [Capabilities Document] as CapDoc
}

package "Data Layer" {
    [Database] as DB
    [plan.models.fact] as FactModel
}

Client --> CapTool : "1. Query capabilities"
Client --> ExecTool : "2. Execute operation"

CapTool --> CapDoc : Returns
ExecTool --> Router : Routes request

Router --> ListOp : "operation: fact/list"
Router --> GetOp : "operation: fact/get"
Router --> CreateOp : "operation: fact/create"
Router --> UpdateOp : "operation: fact/update"
Router --> DeleteOp : "operation: fact/delete"

ListOp --> DB
GetOp --> DB
CreateOp --> DB
UpdateOp --> DB
DeleteOp --> DB

DB --> FactModel

@enduml
```

## Sequence Diagrams

### Initial Discovery Flow

```plantuml
@startuml
!theme plain
actor LLM
participant "MCP Client" as Client
participant "plan mcp start" as Server

LLM -> Client: Connect to MCP server
activate Client

Client -> Server: Initialize connection
activate Server
Server --> Client: Connection established

LLM -> Client: "What can you do?"
Client -> Server: get_capabilities()
Server --> Client: Capabilities JSON

Client --> LLM: Here's the API...
deactivate Server

deactivate Client

@enduml
```

### Fact Creation Flow

```plantuml
@startuml
!theme plain
actor User
participant "MCP Client" as Client
participant "plan mcp start" as Server
database SQLite

User -> Client: "Create a fact about the API endpoint"
activate Client

Client -> Server: execute({
  "operation": "fact/create",
  "plan_id": 1,
  "name": "API URL",
  "content": "https://api.example.com/v1"
})
activate Server

Server -> Server: Validate parameters

alt Validation fails
    Server --> Client: Error response
else Validation passes
    Server -> SQLite: INSERT INTO facts
    activate SQLite
    SQLite --> Server: New fact record
    deactivate SQLite
    
    Server --> Client: Success response
end

deactivate Server

Client --> User: "Created fact 'API URL' (ID: 42)"

deactivate Client

@enduml
```

### Error Handling Flow

```plantuml
@startuml
!theme plain
actor User
participant "MCP Client" as Client
participant "plan mcp start" as Server

User -> Client: "Get fact 9999"
activate Client

Client -> Server: execute({
  "operation": "fact/get",
  "fact_id": 9999
})
activate Server

Server -> Server: Query database
Server -> Server: Fact not found

Server --> Client: {
  "status": "error",
  "operation": "fact/get",
  "message": "Fact not found: 9999"
}
deactivate Server

Client --> User: "Fact not found"

deactivate Client

@enduml
```

## Capabilities Document Structure

```plantuml
@startuml
!theme plain
object "Capabilities Document" as CapDoc {
  version = "1.0.0"
  description = "Plan Management System"
}

object "Operations Map" as Ops {
  :fact/list
  :fact/get
  :fact/create
  :fact/update
  :fact/delete
}

object "fact/create Operation" as CreateOp {
  description = "Create a new fact"
  parameters = {plan_id, name, ...}
  returns = "Created fact object"
}

object "Parameter Spec" as ParamSpec {
  plan_id = {type: "integer", required: true}
  name = {type: "string", required: true}
  description = {type: "string", required: false}
  content = {type: "string", required: true}
}

object "Examples" as Examples {
  [Example 1]
  [Example 2]
  [Example 3]
}

CapDoc --> Ops : contains
Ops --> CreateOp : includes
CreateOp --> ParamSpec : has parameters
CreateOp --> Examples : includes

@enduml
```

## Data Flow

```plantuml
@startuml
!theme plain
skinparam backgroundColor #FEFEFE

start

:LLM sends request;
:Parse JSON request;
:Extract operation & params;

switch (operation?)
case (fact/list)
  :Validate plan_id;
  :Query facts by plan;
case (fact/get)
  :Validate fact_id;
  :Fetch single fact;
case (fact/create)
  :Validate required fields;
  :INSERT new fact;
case (fact/update)
  :Validate fact_id;
  :Validate at least one field;
  :UPDATE fact;
case (fact/delete)
  :Validate fact_id;
  :DELETE fact;
case (unknown)
  :Return error;
  stop
endswitch

:Format response;
:Return JSON result;

stop
@enduml
```

## Tool Definitions

### Tool 1: get_capabilities

| Property | Value |
|----------|-------|
| Name | `get_capabilities` |
| Description | Returns complete API specification |
| Parameters | None |
| Returns | JSON capabilities document |

**Purpose:** Self-discovery mechanism for LLM agents

### Tool 2: execute

| Property | Value |
|----------|-------|
| Name | `execute` |
| Description | Execute any operation via RPC |
| Required Parameter | `request` (JSON string) |
| Returns | JSON operation result |

**Request Format:**
```json
{
  "operation": "fact/create",
  "plan_id": 1,
  "name": "API URL",
  "content": "https://api.example.com/v1"
}
```

**Response Format:**
```json
{
  "status": "success",
  "operation": "fact/create",
  "data": {
    "id": 42,
    "plan_id": 1,
    "name": "API URL",
    "content": "https://api.example.com/v1",
    "created_at": "2025-01-30T...",
    "updated_at": "2025-01-30T..."
  }
}
```

## Supported Operations

| Operation | Description | Required Parameters |
|-----------|-------------|---------------------|
| `fact/list` | List all facts for a plan | `plan_id` |
| `fact/get` | Get fact by ID | `fact_id` |
| `fact/create` | Create new fact | `plan_id`, `name`, `content` |
| `fact/update` | Update existing fact | `fact_id` + at least one field |
| `fact/delete` | Delete fact by ID | `fact_id` |

## Benefits

### For LLM Agents
1. **Discoverability**: Query capabilities anytime to understand available operations
2. **Type Safety**: Full parameter schemas with types and required flags
3. **Examples**: Working examples for each operation
4. **Single Interface**: One tool to learn, many operations to use

### For Developers
1. **Minimal Surface Area**: Only 2 MCP tools to maintain
2. **Easy Extension**: Add operations without client changes
3. **Versioned**: Capabilities include version for compatibility
4. **Self-Testing**: Capabilities serve as living documentation

### For Users
1. **Clean UI**: No tool clutter in MCP client
2. **Consistent**: All operations use same interface pattern
3. **Transparent**: Can see exactly what the LLM can do

## CLI Commands

```bash
# Show MCP info
plan mcp info

# Start MCP server
plan mcp start

# Start with explicit nREPL port
plan mcp start --port 7889
```

## Client Configuration Example

### Claude Code
```bash
claude mcp add plan -- ./plan mcp start
```

### Claude Desktop (claude_desktop_config.json)
```json
{
  "mcpServers": {
    "plan": {
      "command": "/path/to/plan",
      "args": ["mcp", "start"]
    }
  }
}
```

## Future Extensions

The capabilities-based design makes it easy to add:

- Task operations (task/list, task/create, etc.)
- Plan operations (plan/list, plan/create, etc.)
- Search operations (search/query)
- Lesson operations (lesson/add, lesson/validate)

Simply add to the capabilities document and implement the handler!
