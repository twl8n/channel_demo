(ns mc.core
  (:require [clj-http.client :as client]
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

;; https://github.com/dakrone/clj-http

;; 1149 ms
(defn ex1 []
  (time
   (def yy
     (mapv
      #(client/get "http://laudeman.com/" {:query-params {"q" %}})
      ["pie" "cake" "cookie" "flan"]))))

;; 200 to 300 ms
(defn ex2 []
  (time (def xx (client/get "http://laudeman.com/" {:query-params {"q" "foo, bar"}}))))

(defn quick-requ [xx]
  (client/get "http://laudeman.com/" {:query-params {"q" xx}}))

(defn ex3 []
  (def mychan (chan))
  (go (>! mychan (quick-requ "pie")))
  (alts!! [mychan (timeout 10000)]))

(defn use-chans
  [tout]
  (let [mychan (chan)]
    (mapv #(go (>! mychan (quick-requ %)))
          ["pie" "cake" "cookie" "flan"])
    (loop [hc []]
      (let [[input channel] (time (alts!! [mychan (timeout tout)]))]
        (if (nil? input)
          hc
          (recur (conj hc input)))))))

;; (time (def zz (use-chans 10000)))

;; mc.core=> (time (def zz (use-chans 10000)))
;; "Elapsed time: 3274.465471 msecs"
;; "Elapsed time: 219.477439 msecs"
;; "Elapsed time: 263.948376 msecs"
;; "Elapsed time: 287.666309 msecs"
;; "Elapsed time: 10001.843969 msecs"
;; "Elapsed time: 14050.325077 msecs"
;; #'mc.core/zz


(defn fixed-chans
  "Take as many items as were put. The timeout is only a safety net."
  [tout]
  (let [mychan (chan)]
    (mapv #(go (>! mychan (quick-requ %)))
          ["pie" "cake" "cookie" "flan"])
    (loop [hc []
           ndx 0]
      (let [[input channel] (time (alts!! [mychan (timeout tout)]))]
        (if (>= ndx 3)
          hc
          (recur (conj hc input) (inc ndx)))))))

;; (time (def zz (fixed-chans 10000)))

;; mc.core=> (time (def zz (fixed-chans 10000)))
;; "Elapsed time: 1687.492677 msecs"
;; "Elapsed time: 540.0827 msecs"
;; "Elapsed time: 528.715935 msecs"
;; "Elapsed time: 1018.836635 msecs"
;; "Elapsed time: 3778.000257 msecs"
;; #'mc.core/zz
;; mc.core=> (time (def zz (fixed-chans 10000)))
;; "Elapsed time: 1198.047675 msecs"
;; "Elapsed time: 406.607082 msecs"
;; "Elapsed time: 449.838298 msecs"
;; "Elapsed time: 281.651147 msecs"
;; "Elapsed time: 2338.639543 msecs"
;; #'mc.core/zz


(defn multi-chans
  "Create 4 channels, one for each request."
  [tout]
  (let [multis 
        (loop [cseq [(chan)]
               alist ["pie" "cake" "cookie" "flan"]]
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
        (if (>= ndx 3)
          rval
          (recur hc (inc ndx) (conj rval input)))))))

;; (def xx (multi-chans 5000))

;; "Elapsed time: 1210.406838 msecs"
;; "Elapsed time: 366.083887 msecs"
;; "Elapsed time: 241.897777 msecs"
;; "Elapsed time: 369.168177 msecs"
