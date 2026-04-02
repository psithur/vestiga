---
name: vestiga
description: Search and analyse the indexed Clojure codebase using vestiga CLI tools. Use this instead of grep/find when you need semantic code search, symbol references, namespace dependencies, impact analysis, or git history search.
---

# vestiga — Code Intelligence CLI

vestiga indexes Clojure codebases with clj-kondo and provides hybrid BM25 search, structural queries, and git history analysis. Use these CLI tools instead of MCP when you want direct terminal output.

## Prerequisites

The project must be indexed first:

```bash
vestiga index -p /path/to/project
```

All commands accept `-d PATH` to specify the database path (defaults to `.vestiga/db.sqlite`).

## Commands

### Search code

Search indexed code using natural language or code patterns:

```bash
vestiga search "handle request"
vestiga search -k defn "parse"           # filter by symbol kind
vestiga search -n "my.app.*" "config"    # filter by namespace glob
vestiga search -l 5 "database"           # limit results
```

### Find references

Find all call sites of a fully qualified symbol:

```bash
vestiga refs my.app.core/handle-request
vestiga refs my.app.db/query
```

### Find namespace dependents

Find all namespaces that depend on a given namespace:

```bash
vestiga deps my.app.db
vestiga deps my.app.protocols
```

### Impact analysis

Analyse the impact of changing a symbol — shows callers, namespace dependents, and recent git history:

```bash
vestiga impact my.app.core/handle-request
```

### Search git history

Search commit messages and changed files:

```bash
vestiga history "authentication"
vestiga history -f src/my/app/auth.clj "fix"   # filter by file
vestiga history -l 5 "refactor"                 # limit results
```

### Index a project

Index or re-index a project:

```bash
vestiga index -p /path/to/project        # incremental (default)
vestiga index -p /path/to/project -f     # full re-index
```

### Start MCP server

For AI tool integration (used by .mcp.json):

```bash
vestiga mcp
```

## When to Use

- **Use `vestiga search`** instead of grep when you want ranked, semantic results across the codebase
- **Use `vestiga refs`** to find all callers of a function before refactoring
- **Use `vestiga deps`** to understand namespace coupling
- **Use `vestiga impact`** before making breaking changes to a public function
- **Use `vestiga history`** to find commits related to a feature or bug

## Running via clj (development)

```bash
clj -M:dev -m vestiga.server.core search "query"
clj -M:dev -m vestiga.server.core refs my.ns/fn-name
```
