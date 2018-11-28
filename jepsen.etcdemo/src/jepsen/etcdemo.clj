(ns jepsen.etcdemo
  (:require [clojure.tools.logging :refer :all]
            [clojure.string :as str]
            [jepsen [cli :as cli]
                    [control :as c]
                    [db :as db]
                    [tests :as tests]]
            [jepsen.control.util :as cu]
            [jepsen.os.debian :as debian]))

(def dir "/opt/etcd")

(defn db
  "Constructs a database for the given etcd version."
  [version]
  (reify db/DB
    (setup! [_ test node]
      (c/su
        (info node "setting up etcd" version)
        (let [url (str "https://storage.googleapis.com/etcd/" version
                       "/etcd-" version "-linux-amd64.tar.gz")]
          (cu/install-archive! url dir))))

    (teardown! [_ test node]
      (info "Tearing down db"))))

(defn etcd-test
  "Given an options map from the command line runner (e.g. :nodes, :ssh,
  :concurrency, ...), constructs a test map."
  [opts]
  (merge tests/noop-test
         opts
         {:name "etcd"
          :db (db "v3.1.5")
          :os debian/os}))

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  ; This is a comment. Clojure comments are preceded by a semicolon.
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn etcd-test})
                   (cli/serve-cmd))
            args))

