(ns flow-short-circuit.core
  (:require [clojure.core.async :refer [chan >!! <!! close!]]
            [onyx.plugin.core-async :refer [take-segments!]]
            [onyx.api]))

(def workflow
  [[:in :identity]
   [:identity :out]])

(defn my-identity [{:keys [n] :as segment}]
  segment)

(def capacity 1000)

(def input-chan (chan capacity))

(def output-chan (chan capacity))

(def batch-size 10)

(def catalog
  [{:onyx/name :in
    :onyx/plugin :onyx.plugin.core-async/input
    :onyx/type :input
    :onyx/medium :core.async
    :onyx/max-peers 1
    :onyx/batch-size batch-size
    :onyx/doc "Reads segments from a core.async channel"}

   {:onyx/name :identity
    :onyx/fn :flow-short-circuit.core/my-identity
    :onyx/type :function
    :onyx/batch-size batch-size
    :parameterized.core/k 42}

   {:onyx/name :out
    :onyx/plugin :onyx.plugin.core-async/output
    :onyx/type :output
    :onyx/medium :core.async
    :onyx/max-peers 1
    :onyx/batch-size batch-size
    :onyx/doc "Writes segments to a core.async channel"}])

(def flow-conditions
  [{:flow/from :identity
    :flow/to :all
    :flow/short-circuit? true
    :flow/predicate :flow-short-circuit.core/greater-than-5?}
   {:flow/from :identity
    :flow/to :none
    :flow/short-circuit? true
    :flow/predicate :flow-short-circuit.core/odd-segment?}
   {:flow/from :identity
    :flow/to [:out]
    :flow/short-circuit? false
    :flow/predicate :flow-short-circuit.core/positive-segment?}])

(defn greater-than-5? [event old {:keys [n]} all-new]
  (> n 5))

(defn odd-segment? [event old {:keys [n]} all-new]
  (odd? n))

(defn positive-segment? [event old {:keys [n]} all-new]
  (pos? n))

(def input-segments
  [{:n 0}
   {:n 1}
   {:n 2}
   {:n 3}
   {:n 4}
   {:n 5}
   {:n 6}
   {:n 7}
   {:n 8}
   {:n 9}
   :done])

(doseq [segment input-segments]
  (>!! input-chan segment))

(close! input-chan)

(def id (java.util.UUID/randomUUID))

(def env-config
  {:zookeeper/address "127.0.0.1:2188"
   :zookeeper/server? true
   :zookeeper.server/port 2188
   :onyx/tenancy-id id})

(def peer-config
  {:zookeeper/address "127.0.0.1:2188"
   :onyx/tenancy-id id
   :onyx.peer/job-scheduler :onyx.job-scheduler/balanced
   :onyx.messaging/impl :aeron
   :onyx.messaging/peer-port 40200
   :onyx.messaging/bind-addr "localhost"})

(defn inject-in-ch [event lifecycle]
  {:core.async/chan input-chan})

(defn inject-out-ch [event lifecycle]
  {:core.async/chan output-chan})

(def in-calls
  {:lifecycle/before-task-start inject-in-ch})

(def out-calls
  {:lifecycle/before-task-start inject-out-ch})

(def lifecycles
  [{:lifecycle/task :in
    :lifecycle/calls :flow-short-circuit.core/in-calls}
   {:lifecycle/task :in
    :lifecycle/calls :onyx.plugin.core-async/reader-calls}
   {:lifecycle/task :out
    :lifecycle/calls :flow-short-circuit.core/out-calls}
   {:lifecycle/task :out
    :lifecycle/calls :onyx.plugin.core-async/writer-calls}])

(def env (onyx.api/start-env env-config))

(def peer-group (onyx.api/start-peer-group peer-config))

(def n-peers (count (set (mapcat identity workflow))))

(def v-peers (onyx.api/start-peers n-peers peer-group))

(onyx.api/submit-job
 peer-config
 {:catalog catalog
  :workflow workflow :lifecycles lifecycles
  :flow-conditions flow-conditions
  :task-scheduler :onyx.task-scheduler/balanced})

(def results (take-segments! output-chan))

(clojure.pprint/pprint results)

(doseq [v-peer v-peers]
  (onyx.api/shutdown-peer v-peer))

(onyx.api/shutdown-peer-group peer-group)

(onyx.api/shutdown-env env)


