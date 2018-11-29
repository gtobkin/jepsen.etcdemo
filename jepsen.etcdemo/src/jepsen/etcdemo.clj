(ns jepsen.etcdemo
  (:require [clojure.tools.logging :refer :all]
            [clojure.string :as str]
            [jepsen [checker  :as checker]
                    [cli      :as cli]
                    [client   :as client]
                    [control  :as c]
                    [db       :as db]
                    [generator :as gen]
                    [nemesis :as nemesis]
                    [tests    :as tests]]
            [jepsen.control.util :as cu]
            [jepsen.os.debian :as debian]
            [knossos.model :as model]
            [slingshot.slingshot :refer [try+]]
            [jepsen.checker.timeline :as timeline]
            [verschlimmbesserung.core :as v]))

(def dir "/opt/etcd")
(def binary "etcd")
(def logfile (str dir "/etcd.log"))
(def pidfile (str dir "/etcd.pid"))

(defn node-url
  "An HTTP url for connecting to a node on a particular port."
  [node port]
  (str "http://" (name node) ":" port))

(defn peer-url
  "The HTTP url for other peers to talk to a node."
  [node]
  (node-url node 2380))

(defn client-url
  "The HTTP url clients use to talk to a node."
  [node]
  (node-url node 2379))

(defn initial-cluster
  "Constructs an initial cluster string for a test, like
  \"foo=foo:2380,bar=bar:2380,...\""
  [test]
  (->> (:nodes test)
       (map (fn [node]
              (str node "=" (peer-url node))))
       (str/join ",")))

(defn r   [_ _] {:type :invoke, :f :read,   :value nil})
(defn w   [_ _] {:type :invoke, :f :write,  :value (rand-int 5)})
(defn cas [_ _] {:type :invoke, :f :cas,    :value [(rand-int 5) (rand-int 5)]})

(defn parse-long
  "Parses a string to a Long. Passes through 'nil'."
  [s]
  (when s (Long/parseLong s)))

(defrecord Client [conn]
  client/Client
  (open! [this test node]
    (assoc this :conn (v/connect (client-url node)
               {:timeout 5000})))

  (setup! [this test])

  (invoke! [_ test op]
    (try+
      (case (:f op)
            :read (try (assoc op
                         :type :ok
                         ; Single dummy key, "foo", for now.
                         :value (parse-long (v/get conn "foo" {:quorum? true}))))
            :write (do (v/reset! conn "foo" (:value op))
                       (assoc op :type, :ok))
            :cas (let [[old new] (:value op)]
                    (assoc op :type (if (v/cas! conn "foo" old new)
                                      :ok
                                      :fail))))

      (catch java.net.SocketTimeoutException e
        (assoc op
               :type (if (= :read (:f op)) :fail :info)
               :error :timeout))

      (catch [:errorCode 100] e
        (assoc op :type :fail, :error :not-found))))


  (teardown! [this test])

  (close! [_ test]
    ; Connection isn't (yet?) stateful; no destruction required here
    ))

(defn db
  "Constructs a database for the given etcd version."
  [version]
  (reify db/DB
    (setup! [_ test node]
      (c/su
        (info node "setting up etcd" version)
        (let [url (str "https://storage.googleapis.com/etcd/" version
                       "/etcd-" version "-linux-amd64.tar.gz")]
          (cu/install-archive! url dir)
          (cu/start-daemon!
            {:logfile logfile
             :pidfile pidfile
             :chdir dir}
            binary
            :--log-output                   :stderr
            :--name                         node
            :--listen-peer-urls             (peer-url node)
            :--listen-client-urls           (client-url node)
            :--advertise-client-urls        (client-url node)
            :--initial-cluster-state        :new
            :--initial-advertise-peer-urls  (peer-url node)
            :--initial-cluster              (initial-cluster test))
          ; Quick hack to get us through demo
          (Thread/sleep 5000))))

    (teardown! [_ test node]
      (info node "tearing down etcd")
      (cu/stop-daemon! binary pidfile)
      (c/su (c/exec :rm :-rf dir)))

    db/LogFiles
    (log-files [_ test node]
      [logfile])))

(defn etcd-test
  "Given an options map from the command line runner (e.g. :nodes, :ssh,
  :concurrency, ...), constructs a test map."
  [opts]
  (merge tests/noop-test
         opts
         {:name "etcd"
          :db (db "v3.1.5")
          :os debian/os
          :client (Client. nil) ; no connection for now; this is seed client
          :nemesis (nemesis/partition-random-halves)
          :model (model/cas-register)
          :checker (checker/compose {:linear (checker/linearizable)
                                     :perf (checker/perf)
                                     :timeline (timeline/html)})
          :generator (->> (gen/mix [r w cas])
                          (gen/stagger 0.1)
                          (gen/nemesis (->> [(gen/sleep 5)
                                             {:type :info, :f :start, :value nil}
                                             (gen/sleep 5)
                                             {:type :info, :f :stop, :value nil}]
                                            cycle
                                            gen/seq))
                          (gen/time-limit (opts :time-limit)))}))

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  ; This is a comment. Clojure comments are preceded by a semicolon.
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn etcd-test})
                   (cli/serve-cmd))
            args))

