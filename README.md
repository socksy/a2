# a2

Interactive architecture diagrams. Define components and message flows in EDN, get a split-pane HTML viewer with a sequence diagram on the left and an architecture diagram on the right, stepping through in sync.

Uses [D2](https://d2lang.com) for diagram rendering. Written in Clojure, runs with [Babashka](https://babashka.org).

## Usage

```
bb generate examples/simple.edn
```

## Input format

```clojure
{:base "base.d2"

 :nodes
 {:Client  "Client"
  :API     {:path "Platform.Services.API" :label "api-server"}
  :DB      {:path "Platform.Storage.DB"   :label "PostgreSQL"}
  :DB.apps "Platform.Storage.DB.apps"}

 :phases
 [{:color :blue  :title "Deploy"}
  {:color :green :title "Create Run"}]

 :steps
 [[{:from :Client :to :API :message "POST /apps/deploy"
    :set {:DB.apps {:id "abc123" :name "my-app"}}}
   {:from :API :to :DB :message "INSERT apps"}]

  [{:from :Client :to :API :message "POST /apps/runs"}]]}
```

Phases and steps are positional — steps at index 0 belong to phase 0. Nodes that appear in `:from`/`:to` become sequence diagram participants. The rest are path shortcuts for use in `:set`, `:lock`, etc. The `:base` D2 file defines the static architecture layout.
