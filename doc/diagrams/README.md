# MCP Server Diagrams

This directory contains PlantUML diagrams documenting the MCP server architecture.

## Diagrams

### 1. [mcp-architecture.puml](mcp-architecture.puml)
**System architecture overview**

Shows the complete system structure including:
- MCP clients (Claude Desktop, Claude Code, Cursor)
- The two-tool design (get_capabilities + execute)
- Operation handlers (fact/list, fact/get, etc.)
- Data layer (SQLite database)

**To render:**
```bash
plantuml -tsvg mcp-architecture.puml
```

### 2. [sequence-discovery.puml](sequence-discovery.puml)
**Discovery and execution flow**

Sequence diagram showing:
1. MCP server initialization
2. How LLM discovers capabilities
3. Example fact creation flow

**To render:**
```bash
plantuml -tsvg sequence-discovery.puml
```

### 3. [data-flow.puml](data-flow.puml)
**Execute tool internal flow**

Activity diagram showing:
- JSON parsing
- Operation routing
- Parameter validation
- Error handling

**To render:**
```bash
plantuml -tsvg data-flow.puml
```

### 4. [component-structure.puml](component-structure.puml)
**Code organization**

Component diagram showing:
- Namespace structure
- Function relationships
- External dependencies

**To render:**
```bash
plantuml -tsvg component-structure.puml
```

## Rendering Options

### Option 1: VS Code Extension
Install the "PlantUML" extension by jebbs

### Option 2: Command Line
```bash
# Install PlantUML
brew install plantuml

# Generate PNG
plantuml -tpng *.puml

# Generate SVG
plantuml -tsvg *.puml

# Generate with dark theme
plantuml -tsvg -config plantuml-theme.puml *.puml
```

### Option 3: Online Renderer
Upload .puml files to: http://www.plantuml.com/plantuml

### Option 4: IntelliJ/PyCharm
Install the PlantUML plugin and open .puml files directly

## Diagram Conventions

- **Blue boxes (#LightBlue)**: Entry points / public API
- **Green boxes (#LightGreen)**: Core processing functions
- **Yellow boxes (#LightYellow)**: Routing / dispatch logic
- **Pink boxes (#LightPink)**: Data structures / documents
- **Gray boxes (#LightGray)**: Operation implementations

## Quick Preview

To quickly see diagram structure without rendering:

```bash
# View text description
plantuml -ttxt mcp-architecture.puml
```
