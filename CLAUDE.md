# a2 — Architecture Animator

Babashka/Clojure rewrite of archifarchi. EDN input, D2 diagrams, interactive split-pane HTML output.

## Running

```
bb generate examples/simple.edn
```

## Architecture

Single namespace `a2.core`. Split only if it gets unwieldy.

Pipeline: EDN → parse → generate D2 → render SVG (d2 CLI) → build HTML. No SMIL — JS drives all interactivity.

## Data format

- `:nodes` is a unified map of keyword → D2 path (or `{:path :label}` map). Nodes that appear in `:from`/`:to` are sequence diagram participants; the rest are path shortcuts for `:set`, `:lock`, etc.
- `:phases` is a vector of `{:color :title}` maps
- `:steps` is a vector of vectors — each inner vector is the steps for the phase at the same index
- No stateful/sticky fields — phase membership is structural
- No `$alias` syntax — use keywords (`:DB.apps`, `:redis.runs`) resolved from `:nodes`

## MCP

bb-clj MCP server (cljfmt, zprint, malli, clj-kondo, delimiter balancing) at ~/code/mcp-bb-clj. Start it with `bb mcp` before opening Claude Code, or in a separate terminal. Configured in `.mcp.json` on port 8787.

## Conventions

- Prefer data over abstractions
- Don't name intermediate values unless they're used more than once
- No comments unless they explain why
