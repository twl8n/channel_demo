(ns mc.core
  (:require [clj-http.client :as client]
            [clojure.core.reducers :as r]
            [clojure.core.async :as async] ;; [>! <! >!! <!! go chan buffer close! thread alts! alts!! timeout put!]]
            [clojure.java.jdbc :as jdbc] ;; :refer :all]
            [clojure.tools.namespace.repl :as tns]
            [clojure.string :as str]
            [clojure.pprint :refer :all]
            [clojure.repl :refer [doc]]
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

(defn fxget
  "Get that will return a failure value, not an exception."
  ([url]
   (fxget url {}))
  ([url opts]
  (try (client/get url (merge opts {:throw-exceptions false}))
       (catch Exception e (fexmap (.toString e))))))
  
(comment
  (def xx (fxget "http://httpbin.org/delay/10"))
  (def xx (fxget "http://httpbin.org/redirect/2"))
  (def xx (fxget "http://httpbin.org/redirect/1"))
  (def xx (fxget "http://laudeman.com/foo.html/"))
  )

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
  (def mychan (async/chan))
  (async/go (async/>! mychan (quick-requ "pie")))
  (async/alts!! [mychan (async/timeout 10000)]))


(defn ex4
  "The simplest and one of the fastest examples. This lacks a back-pressure mechanism, but real back pressure
  requires callbacks. Callbacks are more complex and more likely to fail, often by hanging."
  []
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


(defn ex41 [tout]
  (let [mychan (async/chan)
        all-e []]
    (mapv
     #(client/get turl {:async? true
                        :query-params {"q" %}}
                  (fn [xx] (async/>!! mychan xx))
                  (fn [xx] (conj all-e xx)))
     qlist)
    (loop [hc []
           ndx 0]
      (let [[input channel] (time (async/alts!! [mychan (async/timeout tout)]))]
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
  "This waits for tout ms after the last take from mychan, because it has no way to know the channel is
  empty. Would using a go with blocking take for reading remove the wait for the last timeout?"
  [tout]
  (let [mychan (async/chan)]
    (mapv #(async/go (async/>! mychan (quick-requ %)))
          qlist)
    (loop [hc []]
      (let [[input channel] (time (async/alts!! [mychan (async/timeout tout)]))]
        (if (nil? input)
          hc
          (recur (conj hc input)))))))

(defn fixed-chans
  "Single channel, fixed number of takes. Take as many items as were put. The timeout is only a safety net."
  [tout]
  (let [mychan (async/chan)]
    (mapv #(async/go (async/>! mychan (quick-requ %)))
          qlist)
    (loop [hc []
           ndx 0]
      (let [[input channel] (time (async/alts!! [mychan (async/timeout tout)]))]
        (if (>= ndx (- (count qlist) 1))
          hc
          (recur (conj hc input) (inc ndx)))))))

(defn multi-chans
  "Create a channel for each request."
  [tout]
  (let [multis (loop [cseq [(async/chan)]
                       alist qlist]
                  (let [mychan (first cseq)
                        arg (first alist)
                        reman (rest alist)]
                    (async/go (async/>! mychan (quick-requ arg)))
                    (if (empty? reman)
                      cseq
                      (recur (conj cseq (async/chan)) reman))))]
     (loop [hc multis
            ndx 0
            rval []]
       (let [[input channel] (time (async/alts!! (conj multis (async/timeout tout))))]
         (if (>= ndx (dec (count qlist)))
           rval
           (recur hc (inc ndx) (conj rval input)))))))

(defn mc2
  "Create a channel for each request. Use a/put! instead of go >! Interesting that this is still slower than ex4."
  [tout]
  (let [multis (loop [cseq [(async/chan)]
                       alist qlist]
                  (let [mychan (first cseq)
                        arg (first alist)
                        reman (rest alist)]
                    (async/put! mychan (quick-requ arg))
                    (if (empty? reman)
                      cseq
                      (recur (conj cseq (async/chan)) reman))))]
     (loop [hc multis
            ndx 0
            rval []]
       (let [[input channel] (time (async/alts!! (conj multis (async/timeout tout))))]
         (if (>= ndx (dec (count qlist)))
           rval
           (recur hc (inc ndx) (conj rval input)))))))

(comment defn go-chan
  "Create a channel for each request."
  [tout]
  (let [multis (loop [cseq []
                      alist qlist]
                 (let [arg (first alist)
                       reman (rest alist)]
                   (if (empty? reman)
                     cseq
                     (recur (conj cseq (async/go (quick-requ arg))))) reman))]
     (loop [hc multis
            ndx 0
            rval []]
       (let [[input channel] (time (async/alts!! (conj multis (async/timeout tout))))]
         (if (>= ndx (dec (count qlist)))
           rval
           (recur hc (inc ndx) (conj rval input)))))))

;; https://github.com/clojure/core.async/wiki/Go-Block-Best-Practices

(defn foo "this doesn't do anything useful, yet."
  [params mychan]
  (async/put! mychan
          #(time (fxget turl {:query-params {"q" (first params)} :socket-timeout 700}))
          (fn [_] (foo (next params) mychan))))


(comment 
  (time (def xx (multi-chans 5000)))
  (time (def yy (ex4)))
  (time (def zz (fixed-chans 10000)))
  ;; Make sure we got complete results
  (map #(count (:body %)) yy)
  )


;; mc.core=> (def ic (mpc))
;; #'mc.core/ic
;; mc.core=> ic
;; #object[clojure.core.async.impl.channels.ManyToManyChannel 0x4cef23ca "clojure.core.async.impl.channels.ManyToManyChannel@4cef23ca"]
;; mc.core=> (async/>!! ic "foo")
;; We only accept numeric values! No Number, No Clothes!
;; true
;; mc.core=> (async/>!! ic 5)
;; "woo: " true
;; 5
;; mc.core=> 

(defn mpc []
  (let [payments (async/chan)]
    (async/go (while true
          (let [in (async/<! payments)]
            (if (number? in)
              (do (prn "woo: " in))
              ;; (do (println (async/<!! warehouse-channel)))
              (println "We only accept numeric values! No Number, No Clothes!")))))
    payments))

(comment
  (def ff (mpx))
  ;; If we listen for good data to stop looping, then send data.
  ;; If there are many listeners, each one would have to push 1 back onto the channel for the others.
  (async/>!! ff 1)

  ;; If we listen for non-nil (as below), then close the channel
  ;; This will allow many listeners on a single channel to all stop.
  (async/close! ff)
  )

(defn mpx
  "Print a message every 1000 ms in the background. Check the ctl channel, and if any input then close the
  channel and exit the loop."
  []
  (let [ctl (async/chan)]
  (async/go-loop [tchan ctl]
    (let [[input channel] (async/alts! [tchan] :default true)]
      (prn "input: " input)
      (if (nil? input)
        (do
          (prn "Stopping loop.")
          ;; (async/close! tchan)
          true)
        (do
          (Thread/sleep 1000)
          (prn "Still alive")
          (recur tchan)))))
  ctl))


(comment
  (close! bg)
  )

(defn bgp []
  (async/go (doseq [xx (range 10)]
        (Thread/sleep 1000)
          (prn "this is bgp"))))

(defn bga []
  (async/go (doseq [xx (range 10)]
        (Thread/sleep 1000)
          (prn "this is bga"))))

