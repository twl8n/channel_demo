(ns mc.log2sql
  (:require [clj-http.client :as client]
            [clojure.core.reducers :as r]
            [clojure.core.async :as async] ;; [>! <! >!! <!! go chan buffer close! thread alts! alts!! timeout put!]]
            [clojure.java.jdbc :as jdbc] ;; :refer :all]
            [clojure.java.io :as io]
            [clojure.tools.namespace.repl :as tns]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.pprint :refer :all]
            [clojure.repl :refer [doc]]
            [clostache.parser :refer [render]]
            [ring.adapter.jetty :as ringa]
            [ring.util.response :as ringu]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.multipart-params :refer [wrap-multipart-params]])
  (:gen-class))

;; Don't forget to create the table
;; cat schema.sql | sqlite3 logger.db

(def db
  {:classname   "org.sqlite.JDBC"
   :subprotocol "sqlite"
   :subname     "logger.db"
   })

(def conn {:connection (jdbc/get-connection db)})

(defn ex-4
  "Multiple transactions. This is 20x slower than ex-3."
  []
  (let [sth (jdbc/prepare-statement (:connection conn) "insert into log (date,msg) values (?,?)")
        data-xx (mapv (fn [xx] ["2017-12-10" xx]) (range 0 1000))
        data-yy (mapv (fn [xx] ["2017-12-10" xx]) (range 1000 2000))]
     (doseq [params (into data-xx data-yy)]
       (jdbc/execute! conn (apply conj ["insert into log (date,msg) values (?,?)"] params)))
     true))

(defn ex-3
  "execute! with a vector of data does many inserts per transaction. This will be 2 transactions."
  []
  (let [sth (jdbc/prepare-statement (:connection conn) "insert into log (date,msg) values (?,?)")
        data-xx (mapv (fn [xx] ["2017-12-10" xx]) (range 0 1000))
        data-yy (mapv (fn [xx] ["2017-12-10" xx]) (range 1000 2000))]
    (jdbc/execute! conn (apply conj ["insert into log (date,msg) values (?,?)"] data-xx) {:multi? true})
    (jdbc/execute! conn (apply conj ["insert into log (date,msg) values (?,?)"] data-yy) {:multi? true})
     true))

;; See jdbc/db-transaction* 

(defn ex-2
  "This runs 2 transactions, but isn't what I want."
  []
  (jdbc/with-db-transaction [dbh db] ;; originally connection
    (let [sth (jdbc/prepare-statement (:connection dbh) "insert into log (date,msg) values (?,?)")]
      (loop [nseq (range 1000)]
        (let [num (first nseq)
              remainder (rest nseq)]
          (if (nil? num)
            nil
            (do
              (jdbc/execute! dbh [sth "2017-12-10" num] )
              ;; (jdbc/execute! dbh ["insert into entry (title,stars) values (?,?)" (str "demo transaction" num) num])
              (recur remainder)))))))
  (jdbc/with-db-transaction [dbh db] ;; originally connection
    (let [sth (jdbc/prepare-statement (:connection dbh) "insert into log (date,msg) values (?,?)")]
      (loop [nseq (range 1000)]
        (let [num (first nseq)
              remainder (rest nseq)]
          (if (nil? num)
            nil
            (do
              (jdbc/execute! dbh [sth "2017-12-10" (+ 2000 num)] )
              ;; (jdbc/execute! dbh ["insert into entry (title,stars) values (?,?)" (str "demo transaction" num) num])
              (recur remainder))))))))

(defn ex-1
  "Transaction where I choose when to commit. Does not work: 
SQLException [SQLITE_ERROR] SQL error or missing database (cannot start a transaction within a transaction)  org.sqlite.DB.newSQLException (DB.java:383)"
  []
  (jdbc/with-db-transaction [dbh db] ;; originally connection
    (let [sth (jdbc/prepare-statement (:connection dbh) "insert into log (date,msg) values (?,?)")]
      (jdbc/execute! dbh ["begin"] )
      (loop [nseq (range 1000)]
        (let [num (first nseq)
              remainder (rest nseq)]
          (if (nil? num)
            nil
            (do
              (jdbc/execute! dbh [sth "2017-12-10" num] )
              ;; (jdbc/execute! dbh ["insert into entry (title,stars) values (?,?)" (str "demo transaction" num) num])
              (recur remainder)))))
      (jdbc/execute! dbh ["commit"] )
      (jdbc/execute! dbh ["begin"] )
      (loop [nseq (range 10000)]
        (let [num (first nseq)
              remainder (rest nseq)]
          (if (nil? num)
            nil
            (do
              (jdbc/execute! dbh [sth "2017-12-10" num] )
              ;; (jdbc/execute! dbh ["insert into entry (title,stars) values (?,?)" (str "demo transaction" num) num])
              (recur remainder)))))
      (jdbc/execute! dbh ["commit"]))))

;; https://stackoverflow.com/questions/39765943/clojure-java-jdbc-lazy-query
;; https://jdbc.postgresql.org/documentation/83/query.html#query-with-cursor
;; http://clojure-doc.org/articles/ecosystem/java_jdbc/using_sql.html#exception-handling-and-transaction-rollback
;; http://clojure-doc.org/articles/ecosystem/java_jdbc/using_sql.html#using-transactions

(defn ex-lazy-select
  []
  (jdbc/with-db-transaction [tx db] ;; originally connection
    (jdbc/query tx
                [(jdbc/prepare-statement (:connection tx)
                                         "select * from mytable"
                                         {:fetch-size 10})]
                {:result-set-fn (fn [result-set] result-set)})))

(defn demo-autocommit
  "Demo looping SQL without a transaction. Every execute will auto-commit, which is time consuming. This
  function takes 62x longer than doing these queries inside a single transaction."
  []
  (jdbc/execute! db ["delete from entry where title like 'demo transaction%'"])
  (loop [nseq (range 10000)]
    (let [num (first nseq)
          remainder (rest nseq)]
      (if (nil? num)
        nil
        (do
          (jdbc/execute! db ["insert into entry (title,stars) values (?,?)" (str "demo transaction" num) num])
          (recur remainder))))))

(defn demo-transaction
  "Demo looping SQL inside a transaction. This seems to lack an explicit commit, which makes it tricky to
commit every X SQL queries. Use doall or something to un-lazy inside with-db-transaction, if you need the
query results.

http://pesterhazy.karmafish.net/presumably/2015-05-25-getting-started-with-clojure-jdbc-and-sqlite.html"
  []
  (jdbc/with-db-transaction [dbh db]
    (jdbc/execute! dbh ["delete from entry where title like 'demo transaction%'"])
    (loop [nseq (range 10000)]
      (let [num (first nseq)
            remainder (rest nseq)]
        (if (nil? num)
          nil
          (do
            (jdbc/execute! dbh ["insert into entry (title,stars) values (?,?)" (str "demo transaction" num) num])
          (recur remainder)))))))
