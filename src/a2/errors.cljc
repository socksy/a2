(ns a2.errors
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]))

(def D2Path [:re {:error/message ".d2 file path"} #".*\.d2$"])

(def Color [:enum :blue :green :mauve :peach :red :teal])

(def ArrowType [:enum "->>" "<<->>" "<->" "->"])

(def Layout [:enum "dagre" "elk" "tala"])

(def Node
  [:or
   [:string {:error/message "D2 path string"}]
   [:map [:path :string] [:label {:optional true} :string]]])

(def Step
  [:map
   [:from :keyword]
   [:to :keyword]
   [:message :string]
   [:type {:optional true} ArrowType]
   [:label {:optional true} :string]
   [:title {:optional true} :string]
   [:color {:optional true} Color]
   [:title-color {:optional true} :string]
   [:set {:optional true} :map]
   [:lock {:optional true} [:or :keyword [:vector :keyword]]]
   [:unlock {:optional true} [:or :keyword [:vector :keyword]]]
   [:show {:optional true} [:or :keyword [:vector :keyword]]]
   [:extra {:optional true} [:or :map [:vector :map]]]
   [:alt-begin {:optional true} :string]
   [:alt-branch {:optional true} :string]
   [:alt-end {:optional true} :boolean]
   [:alt-depth {:optional true} :int]])

(def Phase
  [:map [:color Color] [:title :string]])

(def Document
  [:map
   [:base D2Path]
   [:nodes [:map-of :keyword Node]]
   [:phases [:vector Phase]]
   [:steps [:vector [:vector Step]]]
   [:init {:optional true} :map]
   [:layout {:optional true} Layout]
   [:theme {:optional true} :int]
   [:participants {:optional true} [:vector :keyword]]
   [:aliases {:optional true} [:map-of :keyword :string]]])

(defn- colorize [s code]
  (str "\033[" code "m" s "\033[0m"))

(defn- source-line [source row]
  (nth (str/split source #"\n" -1) (dec row) ""))

(defn- pointer [col len]
  (str (apply str (repeat (dec col) \space)) (apply str (repeat (max 1 len) \^))))

(defn- render [{:keys [file source row col end-col message hint]}]
  (let [line (source-line source row)
        gw (count (str row))]
    (str (colorize "error" "1;31") ": " (colorize message "1") "\n"
         (colorize "  -->" "36") " " file ":" row ":" col "\n"
         (apply str (repeat (+ gw 2) \space)) (colorize "│" "36") "\n"
         " " row " " (colorize "│" "36") " " line "\n"
         (apply str (repeat (+ gw 2) \space)) (colorize "│" "36") " "
         (colorize (pointer col (- (or end-col (inc col)) col)) "31")
         (when hint (str " " hint)) "\n")))

(defn- meta-at [parsed path]
  (reduce
    (fn [form seg]
      (cond
        (and (map? form) (keyword? seg)) (get form seg)
        (and (sequential? form) (int? seg)) (nth form seg nil)
        :else nil))
    parsed path))

(defn- fmt-path [path]
  (if (= 1 (count path))
    (str (first path))
    (str "[" (str/join " " (map str path)) "]")))

(defn- describe-schema [schema]
  (case (m/type schema)
    :enum (str "one of " (str/join ", " (m/children schema)))
    :string "string"
    :keyword "keyword"
    :int "integer"
    :boolean "boolean"
    :map "map"
    :vector "vector"
    (or (-> schema m/properties :error/message)
        (name (m/type schema)))))

(defn- error-message [err]
  (let [path (:in err)]
    (if (= (:type err) :malli.core/missing-key)
      (str "missing required key " (last path))
      (str "expected " (describe-schema (:schema err))
           " for " (fmt-path path)
           ", got " (pr-str (:value err))))))

(defn validate [parsed source file]
  (when-let [explanation (m/explain Document parsed)]
    (let [diags
          (mapv
            (fn [err]
              (let [path (:in err)
                    form (when (seq path) (meta-at parsed path))
                    parent (when (> (count path) 1) (meta-at parsed (butlast path)))
                    m (or (meta form) (meta parent) (meta parsed))
                    row (or (:row m) 1)
                    col (or (:col m) 1)]
                {:file file :source source :row row :col col
                 :end-col (:end-col m)
                 :message (error-message err)}))
            (:errors explanation))]
      (throw (ex-info (str/join "\n" (map render diags)) {:errors (:errors explanation)})))))

(defn- known-ref? [known ref]
  (or (get known ref)
      (let [s (name ref)
            i (str/index-of s ".")]
        (and i (get known (keyword (subs s 0 i)))))))

(defn validate-refs [parsed source file]
  (let [known (merge (:nodes parsed) (:aliases parsed))]
    (doseq [[pi phase-steps] (map-indexed vector (:steps parsed))
            [si step] (map-indexed vector phase-steps)
            k [:from :to]
            :let [ref (get step k)]
            :when (and ref (not (known-ref? known ref)))]
      (let [m (or (meta step) (meta parsed))]
        (throw
          (ex-info
            (render {:file file :source source
                     :row (or (:row m) 1) :col (or (:col m) 1) :end-col (:end-col m)
                     :message (str "unknown node " ref)
                     :hint (str "not defined in :nodes or :aliases")})
            {:node ref :path [:steps pi si k]}))))))
