(defproject crux-datascript "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [juxt/crux-core "20.01-1.6.3-alpha-SNAPSHOT"]
                 [datascript "0.18.8"]]
  :repl-options {:init-ns crux-datascript.core})
