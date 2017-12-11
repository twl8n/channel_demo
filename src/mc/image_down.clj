(ns mc.image-down
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

(def qlist ["pie" "cake" "cookie" "flan" "mousse" "cupcake" "pudding" "torte"])
(def default-timeout 10000)

(defn fexmap
  "Failure as a map instead of an exception. Wrap up exceptions to look like a normal response instead of
  throwing an exception. Add an error status and the exception message as the body. Valid normal reason-phrase
  values: OK, Not Found, etc. This situation is not normal, so we add: Error. :trace-redirects is a seq of
  URLs we were redirected to, if any."
  [exval]
  (let [stat-map (cond (some? (re-find #"(?i:timeout|timed out)" exval))
                       {:status 408
                        :reason-phrase "Timeout"}
                       :else ;; There is no http general error, so lets go with the convention(?) of using 520.
                       {:status 520
                        :reason-phrase "Unknown Error"})]
    (merge {:request-time 0
            :repeatable? false
            :streaming? false
            :chunked? false
            :headers {}
            :orig-content-encoding nil
            :length (count exval)
            :body exval
            :trace-redirects []} stat-map)))

(comment 
  (type (byte-array (map byte (vec "foo"))))
  (instance? (Class/forName "[B") (byte-array (map byte (vec "foo"))))
  (def byte-array-type (Class/forName "[B"))
  (instance? byte-array-type (byte-array (map byte (vec "foo"))))
  ;; true

  )

  ;; ([url dest-file]
  ;;  (log/info "got " url dest-file)
;;  (fxget-binary url {:socket-timeout default-timeout :as :byte-array :throw-exceptions false}))

(def opts {:socket-timeout default-timeout :as :byte-array :throw-exceptions false})

;; https://stackoverflow.com/questions/8558362/writing-to-a-file-in-clojure
;; ...  BufferedWriter has multiple overloaded versions of write and clojure doesn't know which one to call.
;; Java sucks. That said, when the response is a string 404, we shouldn't write that into a file with a .jpg
;; extension.

(def byte-array-type (Class/forName "[B"))

(defn fxget-binary
  "Get that will return a failure value, not an exception. Call without opts for defaults, or with opts to
  ignore the defaults."
  [url dest-file]
   (let [resp (try (client/get url opts)
                   (catch Exception e (fexmap (.toString e))))
         img (:body resp)]
     (if (and (some? img)
              (instance? byte-array-type img)
              (= (:status resp) 200))
       (do
         (with-open [wr (io/output-stream dest-file)]
           (.write wr img))
         (log/info "Wrote " dest-file " img is " (type img)))
       (log/info "Failed on " dest-file " status: " (:status resp)))))

(defn -main []
  (let [url-list (str/split (slurp "image_urls.txt") #"\n")
        hu-list (map #(str/replace % #"http://" "https://") url-list)
        df-list (map #(str "./images/" (nth (re-find  #"http.*://.*/(.*?)(?:\?.*$|$)" %) 1)) hu-list)
        flist (mapv str (filter #(.isFile %) (file-seq (clojure.java.io/file "./images/"))))
        ;; names-only (map #(str/replace % #"^\./images/" "") flist)
        ;; zipmap is cute, but probably better off with list of maps {:dest-file "xx" :url "yy"}
        big-list (zipmap df-list hu-list)
        final-list (apply dissoc big-list flist)]
    ;; make-parents is weird. Must be a better way.
    (io/make-parents "./images/foo.txt")
    (count (vec (pmap #(fxget-binary (second %) (first %)) final-list)))))
