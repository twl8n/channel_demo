(ns mc.core
  (:require [clj-http.client :as client]
            [clojure.core.reducers :as r]
            [clojure.core.async
             :as a
             :refer [>! <! >!! <!! go chan buffer close! thread
                     alts! alts!! timeout]]
            [clojure.java.jdbc :as jdbc] ;; :refer :all]
            [clojure.tools.namespace.repl :as tns]
            [clojure.string :as str]
            [clojure.pprint :refer :all]
            [clostache.parser :refer [render]]
            [ring.adapter.jetty :as ringa]
            [ring.util.response :as ringu]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.multipart-params :refer [wrap-multipart-params]])
  (:gen-class))

(def turl "http://laudeman.com/")
(def qlist ["pie" "cake" "cookie" "flan" "mousse" "cupcake" "pudding" "torte"])
(def default-timeout 10000)

(defn fexmap
  "Failure as a map instead of an exception. Wrap up exceptions to look like a normal response instead of
  throwing an exception. Add an error status and the exception message as the body. Valid normal reason-phrase
  values: OK, Not Found, etc. This situation is not normal, so we add: Error. :trace-redirects is a seq of
  URLs we were redirected to, if any."
  [exval]
  (let [status (cond (some? (re-find #"timeout" exval))
                     498
                     :else
                     499)]
  {:request-time 0
   :repeatable? false
   :streaming? false
   :chunked? false
   :reason-phrase "Error"
   :headers {}
   :orig-content-encoding nil
   :status status
   :length (count exval)
   :body exval
   :trace-redirects []}))
  
(comment
  (def xx (fxget "http://httpbin.org/delay/10"))
  (def xx (fxget "http://httpbin.org/redirect/2"))
  (def xx (fxget "http://httpbin.org/redirect/1"))
  )

(defn fxget
  "Get that will return a failure value, not an exception."
  [url opts]
  (try (client/get url opts)
       (catch Exception e (fexmap (.toString e)))))
  

;; 1149 ms
(defn ex1 []
  (def yy
    (time 
     (mapv
      #(time (client/get turl {:query-params {"q" %}}))
      qlist))))

;; 200 to 300 ms
(defn ex2 []
  (time (def xx (client/get turl {:query-params {"q" "foo, bar"}}))))

(defn quick-requ [xx]
  (client/get turl {:query-params {"q" xx}}))

(defn ex3 []
  (def mychan (chan))
  (go (>! mychan (quick-requ "pie")))
  (alts!! [mychan (timeout 10000)]))


(defn ex4 []
  (vec (pmap
        #(time (client/get turl {:query-params {"q" %} :socket-timeout default-timeout}))
        qlist)))

(defn ex430 []
  (vec (pmap
        #(time (fxget turl {:query-params {"q" %} :socket-timeout 700}))
        qlist)))


(defn ex420 []
  (client/with-connection-pool {:timeout 10000 :threads 4 :insecure? false :default-per-route 10}
    (vec (pmap
          #(time (client/get turl {:query-params {"q" %} :socket-timeout default-timeout}))
          qlist))))

(defn ex421 []
  (client/with-connection-pool {:timeout 10000 :threads 4 :insecure? false :default-per-route 10}
    (vec (map
          #(time (client/get turl {:query-params {"q" %} :socket-timeout default-timeout}))
          qlist))))

(comment
  (with-connection-pool {:timeout 5 :threads 4 :insecure? false :default-per-route 10}
    (get "http://example.org/1")
    (post "http://example.org/2")
    (get "http://example.org/3")
    ...
    (get "http://example.org/999"))
  )

(defn ex41 [tout]
  (let [mychan (chan)
        all-e []]
    (mapv
     #(client/get turl {:async? true
                        :query-params {"q" %}}
                  (fn [xx] (>!! mychan xx))
                  (fn [xx] (conj all-e xx)))
     qlist)
    (loop [hc []
           ndx 0]
      (let [[input channel] (time (alts!! [mychan (timeout tout)]))]
        (if (>= ndx (- (count qlist) 1))
          hc
          (recur (conj hc input) (inc ndx)))))))


(defn ex5 []
  (into [] (r/map
            #(time (client/get turl {:query-params {"q" %}}))
            qlist)))

(defn ex6
  "Much slower than ex4."
  []
  (r/foldcat (r/map
            #(time (client/get turl {:query-params {"q" %}}))
            qlist)))

(defn ex7
  "Interestingly, this is one of the faster methods. Compare this to the source of pmap."
  []
  (let [thseq (map
               #(time (future (client/get turl {:query-params {"q" %}})))
               qlist)]
    (vec (map #(deref % default-timeout nil) thseq))))
    
(defn ex8
  "Seems to be the same speed as ex7, but harder to read. Compare this to the source of pmap."
  []
  (vec (map deref
            (map
             #(time (future (client/get turl {:query-params {"q" %}})))
             qlist))))

(defn use-chans
  "Would using a go with blocking take for reading remove the wait for the last timeout?"
  [tout]
  (let [mychan (chan)]
    (mapv #(go (>! mychan (quick-requ %)))
          qlist)
    (loop [hc []]
      (let [[input channel] (time (alts!! [mychan (timeout tout)]))]
        (if (nil? input)
          hc
          (recur (conj hc input)))))))

(defn fixed-chans
  "Single channel, fixed number of takes. Take as many items as were put. The timeout is only a safety net."
  [tout]
  (let [mychan (chan)]
    (mapv #(go (>! mychan (quick-requ %)))
          qlist)
    (loop [hc []
           ndx 0]
      (let [[input channel] (time (alts!! [mychan (timeout tout)]))]
        (if (>= ndx (- (count qlist) 1))
          hc
          (recur (conj hc input) (inc ndx)))))))

(defn multi-chans
  "Create 4 channels, one for each request."
  [tout]
  (let [multis (loop [cseq [(chan)]
                       alist qlist]
                  (let [mychan (first cseq)
                        arg (first alist)
                        reman (rest alist)]
                    (go (>! mychan (quick-requ arg)))
                    (if (empty? reman)
                      cseq
                      (recur (conj cseq (chan)) reman))))]

     (loop [hc multis
            ndx 0
            rval []]
       (let [[input channel] (time (alts!! (conj multis (timeout tout))))]
         (if (>= ndx (dec (count qlist)))
           rval
           (recur hc (inc ndx) (conj rval input)))))))

(comment 
  (time (def xx (multi-chans 5000)))
  (time (def yy (ex4)))
  (time (def zz (fixed-chans 10000)))
  ;; Make sure we got complete results
  (map #(count (:body %)) yy)
  )

