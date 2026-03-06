(ns a2.core
  (:require [a2.errors :as errors]
            [babashka.process :as p]
            #?(:bb [cheshire.core :as json]
               :clj [clojure.data.json :as json])
            [clojure.java.io :as io]
            [clojure.string :as str]
            [edamame.core :as eda])
  (:import [java.util Base64]
           #?@(:clj [[java.lang ProcessHandle]]))
  #?(:clj (:gen-class)))

(def ^:private json-str #?(:bb json/generate-string :clj json/write-str))

(defn- node-path [nodes k]
  (if-let [v (get nodes k)]
    (if (map? v) (:path v) v)
    (let [s (name k)
          i (str/index-of s ".")
          prefix-kw (when i (keyword (subs s 0 i)))]
      (if (and prefix-kw (get nodes prefix-kw))
        (str (node-path nodes prefix-kw) "." (subs s (inc i)))
        s))))

(defn- d2-escape
  "Escape characters that D2 interprets inside quoted strings.
   Values with ^:raw metadata pass through unescaped."
  [s]
  (if (:raw (meta s))
    s
    (str/replace s "$" "\\$")))

(defn- resolve-state [nodes state]
  (into []
    (mapcat (fn [[k v]]
      (let [base (node-path nodes k)]
        (if (map? v)
          (mapv (fn [[field val]] [(str base "." (name field)) (str val)]) v)
          [[base (str v)]]))))
    state))

(defn- resolve-kws [nodes v]
  (mapv #(node-path nodes %) (if (vector? v) v [v])))

(defn parse
  "Resolve node keywords and flatten phase-grouped steps into arrow sequence."
  [raw]
  (let [{:keys [nodes phases steps init base aliases]} raw
        nodes (merge nodes aliases)
        participants (or (:participants raw)
                        (->> (keys nodes)
                             (filterv (->> steps (mapcat identity)
                                          (mapcat (juxt :from :to)) set))))
        arrows (->> steps
                    (map-indexed (fn [phase-idx phase-steps]
                                   (map #(assoc % :phase-idx phase-idx) phase-steps)))
                    (apply concat)
                    (map-indexed
                      (fn [arrow-idx step]
                        (cond-> (assoc step
                                  :arrow-idx arrow-idx
                                  :type (or (:type step) "->>"))
                          (:set step)    (update :set #(resolve-state nodes %))
                          (:lock step)   (update :lock #(resolve-kws nodes %))
                          (:unlock step) (update :unlock #(resolve-kws nodes %))
                          (:show step)   (update :show #(resolve-kws nodes %))
                          (:extra step)  (update :extra
                                           (fn [e]
                                             (mapv #(assoc %
                                                      :from-path (node-path nodes (:from %))
                                                      :to-path (node-path nodes (:to %)))
                                                   (if (map? e) [e] e)))))))
                    vec)]
    {:base base
     :nodes nodes
     :participants participants
     :phases phases
     :init (when init (resolve-state nodes init))
     :arrows arrows}))

(defn- dark-theme? [theme]
  (contains? #{200 201} (or theme 200)))

(def ^:private phase-fills-dark
  {"blue"  "#1e2d4a"
   "green" "#1e3a2d"
   "mauve" "#2d1e4a"
   "peach" "#3a2d1e"
   "red"   "#3a1e1e"
   "teal"  "#1e3a3a"})

(def ^:private phase-fills-light
  {"blue"  "#e8edfb"
   "green" "#e6f4ed"
   "mauve" "#f0e8f8"
   "peach" "#fbeee8"
   "red"   "#fbe8e8"
   "teal"  "#e8f5f5"})

(defn- d2-arrow-str [arrow-type]
  (if (#{"<->" "<<->>"} arrow-type) "<->" "->"))

(defn- arrow->d2-edge [nodes phase arrow]
  (format "%s %s %s: \"%s\" {\n  class: [edge; %s]\n}"
    (node-path nodes (:from arrow))
    (d2-arrow-str (:type arrow))
    (node-path nodes (:to arrow))
    (d2-escape (or (:label arrow) (:message arrow)))
    (name (or (:color arrow) (:color phase) :blue))))

(defn- html-escape [s]
  (-> s (str/replace "<" "&lt;") (str/replace ">" "&gt;")))

(defn- b64 [s]
  (.encodeToString (Base64/getEncoder) (.getBytes s "UTF-8")))

(defn- d2-edge-ref
  "Reproduce D2's internal edge reference: strip shared path prefix, HTML-escape arrow."
  [source target arrow idx]
  (let [source (str/split source #"\.")
        target (str/split target #"\.")
        n (max 0 (min (count (take-while true? (map = source target)))
                      (dec (count source))
                      (dec (count target))))]
    (cond->> (format "(%s %s %s)[%s]"
               (str/join "." (drop n source))
               (html-escape arrow)
               (str/join "." (drop n target))
               idx)
      (pos? n) (str (str/join "." (take n source)) "."))))

(defn- edge-b64
  "Base64-encode an edge ref matching D2's SVG class naming."
  [source target arrow idx]
  (b64 (d2-edge-ref source target arrow idx)))

(defn- arch-d2-text
  "Architecture diagram D2: base D2 text + generated edge lines per arrow."
  [doc base-d2]
  (let [arrows (remove #(= (:from %) (:to %)) (:arrows doc))
        phase-groups (partition-by :phase-idx arrows)
        edge-lines
        (mapcat
          (fn [group]
            (let [phase (get-in doc [:phases (:phase-idx (first group))])]
              (cons
                (str "# Phase: " (:title phase))
                (mapcat (fn [a]
                          (cond-> [(arrow->d2-edge (:nodes doc) phase a)]
                            (:extra a)
                            (into (mapv (fn [x]
                                          (format "%s -> %s: \"%s\" {\n  class: [edge; %s]\n}"
                                            (:from-path x) (:to-path x) (d2-escape (:label x))
                                            (name (or (:color x) (:color phase) :blue))))
                                        (:extra a)))))
                        group))))
          phase-groups)]
    (str (str/trimr base-d2) "\n\n# -- Generated edges --\n\n"
         (str/join "\n" edge-lines) "\n")))

(defn- d2-path-to-participant
  "Longest-match participant whose D2 path is a prefix of d2-path."
  [nodes participants d2-path]
  (->> participants
       (filter (fn [kw]
                 (let [path (node-path nodes kw)]
                   (or (= d2-path path)
                       (str/starts-with? d2-path (str path "."))))))
       (sort-by #(count (node-path nodes %)) >)
       first))

(defn- to-seq-name
  "Map a keyword to its sequence diagram participant name.
   Aliases and sub-paths resolve to their owning participant."
  [nodes participants kw]
  (let [participant-set (set participants)]
    (if (participant-set kw)
      (name kw)
      (let [path (node-path nodes kw)]
        (or (->> participants
                 (filter (fn [p]
                           (let [pp (node-path nodes p)]
                             (or (= path pp)
                                 (str/starts-with? path (str pp "."))))))
                 (sort-by #(count (node-path nodes %)) >)
                 first
                 name)
            (name kw))))))

(defn- process-lock-changes
  "Process lock/unlock for one arrow. Unlocks before acquires
   so same-step release+acquire works."
  [state arrow nodes participants]
  (let [from-name (to-seq-name nodes participants (:from arrow))]
    (reduce
      (fn [state lock-path]
        (let [span (-> lock-path (str/split #"\.") last (str/replace "lock_" ""))
              target-alias (some-> (d2-path-to-participant nodes participants lock-path) name)]
          (-> state
              (update-in [:source-spans from-name] (fnil conj []) span)
              (cond->
                target-alias
                (update-in [:target-spans [from-name target-alias]] (fnil conj []) span))
              (assoc-in [:lock-info lock-path]
                {:holder from-name :span-name span :target-alias target-alias
                 :acquire-idx (:arrow-idx arrow) :acquire-phase (:phase-idx arrow)}))))
      (reduce
        (fn [state lock-path]
          (if-let [info (get (:lock-info state) lock-path)]
            (let [{:keys [holder span-name target-alias acquire-idx acquire-phase]} info]
              (-> state
                  (update-in [:source-spans holder] #(vec (remove #{span-name} %)))
                  (cond-> target-alias
                    (update-in [:target-spans [holder target-alias]]
                      #(vec (remove #{span-name} %))))
                  (update :lock-info dissoc lock-path)
                  (update :lock-spans conj
                    {:lock-path lock-path
                     :acquire-idx acquire-idx
                     :release-idx (:arrow-idx arrow)
                     :acquire-phase acquire-phase
                     :release-phase (:phase-idx arrow)})))
            state))
        state
        (:unlock arrow []))
      (:lock arrow []))))

(defn- qualify
  "Append active lock spans to a participant name for D2 sequence nesting."
  [participant-name spans]
  (if (seq spans)
    (str participant-name "." (str/join "." spans))
    participant-name))

(defn- seq-d2-text
  "Sequence diagram D2 text + per-step base64 edge classes + lock spans."
  [doc dark?]
  (let [nodes (:nodes doc)
        participants (:participants doc)
        {:keys [phase-lines raw-edges lock-spans]}
        (reduce
          (fn [acc arrow]
            (let [from-name (to-seq-name nodes participants (:from arrow))
                  to-name (to-seq-name nodes participants (:to arrow))
                  acc (process-lock-changes acc arrow nodes participants)
                  qualified-source (qualify from-name (get (:source-spans acc) from-name))
                  qualified-target
                  (if (= (:from arrow) (:to arrow))
                    qualified-source
                    (qualify to-name (get (:target-spans acc) [from-name to-name])))
                  edge-pair [qualified-source qualified-target]
                  edge-idx (get-in acc [:edge-count edge-pair] 0)]
              (let [lines (cond-> []
                            (:alt-begin arrow)
                            (conj (format "  \"alt \u2014 %s\": {" (:alt-begin arrow)))
                            (and (:alt-branch arrow) (not (:alt-begin arrow)))
                            (conj (format "    \"else \u2014 %s\": {" (:alt-branch arrow)))
                            true
                            (conj (format "%s%s -> %s: \"%s\""
                                    (apply str (repeat (+ 2 (* 2 (cond (:alt-begin arrow) 1
                                                                        (and (:alt-branch arrow) (not (:alt-begin arrow))) 2
                                                                        :else (:alt-depth acc 0)))) \space))
                                    qualified-source qualified-target (d2-escape (:message arrow))))
                            (:alt-end arrow)
                            (into (if (or (and (:alt-branch arrow) (not (:alt-begin arrow)))
                                          (>= (:alt-depth acc 0) 2))
                                    ["    }" "  }"]
                                    ["  }"])))]
                (let [eff-depth (cond (:alt-begin arrow) 1
                                     (and (:alt-branch arrow) (not (:alt-begin arrow))) 2
                                     :else (:alt-depth acc 0))]
                  (-> acc
                      (update-in [:phase-lines (:phase-idx arrow)] (fnil into []) lines)
                      (update :raw-edges conj
                        {:src qualified-source :tgt qualified-target
                         :pair edge-pair :seq-idx edge-idx :depth eff-depth})
                      (assoc-in [:edge-count edge-pair] (inc edge-idx))
                      (assoc :alt-depth (cond (:alt-end arrow) 0
                                              (:alt-begin arrow) 1
                                              (and (:alt-branch arrow) (not (:alt-begin arrow))) 2
                                              :else (:alt-depth acc 0))))))))
          {:phase-lines {} :raw-edges [] :edge-count {} :alt-depth 0
           :source-spans {} :target-spans {} :lock-info {} :lock-spans []}
          (:arrows doc))
        ;; D2 numbers edges depth-first (deepest container first), then outer.
        ;; Remap sequential indices to match D2's assignment.
        pair->remap
        (->> raw-edges
             (group-by :pair)
             (into {}
               (keep (fn [[pair edges]]
                       (when (some pos? (map :depth edges))
                         [pair (->> (sort-by (juxt (comp - :depth) :seq-idx) edges)
                                    (map-indexed (fn [d2-idx e] [(:seq-idx e) d2-idx]))
                                    (into {}))])))))
        seq-edges
        (into [[]]
          (mapv (fn [{:keys [src tgt pair seq-idx]}]
                  [(edge-b64 src tgt "->" (get-in pair->remap [pair seq-idx] seq-idx))])
                raw-edges))]
    {:d2 (str/join "\n"
           (concat
             ["shape: sequence_diagram" ""]
             (mapv (fn [kw]
                     (if-let [label (:label (get nodes kw))]
                       (format "%s: \"%s\"" (name kw) label)
                       (name kw)))
                   participants)
             [""]
             (mapcat
               (fn [phase-idx]
                 (let [phase (get-in doc [:phases phase-idx])
                       title (:title phase (str "Phase " phase-idx))]
                   [(format "# -- Phase %s: %s --" phase-idx title)
                    (format "\"%s. %s\": {" phase-idx title)
                    (format "  style.fill: \"%s\""
                      (get (if dark? phase-fills-dark phase-fills-light)
                        (name (:color phase :blue)) (if dark? "#2a2d3f" "#f0f1f5")))
                    ""
                    (str/join "\n" (get phase-lines phase-idx))
                    "}" ""]))
               (range (count (:phases doc))))))
     :seq-edges seq-edges
     :lock-spans lock-spans}))

(defn- init-frame [doc]
  (cond-> {:title (get-in doc [:phases 0 :title] "Start")
           :color (name (get-in doc [:phases 0 :color] :blue))}
    (:init doc) (assoc :init (:init doc))))

(defn- build-frames
  "Per-arrow frame data for JS interactivity: title, color, edge refs, state changes."
  [doc]
  (:frames
    (reduce
      (fn [{:keys [frames arch-edge-count]} arrow]
        (let [source-path (node-path (:nodes doc) (:from arrow))
              target-path (node-path (:nodes doc) (:to arrow))
              self-referential (= (:from arrow) (:to arrow))
              d2-arrow (d2-arrow-str (:type arrow))
              edge-key [source-path d2-arrow target-path]
              phase (get-in doc [:phases (:phase-idx arrow)])
              ec (if self-referential arch-edge-count
                   (update arch-edge-count edge-key (fnil inc 0)))
              [extra-b64s ec]
              (reduce (fn [[b64s ec] x]
                        (let [ek [(:from-path x) "->" (:to-path x)]
                              idx (get ec ek 0)]
                          [(conj b64s (edge-b64 (:from-path x) (:to-path x) "->" idx))
                           (update ec ek (fnil inc 0))]))
                      [[] ec]
                      (:extra arrow []))]
          {:frames
           (conj frames
             (cond->
               {:title (or (:title arrow)
                           (str (:title phase (str "Phase " (:phase-idx arrow)))
                                ": " (:message arrow)))
                :color (name (:color phase :blue))}
               (:title-color arrow) (assoc :title-color (:title-color arrow))
               (not self-referential)
               (assoc :arch-edge
                 (edge-b64 source-path target-path d2-arrow
                   (get arch-edge-count edge-key 0)))
               (seq extra-b64s) (assoc :extra-edges extra-b64s)
               (:set arrow)    (assoc :set (:set arrow))
               (:lock arrow)   (assoc :lock (:lock arrow))
               (:unlock arrow) (assoc :unlock (:unlock arrow))
               (:show arrow)   (assoc :show (:show arrow))))
           :arch-edge-count ec}))
      {:frames [(init-frame doc)] :arch-edge-count {}}
      (:arrows doc))))

(defn generate-d2
  "Parsed doc + base D2 → {:arch-d2 :seq-d2 :sync}"
  [doc base-d2 {:keys [theme]}]
  (let [{:keys [d2 seq-edges lock-spans]} (seq-d2-text doc (dark-theme? theme))]
    {:arch-d2 (arch-d2-text doc base-d2)
     :seq-d2 d2
     :sync {:frames (build-frames doc)
            :seq-edges seq-edges
            :lock-spans lock-spans}}))

(defn- matching-brace [s start]
  (loop [i start depth 1]
    (when (< i (count s))
      (case (nth s i)
        \{ (recur (inc i) (inc depth))
        \} (if (= depth 1) i (recur (inc i) (dec depth)))
        (recur (inc i) depth)))))

(defn- find-table-field [d2 full-path]
  (let [parts (str/split full-path #"\.")
        ;; D2 block opening: tablename: ... {
        m (re-matcher
            (re-pattern (str "\\b" (nth parts (- (count parts) 2)) ":[^\\n]*\\{"))
            d2)]
    (when (.find m)
      (when-let [block-end (matching-brace d2 (.end m))]
        ;; D2 field value: field: "val" (excludes nested blocks like field: "x" {)
        (let [fm (re-matcher
                   (re-pattern (str "\\b" (peek parts) ":\\s*\"([^\"]*)\"(?!\\s*\\{)"))
                   (subs d2 (.end m) block-end))]
          (when (.find fm)
            {:abs-start (+ (.end m) (.start fm 1))
             :abs-end   (+ (.end m) (.end fm 1))
             :value     (.group fm 1)}))))))

(defn- strip-edge-labels [d2-text]
  ;; D2 edge declaration: src -> tgt: "label" — captures src -> tgt, discards label
  (str/replace d2-text
    #"(?m)^([\w.]+\s+(?:<->|->)\s+[\w.]+)\s*:\s*(?:\"[^\"]*\"|\w+)"
    "$1"))

(defn- pad-table-cells [base-d2 doc]
  (->> (concat (:init doc) (mapcat :set (:arrows doc)))
       (reduce (fn [acc [path val]]
                 (if (> (count val) (count (get acc path "")))
                   (assoc acc path val) acc))
               {})
       (reduce-kv
         (fn [d2 full-path widest-val]
           (if-let [{:keys [abs-start abs-end value]} (find-table-field d2 full-path)]
             (if (> (count widest-val) (count value))
               (str (subs d2 0 abs-start) (d2-escape widest-val) (subs d2 abs-end))
               d2)
             d2))
         base-d2)))

(def ^:private mono-fonts
  (delay
    (let [index (into {}
                  (comp (map io/file)
                        (filter #(.isDirectory %))
                        (mapcat file-seq)
                        (filter #(.isFile %))
                        (map (fn [f] [(.getName f) (.getAbsolutePath f)])))
                  ["/Library/Fonts"
                   (str (System/getProperty "user.home") "/Library/Fonts")
                   "/usr/share/fonts"
                   "/usr/local/share/fonts"])]
      (some (fn [[regular bold semibold]]
              (when (and (index regular) (index bold) (index semibold))
                {:regular (index regular) :bold (index bold)
                 :semibold (index semibold) :italic (index regular)}))
            [["FiraCodeNerdFontMono-Regular.ttf"
              "FiraCodeNerdFontMono-Bold.ttf"
              "FiraCodeNerdFontMono-SemiBold.ttf"]
             ["JetBrainsMono-Regular.ttf"
              "JetBrainsMono-Bold.ttf"
              "JetBrainsMono-SemiBold.ttf"]]))))

(def ^:private d2-bin
  (delay
    #?(:bb "d2"
       :clj (let [sibling (some-> (ProcessHandle/current) .info .command (.orElse nil)
                                  io/file .getParentFile (io/file "d2"))]
               (if (and sibling (.canExecute sibling))
                 (.getAbsolutePath sibling)
                 "d2")))))

(defn d2->svg
  "Shell out to d2 CLI, return SVG string."
  [d2-text {:keys [layout theme]}]
  (let [fonts @mono-fonts
        in-file (java.io.File/createTempFile "a2-" ".d2")
        out-file (java.io.File/createTempFile "a2-" ".svg")]
    (try
      (spit in-file d2-text)
      (.delete out-file)
      (let [{:keys [exit err]}
            @(p/process
               (-> (cond-> [@d2-bin
                            (str "--layout=" (or layout "dagre"))
                            (str "--theme=" (or theme 200))]
                     fonts (into (mapv #(str "--font-" (name %) "=" (fonts %))
                                       [:regular :bold :italic :semibold])))
                   (conj (.getAbsolutePath in-file) (.getAbsolutePath out-file)))
               {:out :string :err :string})]
        (if (zero? exit)
          (str/replace (slurp out-file) #"<text (?![^>]*class=)[^>]*>[^<]*</text>" "")
          (throw (ex-info (str "d2 failed:\n" err) {:exit exit}))))
      (finally
        (.delete in-file)
        (.delete out-file)))))

(defn- build-edge-info [arch-d2]
  ;; D2 edge declaration: src -> tgt: "label" — captures (src, arrow, tgt, label)
  (->> (re-seq #"(?m)^([\w.]+)\s+(<->|->)\s+([\w.]+)\s*:\s*(?:\"([^\"]*)\"|(\w+))" arch-d2)
       (reduce
         (fn [[{:keys [edge-map edge-labels]} counts] [_ left arrow right quoted bare]]
           (let [idx (get counts [left arrow right] 0)
                 b64 (edge-b64 left right arrow idx)
                 label (or quoted bare)]
             [{:edge-map (assoc edge-map
                           (format "(%s %s %s)[%s]" left arrow right idx) b64)
               :edge-labels (cond-> edge-labels
                              (not (str/blank? label)) (assoc b64 label))}
              (update counts [left arrow right] (fnil inc 0))]))
         [{:edge-map {} :edge-labels {}} {}])
       first))

(defn- find-blank-tables [base-d2]
  (into #{}
    (keep (fn [[_ table-name body]]
            ;; D2 field values: key: "val" pairs in table body
            (when (->> (re-seq #"(?m)^\s*(\w+):\s*\"([^\"]*)\"" body)
                       (remove #(#{"class" "shape" "label" "near"} (nth % 1)))
                       (every? #(str/blank? (nth % 2))))
              table-name)))
    ;; D2 table blocks: name: { ...class: table... } or { ...shape: sql_table... }
    (re-seq #"([\w-]+):[^\n]*\{([^{}]*(?:class:\s*table|shape:\s*sql_table)[^{}]*)\}" base-d2)))

(defn- parse-initial-cell-values [base-d2 frames]
  (into {}
    (keep (fn [path]
            (when-let [{:keys [value]} (find-table-field base-d2 path)]
              [path value])))
    (into #{} (comp (mapcat :set) (map first)) frames)))

(def ^:private palette-dark
  {"blue" "#8aadf4" "green" "#a6da95" "mauve" "#c6a0f6"
   "peach" "#f5a97f" "red" "#ed8796" "teal" "#8bd5ca"})

(def ^:private palette-light
  {"blue" "#4361ee" "green" "#2d936c" "mauve" "#7b2d8e"
   "peach" "#e76f51" "red" "#d62828" "teal" "#0891b2"})

(def ^:private html-template
  (delay (slurp (io/resource "a2/template.html"))))

(defn- sync->js-data [{:keys [frames seq-edges edge-map edge-labels blank-tables initial-cells palette]}]
  (let [pal (or palette palette-dark)]
    {:allArchEdges (vec (distinct (vals edge-map)))
     :edgeLabels (or edge-labels {})
     :initialCells (or initial-cells {})
     :blankTables (->> (keys (or initial-cells {}))
                       (map #(str/join "." (butlast (str/split % #"\."))))
                       distinct
                       (filterv #(contains? (or blank-tables #{})
                                            (peek (str/split % #"\.")))))
     :seqEdges (or seq-edges [])
     :frames (mapv
               (fn [{:keys [title color arch-edge lock unlock set init show title-color extra-edges]}]
                 (cond->
                   {:title title :titleColor (or title-color (get pal color "#8aadf4"))}
                   arch-edge   (assoc :archEdge arch-edge)
                   extra-edges (assoc :extraEdges extra-edges)
                   lock        (assoc :locks (mapv #(vector % (get pal color "#8aadf4")) lock))
                 unlock      (assoc :unlocks unlock)
                 set         (assoc :cells set)
                 init        (assoc :init init)
                 show        (assoc :show show)))
             frames)}))

(defn build-html
  "Two SVGs + sync data → interactive split-pane HTML."
  [arch-svg seq-svg sync {:keys [theme]}]
  (-> @html-template
      (.replace "{{MODE}}" (if (dark-theme? theme) "dark" "light"))
      (.replace "{{SEQ_SVG}}" seq-svg)
      (.replace "{{ARCH_SVG}}" arch-svg)
      (.replace "{{DATA_JSON}}" (str/replace (json-str (sync->js-data sync))
                                             "</" "<\\/"))
      (.replace "{{N}}" (str (count (:frames sync))))))

(defn run [input-path output-path]
  (let [source   (slurp input-path)
        raw      (try (eda/parse-string source {:all true})
                   (catch Exception e
                     (throw (ex-info (str "Failed to parse " input-path ":\n" (ex-message e))
                                    {:file input-path}))))
        _        (errors/validate raw source input-path)
        _        (errors/validate-refs raw source input-path)
        dir      (some-> (io/file input-path) .getParentFile)
        doc      (parse raw)
        d2-opts  (select-keys raw [:layout :theme])
        raw-base (slurp (if dir (io/file dir (:base doc)) (:base doc)))
        base     (pad-table-cells raw-base doc)
        {:keys [arch-d2 seq-d2 sync]} (generate-d2 doc base d2-opts)
        {:keys [edge-map edge-labels]} (build-edge-info arch-d2)]
    (when-let [f (:regular @mono-fonts)]
      (binding [*out* *err*] (println (str "Font: " (.getName (io/file f))))))
    (spit output-path
      (build-html
        (d2->svg (strip-edge-labels arch-d2) d2-opts)
        (d2->svg seq-d2 d2-opts)
        (assoc sync
          :edge-map edge-map
          :edge-labels edge-labels
          :blank-tables (find-blank-tables raw-base)
          :initial-cells (parse-initial-cell-values raw-base (:frames sync))
          :palette (when-not (dark-theme? (:theme d2-opts)) palette-light))
        d2-opts))
    (println (str "Written: " output-path))))

(defn -main [& args]
  (when-not (first args)
    (binding [*out* *err*] (println "Usage: bb generate <input.edn> [output.html]"))
    (System/exit 1))
  (let [[input output] args]
    (run input (or output (str/replace input #"\.[^.]+$" ".html")))))
