(ns interruttore.core
  (:import
   [java.time Duration LocalDateTime ZoneOffset]))

(defn now
  []
  (LocalDateTime/now ZoneOffset/UTC))

(defn circuit-open?
  "Tells if the circuit is open or not; having retry-after set to nil
   means that the circuit will be open until reset."
  [{::keys [status retry-after]}]
  (and (= ::open status)
    (or (nil? retry-after) (.isBefore (now) retry-after))))

(comment
  (circuit-open? {::status ::closed}) ;; => false
  (circuit-open? {::status ::semi-open}) ;; => false
  (circuit-open? {::status ::open}) ;; => true
  (circuit-open? {::status ::open
                  ::retry-after (.plus (now) (Duration/ofMinutes 1))}) ;; => true
  (circuit-open? {::status ::open
                  ::retry-after (.minus (now) (Duration/ofMinutes 1))}) ;; => false
  )

(defn- circuit-next-state
  "Compute the next state of the circuit based on the result of the call
  to the wrapped function (`wfn`) which sholud have the following schema:
  {:status (s/enum :ok :soft-failure :hard-failure)
   (s/optional-key :value) s/Any}
  depending on the value of the :status key one of the following things
  will happen:
  - :ok : set the circuit state to :closed and return the value of :value
  - :soft-failure : retry calling `wfn` at most max-retries times until:
    - a valid response is returned, then proceed as the :ok case
    - after max-retries times put the circuit in :open state, set
      :retry-after timestamp to control when to try again, and return
      an error
  - :hard-failure : skip :soft-failure machinery, set the circuit to
    :open state, set :retry-after timestamp and return an error
  "
  [{::keys [status retry-count retry-after-ms max-retries] :as circuit
    :or    {retry-count 0
            max-retries 3}}
   ;; aliasing _result just for documentation
   {::keys [result retry-after reason] :as _result}]
  (case result
    ::ok ;; everything is fine this time!
    ;; but maybe it was not :ok before, so be careful
    (assoc circuit
      ::status (if (= ::open status) ::semi-open ::closed)
      ::retry-after nil
      ::retry-count (if (= ::open status) retry-count 0))

    ;; in case of soft failure check how many times we have alredy RE-tried
    ;; to decide if the circuit have to be opened or if we can retry again
    ::soft-failure
    (if (>= retry-count max-retries)
      (assoc circuit
        ::status ::open
        ::reason (or reason ::max-retries)
        ::retry-count (inc retry-count)
        ::retry-after (or retry-after retry-after-ms))
      (assoc circuit
        ::retry-count (inc retry-count)))

    ;; hard failure, STOP THE WORLD!!!
    ;; setting retry-count to max-retries to support semi-open case
    ::hard-failure
    (assoc circuit
      ::status ::open
      ::reason (or reason ::hard-failure)
      ::retry-count max-retries
      ::retry-after (or retry-after retry-after-ms))))

(comment
  (circuit-next-state {::status ::closed} {::result ::ok})
  (circuit-next-state {::status ::closed} {::result ::soft-failure})
  )

(defn- build-exceptions-map
  "Given two sequences, one holding soft failure exception classes,
  the other one holding hard failure exception classes, builds a map
  of exception-type -> :failure-mode-key; hard failure exceptions
  have the precedence over the soft ones."
  [hard-fail-exceptions soft-fail-exceptions]
  (let [soft
        (reduce (fn [acc ex]
                  (assoc acc ex ::hard-failure))
          {} hard-fail-exceptions)]
    (reduce (fn [acc ex]
              (if (contains? acc ex)
                acc
                (assoc acc ex ::soft-failure)))
          soft soft-fail-exceptions)))

(comment
  (build-exceptions-map [ArithmeticException] [NullPointerException])
  ;; =>
  ;; {java.lang.ArithmeticException :hard-failure
  ;;  java.lang.NullPointerException :soft-failure}
  )

(defn- exception->failure-mode
  "Given an exception ex and an exceptions map, with each entry holding
  [ex-type failure-mode-keyword], return the failure mode keyword associated
  to the provided exception if a match is found otherwise return nil."
  [ex exceptions]
  (reduce (fn [_a [ex-class fail-mode]]
            (when (instance? ex-class ex) (reduced fail-mode))) nil exceptions))

