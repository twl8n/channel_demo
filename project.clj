(defproject mc "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/core.async "0.3.443"]
                 [clj-http "3.7.0"] ;; used in http request experiments
                 [org.clojure/java.jdbc "0.7.0-alpha3"]
                 ;; Whereever org.clojure/java.jdbc "0.3.5" came from, it is more than 2 years out of date.
                 ;; [org.clojure/java.jdbc "0.3.5"]
                 [org.xerial/sqlite-jdbc "3.7.2"]
                 [de.ubercode.clostache/clostache "1.4.0"]
                 ;; [cljstache "2.0.0"]
                 [org.clojure/tools.namespace "0.2.11"]
                 [ring "1.5.0"]
                 [ring/ring-core "1.2.1"]
                 [ring/ring-jetty-adapter "1.2.1"]]
  :main ^:skip-aot mc.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
