(ns panaeolus.africanus
  (:use [overtone.live])
  (:require [overtone.ableton-link :as link]
            [clojure.data :refer [diff]]
            [overtone.helpers.old-contrib :refer [name-with-attributes]]
            [clojure.core.async :refer [<! >! timeout go go-loop chan put! poll!] :as async]
            [clojure.string :as string]))


(link/enable-link true)

(link/set-bpm 140)

(def chan-hold (chan 1))

#_(defn calculate-timestamp
    [last-tick mod-div beat] 
    (let [last-tick    (Math/ceil last-tick)
          current-beat (max (mod last-tick mod-div) 0)
          delta        (- beat current-beat)]
      (if (neg? delta)
        (+ beat last-tick (- mod-div current-beat))
        (+ last-tick delta))))

(defn a-seq? [v]
  (or (vector? v)
      (list? v)
      (instance? clojure.lang.LazySeq v)
      (instance? clojure.lang.Repeat v)
      (instance? clojure.lang.Range v)
      (instance? clojure.lang.LongRange v)))

(defn synth-node? [v]
  (= overtone.sc.node.SynthNode (type v)))

(defn fractional-abs [num]
  (if (pos? num) num (* -1 num)))

(defn calc-mod-div
  [durations]
  (let [meter       0
        bar-length  meter
        summed-durs (apply + (map fractional-abs durations))] 
    (if (pos? meter)
      (* bar-length
         (inc (quot (dec summed-durs) bar-length)))
      summed-durs)))

(defn create-event-queue
  [last-tick beats]
  (let [last-tick (Math/ceil last-tick)
        beats     (if (fn? beats) (beats {:last-tick last-tick}) beats)
        mod-div   (calc-mod-div beats)
        ;; CHANGEME, make configureable
        ]
    (loop [beats     (remove zero? beats)
           ;; msg event-callbacks
           silence   0
           last-beat 0
           at        []]
      (if (empty? beats)
        [at (+ last-tick mod-div)]
        ;; (mapv #(calculate-timestamp last-tick mod-div %) at)
        (let [fbeat (first beats)]
          (recur (rest beats)
                 #_(if (neg? fbeat)
                     msg
                     (if (empty? msg)
                       (rest event-callbacks)
                       (rest msg)))
                 (if (neg? fbeat)
                   (+ silence (fractional-abs fbeat))
                   0)
                 (if (neg? fbeat)
                   last-beat
                   fbeat)
                 (if (neg? fbeat)
                   at
                   (conj at (+ last-beat
                               silence
                               (if (empty? at)
                                 last-tick (last at)))))))))))


(defn --resolve-arg-indicies [args index a-index next-timestamp extra-atom]
  (reduce (fn [init val]
            (if (fn? val)
              (conj init (val {:index      index          :a-index a-index
                               :timestamp  next-timestamp :args    args
                               :extra-atom extra-atom}))
              (if-not (a-seq? val)
                (conj init val)
                ;; (prn (nth val (mod a-index (count val))) val a-index)
                (conj init (nth val (mod a-index (count val)))))))
          []
          args))

(defn expand-nested-vectors-to-multiarg [args]
  (let [longest-vec (->> args
                         (filter a-seq?)
                         (map count)
                         (apply max))]
    (for [n (range longest-vec)]
      (reduce (fn [i v]
                (if (a-seq? v)
                  (if (<= (count v) n)
                    (conj i (last v))
                    (conj i (nth v n)))
                  (conj i v))) [] args))))

