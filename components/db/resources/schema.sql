-- ============================================================
-- vestiga schema v1
-- ============================================================

PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;
PRAGMA busy_timeout = 5000;

-- ------------------------------------------------------------
-- Schema version tracking
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS schema_version (
  version INTEGER PRIMARY KEY,
  applied_at TEXT NOT NULL DEFAULT (datetime('now'))
);

-- ------------------------------------------------------------
-- Projects (supports indexing multiple repos)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS projects (
  id INTEGER PRIMARY KEY,
  root_path TEXT NOT NULL UNIQUE,
  name TEXT NOT NULL,
  last_indexed_at TEXT,
  head_sha TEXT,                        -- git HEAD at last index
  kondo_hash TEXT                       -- hash of kondo config for invalidation
);

-- ------------------------------------------------------------
-- Source chunks (the primary unit of indexing)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS chunks (
  id INTEGER PRIMARY KEY,
  project_id INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  file_path TEXT NOT NULL,              -- relative to project root
  namespace TEXT,                       -- e.g. "my.app.core"
  qualified_name TEXT,                  -- e.g. "my.app.core/handle-request"
  symbol_name TEXT,                     -- e.g. "handle-request"
  kind TEXT,                            -- defn, defmacro, defprotocol, ns, etc.
  content TEXT NOT NULL,                -- raw source text of the chunk
  start_line INTEGER NOT NULL,
  end_line INTEGER NOT NULL,
  arity TEXT,                           -- JSON array of arglists, nullable
  docstring TEXT,                       -- extracted docstring, nullable
  file_hash TEXT NOT NULL,              -- for incremental indexing
  indexed_at TEXT NOT NULL DEFAULT (datetime('now')),

  UNIQUE(project_id, file_path, start_line)
);

CREATE INDEX IF NOT EXISTS idx_chunks_project ON chunks(project_id);
CREATE INDEX IF NOT EXISTS idx_chunks_file ON chunks(project_id, file_path);
CREATE INDEX IF NOT EXISTS idx_chunks_ns ON chunks(project_id, namespace);
CREATE INDEX IF NOT EXISTS idx_chunks_qname ON chunks(qualified_name);
CREATE INDEX IF NOT EXISTS idx_chunks_kind ON chunks(project_id, kind);

-- ------------------------------------------------------------
-- FTS5 index for BM25 text search
-- ------------------------------------------------------------
CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts5(
  content,
  symbol_name,
  qualified_name,
  namespace,
  docstring,
  content='chunks',
  content_rowid='id',
  tokenize='porter unicode61'
);

-- Triggers to keep FTS in sync
CREATE TRIGGER IF NOT EXISTS chunks_ai AFTER INSERT ON chunks BEGIN
  INSERT INTO chunks_fts(rowid, content, symbol_name, qualified_name, namespace, docstring)
  VALUES (new.id, new.content, new.symbol_name, new.qualified_name, new.namespace, new.docstring);
END;

CREATE TRIGGER IF NOT EXISTS chunks_ad AFTER DELETE ON chunks BEGIN
  INSERT INTO chunks_fts(chunks_fts, rowid, content, symbol_name, qualified_name, namespace, docstring)
  VALUES ('delete', old.id, old.content, old.symbol_name, old.qualified_name, old.namespace, old.docstring);
END;

CREATE TRIGGER IF NOT EXISTS chunks_au AFTER UPDATE ON chunks BEGIN
  INSERT INTO chunks_fts(chunks_fts, rowid, content, symbol_name, qualified_name, namespace, docstring)
  VALUES ('delete', old.id, old.content, old.symbol_name, old.qualified_name, old.namespace, old.docstring);
  INSERT INTO chunks_fts(rowid, content, symbol_name, qualified_name, namespace, docstring)
  VALUES (new.id, new.content, new.symbol_name, new.qualified_name, new.namespace, new.docstring);
END;

-- ------------------------------------------------------------
-- Symbol references (from clj-kondo var-usages)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS refs (
  id INTEGER PRIMARY KEY,
  project_id INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  from_ns TEXT NOT NULL,                -- caller namespace
  from_name TEXT NOT NULL,              -- caller var name
  to_ns TEXT NOT NULL,                  -- callee namespace
  to_name TEXT NOT NULL,                -- callee var name
  file_path TEXT NOT NULL,
  row INTEGER NOT NULL,
  col INTEGER NOT NULL,

  UNIQUE(project_id, file_path, row, col)
);