(comment
  (exception->failure-mode
    (NullPointerException.)
    (build-exceptions-map [ArithmeticException] [NullPointerException])) ;; => :soft-failure
  (exception->failure-mode
    (ArithmeticException.)
    (build-exceptions-map [ArithmeticException] [NullPointerException])) ;; => :hard-failure
  (exception->failure-mode
    (NullPointerException.)
    (build-exceptions-map [ArithmeticException NullPointerException] [NullPointerException])) ;; => :hard-failure
  )

(defn make-circuit-breaker
  "Return a fn wrapping the provided wrapped-fn with a circuit breaker.

  The wrapped function (wfn) is expected to return a map with the
  following schema:
  {:status (s/enum :ok :soft-failure :hard-failure)
   (s/optional-key :value) s/Any}
  depending on the value of the :status key one of the following things
  will happen:
  - ::ok : set the circuit state to :closed and return the value of :value
  - ::soft-failure : retry calling `wfn` at most max-retries times until:
    - a valid response is returned, then proceed as the :ok case
    - after max-retries times put the circuit in :open state, set
      :retry-after timestamp to control when to try again, and return
      an error
  - ::hard-failure : skip :soft-failure machinery, set the circuit to
    ::open state, set ::retry-after timestamp and return an error

  in the case `wfn` throws the :soft-failure case will be used
  "
  ([wrapped-fn]
   (make-circuit-breaker wrapped-fn {}))
  ([wrapped-fn
    {:keys [hard-failure-exceptions soft-failure-exceptions
            exception-failure-map
            max-retries retry-after-ms]
     :or
     {soft-failure-exceptions [Throwable]
      hard-failure-exceptions []
      max-retries 3
      retry-after-ms 10}}]
   (let [exceptions
         (or
           exception-failure-map
           (build-exceptions-map hard-failure-exceptions soft-failure-exceptions))
         ex->failure-mode (memoize exception->failure-mode) ;; ALERT! premature optimization
         circuit_ (atom {::retry-count 0
                         ::retry-after nil
                         ::retry-after-ms retry-after-ms
                         ::max-retries max-retries
                         ::status :closed})]
     (with-meta
       (fn [& args]
         (if (circuit-open? @circuit_)
           ;; circuit still open, fail early
           {::status :open
            ::retry-after (::retry-after @circuit_)}
           ;; circuit not open, try to call wrapped-fn and handle the result
           (loop []
             (let [{::keys [result value] :as res}
                   (try
                     (let [res (apply wrapped-fn args)]
                       (if (and (map? res) (contains? res ::result))
                         res
                         {::result ::ok
                          ::value res}))

                     (catch Throwable t
                       (if-let [failure-mode (ex->failure-mode t exceptions)]
                         {::result failure-mode}
                         ;; un-handled exception, re-throw
                         (throw t))
                       ))]
               ;; evaluate result and prev state to calculate next state
               (swap! circuit_ #(circuit-next-state % res))

               (let [{::keys [status retry-count retry-after reason]} @circuit_]
                 (cond
                   (= ::open status)
                   ;; wrapped-fn failed too many times
                   {::status ::open
                    ::reason reason
                    ::retry-after retry-after}

                   ;; wrapped-fn did not return ok, sleep a bit and retry
                   (not (= ::ok result))
                   (do
                     (Thread/sleep (* retry-after-ms (inc retry-count)))
                     (recur))

                   ;; wrapped-fn was successful, status can be ::closed
                   ;; or ::semi-open
                   :else
                   {::status status
                    ::value value}
                   ))))))
       {:circuit_ circuit_}))))

(defn inspect
  "Return the status of the provided circuit (as an atom)"
  [c]
  (:circuit_ (meta c)))

(defn reset
  "Reset the circuit to initial state"
  [c]
  (swap! (:circuit_ (meta c)) assoc
    ::status ::closed
    ::retry-count 0
    ::retry-after nil))