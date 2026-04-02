# vestiga

A local, offline code intelligence MCP server for Clojure codebases. Indexes source code with clj-kondo, stores metadata in SQLite with FTS5, and provides hybrid BM25 + semantic search via the Model Context Protocol.

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
clj -M:dev -m vestiga.server.core index /path/to/project

# Search indexed code
clj -M:dev -m vestiga.server.core search "handle request"

# Start the MCP server (for AI tool integration)
clj -M:dev -m vestiga.server.core serve
```

## MCP Integration

Add to your MCP client config (Claude Code, OpenCode, etc.):

```json
{
  "mcpServers": {
    "vestiga": {
      "command": "clj",
      "args": ["-M:dev", "-m", "vestiga.server.core", "serve"],
      "cwd": "/path/to/vestiga"
    }
  }
}
```

### Available Tools

| Tool | Description |
|------|-------------|
| `search_code` | Hybrid BM25 text search across indexed code |
| `find_references` | Find all call sites of a fully qualified symbol |
| `find_dependents` | Find namespaces that depend on a given namespace |
| `impact_analysis` | Callers, namespace dependents, and recent git history for a symbol |
| `search_history` | Search git commit messages and changed files |
| `index_project` | Index or re-index a project on demand |

## Architecture

vestiga is structured as a [Polylith](https://polylith.gitbook.io/) workspace with 6 components and 1 base.

```
                  server (base)
                  |- entry point, CLI
                  v
    .------+------+------+------+------.
    |      |      |      |      |      |
  config   db   index  embed  search  mcp
    |             |             |      |
    '--- leaf     +-> db        +-> db |
                  |             '------+-> search
                  '-> db               '-> db
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

### Base

| Base | Responsibility |
|------|---------------|
| **server** | `-main` entry point, CLI argument parsing, component wiring |

### Brick Dependencies

```
config  .  .  .  .  .  .
db      .  .  .  .  .  .
embed   .  .  .  .  .  .
index   .  x  .  .  .  .    (index -> db)
mcp     .  x  .  .  .  x    (mcp -> db, search)
search  .  x  .  .  .  .    (search -> db)
server  x  x  x  x  x  x    (server -> all)
```

### Directory Structure

```
vestiga/
  workspace.edn              # Polylith workspace config
  deps.edn                   # Root deps with :dev, :test, :poly aliases
  build.clj                  # Uberjar + GraalVM native-image build

  components/
    config/                  # Configuration
    db/                      # SQLite storage + FTS5
    index/                   # Code analysis + git history
    embed/                   # Ollama embeddings
    search/                  # Hybrid search engine
    mcp/                     # MCP protocol server

  bases/
    server/                  # CLI entry point

  projects/
    vestiga/                 # Deployable project (composes all bricks)

  development/
    src/dev/user.clj         # REPL convenience
    test/vestiga/             # Shared test utilities
```

## Development

### Running Tests

```bash
# Run all tests via Polylith (recommended)
clj -M:poly test :all

# Run only tests for changed bricks (CI-friendly)
clj -M:poly test

# Run tests via Kaocha (unit only, skips integration)
clj -M:dev:test -m kaocha.runner --focus :unit
```

### REPL

```bash
clj -M:dev
```

```clojure
;; In the REPL
(require '[vestiga.db.interface :as db])
(require '[vestiga.db.interface.schema :as schema])

(def conn (db/open-db ":memory:"))
(schema/ensure-schema! conn)
;; ... explore the API
(db/close-db conn)
```

### Polylith Commands

```bash
# Workspace overview
clj -M:poly info

# Validate workspace structure
clj -M:poly check

# Show brick dependency graph
clj -M:poly deps

# Show library usage
clj -M:poly libs
```

## Building

### Uberjar

```bash
clj -T:build uber
# Output: target/vestiga-0.1.0-standalone.jar
java -jar target/vestiga-0.1.0-standalone.jar serve
```

### GraalVM Native Image

```bash
# Requires GraalVM with native-image installed
./script/build-native.sh
# Output: target/vestiga
./target/vestiga serve
```

## Configuration

Create `.vestiga/config.edn` in your project root to override defaults:

```clojure
{:embed-model     "nomic-embed-text"    ;; Ollama model for embeddings
 :embed-dim       768                   ;; Embedding dimensions
 :ollama-base-url "http://localhost:11434"
 :index-paths     ["src" "test"]        ;; Paths to index
 :file-extensions #{".clj" ".cljs" ".cljc" ".bb"}
 :git-max-commits 10000                 ;; Max commits to index
 :search-limit    20}                   ;; Default search result limit
```

## Storage

All indexes are stored in a single SQLite file at `<project-root>/.vestiga/db.sqlite`. The database includes:

- **chunks** - Source code split into top-level forms with symbol metadata
- **chunks_fts** - FTS5 full-text index for BM25 search
- **refs** - Cross-reference graph from clj-kondo var-usages
- **ns_deps** - Namespace dependency graph
- **commits** / **commit_files** - Git history with per-file change tracking
- **commits_fts** - FTS5 index over commit messages

Delete `.vestiga/` to reset all indexes.

## License

Copyright 2024-2026. All rights reserved.
