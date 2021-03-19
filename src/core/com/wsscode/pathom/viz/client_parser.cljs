(ns com.wsscode.pathom.viz.client-parser
  (:require [clojure.spec.alpha :as s]
            [com.fulcrologic.guardrails.core :refer [>def >defn >fdef => | <- ?]]
            [com.wsscode.async.async-cljs :refer [let-chan go-promise <?maybe]]
            [com.wsscode.pathom.connect :as pc]
            [com.wsscode.pathom.core :as p]
            [com.wsscode.pathom.viz.lib.fetch :as fetch]
            [edn-query-language.core :as eql]))

(>def ::parser-id any?)
(>def ::parsers (s/map-of ::parser-id ::p/parser))
(>def ::client-parser-request ::eql/query)
(>def ::client-parser-response map?)
(>def ::url string?)

(pc/defresolver client-parser-names
  [{::keys [parsers]} _]
  {::pc/output [::available-parsers]}
  {::available-parsers (set (keys parsers))})

(pc/defmutation add-client-parser-from-url
  [{::keys [parsers*]}
   {::keys [url]}]
  {::pc/params [::url]}
  (swap! parsers* assoc url
    (fn [_ tx]
      (fetch/fetch-body {::fetch/request-url    url
                         ::fetch/request-body   tx
                         ::fetch/request-method "POST"
                         ::fetch/request-as     ::fetch/request-as-transit}))))

(pc/defmutation remove-client-parser
  [{::keys [parsers*]}
   {::keys [parser-id]}]
  {::pc/params [::parser-id]}
  (swap! parsers* dissoc parser-id))

(pc/defmutation client-parser-mutation
  [{::keys [parsers]}
   {::keys [parser-id client-parser-request]}]
  {::pc/params [::client-parser-request ::parser-id]
   ::pc/output [::client-parser-response]}
  (if-let [parser (get parsers parser-id)]
    (let-chan [response (parser {} client-parser-request)]
      {::client-parser-response response})
    (throw (ex-info "Parser not found" {::parser-id parser-id}))))

(def registry
  [client-parser-names
   add-client-parser-from-url
   remove-client-parser
   client-parser-mutation])
