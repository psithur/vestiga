# vestiga TODO

Features from SETUP.md not yet implemented, grouped by priority.

## High Priority — Core Functionality Gaps

### Semantic vector search (Phase 5)
~~The embedding pipeline is scaffolded but not wired in. BM25 text search works; vector search does not.~~

- [x] Wire `embed/ollama.clj` into the indexing pipeline — embed each chunk after insertion
- [x] Load sqlite-vec extension and create `chunk_embeddings` / `commit_embeddings` vec0 tables at runtime (`schema/ensure-vec-tables!` is called when vec0 is available)
- [x] Add KNN vector search to `db/search.clj` (query vec0 tables)
- [x] Hybrid routing in `search/engine.clj` — run BM25 + vector search and fuse via RRF
- [x] Batch embedding during indexing (Ollama `/api/embed` accepts vectors of strings)
- [x] Manage Ollama lifecycle only when embeddings are requested (started only when `skip-embeddings` is false and `vec?` is true)

### MCP server uses separate DB from index
~~The MCP server opens its own DB at `.vestiga/db.sqlite` (relative to CWD), while `index_project` indexes into `<project_root>/.vestiga/db.sqlite`. When the project root differs from CWD, search finds nothing.~~

- [x] Fix DB path resolution: `cmd-mcp` now accepts `--project-root` and resolves DB path consistently with `cmd-index`
- [x] `index_project` MCP tool uses the server's bound `*db*` connection

### Incremental indexing (Phase 7, item 30)
~~File hash tracking is implemented (`file_tracker.clj`) but never tested end-to-end.~~

- [x] Test incremental indexing: index, modify a file, re-index, verify only changed file is reprocessed
- [x] Track git HEAD SHA between index runs (verified end-to-end in integration tests)

## Medium Priority — Quality & Robustness

### Integration test coverage
~~Unit tests pass but no test runs the full pipeline: kondo → chunk → insert → search.~~

- [x] Add integration test: create temp project with sample .clj files, run `index-project!`, then `bm25-search` and verify results
- [x] Add integration test: index, then `find-references` and `find-dependents` return correct results
- [x] Test `index_project` MCP tool end-to-end (full pipeline including incremental re-index)

### Native image SQLite compatibility
The native binary fails to find `libsqlitejdbc.so` when run outside the nix-shell environment.

- [ ] Investigate `org.sqlite.lib.exportPath` for native-image builds — ensure the native lib is bundled or extracted alongside the binary
- [ ] Add a smoke test in `script/test-native.sh` that actually connects to SQLite

### Error messages for missing prerequisites (Phase 7, item 32)
`check-prerequisites!` exists but is only used by the (now-removed) serve startup. CLI commands don't check.

- [ ] Add prerequisite checks to `cmd-index` (needs `clj-kondo` and `git`)
- [ ] Print actionable error messages (e.g., "clj-kondo not found — install with: brew install clj-kondo")

## Low Priority — Polish

### Config file support (Phase 7, item 31)
`config/core.clj` loads `.vestiga/config.edn` but there's no CLI flag to specify a config path, and no documentation of what overrides are possible beyond the README.

- [ ] Add `--config PATH` option to relevant CLI commands
- [ ] Support per-project config discovery (walk up from CWD to find `.vestiga/config.edn`)

### Malli schema validation
Malli schemas are defined for kondo output (`KondoAnalysis`) but never used for runtime validation.

- [ ] Validate kondo output against `KondoAnalysis` schema and report useful errors on mismatch
- [ ] Add Malli schemas for MCP tool input validation

### Native image build improvements
- [ ] Move reflect-config.json and resource-config.json into `META-INF/native-image/` inside the uberjar (avoids experimental `-H:` flags)
- [ ] Add `--enable-native-access=ALL-UNNAMED` to native-image build to suppress JVM warnings about `System.loadLibrary`
- [ ] Bundle `libsqlitejdbc.so` alongside the native binary

### Additional search features
- [ ] File path search/filtering (the `--file-path` option is plumbed but untested)
- [ ] Namespace listing command (`vestiga namespaces` or similar)
- [ ] Symbol listing by namespace (`vestiga symbols my.app.core`)
