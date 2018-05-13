(ns panaeolus.africanus
  (:use [overtone.live])
  (:require [overtone.ableton-link :as link]
            [overtone.helpers.old-contrib :refer [name-with-attributes]]
            [clojure.core.async :refer [<! >! timeout go go-loop chan put!]]
            [clojure.string :as string]))


(link/enable-link true)

#_(defn calculate-timestamp
    [last-tick mod-div beat] 
    (let [last-tick (Math/ceil last-tick)
          current-beat (max (mod last-tick mod-div) 0)
          delta (- beat current-beat)]
      (if (neg? delta)
        (+ beat last-tick (- mod-div current-beat))
        (+ last-tick delta))))

(defn a-seq? [v]
  (or (vector? v)
      (list? v)
      (instance? clojure.lang.LazySeq v)))

(defn calc-mod-div
  [durations]
  (let [meter       0
        bar-length  meter
        summed-durs (apply + (map #(Math/abs %) durations))]
    (if (pos? meter)
      (* bar-length
         (inc (quot (dec summed-durs) bar-length)))
      summed-durs)))

;; (create-event-queue 2 [0.25 0.25 0.25])

(defn create-event-queue
  [last-tick beats]
  (let [mod-div (calc-mod-div beats)
        ;; CHANGEME, make configureable
        last-tick (Math/ceil last-tick)]
    ;; (prn last-tick beats)
    (loop [beats (remove zero? beats)
           ;; msg event-callbacks
           silence 0
           last-beat 0
           at []]
      (if (empty? beats)
        at
        ;; (mapv #(calculate-timestamp last-tick mod-div %) at)
        (let [fbeat (first beats)]
          (recur (rest beats)
                 #_(if (neg? fbeat)
                     msg
                     (if (empty? msg)
                       (rest event-callbacks)
                       (rest msg)))
                 (if (neg? fbeat)
                   (+ silence (Math/abs fbeat))
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


(defn --resolve-arg-indicies [args a-index]
  (reduce (fn [init val]
            (if (fn? val)
              (conj init (val a-index))
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

(defn event-loop [get-event-queue envelope-type]
  (let [[event-queue-fn inst args] (get-event-queue)]
    (go-loop [queue (event-queue-fn)
              inst inst
              args args
              index 0 a-index 0]
      (if-let [next-timestamp (first queue)] 
        (let [wait-chn (chan)]
          (link/at next-timestamp
                   (let [args-processed (--resolve-arg-indicies args index)] 
                     (if (some a-seq? args-processed)
                       (let [multiargs-processed
                             (expand-nested-vectors-to-multiarg args-processed)]
                         ;; (prn multiargs-processed)
                         (fn []
                           (when (instrument? inst)
                             (run! #(apply inst %) multiargs-processed))
                           (put! wait-chn true)))
                       (fn [] 
                         (if (instrument? inst)
                           (apply inst args-processed)
                           (apply ctl inst
                                  (if (= :gated envelope-type)
                                    (into args-processed [:gate 1])
                                    args-processed)))
                         (put! wait-chn true)))))
          (<! wait-chn)
          (recur (rest queue)
                 inst
                 args
                 (inc index)
                 (inc a-index)))
        (when-let [event-form (get-event-queue)]
          (let [[event-queue-fn inst args] event-form]
            (recur (event-queue-fn)
                   inst
                   args
                   0
                   a-index ;;(inc a-index)
                   )))))))

#_(defn functionize-instr [instr]
    (letfn [(resolve-vectors [lst]
              (->> (reduce (fn [init v]
                             (if (vector? v)
                               (conj init (list nth v (list mod 'index `(count ~v)) `(first ~v)))
                               (conj init v)))
                           '()
                           lst)
                   reverse
                   list
                   (into '(doall))
                   reverse
                   ))]
      (->> (map resolve-vectors (if (= 'do (first instr))
                                  (rest instr)
                                  (list instr)))
           (concat '(fn [index])))))

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
            (run! #(let [v (get @pattern-registry %)]
                     (when (a-seq? v)
                       (run! safe-node-kill (filter node? v))))
                  keyz))
          (reset! pattern-registry {}))
      (do (let [v (get @pattern-registry k-name)]
            (when (a-seq? v)
              (run! safe-node-kill (filter node? v))))
          (swap! pattern-registry dissoc k-name)))))

;; Useful util functions
(defn samples-to-buffer [dir]
  (let [sample-buffers (->> (clojure.java.io/file dir)
                            file-seq
                            (filter #(.isFile %))
                            (into [])
                            sort
                            (mapv load-sample))
        array-buffer (buffer (count sample-buffers))]
    (run! #(buffer-set! array-buffer % (:id (nth sample-buffers %)))
          (range (count sample-buffers)))
    array-buffer))

#_(defn resolve-africanus-args [args]
    (let [[args index] (if )]
      (reduce
       (fn [init val]
         (conj ))
       [] args)))

#_(defmacro pat [k-name instr beats]
    (let [instr (functionize-instr instr)]
      `(let [instr# ~instr
             pat-exists# (contains? @pattern-registry ~k-name)]
         (swap! pattern-registry assoc ~k-name
                (fn [] (create-event-queue (link/get-beat) ~beats [instr#])))
         (when-not pat-exists#
           (event-loop (fn [] (get @pattern-registry ~k-name)))))))


(defn --longest-vector [args]
  (let [seqs (filter a-seq? (rest (rest args)))]
    (if (empty? seqs)
      1
      (->> seqs
           (map count)
           (apply max)))))

(defn --loop [k-name envelope-type inst args]
  (let [pat-exists        (contains? @pattern-registry k-name)
        beats             (second args)
        beats             (if (number? beats)
                            [beats]
                            (if (a-seq? beats)
                              beats
                              (throw (AssertionError. beats " must be vector, list or number."))))
        longest-v-in-args (--longest-vector args)
        beats             (if (< (count (filter #(and (number? %) (pos? %)) beats))
                                 longest-v-in-args)
                            (vec (take longest-v-in-args (cycle beats)))
                            beats)]
    (swap! pattern-registry assoc k-name
           [(fn [] (create-event-queue (link/get-beat) beats))
            (if (and (= :inf envelope-type) (not pat-exists))
              (apply inst (--resolve-arg-indicies (rest (rest args)) 0))
              inst)
            (rest (rest args))])
    (when-not pat-exists
      (event-loop (fn [] (get @pattern-registry k-name)) envelope-type))))

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

(defn pattern-control [i-name envelope-type orig-arglists inst]
  (fn [& args]
    (let [[pat-ctl pat-num]
          (if-not (keyword? (first args))
            [nil nil]
            (let [ctl     (name (first args))
                  pat-num (or (re-find #"[0-9]" ctl) 0)
                  ctl-k   (keyword (first (string/split ctl #"-")))]
              [ctl-k pat-num]))
          args (case envelope-type
                 :inf   (--fill-missing-keys-for-ctl args orig-arglists)
                 :gated (--fill-missing-keys-for-ctl args orig-arglists)
                 args)]
      ;; (prn "ORIG: " orig-arglists)
      (case pat-ctl
        :loop (--loop (str i-name "-" pat-num)
                      envelope-type inst args)
        :stop (pkill (str i-name "-" pat-num))
        :kill (pkill (str i-name "-" pat-num))
        (apply inst (rest (rest args))))
      pat-ctl)))

(defmacro definst+
  {:arglists '([name envelope-type params ugen-form])}
  [i-name envelope-type & inst-form]
  (let [[i-name params ugen-form]
        (synth-form i-name inst-form)
        i-name-str      (name i-name)
        orig-arglists   (:arglists (meta i-name))
        i-name-new-meta (assoc (meta i-name)
                               :arglists (list 'quote
                                               (list (->> orig-arglists
                                                          second first
                                                          (map #(symbol (name %)))
                                                          (cons 'beats)
                                                          (cons 'pat-ctl)
                                                          (into [])))))
        i-name          (with-meta i-name (merge i-name-new-meta {:type ::instrument}))]
    `(let [inst# (inst ~i-name ~params ~ugen-form)]
       (def ~i-name
         (pattern-control ~i-name-str ~envelope-type (mapv keyword (first ~orig-arglists)) inst#)))))


(comment 
  (definst+ ding3 :perc
    [note 60 amp 1 gate 1]
    (let [freq (midicps note)
          snd  (sin-osc freq)
          env  (env-gen (lin 0.01 0.1 0.6 0.3) gate :action FREE)]
      (* amp env snd)))
  (ding3 nil nil 80)
  (ding3 :loop
         [0.25 0.25 0.5]
         [60   62   64]))

;; (type ding)
