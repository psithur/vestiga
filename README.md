# vestiga

Local, offline code intelligence for Clojure codebases. Indexes source code with clj-kondo, stores metadata in SQLite with FTS5, and provides hybrid BM25 search via CLI tools or MCP server. Includes an AST-targeted edit engine for surgical source rewriting by qualified symbol name.

## Prerequisites

| Binary | Purpose | Required |
|--------|---------|----------|
| `clj-kondo` | Clojure static analysis | Yes |
| `git` | Version history indexing | Yes |
| `ollama` | Embedding model inference | Yes (for semantic search) |
| Java 21+ | Runtime | Yes |
| Clojure CLI (`clj`) | Build & run | Yes |

## Quick Start

```bash
# Index a Clojure project
vestiga index -p /path/to/project

# Search indexed code
vestiga search "handle request"

# Find all callers of a function
vestiga refs my.app.core/handler

# Find namespace dependents
vestiga deps my.app.db

# Impact analysis before a refactor
vestiga impact my.app.core/handler

# Search git history
vestiga history "authentication"
```

## CLI Reference

```
vestiga <command> [options] [args]

Commands:
  deps       Find all namespaces that depend on a namespace
  history    Search git commit history
  impact     Analyse impact of changing a symbol
  index      Index a project for searching
  mcp        Start the MCP JSON-RPC server (for AI tool integration)
  refs       Find all references to a symbol
  search     Search indexed code

Run 'vestiga <command> --help' for command-specific options.
```

### vestiga search

```bash
vestiga search [opts] <query>
  -d, --db PATH         Database path (default: .vestiga/db.sqlite)
  -l, --limit N         Max results (default: 20)
  -k, --kind KIND       Filter by symbol kind (defn, defmacro, defprotocol, etc.)
  -n, --namespace NS    Filter by namespace (supports * glob)

# Examples
vestiga search "parse request"
vestiga search -k defn "validate"
vestiga search -n "my.app.*" "config"
```

### vestiga refs

```bash
vestiga refs [opts] <qualified-name>
  -d, --db PATH         Database path

# Examples
vestiga refs my.app.core/handle-request
```

### vestiga deps

```bash
vestiga deps [opts] <namespace>
  -d, --db PATH         Database path

# Examples
vestiga deps my.app.db
```

### vestiga impact

```bash
vestiga impact [opts] <qualified-name>
  -d, --db PATH         Database path

# Shows: callers, namespace dependents, recent git commits
vestiga impact my.app.core/handle-request
```

### vestiga history

```bash
vestiga history [opts] <query>
  -d, --db PATH         Database path
  -l, --limit N         Max results (default: 20)
  -f, --file PATH       Filter to commits touching this file

# Examples
vestiga history "authentication"
vestiga history -f src/my/app/auth.clj "fix"
```

### vestiga index

```bash
vestiga index [opts]
  -p, --project-root PATH  Project root directory (default: .)
  -d, --db PATH            Database path (default: <project-root>/.vestiga/db.sqlite)
  -f, --full               Force full re-index

# Examples
vestiga index -p /path/to/project
vestiga index -p . -f                # full re-index
```

### vestiga mcp

```bash
vestiga mcp [opts]
  -d, --db PATH         Database path (default: .vestiga/db.sqlite)
```

Starts the MCP JSON-RPC server over stdio. Used by AI tools (Claude Code, etc.).

## MCP Integration

Add to `.mcp.json` in your project root:

```json
{
  "mcpServers": {
    "vestiga": {
      "command": "/path/to/vestiga",
      "args": ["mcp"]
    }
  }
}
```

Or for development:

```json
{
  "mcpServers": {
    "vestiga": {
      "command": "clj",
      "args": ["-M:dev", "-m", "vestiga.server.core", "mcp"],
      "cwd": "/path/to/vestiga"
    }
  }
}
```

### MCP Tools

| Tool | CLI equivalent |
|------|---------------|
| `search_code` | `vestiga search` |
| `find_references` | `vestiga refs` |
| `find_dependents` | `vestiga deps` |
| `impact_analysis` | `vestiga impact` |
| `search_history` | `vestiga history` |
| `index_project` | `vestiga index` |
| `edit_code` | — (MCP only) |

### edit_code

