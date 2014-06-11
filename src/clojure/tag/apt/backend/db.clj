(ns tag.apt.backend.db
  (:import (com.sleepycat.je Environment EnvironmentConfig Database DatabaseConfig DatabaseEntry OperationStatus)
           (uk.ac.susx.tag.apt PersistentKVStore APTStoreBuilder DistributionalLexicon)
           (uk.ac.susx.tag.apt Util)
           (clojure.lang IDeref)
           (java.io File Writer))
  (:require [clojure.java.io :as io]
            [tag.apt.util :as util]
            [tag.apt.core :as core])
  )

(def ^:dynamic *env-config* (doto (EnvironmentConfig.)
                                (.setAllowCreate true)))

(def ^:dynamic *db-config* (doto (DatabaseConfig.)
                               (.setAllowCreate true)))


(defn db-byte-store [dir dbname]
  (let [env (Environment. (io/as-file dir) *env-config*)
        db (.openDatabase env nil dbname *db-config*)]
    (reify
      PersistentKVStore
      (put [this k v]
        (.put db nil (DatabaseEntry. (Util/int2bytes k)) (DatabaseEntry. v)))
      (get [this k]
        (let [data (DatabaseEntry.)]
          (when (= (OperationStatus/SUCCESS) (.get db nil (DatabaseEntry. (Util/int2bytes k)) data nil))
            (.getData data))))
      (containsKey [this k]
        (not (nil? (.get this k))))
      (close [this]
        (.close db)
        (.close env)))))


(defn get-indexer-map [file]
  (if (.exists file)
    (with-open [in (util/gz-reader file)]
      (into {} (for [line (keep not-empty (line-seq in))
                     :let [[entity idx] (.split line "\t")]]
                 [entity (Long. idx)])))
    {}))

(defn put-indexer-map [file map]
  (with-open [out ^Writer (util/gz-writer file)]
    (doseq [[k v] map]
      (.write out (str k "\t" v "\n")))))

(def entity-index-filename "entity-index.tsv.gz")
(def relation-index-filename "relation-index.tsv.gz")


(defn bdb-lexicon [dir dbname ^APTStoreBuilder store-builder]
  (let [dir (io/as-file dir)
        store (-> store-builder (.setBackend (db-byte-store dir dbname)) (.build))
        entity-indexer (core/indexer (get-indexer-map (File. dir entity-index-filename)))
        relation-indexer (core/relation-indexer (get-indexer-map (File. dir relation-index-filename)))]
    (reify DistributionalLexicon
      (close [this]
        (.close store)
        (put-indexer-map (File. dir entity-index-filename) @entity-indexer)
        (put-indexer-map (File. dir relation-index-filename) @relation-indexer))
      (getEntityIndex [this] entity-indexer)
      (getRelationIndex [this] relation-indexer)
      (containsKey [this k] (.containsKey store k))
      (put [this k v] (.put store k v))
      (get [this k] (.get store k))
      (remove [this k] (.remove store k))
      (include [this k v] (.include store k v))
      (include [this g] (.include store g)))))