(ns mc.core
  (:require [clj-http.client :as client]
            [clojure.core.reducers :as r]
            [clojure.core.async :as async] ;; [>! <! >!! <!! go chan buffer close! thread alts! alts!! timeout put! go-loop]]
            [clojure.java.jdbc :as jdbc] ;; :refer :all]
            [clojure.java.io :as io]
            [clojure.tools.namespace.repl :as tnr]
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
(def tout default-timeout)

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

(defn fxget-binary
  "Get that will return a failure value, not an exception."
  ([url]
   (fxget-binary url {}))
  ([url opts]
   (let [dest-file (nth (re-find  #"https://.*/(.*?)(?:\?.*$|$)" url) 1)
         resp (try (client/get url (merge opts {:as :byte-array :throw-exceptions false}))
                   (catch Exception e (fexmap (.toString e))))
         img (:body resp)]
     (if (some? img)
       (do
         (with-open [wr (io/output-stream dest-file)]
           (.write wr img))
         (str "Wrote " dest-file))
       (str "Failed on " dest-file)))))
  


(comment
  (fxget-binary "https://img.nh-hotels.net/nh_collection_aeropuerto_t2_mexico-046-restaurant.jpg?crop=1535:619;0,332&resize=2000:805")

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
  "Create a channel for each request. Using logs of channels doesn't seem to help."
  [tout]
  (let [multis (loop [cseq []
                       alist qlist]
                  (let [mychan (async/chan)
                        arg (first alist)
                        reman (rest alist)]
                    (async/go (async/>! mychan (quick-requ arg)))
                    (if (empty? reman)
                      cseq
                      (recur (conj cseq mychan) reman))))]
     (loop [hc multis
            ndx 0
            rval []]
       (let [[input channel] (time (async/alts!! (conj multis (async/timeout tout))))]
         (if (>= ndx (dec (count qlist)))
           rval
           (recur hc (inc ndx) (conj rval input)))))))

(defn multi-chans-buggy
  "Create lot of channel, but the bug it that we use one channel for all requests. 
  Speed is about the same as the non-buggy version above."
  [tout]
  (let [multis (loop [cseq [(async/chan)]
                       alist qlist]
                 ;; Bug! conj post-pends, so (first cseq) is always the same channel
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
  "Create a channel for each request. Use async/put! instead of go >! Interesting that this is still slower than ex4."
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

(comment
  (defn foo []
    (println "Running forever...?")
    (async/<!! (async/go-loop [n 0]
             (prn n)
             (async/<! (async/timeout 10))
             (recur (inc n)))))
  
  (go-loop [seconds 1]
    (<! (timeout 1000))
    (print "waited" seconds "seconds")
    (recur (inc seconds)))
  )


(defn multi-reporter []
  (let [a (async/chan)  ; a channel for a to report it's answer
        b (async/chan)  ; a channel for b to report it's answer
        output (async/chan)] ; a channel for the reporter to report back to the repl
    (async/go (while true
          (async/<!! (async/timeout (rand-int 1000))) ; process a
          (async/>! a (rand-nth [true false]))))
    (async/go (while true
          (async/<!! (async/timeout (rand-int 1000))) ; process b
          (async/>! b (rand-nth [true false]))))
    ;; the reporter process
    (async/<!! (async/go (while true
          ;; (async/>! output (and (async/<!! a) (async/<!! b)))
          ;; (async/>! output (and (async/<!! a) (async/<!! b)))
          (do
            (prn "output: " (and (async/<!! a) (async/<!! b)))
            true))))
    output))

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

;; Async http get requests via 4 agents.
(def ag-list (map (fn [xx] (agent [])) (range 4)))

(defn agent-get [myagent short-qlist]
  (prn "mya: " myagent)
  (doall (map
   (fn [qparam] (send myagent #(conj % (client/get turl {:query-params {"q" qparam}}))))
   short-qlist)))

(defn ex-agent []
  (prn "ag-list: " ag-list)
  (doall (map (fn [ag ql]
         (prn "ag: " ag "ql: " ql)
         (send ag (fn [xx] []))
         (agent-get ag ql)) ag-list (partition 2 qlist)))
  (apply await-for 10000 ag-list)
  (map #(prn (count (deref %))) ag-list))
;; (count (:body (first (deref (first ag-list)))))

;; Logging via an agent
(def ag-log (agent []))

(defn agent-printf
  "Overload printf with printf to an agent"
  []
  (def core-printf clojure.core/printf)
  (defn printf [fmt & args]
    ;; (prn "have: " args)
    (send ag-log #(conj % (with-out-str (apply core-printf fmt args))))))

(defn test-ag []
  (agent-printf)
  ;; Agents don't die, so set/reset the value of our agent, in case this fn is run multiple times.
  (send ag-log (fn [xx] []))
  (doseq [cc (range 2048)]
    (printf "this is %s\n" cc))
  (await-for 10000 ag-log)
  (println "Have " (count @ag-log) " log messages."))

;; This appears to work, and test-big-p below does not.
(defn test-ag-p []
  (agent-printf)
  ;; Agents don't die, so set/reset the value of our agent, in case this fn is run multiple times.
  (send ag-log (fn [xx] []))
  (time (do (doall (pmap #(printf "this is %s\n" %) (range 1000)))
            (await-for 10000 ag-log)))
  (println "Have " (count @ag-log) " log messages."))

;; Slower than test-ag-p above. Almost 4x slower.
;; Run this via run-mca below so that setup and post-run printing isn't counted in time.
(defn multi-channel-agent
  "Create lots of channels all writing to a single agent in an attempt to deadlock or slow the agent."
  [tout]
  (let [run-size 1000 ;; Must be less than impl/MAX-QUEUE-SIZE
        multis (loop [cseq []
                      alist (range run-size)]
                 (let [mychan (async/chan)
                       arg (first alist)
                       reman (rest alist)]
                   (async/go (async/>! mychan (printf "this is %s\n" arg)))
                   (if (empty? reman)
                     cseq
                     (recur (conj cseq mychan) reman))))]
    (loop [hc multis
           ndx 0
           rval []]
      (let [[input channel] (async/alts!! (conj multis (async/timeout tout)))]
        (if (>= ndx (dec run-size))
          rval
          (recur hc (inc ndx) (conj rval input)))))))

(defn run-mca []
  (agent-printf)
  (send ag-log (fn [xx] []))
  (time (do (multi-channel-agent 1)
            (await-for 10000 ag-log)))
  (prn "first: " (first @ag-log))
  (println "Have " (count @ag-log) " log messages."))

;; Logging via an atom. 
(def all-log (atom []))

(defn atom-printf
  "Overload printf with printf to an atom"
  []
  (def core-printf clojure.core/printf)
  (defn printf [fmt & args]
    (swap! all-log #(conj % (with-out-str (apply core-printf fmt args))))))

(defn test-atom []
  (atom-printf)
  (swap! all-log (fn [xx] []))
  (doseq [cc (range 2048)]
    (printf "this is %s\n" cc))
  (println "Have " (count @all-log) " log messages."))

(defn test-atom-p []
  (atom-printf)
  (swap! all-log (fn [xx] []))
  (doall (pmap #(printf "this is %s\n" %) (range 2048)))
  (println "Have " (count @all-log) " log messages."))
  
;; Demo logging via a channel.

;; Channel for log messages
(def logch (async/chan))

(defn channel-printf
  "Overload printf with printf to a channel"
  []
  (def core-printf clojure.core/printf)
  (defn printf [fmt & args]
    (async/put! logch (with-out-str (apply core-printf fmt args)))))

(defn log-msgs []
  (loop [hc []]
    (let [[input channel] (async/alts!! [logch (async/timeout tout)])]
      (if (nil? input)
        hc
        (recur (conj hc input))))))

;; This only works for < 1024 messages:

;; AssertionError Assert failed: No more than 1024 pending puts are allowed on a single channel. Consider using a windowed buffer.
;; (< (.size puts) impl/MAX-QUEUE-SIZE)  clojure.core.async.impl.channels.ManyToManyChannel (channels.clj:152)

(defn test-logs []
  (channel-printf)
  (doseq [cc (range 1023)]
    (printf "this is %s\n" cc))
  (let [msg (log-msgs)]
    (println "Have " (count msg) " log messages.")))