The `edit_code` tool applies AST-targeted edits to Clojure source files. Edits target definitions by namespace-qualified name rather than by line number or text matching, eliminating transcription errors.

**Operations:**

| Operation | Target | Description |
|-----------|--------|-------------|
| `replace_form` | `my.ns/my-fn` | Replace an entire top-level form |
| `replace_body` | `my.ns/my-fn` | Replace only the body of a single-arity defn (preserves name, arglist, docstring) |
| `add_form_before` | `my.ns/my-fn` | Insert a new form before the target |
| `add_form_after` | `my.ns/my-fn` | Insert a new form after the target |
| `delete_form` | `my.ns/my-fn` | Remove a top-level form |
| `add_require` | `clojure.string :as str` | Add a `:require` clause to the ns form (idempotent) |
| `replace_ns` | — | Replace the entire `(ns ...)` form |
| `append_to_ns` | `my.ns` | Append a new form at the end of the file |
| `replace_defmethod` | `my.ns/dispatch :http` | Replace a specific defmethod by dispatch value |

**Example (JSON-RPC):**

```json
{
  "operations": [
    {"operation": "add_require",
     "target": "clojure.string :as str",
     "file": "src/my/app/handler.clj"},
    {"operation": "replace_body",
     "target": "my.app.handler/process-request",
     "content": "  (-> req validate transform persist!)"}
  ]
}
```

The engine uses [rewrite-clj](https://github.com/clj-commons/rewrite-clj) for whitespace-and-comment-preserving source rewriting. It runs fresh clj-kondo analysis on each call to resolve qualified names to file locations.

## Architecture

Polylith workspace with 7 components and 1 base.

```
                  server (base)
                  |- CLI + MCP entry point
                  v
    .------+------+------+------+------+------.
    |      |      |      |      |      |      |
  config   db   index  embed  search  mcp    ast
```

### Components

| Component | Responsibility |
|-----------|---------------|
| **config** | Configuration loading from `.vestiga/config.edn` |
| **db** | SQLite connection, schema, CRUD operations, FTS5 search queries |
| **index** | clj-kondo analysis, source chunking, git history extraction, indexing orchestration |
| **embed** | EmbeddingProvider protocol, Ollama HTTP client, Ollama process lifecycle |
| **search** | Hybrid search engine, Reciprocal Rank Fusion ranking |
| **mcp** | MCP JSON-RPC server, tool definitions and handlers, stdio transport |
| **ast** | AST-targeted edit engine: resolve symbols by qualified name, surgical source rewriting via rewrite-clj |

### Directory Structure

```
vestiga/
  workspace.edn              # Polylith workspace config
  deps.edn                   # Root deps with :dev, :test, :poly aliases
  build.clj                  # Uberjar + GraalVM native-image build
  components/                # 7 components (config, db, index, embed, search, mcp, ast)
  bases/server/              # CLI entry point
  projects/vestiga/          # Deployable project
  development/               # REPL + shared test utilities
```

## Development

### Running Tests

```bash
# Run all tests via Polylith (recommended)
clj -M:poly test :all

# Run only tests for changed bricks (CI-friendly)
clj -M:poly test
```

### Running via clj (development)

```bash
clj -M:dev -m vestiga.server.core search "query"
clj -M:dev -m vestiga.server.core refs my.ns/fn-name
clj -M:dev -m vestiga.server.core index -p .
```

### Polylith Commands

```bash
clj -M:poly info     # workspace overview
clj -M:poly check    # validate workspace
clj -M:poly deps     # brick dependency graph
clj -M:poly libs     # library usage
```

## Building

```bash
# Uberjar
clj -T:build uber
java -jar target/vestiga-0.1.0-standalone.jar search "query"

# GraalVM native image
./script/build-native.sh
./target/vestiga search "query"
```

## Configuration

Create `.vestiga/config.edn` in your project root:

```clojure
{:embed-model     "nomic-embed-text"
 :embed-dim       768
 :ollama-base-url "http://localhost:11434"
 :index-paths     ["src" "test"]
 :file-extensions #{".clj" ".cljs" ".cljc" ".bb"}
 :git-max-commits 10000
 :search-limit    20}
```

## Storage

All indexes are stored in `<project-root>/.vestiga/db.sqlite`. Delete `.vestiga/` to reset.

## License

Copyright 2024-2026. All rights reserved.
