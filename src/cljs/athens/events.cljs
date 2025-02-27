(ns athens.events
  (:require
    [athens.athens-datoms                 :as athens-datoms]
    [athens.common-db                     :as common-db]
    [athens.common-events                 :as common-events]
    [athens.common-events.bfs             :as bfs]
    [athens.common-events.graph.atomic    :as atomic-graph-ops]
    [athens.common-events.graph.composite :as composite-ops]
    [athens.common-events.graph.ops       :as graph-ops]
    [athens.common-events.resolver.atomic :as atomic-resolver]
    [athens.common-events.resolver.undo   :as undo-resolver]
    [athens.common-events.schema          :as schema]
    [athens.common.logging                :as log]
    [athens.common.sentry                 :refer-macros [wrap-span-no-new-tx]]
    [athens.common.utils                  :as common.utils]
    [athens.dates                         :as dates]
    [athens.db                            :as db]
    [athens.electron.db-picker            :as db-picker]
    [athens.electron.images               :as images]
    [athens.electron.monitoring.core      :as monitoring]
    [athens.electron.utils                :as electron.utils]
    [athens.events.remote                 :as events-remote]
    [athens.events.sentry]
    [athens.interceptors                  :as interceptors]
    [athens.undo                          :as undo]
    [athens.util                          :as util]
    [athens.utils.sentry                  :as sentry]
    [athens.views.blocks.textarea-keydown :as textarea-keydown]
    [athens.views.comments.core :as comments]
    [clojure.pprint                       :as pp]
    [clojure.string                       :as string]
    [datascript.core                      :as d]
    [day8.re-frame.async-flow-fx]
    [day8.re-frame.tracing                :refer-macros [fn-traced]]
    [goog.dom                             :refer [getElement]]
    [malli.core                           :as m]
    [malli.error                          :as me]
    [re-frame.core                        :as rf :refer [reg-event-db reg-event-fx subscribe]]))


;; -- re-frame app-db events ---------------------------------------------

(reg-event-fx
  :create-in-memory-conn
  (fn [_ _]
    (let [conn (common-db/create-conn)]
      (doseq [[_id data] athens-datoms/welcome-events]
        (atomic-resolver/resolve-transact! conn data))
      {:async-flow {:id             :db-in-mem-load
                    :db-path        [:async-flow :db/in-mem-load]
                    :first-dispatch [:reset-conn @conn]
                    :rules          [{:when     :seen?
                                      :events   :success-reset-conn
                                      :dispatch [:stage/success-db-load]
                                      :halt?    true}]}})))


(rf/reg-event-db
  :stage/success-db-load
  (fn [db]
    (js/console.debug ":stage/success-db-load")
    db))


(rf/reg-event-db
  :stage/fail-db-load
  (fn [db]
    (js/console.debug ":stage/fail-db-load")
    db))


(reg-event-db
  :init-rfdb
  [(interceptors/sentry-span-no-new-tx "init-rfdb")]
  (fn [_ _]
    db/rfdb))


(reg-event-db
  :db/sync
  [(interceptors/sentry-span-no-new-tx "db/sync")]
  (fn [db [_]]
    (assoc db :db/synced true)))


(reg-event-db
  :db/not-synced
  [(interceptors/sentry-span-no-new-tx "db/not-synced")]
  (fn [db [_]]
    (assoc db :db/synced false)))


(reg-event-fx
  :athena/toggle
  [(interceptors/sentry-span-no-new-tx "athena/toggle")]
  (fn [{:keys [db]} _]
    {:db (update db :athena/open not)
     :dispatch [:posthog/report-feature :athena]}))


(reg-event-db
  :athena/update-recent-items
  [(interceptors/sentry-span-no-new-tx "athena/undate-recent-items")]
  (fn-traced [db [_ selected-page]]
             (when (nil? ((set (:athena/recent-items db)) selected-page))
               (update db :athena/recent-items conj selected-page))))


(reg-event-fx
  :help/toggle
  [(interceptors/sentry-span-no-new-tx "help/toggle")]
  (fn [{:keys [db]} _]
    {:db (update db :help/open? not)
     :dispatch [:posthog/report-feature :help]}))


(reg-event-fx
  :left-sidebar/toggle
  [(interceptors/sentry-span-no-new-tx "left-sidebar/toggle")]
  (fn [{:keys [db]} _]
    {:db (update db :left-sidebar/open not)
     :dispatch [:posthog/report-feature :left-sidebar]}))


(reg-event-db
  :mouse-down/set
  (fn [db _]
    (assoc db :mouse-down true)))


(reg-event-db
  :mouse-down/unset
  (fn [db _]
    (assoc db :mouse-down false)))


;; no ops -- does not do anything
;; useful in situations where there is no dispatch value
(reg-event-fx
  :no-op
  (fn [_ _]
    (log/warn "Called :no-op re-frame event, this shouldn't be happening.")
    {}))


(reg-event-fx
  :editing/uid
  [(interceptors/sentry-span-no-new-tx "editing/uid")]
  (fn [{:keys [db]} [_ uid index]]
    (let [remote? (db-picker/remote-db? db)]
      {:db            (assoc db :editing/uid uid)
       :editing/focus [uid index]
       :dispatch-n    [(when (and uid remote?)
                         [:presence/send-update {:block-uid (util/embed-uid->original-uid uid)}])]})))


(reg-event-fx
  :editing/target
  [(interceptors/sentry-span-no-new-tx "editing/target")]
  (fn [_ [_ target]]
    (let [uid (-> (.. target -id)
                  (string/split "editable-uid-")
                  second)]
      {:dispatch [:editing/uid uid]})))


(reg-event-fx
  :editing/first-child
  [(interceptors/sentry-span-no-new-tx "editing/first-child")]
  (fn [_ [_ uid]]
    (when-let [first-block-uid (db/get-first-child-uid uid @db/dsdb)]
      {:dispatch [:editing/uid first-block-uid]})))


(reg-event-fx
  :editing/last-child
  [(interceptors/sentry-span-no-new-tx "editing/last-child")]
  (fn [_ [_ uid]]
    (when-let [last-block-uid (db/get-last-child-uid uid @db/dsdb)]
      {:dispatch [:editing/uid last-block-uid]})))