CREATE INDEX IF NOT EXISTS idx_refs_to ON refs(project_id, to_ns, to_name);
CREATE INDEX IF NOT EXISTS idx_refs_from ON refs(project_id, from_ns, from_name);

-- ------------------------------------------------------------
-- Namespace dependencies (from clj-kondo namespace-usages)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ns_deps (
  id INTEGER PRIMARY KEY,
  project_id INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  from_ns TEXT NOT NULL,
  to_ns TEXT NOT NULL,

  UNIQUE(project_id, from_ns, to_ns)
);

CREATE INDEX IF NOT EXISTS idx_ns_deps_from ON ns_deps(project_id, from_ns);
CREATE INDEX IF NOT EXISTS idx_ns_deps_to ON ns_deps(project_id, to_ns);

-- ------------------------------------------------------------
-- Git commits
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS commits (
  id INTEGER PRIMARY KEY,
  project_id INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  sha TEXT NOT NULL,
  author TEXT NOT NULL,
  timestamp INTEGER NOT NULL,           -- unix epoch
  message TEXT NOT NULL,

  UNIQUE(project_id, sha)
);

CREATE INDEX IF NOT EXISTS idx_commits_project ON commits(project_id, timestamp DESC);

-- FTS for commit messages
CREATE VIRTUAL TABLE IF NOT EXISTS commits_fts USING fts5(
  message,
  content='commits',
  content_rowid='id',
  tokenize='porter unicode61'
);

CREATE TRIGGER IF NOT EXISTS commits_ai AFTER INSERT ON commits BEGIN
  INSERT INTO commits_fts(rowid, message) VALUES (new.id, new.message);
END;

CREATE TRIGGER IF NOT EXISTS commits_ad AFTER DELETE ON commits BEGIN
  INSERT INTO commits_fts(commits_fts, rowid, message) VALUES ('delete', old.id, old.message);
END;

-- ------------------------------------------------------------
-- Commit-file associations
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS commit_files (
  id INTEGER PRIMARY KEY,
  commit_id INTEGER NOT NULL REFERENCES commits(id) ON DELETE CASCADE,
  file_path TEXT NOT NULL,
  change_type TEXT NOT NULL,            -- A, M, D, R
  lines_added INTEGER,
  lines_removed INTEGER,
  patch TEXT                            -- unified diff for this file in this commit
);

CREATE INDEX IF NOT EXISTS idx_commit_files_commit ON commit_files(commit_id);
CREATE INDEX IF NOT EXISTS idx_commit_files_path ON commit_files(file_path);

-- FTS for searching diff content
CREATE VIRTUAL TABLE IF NOT EXISTS commit_patches_fts USING fts5(
  file_path,
  patch,
  content='commit_files',
  content_rowid='id',
  tokenize='porter unicode61'
);

CREATE TRIGGER IF NOT EXISTS commit_files_ai AFTER INSERT ON commit_files
WHEN new.patch IS NOT NULL BEGIN
  INSERT INTO commit_patches_fts(rowid, file_path, patch)
  VALUES (new.id, new.file_path, new.patch);
END;

CREATE TRIGGER IF NOT EXISTS commit_files_ad AFTER DELETE ON commit_files
WHEN old.patch IS NOT NULL BEGIN
  INSERT INTO commit_patches_fts(commit_patches_fts, rowid, file_path, patch)
  VALUES ('delete', old.id, old.file_path, old.patch);
END;

-- ------------------------------------------------------------
-- Vector embeddings (sqlite-vec)
-- Loaded conditionally — if vec0 extension is available
-- ------------------------------------------------------------
-- These are created at runtime after loading the vec0 extension:
--
-- CREATE VIRTUAL TABLE IF NOT EXISTS chunk_embeddings USING vec0(
--   id INTEGER PRIMARY KEY,
--   embedding float[768]
-- );
--
-- CREATE VIRTUAL TABLE IF NOT EXISTS commit_embeddings USING vec0(
--   id INTEGER PRIMARY KEY,
--   embedding float[768]
-- );
