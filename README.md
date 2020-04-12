# interruttore

A Clojure library that implements the [circuit breaker design pattern](http://en.wikipedia.org/wiki/Circuit_breaker_design_pattern)

```clj
[interruttore "0.0.1"]

```

Still very very alpha, interfaces, namespaces, functionality may change
every time; not tested, use at your own risk.

## Usage

This library provides two ways to wrap a function with a circuit breaker:
- Exception based
- Based on the return value of the wrapped function (richer API)

### Exception based example

``` clj

(require '[interruttore.core :as cb])

(defn to-be-wrapped [a b]
  (/ a b))

(def wrapped (cb/make-circuit-breaker to-be-wrapped {:max-retries 2
                                                     :retry-after-ms 10}))

;; happy case
(wrapped 2 2) ;; => {:status :closed :value 1}

;; failing case, retry max-retry times and wait retry-after-ms before retrying
(wrapped 2 0) ;; => {:status :open :retry-after 10 :reason :max-retries}

;; wrapped start to work again, we are in the semi-open state now
(wrapped 4 2) ;; => {:status :semi-open :value 2}

;; fail again, but this time it does not retry
(wrapped 2 0) ;; => {:status :open :retry-after 10 :reason :max-retries}

```

### Return based example

``` clj

(defn to-be-wrapped [result]
  result)

(def wrapped (cb/make-circuit-breaker to-be-wrapped {:max-retries 2
                                                     :retry-after-ms 10}))

;; happy case
(wrapped {:result :ok :value 1}) ;; => {:status :closed :value 1}

;; failing case, retry max-retry times and wait retry-after-ms before retrying
(wrapped {:result :soft-failure
          :retry-after (in-a-minute)}) ;; => {:status :open
                                       ;;     :reason :max-retries
                                       ;;     :retry-after :same-date-time}

;; wrapped start to work again, we are in the semi-open state now
(wrapped {:result :ok :value 2}) ;; => {:status :semi-open :value 2}

;; fail again, but this time it does not retry
(wrapped {:result :soft-failure
          :retry-after (in-a-minute)}) ;; => {:status :open
                                       ;;     :reason :max-retries
		                               ;;     :retry-after :some-date-time}

```

Return based approach, so far, seems very close to the exception based one,
if we exclude the possibility, for the wrapped function, to tell exactly until
when the circuit must stay open; this method provides an additional result
key :hard-failure which will open the circuit without retrying to call the
wrapped function.
This can be convenient, for example, in case an external API has a quota,
it does not make any sense to call the external API again today if we have
exceed our daily quota.

``` clj

(cb/reset wrapped)  ;; reset the circuit to its initial state

;; fail hard this time, so it does not retry
(wrapped {:result :hard-failure
          :retry-after (tomorrow)}) ;; => {:status :open
                                    ;;     :reason :hard-failure
		                            ;;     :retry-after :some-date-time}

```

The map returned by the wrapped can provide another key, `:reason`, that can be
used to replace the builtin `:reason` returned when the circuit is open, here is
an example:

``` clj

(cb/reset wrapped)  ;; reset the circuit to its initial state

;; fail hard, this time providing a :reason
(wrapped {:result :hard-failure
          :reason :daily-quota-exceeded
          :retry-after (tomorrow)}) ;; => {:status :open
                                    ;;     :reason :daily-quota-exceeded
		                            ;;     :retry-after :some-date-time}

```

## TODO

- get feedback: is it useful? missing something?
- better name of the namespace (interrutture may be enough)
- put everything on clojars
- eventually apply the return based method to exceptions
- better documentation
- def-circuit-breaker macro?
- support to ClojureScript (should require few changes exp datetime handling)

## License

Copyright © 2020 Francesco Pischedda

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
