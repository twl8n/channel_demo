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

(def turl "https://google.com/")
(def qlist ["pie" "cake" "cookie" "flan" "mousse" "cupcake" "pudding" "torte"])

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
        #(time (client/get turl {:query-params {"q" %}}))
        qlist)))



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

