(ns parsing-aas.core
  (:use ring.adapter.jetty
        ring.middleware.params
        ring.middleware.stacktrace
        compojure.core
        [compojure.route :only [files]]
        [clojure.java.shell :only [sh]])
  (:require [clojure.java.io :as io]
            [clojure.core.cache :as cache])
  (:import java.util.UUID
           org.treedecor.Parser))

(def config (atom {:s2t-exec         nil
                   :port             8080
                   :table-cache-size 20}))

(def table-cache (atom {}))

(defn uuid [] (.toString (java.util.UUID/randomUUID)))

(defmacro log [message & body]
  `(let [start# (System/nanoTime)
         ~'_ (println "Start" ~message "at" start#)
         ret# (do ~@body)
         end# (System/nanoTime)]
     (println "End" ~message "at" end# "Duration:" (/ (- end# start#) 1000000.0) "ms")
     ret#))

(defn get-table [grammar-hash]
  (get @table-cache grammar-hash))

(defn make-parser [tbl]
  (Parser. ^bytes tbl))

(defn parse [table-id stream do-not-annotate]
  (log "parse request"
       (if-let [table (log "lookup table in cache" (get-table table-id))]
         (let [parse-result (log "actually parsing" (.parse ^Parser (log "create parser" (make-parser table)) ^java.io.InputStream stream))]
           (if do-not-annotate
             (str parse-result)
             (str (log "annotate source location information" (Parser/annotateSourceLocationInformation ^IStrategoTerm parse-result)))))
         {:status 410 ; Gone
          :body "Please (re-)register your grammar or table."})))

(defn sdf-to-table
  ([def module]
     (sh (or (:s2t-exec @config) "sdf2table")
         "-m" module :in def :out-enc :bytes)))

(defn register-table [id tbl]
  (swap! table-cache assoc id tbl)
  {:status 201 ; Created
   :headers {"Location" (str "/table/" id)}
   :body id})

(defn register-grammar [in-stream module]
  (let [def (slurp in-stream)
        hash (str (hash def))]
    (if (= "" def)
      {:status 400
       :body "No content. Fix your client. Try using Content-Type:application/x-sdf"}
      (if (get-table hash)
        hash
        (let [ext-call (sdf-to-table def module)]
          (if (= (:exit ext-call) 0)
            (register-table hash (:out ext-call))
            {:status 422
             :body (:err ext-call)}))))))

(defroutes handler
  (POST "/grammar" {body                                  :body
                    {module "module" :or {module "Main"}} :params}
        (register-grammar body module))
  (GET "/table" [] (apply str (interpose "\n" (keys @table-cache))))
  (ANY "/parse/:table-id" {body :body
                         {table-id :table-id
                          do-not-annotate "disableSourceLocationInformation"} :params}
       (parse table-id body (= do-not-annotate "true")))
  (POST "/table" {body :body} (register-table (uuid) body))
  (files "/" {:root "web"})
)

(def app
  (-> #'handler
      (wrap-params)
      (wrap-stacktrace)))

(defn -main [& args]
  (println "Config string:" args)
  (let [new-config (if args (read-string (first args)) {})]
    (swap! config merge new-config))
  (println "Effective config:" @config)
  (swap! table-cache #(cache/lru-cache-factory (:table-cache-size @config) %))
  (run-jetty #'app {:port (:port @config)}))

(comment
  (defonce server
    (run-jetty #'app {:port (:port @config)})))


;; install localrepo leiningen plugin:
;; put the following in .lein/profiles.clj
;; {:user {:plugins [
;;                   [lein-localrepo "0.4.0"]
;;                   [lein-swank "1.4.3"]
;;                   ]}}

;; install current parser to local repo using 
;; $ lein localrepo install `lein localrepo coords treedecor-parser-0.0.2.jar`

;; make sure to use proper content type for sending post stuff. wrap-params eats
;; form-url-encoded bodies as sent by web browsers. Not sure whether multipart params is needed
;; use e.g. this for testing (reads post data from stdin, use --data-binary @filename to read and send a file as is)

;; Register grammar
;;   curl -X POST -H 'Content-Type:application/x-sdf' --data-binary @xml.def http://127.0.0.1:8080/grammar?module=xml
;; Parse file
;;   curl -X GET -H 'Content-Type: application/binary' -d @/home/stefan/Work/treedecor/renderer.imp/plugin.xml http://127.0.0.1:8080/parse/-993726346
