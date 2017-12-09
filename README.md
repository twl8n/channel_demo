
#### Demo of Clojure channels

Fully functional demo of Clojure go and channels doing http requests, with timings.

Using pmap and get is as fast as anything, including using clj-http's built in async and connection pooling.


Uses clj-http which seems simpler than http-async, using the Apache HTTP client:

https://github.com/dakrone/clj-http

Similar, but using the JRE HttpURLConnection. Has not been updated since 2015. Has very few dependencies:

https://github.com/hiredman/clj-http-lite

#### todo

Run the image downloader. Requires a list of image urls in image_urls.txt.

```
lein run -m mc.image-down/-main < /dev/null > run.log 2>&1 &
```

See ex420 with-connection-pool.

See ex41 for an example using built in async.

* try (with-async-connection-pool ...


#### Catching exceptions in threads

I strongly prefer failure values over exceptions. Thus defn fxget. The failure map is like the normal response
map, but with appropriate value subsitutions, especially :status and :body.

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
