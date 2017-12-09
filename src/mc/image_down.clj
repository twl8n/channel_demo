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

(defn fxget-binary
  "Get that will return a failure value, not an exception. Call without opts for defaults, or with opts to
  ignore the defaults."
  ([url]
   (log/info "Starting outer" url)
   (fxget-binary url {:socket-timeout default-timeout :as :byte-array :throw-exceptions false}))
  ([url opts]
   (log/info "Starting inner" url)
   (let [https-url (str/replace url #"http://" "https://")
         dest-file (str "images/" (nth (re-find  #"http.*://.*/(.*?)(?:\?.*$|$)" https-url) 1))
         resp (try (client/get https-url opts)
                   (catch Exception e (fexmap (.toString e))))
         img (:body resp)]
     (if (some? img)
       (do
         (with-open [wr (io/output-stream dest-file)]
           (.write wr img))
         (log/info "Wrote " dest-file))
       (log/info "Failed on " dest-file)))))


(defn -main []
  (let [url-list (str/split (slurp "image_urls.txt") #"\n")]
    ;; make-parents is weird. Must be a better way.
    (io/make-parents "./images/foo.txt")
    (count (vec (pmap #(fxget-binary %) url-list)))))
