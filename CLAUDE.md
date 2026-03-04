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

- `:base` — path to a `.d2` file (relative to the EDN file) defining the architecture diagram's static structure
- `:nodes` — map of keyword → D2 path (or `{:path :label}` map). Nodes in `:from`/`:to` become sequence diagram participants; the rest are path shortcuts for `:set`, `:lock`, etc.
- `:phases` — vector of `{:color :title}` maps. Colors: `:blue`, `:green`, `:mauve`, `:peach`, `:red`, `:teal`
- `:steps` — vector of vectors, one per phase. Each inner vector is the steps for that phase
- `:init` — optional initial state for table cells (shown on the "step 0" frame before any arrows fire)
- `:layout` — optional D2 layout engine: `"dagre"` (default), `"elk"`, `"tala"`
- `:theme` — optional D2 theme ID (default `200`, Catppuccin Mocha dark)

Each step is a map with:
- `:from`, `:to` — node keywords
- `:message` — edge label
- `:type` — arrow style, default `"->>"`. Also `"->>"`, `"<->"`
- `:set` — map of node keyword → field map, updates table cell values in the arch diagram
- `:lock`, `:unlock` — vector of node keywords for lock visualization
- `:show` — vector of node keywords to reveal (initially hidden nodes)

Phase membership is structural (no sticky fields). Use keywords resolved from `:nodes`, not raw D2 paths.

## MCP

bb-clj MCP server (cljfmt, zprint, malli, clj-kondo, delimiter balancing) at ~/code/mcp-bb-clj. Start it with `bb mcp` before opening Claude Code, or in a separate terminal. Configured in `.mcp.json` on port 8787.

## Conventions

- Prefer data over abstractions
- Don't name intermediate values unless they're used more than once
- No comments unless they explain why
