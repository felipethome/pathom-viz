(ns com.wsscode.pathom.viz.query-editor
  (:require
    [cljs.spec.alpha :as s]
    [clojure.string :as str]
    [com.fulcrologic.fulcro-css.css :as css]
    [com.fulcrologic.fulcro-css.localized-dom :as dom]
    [com.fulcrologic.fulcro.components :as fc]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.mutations :as fm]
    [com.fulcrologic.guardrails.core :refer [>def >defn >fdef => | <- ?]]
    [com.wsscode.async.async-cljs :refer [<?maybe go-promise <!]]
    [com.wsscode.pathom.connect :as pc]
    [com.wsscode.pathom.core :as p]
    [com.wsscode.pathom.misc :as p.misc]
    [com.wsscode.pathom.viz.client-parser :as cp]
    [com.wsscode.pathom.viz.codemirror :as cm]
    [com.wsscode.pathom.viz.codemirror6 :as cm6]
    [com.wsscode.pathom.viz.helpers :as pvh]
    [com.wsscode.pathom.viz.lib.local-storage :as ls]
    [com.wsscode.pathom.viz.query-plan :as plan-view]
    [com.wsscode.pathom.viz.trace :as pvt]
    [com.wsscode.pathom.viz.trace-with-plan :as trace+plan]
    [com.wsscode.pathom.viz.ui.kit :as ui]
    [com.wsscode.pathom3.viz.plan :as viz-plan]
    [helix.core :as h]
    [helix.hooks :as hooks]))

(declare QueryEditor TransactionResponse)

(>def ::query string?)
(>def ::query-history (s/coll-of ::query :kind vector?))

;; Helpers

(def history-max-size 100)

