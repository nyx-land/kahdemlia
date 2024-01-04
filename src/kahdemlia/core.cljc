(ns kahdemlia.core
  #?(:clj
     (:require
      [clojure.spec.alpha :as spec]
      ;; [clojure.core.async :refer [chan put! <!]]
      ;; [clojure.core.async.impl.protocols :refer [Channel]]
      [manifold.stream :refer [stream put! sinkable?]]
      [manifold.go-off :refer [<!]]
      [taoensso.timbre :as log]
      [kahdemlia.encoding :as enc])
     :cljs
     (:require
      [cljs.spec.alpha :as spec]
      ;; [cljs.core.async :refer [chan put! <!]]
      ;; [cljs.core.async.impl.protocols :refer [Channel]]
      [manifold-cljs.stream :refer [stream put! <! sinkable?]]
      [manifold-cljs.go-off :refer [<!]]
      [taoensso.timbre :as log]
      [kahdemlia.encoding :as enc])))

;;; System-wide parameters ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic *k* 20)                                      ; ID size in octets
(def ^:dynamic *alpha* 3)                                   ; degree of paralelism

;;; Bucket-based routing ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(spec/def ::octet (spec/and int? #(<= 0 % 255)))
(spec/def ::array-id (spec/coll-of ::octet :count *k*))
(spec/def ::id (spec/and string? #(= (int (Math/ceil (/ *k* 0.75))) (count %))))
(spec/def ::id-or-array (spec/or :id ::id :array ::array-id))

(spec/def ::nna (spec/map-of ::id ::ip))
(spec/def ::bucket (spec/coll-of ::nna :kind list?))
(spec/def ::buckets (spec/coll-of ::bucket :kind vector?))

(defn- take-while-plus-one
  "take-while, then another one. Note this does not keep the correct order."
  [pred coll]
  (let [[h t] (split-with pred coll)]
    (conj h (first t))))

(defn- octet-bucket
  "Bucket corresponding to this XOR distance. This calculates it for just one octet."
  [o]
  (if (zero? o) 0 (inc (int (/ (Math/log o) (Math/log 2))))))

(defn- id-distances
  [id0 id1]
  {:pre [(spec/valid? ::id-or-array id0)
         (spec/valid? ::id-or-array id1)]}
  (let [id0 (enc/->octets id0)
        id1 (enc/->octets id1)]
    (map bit-xor id0 id1)))

(defn- bucket-index
  "Returns the bucket corresponding to the distance between the ids, or nil
   if they are equal."
  [id0 id1]
  {:pre [(spec/valid? ::id-or-array id0)
         (spec/valid? ::id-or-array id1)]}
  (let [l (* 8 *k*)
        b (->> (id-distances id0 id1)
               (map octet-bucket)
               (take-while-plus-one zero?)
               (reduce #(+ -8 %1 (or %2 0)) (dec l)))]
    (if (neg? b) nil
                 b)))

(defn- find-closest-ids
  [node source-id target-id]
  {:pre [(spec/valid? ::id source-id)
         (spec/valid? ::id target-id)]}
  (->> (:buckets node)
       (flatten)
       (filter #(not= source-id (first %)))                 ; never return id of the requestor
       (sort-by (comp - (partial id-distances target-id) first))
       (take *alpha*)))

(defn- random-id
  "Random base 64 encoded id."
  []
  (-> (repeatedly *k* #(rand-int 256))
      (enc/octets->base64)))

;;; RPCs ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- stream? [s]
  (sinkable? s))

(spec/def ::msg (spec/and seq? (comp keyword? first)))

(spec/def ::network-out-ch stream?)
(spec/def ::found-ch stream?)
(spec/def ::network-fn fn?)
(spec/def ::find-fn fn?)
(spec/def ::ip string?)
(spec/def ::storage map?)
(spec/def ::node (spec/keys :req-un [::id, ::network-fn, ::network-out-ch, ::find-fn, ::found-ch]))

(defn- send-msg!
  [{ch :network-out-ch id :id} dest-ip msg]
  {:pre [(spec/valid? ::network-out-ch ch)
         (spec/valid? ::id id)
         (spec/valid? ::ip dest-ip)
         (spec/valid? ::msg msg)]}
  (put! ch [dest-ip (conj id msg)]))

(defmulti msg-received (fn [_ _ msg] (first msg)))

(defmethod msg-received :ping
  [node [_ source-ip] _]
  (send-msg! node source-ip [:pong]))

(defmethod msg-received :pong
  [_ _ _])

(defmethod msg-received :store!
  [node [_ source-ip] [_ k v]]
  (swap! (get-in node [:state :storage]) assoc k v)
  (send-msg! node source-ip [:pong]))

(defmethod msg-received :find-id
  [node [source-id source-ip] [_ k]]
  (send-msg! node source-ip [:ids-found k (find-closest-ids node source-id k)]))

(defmethod msg-received :find-value
  [node [source-id source-ip] [_ k]]
  (if-let [v (get-in @(:state node) [:storage k])]
    (send-msg! node source-ip [:store! k v])
    (send-msg! node source-ip [:ids-found k (find-closest-ids node source-id k)])))

(defn- update-buckets! [{:keys [id state] :as node} [source-id source-ip]]
  (when (not= id source-id)
    (let [buckets (:buckets @state)
          index (bucket-index id source-id)
          old-bucket (seq (get buckets index))
          new-nna [source-id source-ip]
          [new-bucket old-nnas] (split-at *k* (conj old-bucket new-nna))]
      (doall (map #(send-msg! node (:ip %) [:ping])) old-nnas)
      (swap! state assoc-in [:buckets index] new-bucket))))

;;; Interface ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- on-network [node source-ip id-and-msg]
  (let [source-id (first id-and-msg)
        msg (rest id-and-msg)]
    (log/debug "Node" (:id node) "received message" msg "from" source-ip)
    (update-buckets! node [source-id source-ip])
    (msg-received node [source-id source-ip] msg)))

(defn- on-request [node key]
  (log/debug "Node" (:id node) "requests key" key)
  ((:network-fn node) nil [(:id node) :find-value key]))

(defn- on-store [node key value]
  ((:network-fn node) nil [(:id node) :store! key value]))

(defn make-node!
  "Create a DHT node taking an optional map with keys:
     :id                 ; base-64 node id, defaults to random
     :storage            ; map to prepopulate node storage with
   and returns a map with keys:
     :id                 ; base-64 node id
     :state              ; read-only atom containing node state (known nodes and storage)
     :network-fn         ; (fn [source msg]) to be called when receiving data from other nodes†
     :network-out-ch     ; asyc channel of data to be sent over network as [dest msg] pairs†
     :store-fn           ; (fn [key value]) to store values in the DHT
     :find-fn            ; (fn [key]) to request value lookup
     :found-ch           ; async channel of found values as [key value] pairs

   † while the orginal Kademlia is designed for communications over UDP, this library is
   network-agnostic, it's the user's responsability to route messages based on the value of the
   source/dest strings."
  ([] (make-node! {}))
  ([{:keys [id storage] :or {id (random-id) storage {}}}]
   {:pre  [(spec/valid? ::id id)
           (spec/valid? ::storage storage)]
    :post [(spec/valid? ::node %)]}
   (as-> {:id             id
          :state          (atom {:buckets [] :storage storage})
          :network-out-ch (stream)
          :found-ch       (stream)} node
     (assoc node :network-fn (partial on-network node))
     (assoc node :find-fn (partial on-request node))
     (assoc node :store-fn (partial on-store node)))))
