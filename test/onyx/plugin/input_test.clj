(ns onyx.plugin.input-test
  (:require [clojure.core.async :refer [chan <!!]]
            [clojure.data.fressian :as fressian]
            [midje.sweet :refer :all]
            [clj-kafka.producer :as kp]
            [onyx.peer.task-lifecycle-extensions :as l-ext]
            [onyx.plugin.core-async]
            [onyx.plugin.kafka]
            [onyx.api]))

(def topic (str "onyx-test-" (java.util.UUID/randomUUID)))

(def producer
  (kp/producer
   {"metadata.broker.list" "127.0.0.1:9092"
    "serializer.class" "kafka.serializer.DefaultEncoder"
    "partitioner.class" "kafka.producer.DefaultPartitioner"}))

(kp/send-message producer (kp/message topic (.array (fressian/write {:n 1}))))
(kp/send-message producer (kp/message topic (.array (fressian/write {:n 2}))))
(kp/send-message producer (kp/message topic (.array (fressian/write {:n 3}))))
(kp/send-message producer (kp/message topic (.array (fressian/write :done))))

(def workflow {:read-messages {:identity :out}})

(def catalog
  [{:onyx/name :read-messages
    :onyx/ident :kafka/read-messages
    :onyx/type :input
    :onyx/medium :kafka
    :onyx/consumption :sequential
    :kafka/topic topic
    :kafka/zookeeper "127.0.0.1:2181"
    :kafka/group-id "onyx-consumer"
    :kafka/offset-reset "smallest"
    :onyx/batch-size 1
    :onyx/doc "Reads messages from a Kafka topic"}

   {:onyx/name :identity
    :onyx/fn :clojure.core/identity
    :onyx/type :transformer
    :onyx/consumption :concurrent
    :onyx/batch-size 1}

   {:onyx/name :out
    :onyx/ident :core.async/write-to-chan
    :onyx/type :output
    :onyx/medium :core.async
    :onyx/consumption :concurrent
    :onyx/batch-size 1
    :onyx/doc "Writes segments to a core.async channel"}])

(def out-chan (chan 1000))

(defmethod l-ext/inject-lifecycle-resources :out
  [_ _] {:core-async/out-chan out-chan})

(def id (str (java.util.UUID/randomUUID)))

(def coord-opts
  {:hornetq/mode :vm
   :hornetq/server? true
   :hornetq.server/type :vm
   :zookeeper/address "127.0.0.1:2185"
   :zookeeper/server? true
   :zookeeper.server/port 2185
   :onyx/id id
   :onyx.coordinator/revoke-delay 5000})

(def peer-opts
  {:hornetq/mode :vm
   :zookeeper/address "127.0.0.1:2185"
   :onyx/id id})

(def conn (onyx.api/connect :memory coord-opts))

(def v-peers (onyx.api/start-peers conn 1 peer-opts))

(onyx.api/submit-job conn {:catalog catalog :workflow workflow})

(def results (doall (map (fn [_] (<!! out-chan)) (range 4))))

(fact results => [{:n 1} {:n 2} {:n 3} :done])

(doseq [v-peer v-peers]
  ((:shutdown-fn v-peer)))

(onyx.api/shutdown conn)

