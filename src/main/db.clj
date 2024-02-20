(ns main.db
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [honey.sql.helpers :as sqlh]))

(def ^:private db
  {:dbtype "postgresql"
   :dbname "dungeon_big"
   :user "kobe"
   :password "seey0uaga1n"})

(def ^:private ds (jdbc/get-datasource db))

(defn- sql-execute! [statement]
  (jdbc/execute! ds statement {:builder-fn rs/as-unqualified-maps}))

(defn db->message [& [limit]]
  (-> (sqlh/select :*)
      (sqlh/from :message)
      (sqlh/limit (or limit 50))
      (sql/format)
      (sql-execute!)))

(defn- message->db [name content timestamp]
  (-> (sqlh/insert-into :message)
      (sqlh/columns :name :content :inserted_at)
      (sqlh/values (vector [name content timestamp]))
      (sql/format)
      (sql-execute!)))

(comment
  (-> (sqlh/create-table :message :if-not-exists)
      (sqlh/with-columns
        [:id :serial :primary-key [:not nil]]
        [:name :text]
        [:content :text]
        [:inserted_at :timestamp])
      (sql/format)
      (sql-execute!))

  (-> (sqlh/select :*)
      (sqlh/from :message)
      (sql/format)
      (sql-execute!))

  (-> (sqlh/insert-into :message)
      (sqlh/columns :name :content :inserted_at)
      (sqlh/values [["aikokusha" "test" (java.time.LocalDateTime/now)]])
      (sql/format {:pretty true})
      (sql-execute!))

  (-> (sqlh/insert-into :message)
      (sqlh/columns :name :content :inserted_at)
      (sqlh/values [["爱国者" "测试" (java.time.LocalDateTime/now)]])
      (sql/format {:pretty true})
      (sql-execute!))

  (-> (sqlh/update :message)
      (sqlh/set {:name "aikokusha"})
      (sqlh/where [:= :id 1])
      (sql/format)
      (sql-execute!))

  (-> 1
      (db->message))

;; For test only.
  )
