(defproject panaeolus/africanus "1.0.0-alpha1"
  :description "Pattern bindings for live-coding in Overtone."
  :url "https://github.com/panaeolus/africanus"

  :license {:name "GNU Affero General Public License v3.0"
            :url  "https://www.gnu.org/licenses/gpl-3.0.en.html"}

  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/spec.alpha "0.1.143"]
                 [org.clojure/core.async "0.4.474"] 
                 [overtone/overtone "0.11.0"]
                 [overtone/scsynth "3.9.3-1"]
                 [overtone/scsynth-extras "3.9.3-1"]
                 [rm-hull/markov-chains "0.1.1"]]

  :jvm-opts ^:replace ["-Xms512m" "-Xmx1g"
                       "-XX:+UseParNewGC"
                       "-XX:+UseConcMarkSweepGC"
                       "-XX:+CMSConcurrentMTEnabled"
                       "-XX:MaxGCPauseMillis=20"
                       "-XX:MaxNewSize=257m"
                       "-XX:NewSize=256m"
                       "-XX:+UseTLAB"
                       "-XX:MaxTenuringThreshold=0"]
  
  ;; :native-path "native"
  
  :source-paths ["src"]

  )