(defn event-loop [get-current-state envelope-type extra-atom]
  (let [[event-queue-fn inst args fx] (get-current-state)]
    (go-loop [[queue mod-div] (event-queue-fn)
              inst inst
              args args
              fx fx
              index 0
              a-index 0]
      ;; (prn "fx" fx)
      (if-let [next-timestamp (first queue)] 
        (let [wait-chn (chan)]
          (link/at next-timestamp
                   (let [args-processed (--resolve-arg-indicies args index a-index next-timestamp extra-atom)
                         fx-ctl-cb      (fn [] (when-not (empty? fx)
                                                 (run! #(apply ctl (last %)
                                                               (--resolve-arg-indicies
                                                                (second %) index a-index
                                                                next-timestamp extra-atom))
                                                       (vals fx))))]
                     (if (some a-seq? args-processed)
                       (let [multiargs-processed
                             (expand-nested-vectors-to-multiarg args-processed)]
                         (fn []
                           (go (fx-ctl-cb))
                           (when (instrument? inst)
                             (run! #(apply inst %) multiargs-processed))
                           (put! wait-chn true)))
                       (fn []
                         ;; (prn "pre-shoot")
                         (go (fx-ctl-cb))
                         ;; (prn "post-shoot")
                         (if (instrument? inst)
                           (apply inst args-processed)
                           (apply ctl inst
                                  (if (= :gated envelope-type)
                                    (into args-processed [:gate 1])
                                    args-processed)))
                         ;; (prn "post-inst")
                         (put! wait-chn true)))))
          (<! wait-chn)
          (recur [(rest queue) mod-div]
                 inst
                 args
                 fx
                 (inc index)
                 (inc a-index)))
        (let [;;wait-for-mod-div (chan)
              ]
          ;; (link/at mod-div #(put! wait-for-mod-div true))
          ;; (<! wait-for-mod-div)
          (when-let [event-form (get-current-state)]
            (let [[event-queue-fn inst args new-fx] event-form]
              ;; (when next-fx )
              (recur (event-queue-fn mod-div)
                     inst
                     args
                     new-fx
                     0
                     a-index ;;(inc a-index)
                     ))))))))


(def pattern-registry (atom {}))

(defn pkill [k-name]
  (letfn [(safe-node-kill [node]
            (future
              (try
                (node-free* node)
                (kill node)
                (catch Exception e nil))))]
    (if (= :all k-name)
      (do (when-let [keyz (keys @pattern-registry)]
            (run! (fn [k] (let [v (get @pattern-registry k)]
                            (when (a-seq? v)
                              (run! safe-node-kill (filter synth-node? v)))))
                  keyz))
          (reset! pattern-registry {}))
      (do (let [v (get @pattern-registry k-name)]
            (when (a-seq? v)
              (run! safe-node-kill (filter synth-node? v))))
          (swap! pattern-registry dissoc k-name)))))

;; Useful util functions
(defn samples-to-buffer [dir]
  (let [sample-buffers (->> (clojure.java.io/file dir)
                            file-seq
                            (filter #(.isFile %))
                            (into [])
                            sort
                            (mapv load-sample))
        array-buffer   (buffer (count sample-buffers))]
    (run! #(buffer-set! array-buffer % (:id (nth sample-buffers %)))
          (range (count sample-buffers)))
    array-buffer))


#_(defn --longest-vector [args]
    (let [seqs (filter a-seq? (rest (rest args)))]
      (if (empty? seqs)
        1
        (->> seqs
             (map count)
             (apply max)))))

(defn --filter-fx [args]
  (loop [args         args
         fx-free-args []
         fx           []]
    (if (empty? args)
      [fx-free-args (if (a-seq? fx) fx [fx])]
      ;; QUICK FIX, REPAIR!
      (if (and (= :fx (first args)) (< 1 (count args)))
        (recur (rest (rest args))
               fx-free-args
               (second args))
        (recur (rest args)
               (conj fx-free-args (first args))
               fx)))))


(defn --replace-args-in-fx [old-fx new-fx]
  (reduce (fn [init old-k]
            (if (contains? new-fx old-k)
              (assoc init old-k (assoc (get old-fx old-k) 1 (nth (get new-fx old-k) 1)))
              init))
          old-fx
          (keys old-fx)))

(defn --loop [k-name envelope-type inst args]
  (let [pat-exists?              (contains? @pattern-registry k-name)
        old-state                (get @pattern-registry k-name)
        beats                    (second args)
        beats                    (if (number? beats)
                                   [beats]
                                   (if (a-seq? beats)
                                     beats
                                     (if (fn? beats)
                                       beats                        
                                       (throw (AssertionError. beats " must be vector, list or number.")))))
        [args fx-vector]         (--filter-fx args)
        ;; longest-v-in-args        (--longest-vector args)
        ;; beats                    (if (< (count (filter #(and (number? %) (pos? %)) beats))
        ;;                                 longest-v-in-args)
        ;;                            (vec (take longest-v-in-args (cycle beats)))
        ;;                            beats)
        extra-atom               (atom {})
        fx-handle-atom           (if pat-exists?
                                   (nth old-state 4)
                                   (atom nil)) 
        new-fx                   (reduce (fn [i v] (assoc i (first v) (vec (rest v)))) {} fx-vector)
        old-fx                   (if pat-exists? (--replace-args-in-fx (nth old-state 3) new-fx)
                                     {})
        [rem-fx next-fx curr-fx] (diff (set (keys old-fx)) (set (keys new-fx)))
        ;; _                        (prn "rem-fx" rem-fx "next-fx" next-fx "curr-fx" curr-fx "old-fx" old-fx)
        fx-handle-callback       (when (or (not (empty? rem-fx)) (not (empty? next-fx)))
                                   (fn []
                                     (when-not (empty? rem-fx)
                                       ;; (prn "NOT EMPTY!")
                                       (let [old-fx-at-event (nth (get @pattern-registry k-name) 3)]
                                         ;; (prn "FOUND FROM OLD: " (keys old-fx-at-event))
                                         (run! #(let [fx-node (last %)
                                                      stereo? (vector? fx-node)]
                                                  (if stereo?
                                                    (when (node-active? (first fx-node))
                                                      (run! (fn [n] (node-free n)) fx-node))
                                                    (when (node-active? (last %)) (node-free (last %)))))
                                               (vals (select-keys old-fx-at-event (vec rem-fx))))
                                         (swap! pattern-registry assoc k-name
                                                (assoc (get @pattern-registry k-name) 3
                                                       (apply dissoc old-fx-at-event (vec rem-fx))))))
                                     (when-not (empty? next-fx)
                                       (swap! pattern-registry assoc k-name
                                              (assoc (get @pattern-registry k-name) 3
                                                     (reduce (fn [old next]
                                                               (let [new-v   (get new-fx next)
                                                                     fx-node (inst-fx! inst (first new-v))
                                                                     indx0   (--resolve-arg-indicies
                                                                              (second new-v)
                                                                              0 0 (link/get-beat)
                                                                              extra-atom)]
                                                                 (apply ctl fx-node indx0)
                                                                 (assoc old next (conj new-v fx-node))))
                                                             (nth (get @pattern-registry k-name) 3) next-fx))))))
        get-cur-state-fn         (fn []
                                   (let [cur-state (get @pattern-registry k-name)]
                                     (when cur-state
                                       (when-let [fx-handle-cb @(nth cur-state 4)]
                                         (fx-handle-cb)
                                         (reset! (nth cur-state 4) nil))))
                                   (get @pattern-registry k-name))]
    (reset! fx-handle-atom fx-handle-callback)
    (swap! pattern-registry assoc k-name
           [(fn [& [last-beat]] (create-event-queue (or last-beat (link/get-beat)) beats))
            (if (and (= :inf envelope-type) (not pat-exists?))
              (apply inst (--resolve-arg-indicies (rest (rest args)) 0 0 (link/get-beat) extra-atom))
              (if (and pat-exists? (synth-node? (second old-state)))
                (second old-state)
                inst))
            (rest (rest args))
            old-fx
            fx-handle-atom
            ;; Restart-fn if someone solo'd
            (fn [] (event-loop get-cur-state-fn envelope-type extra-atom))])
    (when-not pat-exists?
      (clear-fx inst)
      (event-loop get-cur-state-fn envelope-type extra-atom))))

(defn --fill-missing-keys-for-ctl
  "Function that makes sure that calling inst
   and calling ctl is possible with exact same
   parameters produceing same result."
  [args orig-arglists]
  (letfn [(advance-to-arg [arg orig]
            (let [idx (.indexOf orig arg)]
              (if (neg? idx)
                orig
                (vec (subvec orig (inc idx))))))]
    (loop [args     args
           orig     orig-arglists
           out-args []]
      (if (or (empty? args)
              ;; ignore tangling keyword
              (and (= 1 (count args)) (keyword? (first args))))
        out-args
        (if (keyword? (first args))
          (recur (rest (rest args))
                 ;; (rest orig)
                 (advance-to-arg (first args) orig)
                 (conj out-args (first args) (second args)))
          (recur (rest args)
                 (vec (rest orig))
                 (conj out-args (first orig) (first args))))))))

(def --dozed-patterns
  "When 1 pattern is solo,
  restart functions from the
  other patterns are kept here."
  (atom {}))

(defn unsolo []
  (when-not (empty? @--dozed-patterns)
    (swap! pattern-registry merge @--dozed-patterns)
    (run! (fn [v] ((nth v 5))) (vals @--dozed-patterns))
    (reset! --dozed-patterns {})))

(defn solo [pat-name & [duration]]
  (let [dozed-state (dissoc @pattern-registry pat-name)]
    (reset! --dozed-patterns dozed-state)
    (reset! pattern-registry  {pat-name (get @pattern-registry pat-name)})
    (when (and (number? duration) (< 0 duration))
      (link/at (+ (Math/ceil (link/get-beat)) duration)
               #(unsolo)))))


(defn pattern-control [i-name envelope-type orig-arglists inst]
  (fn [& args]
    (let [[pat-ctl pat-num]
          (if-not (keyword? (first args))
            [nil nil]
            (let [ctl     (name (first args))
                  pat-num (or (re-find #"[0-9]+" ctl) 0)
                  ctl-k   (keyword (first (string/split ctl #"-")))]
              [ctl-k pat-num]))
          args (case envelope-type
                 :inf   (--fill-missing-keys-for-ctl args orig-arglists)
                 :gated (--fill-missing-keys-for-ctl args orig-arglists)
                 args)]
      ;; (prn "ORIG: " orig-arglists)
      (case pat-ctl
        :loop (do (unsolo)
                  (--loop (str i-name "-" pat-num)
                          envelope-type inst args))
        :stop (pkill (str i-name "-" pat-num))
        :solo (solo (str i-name "-" 0) (if (empty? pat-num) 0 (read-string pat-num)))
        :kill (pkill (str i-name "-" pat-num))
        (apply inst (rest (rest args))))
      pat-ctl)))

(defmacro adapt-fx
  "Takes a normal fx from overtone and adapts it to africanus"
  [original-fx new-name default-args]
  (let [original-fx-name  `(keyword (:name ~original-fx))
        new-arglists      `(list (->> (:arglists (meta (var ~original-fx)))
                                      first
                                      (map #(symbol (name %)))
                                      rest
                                      vec)
                                 (or ~default-args []))
        new-name-and-meta (with-meta new-name
                            {:arglists new-arglists
                             :fx-name  original-fx-name})]
    `(def ~new-name-and-meta
       (fn [& args#]
         [~original-fx-name ~original-fx
          (--fill-missing-keys-for-ctl args# (mapv keyword (first ~new-arglists)))]))))

(defmacro definst+
  {:arglists '([name envelope-type params ugen-form])}
  [i-name envelope-type & inst-form]
  (let [[i-name params ugen-form]
        (synth-form i-name inst-form)
        i-name-str          (name i-name)
        orig-arglists       (:arglists (meta i-name))
        arglists-w-defaults (reduce (fn [i v] (if (symbol? v)
                                                (conj i (keyword v))
                                                (conj i v))) [] (first inst-form))
        i-name-new-meta     (assoc (meta i-name)
                                   :arglists (list 'quote
                                                   (list (conj (->> orig-arglists
                                                                    second first
                                                                    (map #(symbol (name %)))
                                                                    (cons 'beats)
                                                                    (cons 'pat-ctl) 
                                                                    (into []))
                                                               'fx)
                                                         arglists-w-defaults)))
        i-name              (with-meta i-name (merge i-name-new-meta {:type ::instrument}))
        inst                `(inst ~i-name ~params ~ugen-form)]
    `(def ~(vary-meta i-name assoc :inst inst)
       (pattern-control ~i-name-str ~envelope-type (mapv keyword (first ~orig-arglists)) ~inst))))



;; (defsynth fx-echo2
;;   [bus 0 max-delay 1.0 delay-time 0.4 decay-time 2.0]
;;   (let [source (in bus)
;;         echo   (comb-n source max-delay delay-time decay-time)]
;;     (out bus (pan2 (+ echo source) 0))))


(comment
  (def chorus-fx (inst-fx! (:inst (meta #'ding20)) fx-chorus))
  (demo (:inst (meta #'ding20)))
  (chorus :depth 100)
  (ctl chorus-fx :rate 1000 :depth 2)
  (clear-fx (:inst (meta #'ding20)))
  (chorus  100)
  (node-free chorus-fx)

  (ding20 nil nil 80 )
  (stop)
  
  (definst+ ding20 :perc
    [note 60 amp 1 gate 1]
    (let [freq (midicps note)
          snd  (sin-osc freq)
          env  (env-gen (lin 0.01 0.1 0.2 0.3) gate :action FREE)]
      (* amp env snd)))

  (definst ding1
    [note 60 amp 1 gate 1]
    (let [freq (midicps note)
          snd  (sin-osc freq)
          env  (env-gen (lin 0.01 0.1 0.4 0.3) gate :action FREE)]
      (* amp env snd)))

  (def chorus-fx (inst-fx! ding1 fx-chorus))

  (node-active? chorus-fx)
  
  (meta #'ding1 )
  (demo (ding1))
  (tubescreamer )
  (inst-fx! ding1 fx-echo)
  (ctl tubescreamer 200 50 1000 2)
  (demo (sin-osc))
  
  (ding20 :loop
          ;; (fn [] [1 1 1 1])
          [0.25 0.25 0.25 0.25 -1 0.25 0.25 0.25 0.25 -1 -4]
          [60   62   64]
          0.8
          ;; :fx [(tubescreamer :gain 0)]
          )

  (node-tree)
  (ding20 :stop
          [0.25 0.25 0.5]
          [60   62   64])

  ;; (type ding)
  )