(defn select-up
  [selected-items]
  (let [first-item       (first selected-items)
        [_ o-embed]      (db/uid-and-embed-id first-item)
        prev-block-uid   (db/prev-block-uid first-item)
        prev-block-o-uid (-> prev-block-uid db/uid-and-embed-id first)
        prev-block       (db/get-block [:block/uid prev-block-o-uid])
        parent           (db/get-parent [:block/uid (-> first-item db/uid-and-embed-id first)])
        editing-uid      @(subscribe [:editing/uid])
        editing-idx      (first (keep-indexed (fn [idx x]
                                                (when (= x editing-uid)
                                                  idx))
                                              selected-items))
        n                (count selected-items)
        new-items        (cond
                           ;; if prev-block is root node TODO: (OR context root), don't do anything
                           (and (zero? editing-idx) (> n 1)) (pop selected-items)
                           (:node/title prev-block) selected-items
                           ;; if prev block is parent, replace editing/uid and first item w parent; remove children
                           (= (:block/uid parent) prev-block-o-uid) (let [parent-children (-> (common-db/sorted-prop+children-uids @db/dsdb [:block/uid prev-block-uid])
                                                                                              set)
                                                                          to-keep         (->> selected-items
                                                                                               (map #(-> % db/uid-and-embed-id first))
                                                                                               (filter (fn [x] (not (contains? parent-children x)))))
                                                                          new-vec         (into [prev-block-uid] to-keep)]
                                                                      new-vec)

                           ;; shift up started from inside the embed should not go outside embed block
                           o-embed (let [selected-uid (str prev-block-o-uid "-embed-" o-embed)
                                         html-el      (js/document.querySelector (str "#editable-uid-" prev-block-o-uid "-embed-" o-embed))]
                                     (if html-el
                                       (into [selected-uid] selected-items)
                                       selected-items))

                           :else (into [prev-block-uid] selected-items))]
    new-items))


(reg-event-db
  :selected/up
  [(interceptors/sentry-span-no-new-tx "selected/up")]
  (fn [db [_ selected-items]]
    (assoc-in db [:selection :items] (select-up selected-items))))


;; using a set or a hash map, we would need a secondary editing/uid to maintain the head/tail position
;; this would let us know if the operation is additive or subtractive
(reg-event-db
  :selected/down
  [(interceptors/sentry-span-no-new-tx "selected/down")]
  (fn [db [_ selected-items]]
    (let [last-item         (last selected-items)
          next-block-uid    (db/next-block-uid last-item true)
          ordered-selection (cond-> (into [] selected-items)
                              next-block-uid (into [next-block-uid]))]
      (log/debug ":selected/down, new-selection:" (pr-str ordered-selection))
      (assoc-in db [:selection :items] ordered-selection))))


(reg-event-fx
  :alert/js
  (fn [_ [_ message]]
    {:alert/js! message}))


(reg-event-fx
  :confirm/js
  (fn [_ [_ message true-cb false-cb]]
    {:confirm/js! [message true-cb false-cb]}))


;; Modal


(reg-event-db
  :modal/toggle
  (fn [db _]
    (update db :modal not)))


;; Loading

(reg-event-db
  :loading/set
  (fn-traced [db]
             (assoc-in db [:loading?] true)))


(reg-event-db
  :loading/unset
  (fn-traced [db]
             (assoc-in db [:loading?] false)))


(reg-event-db
  :tooltip/uid
  (fn [db [_ uid]]
    (assoc db :tooltip/uid uid)))


;; Connection status

(reg-event-fx
  :conn-status
  (fn [{:keys [db]} [_ to-status]]
    (let [from-status (:connection-status db)]
      {:db (assoc db :connection-status to-status)
       :dispatch-n [(condp = [from-status to-status]
                      [:reconnecting :connected] [:loading/unset]
                      [:connected :reconnecting] [:loading/set]
                      nil)]})))


;; Daily Notes

(reg-event-db
  :daily-note/reset
  (fn [db [_ uids]]
    (assoc db :daily-notes/items uids)))


(reg-event-db
  :daily-note/add
  (fn [db [_ uid]]
    (update db :daily-notes/items (comp vec rseq sort distinct conj) uid)))


(reg-event-fx
  :daily-note/ensure-day
  (fn [_ [_ {:keys [uid title]}]]
    (when-not (db/e-by-av :block/uid uid)
      {:dispatch [:page/new {:title     title
                             :block-uid (common.utils/gen-block-uid)
                             :source    :auto-make-daily-note}]})))


(reg-event-fx
  :daily-note/prev
  (fn [{:keys [db]} [_ {:keys [uid] :as day}]]
    (let [new-db (update db :daily-notes/items (fn [items]
                                                 (into [uid] items)))]
      {:db       new-db
       :dispatch [:daily-note/ensure-day day]})))


(reg-event-fx
  :daily-note/next
  (fn [_ [_ {:keys [uid] :as day}]]
    {:dispatch-n [[:daily-note/ensure-day day]
                  [:daily-note/add uid]]}))


(reg-event-fx
  :daily-note/delete
  (fn [{:keys [db]} [_ uid title]]
    (let [filtered-dn        (filterv #(not= % uid) (:daily-notes/items db)) ; Filter current date from daily note vec
          new-db (assoc db :daily-notes/items filtered-dn)]
      {:fx [[:dispatch [:page/delete title]]]
       :db new-db})))


(reg-event-fx
  :daily-note/scroll
  (fn [_ [_]]
    (let [daily-notes @(subscribe [:daily-notes/items])
          el          (getElement "daily-notes")]
      (when el
        (let [offset-top   (.. el -offsetTop)
              rect         (.. el getBoundingClientRect)
              from-bottom  (.. rect -bottom)
              from-top     (.. rect -top)
              doc-height   (.. js/document -documentElement -scrollHeight)
              top-delta    (- offset-top from-top)
              bottom-delta (- from-bottom doc-height)]
          ;; Don't allow user to scroll up for now.
          (cond
            (< top-delta 1) nil #_(dispatch [:daily-note/prev (get-day (uid-to-date (first daily-notes)) -1)])
            (< bottom-delta 1) {:fx [[:dispatch [:daily-note/next (dates/get-day (dates/uid-to-date (last daily-notes)) 1)]]]}))))))


;; -- event-fx and Datascript Transactions -------------------------------

;; Import/Export


(reg-event-fx
  :http-success/get-db
  [(interceptors/sentry-span-no-new-tx "http-success/get-db")]
  (fn [_ [_ json-str]]
    (let [datoms (db/str-to-db-tx json-str)
          new-db (d/db-with common-db/empty-db datoms)]
      {:dispatch [:reset-conn new-db]})))


(reg-event-fx
  :theme/set
  [(interceptors/sentry-span-no-new-tx "theme/set")]
  (fn [{:keys [db]} _]
    (util/switch-body-classes (if (-> db :athens/persist :theme/dark)
                                ["is-theme-light" "is-theme-dark"]
                                ["is-theme-dark" "is-theme-light"]))
    {}))


(reg-event-fx
  :theme/toggle
  [(interceptors/sentry-span-no-new-tx "theme/toggle")]
  (fn [{:keys [db]} _]
    {:db         (update-in db [:athens/persist :theme/dark] not)
     :dispatch-n [[:theme/set]
                  [:posthog/report-feature :theme]]}))


;; Datascript

;; These events are used for async flows, so we know when changes are in the
;; datascript db.
;; If you need to know which event was resolved, check the arg as
;; shown in https://github.com/day8/re-frame-async-flow-fx#advanced-use.
(rf/reg-event-fx
  :success-resolve-forward-transact
  (fn [_ [_ _event]]
    {}))


(rf/reg-event-fx
  :fail-resolve-transact-forward
  (fn [_ [_ _event]]
    {}))


(reg-event-fx
  :reset-conn
  [(interceptors/sentry-span-no-new-tx "reset-conn")]
  (fn-traced [_ [_ db skip-health-check?]]
             {:reset-conn! [db skip-health-check?]}))


(rf/reg-event-fx
  :success-reset-conn
  (fn [_ _]
    (js/console.debug ":success-reset-conn")
    {}))


(defn datom->tx-entry
  [[e a v :as datom]]
  (if (and (string/includes? (name a) "+")
           (nil? (second v)))
    (log/warn "Offending attribute entity (it has `nil` for `:block/key` value):" (pr-str datom))
    [:db/add e a v]))


(rf/reg-event-fx
  :db-dump-handler
  (fn-traced [{:keys [db]} [_ datoms]]
             (let [existing-tx     (sentry/transaction-get-current)
                   sentry-tx       (if existing-tx
                                     existing-tx
                                     (sentry/transaction-start "db-dump-handler"))
                   conversion-span (sentry/span-start sentry-tx "convert-datoms")
                   ;; TODO: this new-db should be derived from an internal representation transact event instead.
                   new-db          (d/db-with common-db/empty-db
                                              (into [] (map datom->tx-entry) datoms))]
               (sentry/span-finish conversion-span)
               {:db         db
                :async-flow {:id             :db-dump-handler-async-flow ; NOTE do not ever use id that is defined event
                             :db-path        [:async-flow :db-dump-handler]
                             :first-dispatch [:reset-conn new-db true]
                             :rules          [{:when       :seen?
                                               :events     :success-reset-conn
                                               :dispatch-n [[:remote/start-event-sync]
                                                            [:db/sync]
                                                            [:remote/connected]]}
                                              {:when       :seen-all-of?
                                               :events     [:success-reset-conn
                                                            :remote/start-event-sync
                                                            :db/sync
                                                            :remote/connected]
                                               :dispatch-n (cond-> [[:stage/success-db-load]]
                                                             (not existing-tx) (conj [:sentry/end-tx sentry-tx]))
                                               :halt?      true}]}})))


(reg-event-fx
  :electron-sync
  [(interceptors/sentry-span-no-new-tx "electron-sync")]
  (fn [_ _]
    (let [synced?   @(subscribe [:db/synced])
          electron? electron.utils/electron?]
      (merge {}
             (when (and synced? electron?)
               {:fx [[:dispatch [:db/not-synced]]
                     [:dispatch [:save]]]})))))


(reg-event-fx
  :resolve-transact-forward
  [(interceptors/sentry-span "resolve-transact-forward")]
  (fn [{:keys [db]} [_ event]]
    (let [remote?     (db-picker/remote-db? db)
          valid?      (schema/valid-event? event)
          dsdb        @db/dsdb
          undo?       (undo-resolver/undo? event)
          presence-id (-> (subscribe [:presence/current-user]) deref :username)
          event       (if (and remote? presence-id)
                        (common-events/add-presence event presence-id)
                        event)]
      (log/debug ":resolve-transact-forward event:" (pr-str event)
                 "remote?" (pr-str remote?)
                 "valid?" (pr-str valid?)
                 "undo?" (pr-str undo?))
      (if-not valid?
        ;; Don't try to process invalid events, just log them.
        (let [explanation (-> schema/event
                              (m/explain event)
                              (me/humanize))]
          (log/warn "Not sending invalid event. Error:" (with-out-str (pp/pprint explanation))
                    "\nInvalid event was:" (with-out-str (pp/pprint event)))
          {:fx [[:dispatch [:fail-resolve-forward-transact event]]]})


        (try
          ;; Seems valid, lets process it.
          (let [;; First, resolve it into dsdb.
                db' (if remote?
                      ;; Remote db events have to be managed via the synchronizer in events.remote.
                      (first (events-remote/add-memory-event! [db db/dsdb] event))
                      (do
                        ;; For local dbs, just transact it directly into dsdb.
                        (atomic-resolver/resolve-transact! db/dsdb event)
                        db))

                ;; Then figure out the undo situation.
                db'' (if undo?
                       ;; For undos, let the undo/redo handlers manage db state.
                       db'
                       ;; Otherwise wipe the redo stack and add the new event.
                       (-> db'
                           undo/reset-redo
                           (undo/push-undo (:event/id event) [dsdb event])))]

            ;; Wrap it up.
            (merge
              {:db db''
               :fx [;; Local dbs will need to be synced via electron.
                    (when-not remote? [:dispatch [:electron-sync]])
                    ;; Remote dbs just wait for the event to be confirmed by the server.
                    (when remote?     [:dispatch [:db/not-synced]])
                    ;; Processing has finished successfully at this point, signal the async flows.
                    [:dispatch [:success-resolve-forward-transact event]]]}
              ;; Remote dbs need to actually send the event via the network.
              (when remote? {:remote/send-event-fx! event})))

          ;; Bork bork, still need to clean up.
          (catch :default e
            (log/error ":resolve-transact-forward failed with event " event " with error " e)
            {:fx [[:dispatch [:fail-resolve-forward-transact event]]]}))))))


(reg-event-fx
  :page/delete
  [(interceptors/sentry-span "page/delete")]
  (fn [_ [_ title]]
    (log/debug ":page/delete:" title)
    (let [event (common-events/build-atomic-event (atomic-graph-ops/make-page-remove-op title))]
      {:fx [[:dispatch [:resolve-transact-forward event]]]})))


(reg-event-fx
  :left-sidebar/add-shortcut
  [(interceptors/sentry-span-no-new-tx "left-sidebar/add-shortcut")]
  (fn [_ [_ name]]
    (log/debug ":page/add-shortcut:" name)
    (let [add-shortcut-op (atomic-graph-ops/make-shortcut-new-op name)
          event           (common-events/build-atomic-event add-shortcut-op)]
      {:fx [[:dispatch [:resolve-transact-forward event]]]})))


(reg-event-fx
  :left-sidebar/remove-shortcut
  [(interceptors/sentry-span-no-new-tx "left-sidebar/remove-shortcut")]
  (fn [_ [_ name]]
    (log/debug ":page/remove-shortcut:" name)
    (let [remove-shortcut-op (atomic-graph-ops/make-shortcut-remove-op name)
          event              (common-events/build-atomic-event remove-shortcut-op)]
      {:fx [[:dispatch [:resolve-transact-forward event]]]})))


(reg-event-fx
  :left-sidebar/drop
  [(interceptors/sentry-span-no-new-tx "left-sidebar/drop")]
  (fn [_ [_ source-order target-order relation]]
    (let [[source-name target-name] (common-db/find-source-target-title @db/dsdb source-order target-order)
          drop-op                   (atomic-graph-ops/make-shortcut-move-op source-name
                                                                            {:page/title target-name
                                                                             :relation relation})
          event (common-events/build-atomic-event drop-op)]
      {:fx [[:dispatch [:resolve-transact-forward event]]
            [:dispatch [:posthog/report-feature :left-sidebar]]]})))


(reg-event-fx
  :save
  [(interceptors/sentry-span-no-new-tx "save")]
  (fn [_ _]
    {:fs/write! nil}))


(reg-event-fx
  :undo
  [(interceptors/sentry-span-no-new-tx "undo")]
  (fn [{:keys [db]} _]
    (try
      (log/debug ":undo count" (undo/count-undo db))
      (if-some [[undo db'] (undo/pop-undo db)]
        (let [[evt-dsdb evt] undo
              evt-id         (:event/id evt)
              dsdb           @db/dsdb
              undo-evt       (undo-resolver/build-undo-event dsdb evt-dsdb evt)
              undo-ops       (:event/op undo-evt)
              new-titles     (graph-ops/ops->new-page-titles undo-ops)
              new-uids       (graph-ops/ops->new-block-uids undo-ops)
              [_rm add]      (graph-ops/structural-diff @db/dsdb undo-ops)
              undo-evt-id    (:event/id undo-evt)
              db''           (undo/push-redo db' undo-evt-id [dsdb undo-evt])]
          (log/debug ":undo evt" (pr-str evt-id) "as" (pr-str undo-evt-id))
          {:db db''
           :fx [[:dispatch-n (cond-> [[:resolve-transact-forward undo-evt]]
                               (seq new-titles)
                               (conj [:reporting/page.create {:source :undo
                                                              :count  (count new-titles)}])
                               (seq new-uids)
                               (conj [:reporting/block.create {:source :undo
                                                               :count  (count new-uids)}])
                               (seq add)
                               (concat (monitoring/build-reporting-link-creation add :undo)))]]})
        {})
      (catch :default _
        {:fx (util/toast (clj->js {:status "error"
                                   :title "Couldn't undo"
                                   :description "Undo for this operation not supported in Lan-Party, yet."}))}))))


(reg-event-fx
  :redo
  [(interceptors/sentry-span-no-new-tx "redo")]
  (fn [{:keys [db]} _]
    (log/debug ":redo")
    (try
      (log/debug ":redo count" (undo/count-redo db))
      (if-some [[redo db'] (undo/pop-redo db)]
        (let [[evt-dsdb evt] redo
              evt-id         (:event/id evt)
              dsdb           @db/dsdb
              undo-evt       (undo-resolver/build-undo-event dsdb evt-dsdb evt)
              undo-ops       (:event/op undo-evt)
              undo-evt-id    (:event/id undo-evt)
              new-titles     (graph-ops/ops->new-page-titles undo-ops)
              new-uids       (graph-ops/ops->new-block-uids undo-ops)
              [_rm add]      (graph-ops/structural-diff @db/dsdb undo-ops)
              db''           (undo/push-undo db' undo-evt-id [dsdb undo-evt])]
          (log/debug ":redo evt" (pr-str evt-id) "as" (pr-str undo-evt-id))
          {:db db''
           :fx [[:dispatch-n (cond-> [[:resolve-transact-forward undo-evt]]
                               (seq new-titles)
                               (conj [:reporting/page.create {:source :redo
                                                              :count  (count new-titles)}])
                               (seq new-uids)
                               (conj [:reporting/block.create {:source :redo
                                                               :count  (count new-uids)}])
                               (seq add)
                               (concat (monitoring/build-reporting-link-creation add :redo)))]]})
        {})
      (catch :default _
        {:fx (util/toast (clj->js {:status "error"
                                   :title "Couldn't redo"
                                   :description "Redo for this operation not supported in Lan-Party, yet."}))}))))


(reg-event-fx
  :reset-undo-redo
  [(interceptors/sentry-span-no-new-tx "reset-undo-redo")]
  (fn [{:keys [db]} _]
    {:db (undo/reset db)}))


(defn window-uid?
  "Returns true if uid matches the toplevel window.
  Only works for the main window."
  [uid]
  (let [[uid _]    (db/uid-and-embed-id uid)
        window-uid @(subscribe [:current-route/uid-compat])]
    (and uid window-uid (= uid window-uid))))


(reg-event-fx
  :up
  [(interceptors/sentry-span-no-new-tx "up")]
  (fn [_ [_ uid target-pos]]
    (let [prev-block-uid  (db/prev-block-uid uid)
          prev-block-uid' (when-not (window-uid? prev-block-uid)
                            prev-block-uid)]
      {:dispatch [:editing/uid (or prev-block-uid' uid) target-pos]})))


(reg-event-fx
  :down
  [(interceptors/sentry-span-no-new-tx "down")]
  (fn [_ [_ uid target-pos]]
    (let [next-block-uid (db/next-block-uid uid)]
      #_(log/debug ::down (pr-str {:uid uid :target-pos target-pos :next-block-uid next-block-uid}))
      {:dispatch [:editing/uid (or next-block-uid uid) target-pos]})))


(defn backspace
  "If root and 0th child, 1) if value, no-op, 2) if blank value, delete only block.
  No-op if parent is missing.
  No-op if parent is prev-block and block has children.
  No-op if prev-sibling-block has children.
  Otherwise delete block and join with previous block
  If prev-block has children"
  ([uid value]
   (backspace uid value nil))
  ([uid value maybe-local-updates]
   (let [root-embed?     (= (some-> (str "#editable-uid-" uid)
                                    js/document.querySelector
                                    (.. (closest ".block-embed"))
                                    (. -firstChild)
                                    (.getAttribute "data-uid"))
                            uid)
         db              @db/dsdb
         [uid embed-id]  (common-db/uid-and-embed-id uid)
         block           (common-db/get-block db [:block/uid uid])
         children-uids   (common-db/sorted-prop+children-uids @db/dsdb [:block/uid uid])
         parent          (common-db/get-parent db [:block/uid uid])
         prev-block-uid  (db/prev-block-uid uid)
         prev-block      (common-db/get-block db [:block/uid prev-block-uid])
         prev-sib        (db/nth-sibling uid :before)
         prev-sib-children-uids (common-db/sorted-prop+children-uids @db/dsdb [:block/uid (:block/uid prev-sib)])
         event           (cond
                           (or (not parent)
                               root-embed?
                               (and (seq children-uids) (seq prev-sib-children-uids))
                               (and (seq children-uids) (= parent prev-block)))
                           nil

                           (:block/key block)
                           [:block/move {:source-uid uid
                                         :target-uid (:block/uid parent)
                                         :target-rel :first
                                         :local-string value}]

                           (and (empty? children-uids) (:node/title parent)
                                (= uid (first children-uids)) (clojure.string/blank? value))
                           [:backspace/delete-only-child uid]

                           maybe-local-updates
                           [:backspace/delete-merge-block-with-save {:uid            uid
                                                                     :value          value
                                                                     :prev-block-uid prev-block-uid
                                                                     :embed-id       embed-id
                                                                     :prev-block     prev-block
                                                                     :local-update   maybe-local-updates}]
                           :else
                           [:backspace/delete-merge-block {:uid uid
                                                           :value value
                                                           :prev-block-uid prev-block-uid
                                                           :embed-id embed-id
                                                           :prev-block prev-block}])]
     (log/debug "[Backspace] args:" (pr-str {:uid uid
                                             :value value})
                ", event:" (pr-str event))
     (when event
       {:fx [[:dispatch event]]}))))


;; todo(abhinav) -- stateless backspace
;; will pick db value of backspace/delete instead of current state
;; which might not be same as blur is not yet called
(reg-event-fx
  :backspace
  [(interceptors/sentry-span-no-new-tx "backspace")]
  (fn [_ [_ uid value maybe-local-updates]]
    (backspace uid value maybe-local-updates)))


;; Atomic events start ==========

(defn- wait-for-rft
  [sentry-tx success-dispatch-n]
  [{:when       :seen?
    :events     :fail-resolve-forward-transact
    :dispatch   [:sentry/end-tx sentry-tx]
    :halt?      true}
   {:when       :seen?
    :events     :success-resolve-forward-transact
    :dispatch-n (into [[:sentry/end-tx sentry-tx]] success-dispatch-n)
    :halt?      true}])


(defn- transact-async-flow
  [id-kw event sentry-tx success-dispatch-n]
  [:async-flow {:id             (keyword (str (name id-kw) "-async-flow"))
                :db-path        [:async-flow id-kw]
                :first-dispatch [:resolve-transact-forward event]
                :rules          (wait-for-rft sentry-tx success-dispatch-n)}])


(defn- close-and-get-sentry-tx
  "Always closes old running transaction and starts new one"
  [name]
  (let [running-tx? (sentry/tx-running?)]
    (when running-tx?
      (sentry/transaction-finish (sentry/transaction-get-current)))
    (sentry/transaction-start name)))


(defn- focus-on-uid
  ([uid embed-id]
   [:editing/uid
    (str uid (when embed-id
               (str "-embed-" embed-id)))])
  ([uid embed-id idx]
   [:editing/uid
    (str uid (when embed-id
               (str "-embed-" embed-id)))
    idx]))


(reg-event-fx
  :backspace/delete-only-child
  (fn [_ [_ uid]]
    (log/debug ":backspace/delete-only-child:" (pr-str uid))
    (let [sentry-tx   (close-and-get-sentry-tx "backspace/delete-only-child")
          op          (wrap-span-no-new-tx "build-block-remove-op"
                                           (graph-ops/build-block-remove-op @db/dsdb uid))
          event       (common-events/build-atomic-event op)]
      {:fx [(transact-async-flow :backspace-delete-only-child event sentry-tx [[:editing/uid nil]])]})))


(reg-event-fx
  :enter/new-block
  (fn [_ [_ {:keys [block parent new-uid embed-id]}]]
    (log/debug ":enter/new-block" (pr-str block) (pr-str parent) (pr-str new-uid))
    (let [sentry-tx   (close-and-get-sentry-tx "enter/new-block")
          op          (atomic-graph-ops/make-block-new-op new-uid {:block/uid (:block/uid block)
                                                                   :relation  :after})
          event       (common-events/build-atomic-event op)]
      {:fx [(transact-async-flow :enter-new-block event sentry-tx [(focus-on-uid new-uid embed-id)])
            [:dispatch [:reporting/block.create {:source :enter-new-block
                                                 :count  1}]]]})))


(reg-event-fx
  :check-for-mentions
  (fn [_ [_ uid string]]
    (let [username          (rf/subscribe [:username])
          mentions          (comments/get-all-mentions string @username)
          mention-op        (when (not-empty mentions)
                              (comments/create-notification-op-for-users {:db                     @db/dsdb
                                                                          :parent-block-uid       uid
                                                                          :notification-for-users mentions
                                                                          :author                 @username
                                                                          :trigger-block-uid      uid
                                                                          :notification-type      "athens/notification/type/mention"}))
          event             (common-events/build-atomic-event  (composite-ops/make-consequence-op {:op/type :mention-notifications}
                                                                                                  mention-op))]
      (when mention-op
        {:fx [[:dispatch [:resolve-transact-forward event]]]}))))


(reg-event-fx
  :notification-for-assigned-task
  (fn [{:keys [db]} [_ uid assignee]]
    (let [username          (-> db :athens/persist :settings :username)
          assignee-op       (when assignee
                              (comments/create-notification-op-for-users {:db                     @db/dsdb
                                                                          :parent-block-uid       uid
                                                                          :notification-for-users [assignee]
                                                                          :author                 username
                                                                          :trigger-block-uid      uid
                                                                          :notification-type      "athens/task/assigned/to"}))
          task-creator-op   (comments/create-notification-op-for-users {:db                     @db/dsdb
                                                                        :parent-block-uid       uid
                                                                        :notification-for-users [(str "[[@" username "]]")]
                                                                        :author                 username
                                                                        :trigger-block-uid      uid
                                                                        :notification-type      "athens/task/assigned/by"})
          event             (common-events/build-atomic-event  (composite-ops/make-consequence-op {:op/type :mention-notifications}
                                                                                                  (concat
                                                                                                    assignee-op
                                                                                                    task-creator-op)))]
      (when assignee-op
        {:fx [[:dispatch [:resolve-transact-forward event]]]}))))


(reg-event-fx
  :block/save
  (fn [{:keys [db]} [_ {:keys [uid string source] :as args}]]
    (log/debug ":block/save args" (pr-str args))
    (let [local?      (not (db-picker/remote-db? db))
          block-eid   (common-db/e-by-av @db/dsdb :block/uid uid)
          old-string  (->> block-eid
                           (d/entity @db/dsdb)
                           :block/string)
          do-nothing? (or (not block-eid)
                          (= old-string string))
          op          (graph-ops/build-block-save-op @db/dsdb uid string)
          new-titles  (graph-ops/ops->new-page-titles op)
          [_rm add]   (graph-ops/structural-diff @db/dsdb op)
          event       (common-events/build-atomic-event op)]
      (log/debug ":block/save local?" local?
                 ", do-nothing?" do-nothing?)
      (when-not do-nothing?
        {:fx [[:dispatch-n (cond-> [[:resolve-transact-forward event]
                                    [:check-for-mentions uid string]]
                             (seq new-titles)
                             (conj [:reporting/page.create {:source (or source :unknown-block-save)
                                                            :count  (count new-titles)}])
                             (seq add)
                             (concat (monitoring/build-reporting-link-creation add (or source :unknown-block-save))))]]}))))


(reg-event-fx
  :page/new
  (fn [_ [_ {:keys [title block-uid shift? source]
             :or   {shift? false
                    source :unknown-page-new}
             :as   args}]]
    (log/debug ":page/new args" (pr-str args))
    (let [new-page-op (graph-ops/build-page-new-op @db/dsdb
                                                   title
                                                   block-uid)
          new-titles  (graph-ops/ops->new-page-titles new-page-op)
          event       (common-events/build-atomic-event new-page-op)]
      {:fx [[:dispatch-n [[:resolve-transact-forward event]
                          [:page/new-followup title shift?]
                          [:editing/uid block-uid]
                          [:reporting/page.create {:source source
                                                   :count  (count new-titles)}]]]]})))


(reg-event-fx
  :page/rename
  (fn [_ [_ {:keys [old-name new-name callback] :as args}]]
    (log/debug ":page/rename args:" (pr-str (select-keys args [:old-name :new-name])))
    (let [event (common-events/build-atomic-event (graph-ops/build-page-rename-op @db/dsdb old-name new-name))]
      {:fx [[:dispatch [:resolve-transact-forward event]]
            [:invoke-callback callback]]})))


(reg-event-fx
  :page/merge
  (fn [_ [_ {:keys [from-name to-name callback] :as args}]]
    (log/debug ":page/merge args:" (pr-str (select-keys args [:from-name :to-name])))
    (let [event (common-events/build-atomic-event (atomic-graph-ops/make-page-merge-op from-name to-name))]
      {:fx [[:dispatch [:resolve-transact-forward event]]
            [:invoke-callback callback]]})))


(reg-event-fx
  :page/new-followup
  (fn [_ [_ title shift?]]
    (log/debug ":page/new-followup title" title "shift?" shift?)
    (let [page-uid (common-db/get-page-uid @db/dsdb title)]
      {:fx [[:dispatch-n [(cond
                            shift?
                            [:right-sidebar/open-item [:node/title title]]

                            (not (dates/is-daily-note page-uid))
                            [:navigate :page {:id page-uid}])]]]})))


(reg-event-fx
  :backspace/delete-merge-block
  (fn [_ [_ {:keys [uid value prev-block-uid embed-id prev-block] :as args}]]
    (log/debug ":backspace/delete-merge-block args:" (pr-str args))
    (let [sentry-tx  (close-and-get-sentry-tx "backspace/delete-merge-block")
          op         (wrap-span-no-new-tx "build-block-remove-merge-op"
                                          (graph-ops/build-block-remove-merge-op @db/dsdb
                                                                                 uid
                                                                                 prev-block-uid
                                                                                 value))
          new-titles (graph-ops/ops->new-page-titles op)
          [_rm add]  (graph-ops/structural-diff @db/dsdb op)
          event      (common-events/build-atomic-event  op)]
      {:fx [(transact-async-flow :backspace-delete-merge-block event sentry-tx
                                 [(focus-on-uid prev-block-uid embed-id
                                                (count (:block/string prev-block)))])
            [:dispatch-n (cond-> []
                           (seq new-titles)
                           (conj [:reporting/page.create {:source :kbd-backspace-merge
                                                          :count  (count new-titles)}])
                           (seq add)
                           (concat (monitoring/build-reporting-link-creation add :kbd-backspace-merge)))]]})))


(reg-event-fx
  :backspace/delete-merge-block-with-save
  (fn [_ [_ {:keys [uid value prev-block-uid embed-id local-update] :as args}]]
    (log/debug ":backspace/delete-merge-block-with-save args:" (pr-str args))
    (let [sentry-tx  (close-and-get-sentry-tx "backspace/delete-merge-block-with-save")
          op         (wrap-span-no-new-tx "build-block-merge-with-updated-op"
                                          (graph-ops/build-block-merge-with-updated-op @db/dsdb
                                                                                       uid
                                                                                       prev-block-uid
                                                                                       value
                                                                                       local-update))
          new-titles (graph-ops/ops->new-page-titles op)
          [_rm add]  (graph-ops/structural-diff @db/dsdb op)
          event      (common-events/build-atomic-event  op)]
      {:fx [(transact-async-flow :backspace-delete-merge-block-with-save event sentry-tx
                                 [(focus-on-uid prev-block-uid embed-id (count local-update))])
            [:dispatch-n (cond-> []
                           (seq new-titles)
                           (conj [:dispatch [:reporting/page.create  {:source :kbd-backspace-merge-with-save
                                                                      :count  (count new-titles)}]])
                           (seq add)
                           (concat (monitoring/build-reporting-link-creation add :kbd-backspace-merge-with-save)))]]})))


;; Atomic events end ==========


(reg-event-fx
  :enter/add-child
  (fn [_ [_ {:keys [block new-uid embed-id] :as args}]]
    (log/debug ":enter/add-child args:" (pr-str args))
    (let [sentry-tx   (close-and-get-sentry-tx "enter/add-child")
          position    (wrap-span-no-new-tx "compat-position"
                                           (common-db/compat-position @db/dsdb {:block/uid (:block/uid block)
                                                                                :relation  :first}))
          event       (common-events/build-atomic-event (atomic-graph-ops/make-block-new-op new-uid position))]
      {:fx [(transact-async-flow :enter-add-child event sentry-tx [(focus-on-uid new-uid embed-id)])
            [:dispatch [:reporting/block.create {:source :enter-add-child
                                                 :count  1}]]]})))


(reg-event-fx
  :enter/split-block
  (fn [_ [_ {:keys [uid new-uid value index embed-id relation] :as args}]]
    (log/debug ":enter/split-block" (pr-str args))
    (let [sentry-tx  (close-and-get-sentry-tx "enter/split-block")
          op         (wrap-span-no-new-tx "build-block-split-op"
                                          (graph-ops/build-block-split-op @db/dsdb
                                                                          {:old-block-uid uid
                                                                           :new-block-uid new-uid
                                                                           :string        value
                                                                           :index         index
                                                                           :relation      relation}))
          new-titles (graph-ops/ops->new-page-titles op)
          [_rm add]  (graph-ops/structural-diff @db/dsdb op)
          event      (common-events/build-atomic-event op)]
      {:fx [(transact-async-flow :enter-split-block event sentry-tx [(focus-on-uid new-uid embed-id)])
            [:dispatch-n (cond-> [[:reporting/block.create {:source :enter-split
                                                            :count  1}]
                                  [:check-for-mentions uid value]]
                           (seq new-titles)
                           (conj [:reporting/page.create {:source :enter-split
                                                          :count  (count new-titles)}])
                           (seq add)
                           (concat (monitoring/build-reporting-link-creation add :enter-split)))]]})))


(reg-event-fx
  :enter/bump-up
  (fn [_ [_ {:keys [uid new-uid embed-id] :as args}]]
    (log/debug ":enter/bump-up args" (pr-str args))
    (let [sentry-tx   (close-and-get-sentry-tx "enter/bump-up")
          position    (wrap-span-no-new-tx "compat-position"
                                           (common-db/compat-position @db/dsdb {:block/uid uid
                                                                                :relation  :before}))
          event       (common-events/build-atomic-event (atomic-graph-ops/make-block-new-op new-uid position))]
      {:fx [(transact-async-flow :enter-bump-up event sentry-tx [(focus-on-uid new-uid embed-id)])
            [:dispatch [:reporting/block.create {:source :enter-bump-up
                                                 :count  1}]]]})))


(reg-event-fx
  :enter/open-block-add-child
  (fn [_ [_ {:keys [block new-uid embed-id]}]]
    ;; Triggered when there is a closed embeded block with no content in the top level block
    ;; and then one presses enter in the embeded block.
    (log/debug ":enter/open-block-add-child" (pr-str block) (pr-str new-uid))
    (let [sentry-tx               (close-and-get-sentry-tx "enter/open-block-add-child")
          block-uid               (:block/uid block)
          block-open-op           (atomic-graph-ops/make-block-open-op block-uid
                                                                       true)
          position                (wrap-span-no-new-tx "compat-position"
                                                       (common-db/compat-position @db/dsdb {:block/uid (:block/uid block)
                                                                                            :relation  :first}))
          add-child-op            (atomic-graph-ops/make-block-new-op new-uid position)
          open-block-add-child-op (composite-ops/make-consequence-op {:op/type :open-block-add-child}
                                                                     [block-open-op
                                                                      add-child-op])
          event                   (common-events/build-atomic-event open-block-add-child-op)]
      {:fx [(transact-async-flow :enter-open-block-add-child event sentry-tx [(focus-on-uid new-uid embed-id)])
            [:dispatch [:reporting/block.create {:source :enter-open-block-add-child
                                                 :count  1}]]]})))


(defn enter
  "- If block is a property, always open and create a new child
  - If block is open, has children, and caret at end, create new child
  - If block is CLOSED, has children, and caret at end, add a sibling block.
  - If value is empty and a root block, add a sibling block.
  - If caret is not at start, split block in half.
  - If block has children and is closed, if at end, just add another child.
  - If block has children and is closed and is in middle of block, split block.
  - If value is empty, unindent.
  - If caret is at start and there is a value, create new block below but keep same block index."
  [rfdb uid d-key-down]
  (let [root-embed?                         (= (some-> d-key-down :target
                                                       (.. (closest ".block-embed"))
                                                       (. -firstChild)
                                                       (.getAttribute "data-uid"))
                                               uid)
        [uid embed-id]                      (db/uid-and-embed-id uid)
        block                               (db/get-block [:block/uid uid])
        block-properties                    (common-db/get-block-property-document @db/dsdb [:block/uid uid])
        has-comments?                       (not-empty (get block-properties ":comment/threads"))
        block-has-comments-but-no-children? (and has-comments?
                                                 (empty? (:block/children block)))

        {parent-uid :block/uid
         :as        parent}                 (db/get-parent [:block/uid uid])
        is-parent-root-embed?               (= (some-> d-key-down :target
                                                       (.. (closest ".block-embed"))
                                                       (. -firstChild)
                                                       (.getAttribute "data-uid"))
                                               (str parent-uid "-embed-" embed-id))
        root-block?                         (boolean (:node/title parent))
        context-root-uid                    (get-in rfdb [:current-route :path-params :id])
        new-uid                             (common.utils/gen-block-uid)
        has-children?                       (seq (common-db/sorted-prop+children-uids @db/dsdb [:block/uid uid]))
        {:keys [value start]}               d-key-down
        caret-at-the-end-of-text            (= start
                                               (count value))
        caret-at-the-start-of-text          (and (zero? start)
                                                 value)
        event                               (cond
                                              (and block-has-comments-but-no-children?
                                                   caret-at-the-end-of-text)
                                              [:enter/new-block {:block    block
                                                                 :parent   parent
                                                                 :new-uid  new-uid
                                                                 :embed-id embed-id}]

                                              (:block/key block)
                                              [:enter/split-block {:uid        uid
                                                                   :value      value
                                                                   :index      start
                                                                   :new-uid    new-uid
                                                                   :embed-id   embed-id
                                                                   :relation   :first}]

                                              (and (:block/open block)
                                                   has-children?
                                                   caret-at-the-end-of-text)
                                              [:enter/add-child {:block    block
                                                                 :new-uid  new-uid
                                                                 :embed-id embed-id}]

                                              (and embed-id root-embed?
                                                   caret-at-the-end-of-text)
                                              [:enter/open-block-add-child {:block    block
                                                                            :new-uid  new-uid
                                                                            :embed-id embed-id}]

                                              (and (not (:block/open block))
                                                   has-children?
                                                   caret-at-the-end-of-text)
                                              [:enter/new-block {:block    block
                                                                 :parent   parent
                                                                 :new-uid  new-uid
                                                                 :embed-id embed-id}]

                                              (and (empty? value)
                                                   (or (= context-root-uid (:block/uid parent))
                                                       root-block?))
                                              [:enter/new-block {:block    block
                                                                 :parent   parent
                                                                 :new-uid  new-uid
                                                                 :embed-id embed-id}]

                                              (and (:block/open block)
                                                   embed-id root-embed?
                                                   (not caret-at-the-end-of-text))
                                              [:enter/split-block {:uid        uid
                                                                   :value      value
                                                                   :index      start
                                                                   :new-uid    new-uid
                                                                   :embed-id   embed-id
                                                                   :relation   :first}]

                                              (and (empty? value) embed-id (not is-parent-root-embed?))
                                              [:unindent {:uid              uid
                                                          :d-key-down       d-key-down
                                                          :context-root-uid context-root-uid
                                                          :embed-id         embed-id
                                                          :local-string     ""}]

                                              (and (empty? value) embed-id is-parent-root-embed?)
                                              [:enter/new-block {:block    block
                                                                 :parent   parent
                                                                 :new-uid  new-uid
                                                                 :embed-id embed-id}]

                                              (not caret-at-the-start-of-text)
                                              [:enter/split-block {:uid      uid
                                                                   :value    value
                                                                   :index    start
                                                                   :new-uid  new-uid
                                                                   :embed-id embed-id
                                                                   :relation :after}]

                                              (empty? value)
                                              [:unindent {:uid              uid
                                                          :d-key-down       d-key-down
                                                          :context-root-uid context-root-uid
                                                          :embed-id         embed-id
                                                          :local-string     ""}]

                                              caret-at-the-start-of-text
                                              [:enter/bump-up {:uid      uid
                                                               :new-uid  new-uid
                                                               :embed-id embed-id}])]
    (log/debug "[Enter] ->" (pr-str event))
    (assert parent-uid (str "[Enter] no parent for block-uid: " uid))
    {:fx [[:dispatch event]]}))


(reg-event-fx
  :enter
  [(interceptors/sentry-span-no-new-tx "enter")]
  (fn [{rfdb :db} [_ uid d-event]]
    (enter rfdb uid d-event)))


(defn get-prev-block-uid-and-target-rel
  [uid]
  (let [db                        @db/dsdb
        prev-block-uid            (:block/uid (db/nth-sibling uid :before))
        prev-block-children?      (if prev-block-uid
                                    (seq (common-db/sorted-prop+children-uids db [:block/uid prev-block-uid]))
                                    nil)
        prop-key                  (common-db/property-key db [:block/uid uid])
        target-rel                (cond
                                    prop-key             {:page/title prop-key}
                                    prev-block-children? :last
                                    :else                :first)]
    [prev-block-uid target-rel]))


(defn block-save-block-move-composite-op
  [source-uid ref-uid relation string]
  (let [db                        @db/dsdb
        block-save-op             (graph-ops/build-block-save-op db source-uid string)
        position                  (common-db/compat-position db {:block/uid ref-uid
                                                                 :relation relation})
        block-move-op             (graph-ops/build-block-move-op db source-uid position)
        block-save-block-move-op  (composite-ops/make-consequence-op {:op/type :block-save-block-move}
                                                                     [block-save-op
                                                                      block-move-op])]
    block-save-block-move-op))


(reg-event-fx
  :indent
  (fn [{:keys [_db]} [_ {:keys [uid d-key-down local-string] :as args}]]
    ;; - `block-zero`: The first block in a page
    ;; - `value`     : The current string inside the block being indented. Otherwise, if user changes block string and indents,
    ;;                 the local string  is reset to original value, since it has not been unfocused yet (which is currently the
    ;;                 transaction that updates the string).
    (let [sentry-tx                 (close-and-get-sentry-tx "indent")
          first-block?              (= uid (first (db/sibling-uids uid)))
          [prev-block-uid
           target-rel]              (wrap-span-no-new-tx "get-prev-block-uid-and-target-rel"
                                                         (get-prev-block-uid-and-target-rel uid))
          sib-block                 (wrap-span-no-new-tx "get-block-sib-block"
                                                         (common-db/get-block @db/dsdb [:block/uid prev-block-uid]))
          ;; if sibling block is closed with children, open
          {sib-open     :block/open
           sib-uid      :block/uid} sib-block
          block-closed?             (and (not sib-open)
                                         (common-db/sorted-prop+children-uids @db/dsdb [:block/uid prev-block-uid]))
          sib-block-open-op         (when block-closed?
                                      (atomic-graph-ops/make-block-open-op sib-uid true))
          {:keys [start end]}       d-key-down
          block-save-block-move-op  (block-save-block-move-composite-op uid
                                                                        prev-block-uid
                                                                        target-rel
                                                                        local-string)
          composite-ops             (composite-ops/make-consequence-op {:op/type :indent}
                                                                       (cond-> [block-save-block-move-op]
                                                                         block-closed? (conj sib-block-open-op)))
          new-titles                (graph-ops/ops->new-page-titles composite-ops)
          [_rm add]                 (graph-ops/structural-diff @db/dsdb composite-ops)
          event                     (common-events/build-atomic-event composite-ops)]
      (log/debug "null-sib-uid" (and first-block?
                                     prev-block-uid)
                 ", args:" (pr-str args)
                 ", first-block?" first-block?)
      (when (and prev-block-uid
                 (not first-block?))
        {:fx [(transact-async-flow :indent event sentry-tx [])
              [:set-cursor-position [uid start end]]
              [:dispatch-n (cond-> []
                             (seq new-titles)
                             (conj [:reporting/page.create {:source :indent
                                                            :count  (count new-titles)}])
                             (seq add)
                             (concat (monitoring/build-reporting-link-creation add :indent)))]]}))))


(reg-event-fx
  :indent/multi
  (fn [_ [_ {:keys [uids]}]]
    (log/debug ":indent/multi" (pr-str uids))
    (let [sentry-tx                (close-and-get-sentry-tx "indent/multi")
          sanitized-selected-uids  (mapv (comp first common-db/uid-and-embed-id) uids)
          f-uid                    (first sanitized-selected-uids)
          dsdb                     @db/dsdb
          [prev-block-uid
           target-rel]             (wrap-span-no-new-tx "get-prev-block-uid-and-target-rel"
                                                        (get-prev-block-uid-and-target-rel f-uid))
          same-parent?             (wrap-span-no-new-tx "same-parent"
                                                        (common-db/same-parent? dsdb sanitized-selected-uids))
          first-block?             (= f-uid (first (db/sibling-uids f-uid)))]
      (log/debug ":indent/multi same-parent?" same-parent?
                 ", not first-block?" (not  first-block?))
      (when (and same-parent? (not first-block?))
        {:fx [[:async-flow {:id             :indent-multi-async-flow
                            :db-path        [:async-flow :indent-multi]
                            :first-dispatch [:drop-multi/sibling {:source-uids sanitized-selected-uids
                                                                  :target-uid  prev-block-uid
                                                                  :drag-target target-rel}]
                            :rules          (wait-for-rft sentry-tx [])}]]}))))


(reg-event-fx
  :unindent
  (fn [{:keys [_db]} [_ {:keys [uid d-key-down context-root-uid embed-id local-string] :as args}]]
    (log/debug ":unindent args" (pr-str args))
    (let [sentry-tx                (close-and-get-sentry-tx "unindent")
          db                       @db/dsdb
          property-key             (common-db/property-key db [:block/uid uid])
          parent                   (wrap-span-no-new-tx "parent"
                                                        (common-db/get-parent db (common-db/e-by-av db :block/uid uid)))
          is-parent-property?      (:block/key parent)
          parent-of-parent         (->> parent
                                        :db/id
                                        (common-db/get-parent db)
                                        :block/uid)
          is-parent-root-embed?    (= (some-> d-key-down
                                              :target
                                              (.. (closest ".block-embed"))
                                              (. -firstChild)
                                              (.getAttribute "data-uid"))
                                      (str (:block/uid parent) "-embed-" embed-id))
          do-nothing?              (or is-parent-root-embed?
                                       (:node/title parent)
                                       (= context-root-uid (:block/uid parent)))
          {:keys [start end]}      d-key-down
          block-save-block-move-op (cond
                                     property-key        (block-save-block-move-composite-op uid parent-of-parent {:page/title property-key} local-string)
                                     is-parent-property? (block-save-block-move-composite-op uid parent-of-parent :first local-string)
                                     :else               (block-save-block-move-composite-op uid (:block/uid parent) :after local-string))
          new-titles               (graph-ops/ops->new-page-titles block-save-block-move-op)
          [_rm add]                (graph-ops/structural-diff @db/dsdb block-save-block-move-op)
          event                    (common-events/build-atomic-event block-save-block-move-op)]
      (log/debug ":unindent do-nothing?" do-nothing?)
      (when-not do-nothing?
        {:fx [(transact-async-flow :unindent event sentry-tx [(focus-on-uid uid embed-id)])
              [:set-cursor-position [uid start end]]
              [:dispatch-n (cond-> []
                             (seq new-titles)
                             (conj [:reporting/page.create {:source :unindent
                                                            :count  (count new-titles)}])
                             (seq add)
                             (concat (monitoring/build-reporting-link-creation add :unindent)))]]}))))


(reg-event-fx
  :unindent/multi
  (fn [{:keys [db]} [_ {:keys [uids]}]]
    (log/debug ":unindent/multi" uids)
    (let [sentry-tx                   (close-and-get-sentry-tx "unindent/multi")
          [f-uid f-embed-id]          (wrap-span-no-new-tx "uid-and-embed-id"
                                                           (common-db/uid-and-embed-id (first uids)))
          sanitized-selected-uids     (mapv (comp
                                              first
                                              common-db/uid-and-embed-id) uids)
          {parent-title :node/title
           parent-uid   :block/uid}   (wrap-span-no-new-tx "get-parent"
                                                           (common-db/get-parent @db/dsdb [:block/uid f-uid]))
          same-parent?                (wrap-span-no-new-tx "same-parent"
                                                           (common-db/same-parent? @db/dsdb sanitized-selected-uids))
          is-parent-root-embed?       (when same-parent?
                                        (some-> "#editable-uid-"
                                                (str f-uid "-embed-" f-embed-id)
                                                js/document.querySelector
                                                (.. (closest ".block-embed"))
                                                (. -firstChild)
                                                (.getAttribute "data-uid")
                                                (= (str parent-uid "-embed-" f-embed-id))))
          context-root-uid            (get-in db [:current-route :path-params :id])
          do-nothing?                 (or parent-title
                                          (not same-parent?)
                                          (and same-parent? is-parent-root-embed?)
                                          (= parent-uid context-root-uid))]
      (log/debug ":unindent/multi do-nothing?" do-nothing?)
      (when-not do-nothing?
        {:fx [[:async-flow {:id             :unindent-multi-async-flow
                            :db-path        [:async-flow :unindent-multi]
                            :first-dispatch [:drop-multi/sibling {:source-uids  sanitized-selected-uids
                                                                  :target-uid   parent-uid
                                                                  :drag-target  :after}]
                            :rules          (wait-for-rft sentry-tx [])}]]}))))


(reg-event-fx
  :block/move
  (fn [_ [_ {:keys [source-uid target-uid target-rel local-string] :as args}]]
    (log/debug ":block/move args" (pr-str args))
    (let [sentry-tx (close-and-get-sentry-tx "block/move")
          local-string (or local-string
                           (:block/string (common-db/get-block-document @db/dsdb [:block/uid source-uid])))
          event     (-> (block-save-block-move-composite-op source-uid target-uid target-rel local-string)
                        common-events/build-atomic-event)]
      {:fx [(transact-async-flow :block-move event sentry-tx [(focus-on-uid source-uid nil)])]})))


(reg-event-fx
  :block/link
  (fn [_ [_ {:keys [source-uid target-uid target-rel] :as args}]]
    (log/debug ":block/link args" (pr-str args))
    (let [block-uid    (common.utils/gen-block-uid)
          atomic-event (common-events/build-atomic-event
                         (composite-ops/make-consequence-op {:op/type :block/link}
                                                            [(atomic-graph-ops/make-block-new-op block-uid
                                                                                                 {:block/uid target-uid
                                                                                                  :relation target-rel})
                                                             (atomic-graph-ops/make-block-save-op block-uid
                                                                                                  (str "((" source-uid "))"))]))]
      {:fx [[:dispatch-n [[:resolve-transact-forward atomic-event]
                          [:reporting/block.create {:source :bullet-drop
                                                    :count  1}]]]]}))) ; TODO :reporting/block.link


(reg-event-fx
  :drop-multi/child
  (fn [_ [_ {:keys [source-uids target-uid] :as args}]]
    (log/debug ":drop-multi/child args" (pr-str args))
    (let [atomic-op (graph-ops/block-move-chain @db/dsdb target-uid source-uids :first)
          event     (common-events/build-atomic-event atomic-op)]
      {:fx [[:dispatch [:resolve-transact-forward event]]]})))


(reg-event-fx
  :drop-multi/sibling
  (fn [_ [_ {:keys [source-uids target-uid drag-target] :as args}]]
    ;; When the selected blocks have same parent and are DnD under the same parent this event is fired.
    ;; This also applies if on selects multiple Zero level blocks and change the order among other Zero level blocks.
    (log/debug ":drop-multi/sibling args" (pr-str args))
    (let [rel-position drag-target
          atomic-op    (graph-ops/block-move-chain @db/dsdb target-uid source-uids rel-position)
          event        (common-events/build-atomic-event atomic-op)]
      {:fx [[:dispatch [:resolve-transact-forward event]]]})))


(reg-event-fx
  :paste-internal
  [(interceptors/sentry-span-no-new-tx "paste-internal")]
  (fn [_ [_ uid local-str internal-representation]]
    (when (seq internal-representation)
      (let [[uid]      (db/uid-and-embed-id uid)
            op         (bfs/build-paste-op @db/dsdb
                                           uid
                                           local-str
                                           internal-representation)
            new-titles (graph-ops/ops->new-page-titles op)
            new-uids   (graph-ops/ops->new-block-uids op)
            [_rm add]  (graph-ops/structural-diff @db/dsdb op)
            event      (common-events/build-atomic-event op)
            focus-uid  (-> (graph-ops/contains-op? op :block/new)
                           first
                           :op/args
                           :block/uid)]
        (log/debug "paste internal event is" (pr-str event))
        {:fx [[:async-flow {:id             :paste-internal-async-flow
                            :db-path        [:async-flow :paste-internal]
                            :first-dispatch [:resolve-transact-forward event]
                            :rules          [{:when   :seen?
                                              :events :fail-resolve-forward-transact
                                              :halt?  true}
                                             {:when       :seen?
                                              :events     :success-resolve-forward-transact
                                              :dispatch-n [(when focus-uid
                                                             [:editing/uid focus-uid])]
                                              :halt?      true}]}]
              [:dispatch-n (cond-> []

                             (seq new-titles)
                             (conj [:reporting/page.create {:source :paste-internal
                                                            :count  (count new-titles)}])

                             (seq new-uids)
                             (conj [:reporting/block.create {:source :paste-internal
                                                             :count  (count new-uids)}])
                             (seq add)
                             (concat (monitoring/build-reporting-link-creation add :paste-internal)))]]}))))


(reg-event-fx
  :paste-image
  [(interceptors/sentry-span-no-new-tx "paste-image")]
  (fn [{:keys [db]} [_ items head tail callback]]
    (let [local?     (not (db-picker/remote-db? db))
          img-regex  #"(?i)^image/(p?jpeg|gif|png)$"]
      (log/debug ":paste-image : local?" local?)
      (if local?
        (do
          (mapv (fn [item]
                  (let [datatype (.. item -type)]
                    (cond
                      (re-find img-regex datatype)    (when electron.utils/electron?
                                                        (let [new-str (images/save-image head tail item "png")]
                                                          (callback new-str)))
                      (re-find #"text/html" datatype) (.getAsString item (fn [_] #_(prn "getAsString" _))))))
                items)
          {})
        {:fx (util/toast (clj->js {:status "error"
                                   :title "Couldn't paste"
                                   :description "Image paste is not supported in Lan-Party, yet."}))}))))


(reg-event-fx
  :paste-verbatim
  [(interceptors/sentry-span-no-new-tx "paste-verbatim")]
  (fn [_ [_ uid text]]
    ;; NOTE: use of `value` is questionable, it's the DOM so it's what users sees,
    ;; but what users sees should taken from DB. How would `value` behave with multiple editors?
    (let [{:keys [start
                  value]} (textarea-keydown/destruct-target js/document.activeElement)
          block-empty?    (string/blank? value)
          block-start?    (zero? start)
          new-string      (cond
                            block-empty?       text
                            (and (not block-empty?)
                                 block-start?) (str text value)
                            :else              (str (subs value 0 start)
                                                    text
                                                    (subs value start)))
          op              (graph-ops/build-block-save-op @db/dsdb uid new-string)
          new-titles      (graph-ops/ops->new-page-titles op)
          [_rm add]       (graph-ops/structural-diff @db/dsdb op)
          event           (common-events/build-atomic-event op)]
      {:fx [[:dispatch-n (cond-> [[:resolve-transact-forward event]]
                           (seq new-titles)
                           (conj [:reporting/page.create {:source :paste-verbatim
                                                          :count  (count new-titles)}])
                           (seq add)
                           (concat (monitoring/build-reporting-link-creation add :paste-verbatim)))]]})))


(reg-event-fx
  :unlinked-references/link
  (fn [_ [_ {:block/keys [string uid]} title]]
    (log/debug ":unlinked-references/link:" uid)
    (let [ignore-case-title (re-pattern (str "(?i)" title))
          new-str           (string/replace string ignore-case-title (str "[[" title "]]"))
          op                (graph-ops/build-block-save-op @db/dsdb
                                                           uid
                                                           new-str)
          [_rm add]         (graph-ops/structural-diff @db/dsdb op)
          event             (common-events/build-atomic-event op)]
      {:fx [[:dispatch-n (cond-> [[:resolve-transact-forward event]
                                  [:posthog/report-feature :unlinked-references]]
                           (seq add)
                           (concat (monitoring/build-reporting-link-creation add :unlinked-refs-link)))]]})))


(reg-event-fx
  :unlinked-references/link-all
  (fn [_ [_ unlinked-refs title]]
    (log/debug ":unlinked-references/link:" title)
    (let [block-save-ops (mapv
                           (fn [{:block/keys [string uid]}]
                             (let [ignore-case-title (re-pattern (str "(?i)" title))
                                   new-str           (string/replace string ignore-case-title (str "[[" title "]]"))]
                               (graph-ops/build-block-save-op @db/dsdb
                                                              uid
                                                              new-str)))
                           unlinked-refs)
          link-all-op    (composite-ops/make-consequence-op {:op/type :block/unlinked-refs-link-all}
                                                            block-save-ops)
          [_rm add]      (graph-ops/structural-diff @db/dsdb link-all-op)
          event          (common-events/build-atomic-event link-all-op)]
      {:fx [[:dispatch-n (cond-> [[:resolve-transact-forward event]
                                  [:posthog/report-feature :unlinked-references]]
                           (seq add)
                           (concat (monitoring/build-reporting-link-creation add :unlinked-refs-link-all)))]]})))


(rf/reg-event-fx
  :block/open
  (fn [_ [_ {:keys [block-uid open?] :as args}]]
    (log/debug ":block/open args" args)
    (let [event (common-events/build-atomic-event
                  (atomic-graph-ops/make-block-open-op block-uid open?))]
      {:fx [[:dispatch [:resolve-transact-forward event]]]})))


;; Works like clojure's update-in.
;; Calls (f db uid), where uid is the existing block uid, or a uid that will be created in ks property path.
;; (f db uid) should return a seq of operations to perform. If no operations are returned, nothing is transacted.
(reg-event-fx
  :graph/update-in
  [(interceptors/sentry-span-no-new-tx "graph/update-in")]
  (fn [_ [_ eid ks f]]
    (log/debug ":graph/update-in args" eid ks)
    (when (seq ks)
      (let [db                  @db/dsdb
            [prop-uid path-ops] (graph-ops/build-path db eid ks)
            f-ops               (f db prop-uid)]
        (when (seq f-ops)
          {:fx [[:dispatch-n [[:resolve-transact-forward (->> (into path-ops f-ops)
                                                              (composite-ops/make-consequence-op {:op/type :graph/update-in})
                                                              common-events/build-atomic-event)]]]]})))))


;; Add internal representation to graph, using default-position for blocks without pages.
(reg-event-fx
  :graph/add-internal-representation
  [(interceptors/sentry-span-no-new-tx "graph/add-internal-representation")]
  (fn [_ [_ internal-representation default-position]]
    (log/debug ":graph/add-internal-representation args" internal-representation default-position)
    (when (seq internal-representation)
      {:fx [[:dispatch-n [[:resolve-transact-forward (->> (bfs/internal-representation->atomic-ops @db/dsdb internal-representation default-position)
                                                          (composite-ops/make-consequence-op {:op/type :graph/add-internal-representation})
                                                          common-events/build-atomic-event)]]]]})))
