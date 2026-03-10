(ns a2.errors
  (:require [clojure.string :as str]
            [ifu.core :as ifu]
            [ifu.malli :as ifu-malli]))

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

(def validate-document (ifu-malli/validator Document))

(defn parse-and-validate [source file]
  (ifu/parse source file validate-document))

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
            (ifu/render {:file file :source source
                     :row (or (:row m) 1) :col (or (:col m) 1) :end-col (:end-col m)
                     :message (str "unknown node " ref)
                     :hint (str "not defined in :nodes or :aliases")})
            {:node ref :path [:steps pi si k]}))))))
