
#### Demo of Clojure channels

Fully functional demo of Clojure go and channels doing http requests, with timings.


Uses clj-http which seems simpler than http-async:

https://github.com/dakrone/clj-http

#### todo

* try (with-connection-pool ...

* try (with-async-connection-pool ...

#### Catching exceptions in threads

https://adambard.com/blog/acceptable-error-handling-in-clojure/

https://brehaut.net/blog/2011/error_monads

https://stuartsierra.com/2015/05/27/clojure-uncaught-exceptions


```
;; Assuming require [clojure.tools.logging :as log]
(Thread/setDefaultUncaughtExceptionHandler
 (reify Thread$UncaughtExceptionHandler
   (uncaughtException [_ thread ex]
   (log/error ex "Uncaught exception on" (.getName thread)))))
```

Test for timeout:

```
(client/get "http://httpbin.org/delay/50")
```

My preference is not to have exceptions. I will wrap this up in a normal response, but with an appropriate
status and a nil or "" :body.

```
mc.core=> (client/get "http://httpbin.org/delay/5" {:conn-timeout 50})

SocketTimeoutException connect timed out  java.net.PlainSocketImpl.socketConnect (PlainSocketImpl.java:-2)
mc.core=> (client/get "http://httpbin.org/delay/5" {:socket-timeout 50})

SocketTimeoutException Read timed out  java.net.SocketInputStream.socketRead0 (SocketInputStream.java:-2)

```

Try this:

https://eng.climate.com/2014/02/25/claypoole-threadpool-tools-for-clojure/

#### License

Copyright © 2017 Tom Laudeman

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