(defn history-remove [history query]
  (into [] (remove #{query}) history))

(defn history-append [history query]
  (-> (into [] (comp (remove #{query})
                     (take (dec history-max-size))) history)
      (p.misc/vconj query)))

;; Registry

(pc/defmutation add-query-to-history-remote [_ {::keys [query] ::cp/keys [parser-id]}]
  {::pc/params [::query ::cp/parser-id]}
  (let [store-key       [::query-history parser-id]
        current-history (ls/get store-key [])
        new-history     (history-append current-history query)]
    (ls/set! store-key new-history)))

(pc/defmutation remove-query-from-history-remote [_ {::keys [query] ::cp/keys [parser-id]}]
  {::pc/params [::query ::cp/parser-id]}
  (let [store-key       [::query-history parser-id]
        current-history (ls/get store-key [])
        new-history     (history-remove current-history query)]
    (ls/set! store-key new-history)))

(pc/defresolver query-history-resolver [env {::cp/keys [parser-id]}]
  {::pc/input  #{::cp/parser-id}
   ::pc/output [::query-history]}
  {::query-history (ls/get [::query-history parser-id] [])})

(def registry
  [add-query-to-history-remote
   remove-query-from-history-remote
   query-history-resolver])

;; Parser

(def index-query
  [{::pc/indexes [::pc/index-attributes ::pc/idents ::pc/index-io ::pc/autocomplete-ignore]}])

(pc/defresolver indexes [{::keys [client-parser]} _]
  {::pc/output [::pc/indexes]}
  (client-parser {} index-query))

(fm/defmutation run-query [{::keys [request-trace?]}]
  (action [{:keys [state ref] :as env}]
    (swap! state update-in ref assoc :ui/query-running? true))
  (ok-action [{:keys [state ref] :as env}]
    (let [response (pvh/env-parser-response env)]
      (swap! state update-in ref assoc
        :ui/query-running? false
        ::result (pvh/pprint (dissoc response :com.wsscode.pathom/trace)))
      (pvh/swap-in! env [] assoc-in [:ui/trace-viewer :com.wsscode.pathom/trace]
        (pvh/response-trace response))))
  (error-action [env]
    (js/console.log "QUERY ERROR" env))
  (remote [{:keys [ast]}]
    (cond-> (assoc ast :key `cp/client-parser-mutation)
      request-trace?
      (update-in [:params ::cp/client-parser-request] conj :com.wsscode.pathom/trace))))

(fm/defmutation load-index [_]
  (action [{:keys [state ref]}]
    (swap! state update-in ref assoc :ui/query-running? true))
  (ok-action [{:keys [state ref] :as env}]
    (let [response (pvh/env-parser-response env)]
      (swap! state update-in ref assoc
        :ui/query-running? false
        ::pc/indexes (-> response
                         p/elide-special-outputs
                         ::pc/indexes))))
  (error-action [env]
    (js/console.log "QUERY ERROR" env))
  (remote [{:keys [ast]}]
    (assoc ast :key `cp/client-parser-mutation)))

(fm/defmutation add-query-to-history [{::keys [query]}]
  (action [{:keys [state ref]}]
    (swap! state update-in (conj ref ::query-history) history-append query))
  (remote [{:keys [ast]}]
    (assoc ast :key `add-query-to-history-remote)))

(fm/defmutation remove-query-from-history [{::keys [query]}]
  (action [{:keys [state ref]}]
    (swap! state update-in (conj ref ::query-history) history-remove query))
  (remote [{:keys [ast]}]
    (assoc ast :key `remove-query-from-history-remote)))

(defn load-indexes
  [app {::keys    [id]
        ::cp/keys [parser-id]}]
  (let [props {::id                       id
               ::cp/parser-id             parser-id
               ::cp/client-parser-request index-query}]
    (fc/transact! app [(load-index props)]
      {:ref [::id id]})))

;; UI

(fc/defsc TransactionResponse [_ _]
  {:ident [::id ::id]
   :query [::id ::result :com.wsscode.pathom/trace]})

(defn load-query-editor-index [])

(defn run-query! [this]
  (let [{::keys [id]
         :as    props} (fc/props this)
        {:ui/keys  [query-running? plan-viewer]
         ::keys    [id query request-trace?]
         ::cp/keys [parser-id]} (get-in (fc/component->state-map this) [::id id])
        {::keys [enable-trace?]
         :or    {enable-trace? true}} (fc/get-computed props)]
    (when-not query-running?
      (plan-view/set-plan-view-graph! this plan-viewer nil)
      (if-let [query' (pvh/safe-read query)]
        (let [props' {::id                       id
                      ::request-trace?           (and request-trace? enable-trace?)
                      ::cp/parser-id             parser-id
                      ::cp/client-parser-request query'}]
          (fc/transact! this [(run-query props')
                              (add-query-to-history {::cp/parser-id parser-id
                                                     ::query        (str/trim query)})]))))))

(fc/defsc HistoryView
  [this {::keys [query-history
                 on-pick-query
                 on-delete-query]
         :or    {on-pick-query   identity
                 on-delete-query identity}}]
  {:css [[:.container {}]
         [:.title {:background    "#eee"
                   :border-bottom "1px solid #ccc"
                   :padding       "6px"}
          ui/text-sans-13]
         [:.actions {:display "none"}]
         [:.history-item {:border-bottom "1px solid #ccc"
                          :cursor        "pointer"
                          :font-family   ui/font-code
                          :max-height    "45px"
                          :overflow      "auto"
                          :padding       "5px"
                          :white-space   "pre"
                          :display       "flex"}
          [:&:hover {:background ui/color-highlight}
           [:.actions {:display "flex"}]]]]}
  (dom/div :.container
    (dom/div :.title "History")
    (for [query (rseq query-history)]
      (dom/div :.history-item {:key     (hash query)
                               :onClick #(on-pick-query query %)}
        (dom/div {:style {:flex "1"}} (str query))
        (dom/div :.actions
          {:onClick (fn [e]
                      (.stopPropagation e)
                      (on-delete-query query e))}
          (dom/i {:classes ["fa" "fa-trash"]}))))))

(def history-view (fc/factory HistoryView))

(defn init-query-editor [this]
  (let [parser-id (-> this fc/props ::cp/parser-id)]
    (df/load! this (fc/get-ident this) QueryEditor
      {:focus  [::query-history ::id]
       :params {:pathom/context {::cp/parser-id parser-id}}}))
  (js/setTimeout
    #(fc/set-state! this {:render? true})
    100))

(h/defnc GraphWithOptions [{:keys [graph size]}]
  (let [[ds ds!] (hooks/use-state ::viz-plan/display-type-label)]
    (fc/fragment
      (ui/section-header {}
        (ui/row {}
          (dom/div (ui/gc :.flex) "Graph Viz")
          (ui/dom-select {:value    ds
                          :onChange #(ds! %2)}
            (ui/dom-option {:value ::viz-plan/display-type-label} "Display: resolver name")
            (ui/dom-option {:value ::viz-plan/display-type-node-id} "Display: node id"))))
      (dom/div :.trace {:style {:height (str size "px")}}
        (h/$ viz-plan/PlanGraphView
          {:run-stats    graph
           :display-type ds})))))

(fc/defsc QueryEditor
  [this
   {::keys    [query result request-trace? query-history]
    ::pc/keys [indexes]
    ::cp/keys [parser-id]
    :ui/keys  [query-running? trace-viewer graph-view]}
   {::keys [editor-props enable-trace?
            default-trace-size
            default-query-size
            default-history-size]
    :or    {enable-trace? true}}]
  {:pre-merge   (fn [{:keys [current-normalized data-tree]}]
                  (let [id (or (::id data-tree)
                               (::id current-normalized)
                               (random-uuid))]
                    (merge {::id               id
                            ::request-trace?   true
                            ::query            "[]"
                            ::result           ""
                            ::query-history    []
                            :ui/show-history?  true
                            :ui/query-running? false}
                      current-normalized data-tree)))

   :ident       ::id
   :query       [::id
                 ::request-trace?
                 ::query
                 ::result
                 ::query-history
                 ::cp/parser-id
                 ::pc/indexes
                 :ui/query-running?
                 :ui/show-history?
                 :com.wsscode.pathom/trace
                 :ui/graph-view
                 :ui/trace-viewer]
   :css         [[:.container {:border         "1px solid #ddd"
                               :display        "flex"
                               :flex-direction "column"
                               :flex           "1"
                               :max-width      "100%"
                               :min-height     "200px"}]
                 [:.query-row {:display  "flex"
                               :flex     "1"
                               :overflow "hidden"
                               :position "relative"}]
                 [:.toolbar {:background    "#eeeeee"
                             :border-bottom "1px solid #e0e0e0"
                             :padding       "5px 4px"
                             :display       "flex"
                             :align-items   "center"
                             :font-family   "sans-serif"
                             :font-size     "13px"}
                  [:label {:display     "flex"
                           :align-items "center"}
                   [:input {:margin-right "5px"}]]]
                 [:.flex {:flex "1"}]
                 [:.editor {:position "relative"}]
                 [:.result {:flex     "1"
                            :position "relative"}
                  [:$CodeMirror {:background "#f6f7f8"}]]
                 [:.trace {:display     "flex"
                           :padding-top "18px"
                           :overflow    "hidden"}]
                 [:.history-container {:width      "250px"
                                       :max-height "100%"
                                       :overflow   "auto"}]]
   :css-include [pvt/D3Trace HistoryView]
   :use-hooks?  true}
  (pvh/use-layout-effect #(init-query-editor this) [])
  (let [run-query     (pvh/use-callback #(run-query! this))
        css           (css/get-classnames QueryEditor)
        show-history? (pvh/use-persistent-state ::show-history? true)
        history-size  (pvh/use-persistent-state ::history-width (or default-history-size 250))
        query-size    (pvh/use-persistent-state ::query-width (or default-query-size 400))
        trace-size    (pvh/use-persistent-state ::trace-height (or default-trace-size 200))]
    (dom/div :.container
      (dom/div :.toolbar
        (if enable-trace?
          (dom/label
            (dom/input {:type     "checkbox"
                        :checked  request-trace?
                        :onChange #(fm/toggle! this ::request-trace?)})
            "Request trace"))
        (dom/div :.flex)
        (ui/button {:onClick  #(swap! show-history? not)
                    :disabled (not (seq query-history))
                    :style    {:marginRight "6px"}}
          "History")
        (ui/button {:onClick #(load-indexes (fc/any->app this) (fc/props this))
                    :style   {:marginRight "6px"}}
          "Refresh index")
        (ui/button {:onClick  run-query
                    :disabled query-running?}
          "Run query"))

      (dom/div :.query-row$min-h-20
        (if (and @show-history? (seq query-history))
          (fc/fragment
            (dom/div :.history-container$min-w-40 {:style {:width (str @history-size "px")}}
              (history-view {::query-history   query-history
                             ::on-pick-query   #(fm/set-value! this ::query %)
                             ::on-delete-query #(fc/transact! this [(remove-query-from-history {::query        %
                                                                                                ::cp/parser-id parser-id})])}))
            (ui/drag-resize
              {:direction "left"
               :key       "dragHandlerHistory"
               :state     history-size})))

        (cm/pathom
          (merge {:className   (str (:editor css) " min-w-40")
                  :style       {:width (str @query-size "px")}
                  :value       (or (str query) "")
                  ::pc/indexes (if (map? indexes) (p/elide-not-found indexes))
                  ::cm/options {::cm/extraKeys
                                {"Cmd-Enter"   run-query
                                 "Ctrl-Enter"  run-query
                                 "Shift-Enter" run-query
                                 "Cmd-J"       "pathomJoin"
                                 "Ctrl-Space"  "autocomplete"}}
                  :onChange    #(fm/set-value! this ::query %)}
            editor-props))

        (ui/drag-resize
          {:direction "left"
           :state     query-size})

        (cm6/clojure-read result {:classes ["min-w-40"]}))

      (if trace-viewer
        (fc/fragment
          (ui/drag-resize
            {:direction "down"
             :state     trace-size})

          (dom/div :.trace$min-h-20 {:style {:height (str @trace-size "px")}}
            (trace+plan/trace-with-plan
              (:com.wsscode.pathom/trace trace-viewer))))))))

(def query-editor (fc/computed-factory QueryEditor))
