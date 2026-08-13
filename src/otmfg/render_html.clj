(ns otmfg.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for `cloud-itonami-isic-3099`: this
  repo previously had NO demo page and no generator at all. This
  namespace drives the REAL actor stack (`otmfg.operation` ->
  `otmfg.governor` -> `otmfg.store`, compiled by langgraph and run with
  `g/run*`) through a scenario adapted from this repo's own `otmfg.sim`
  demo driver, and renders the resulting store + audit ledger.

  Every row on the page is derived from that run:

    - batch / equipment rows come from `store/all-batches` and
      `store/all-equipment` (i.e. `store/sample-data!`'s own seed), so
      the ids, product categories, weight capacities, quantities and
      defect rates on the page are the seed's, never invented;
    - maintenance / shipment / safety-concern rows come from what the
      run actually committed;
    - the HARD-hold table is grouped from the run's own
      `:governor-hold` audit facts -- rule names AND the Japanese
      `:detail` strings are the governor's own output, not prose typed
      here;
    - the phase-gate table is computed from `otmfg.phase/phases` and
      `otmfg.governor/allowed-ops`, so it cannot drift from the code;
    - the approver-retention disclosure is MEASURED (see
      `approver-retention` below), not asserted.

  Deterministic: the advisor is `otmfg.advisor/mock-advisor`, the store
  is a fresh `MemStore`, and no timestamp or hostname enters the page.
  Two consecutive runs are byte-identical.

  BUILD-TIME INVARIANT: `-main` throws unless the run actually produced
  `:governor-hold` facts covering every rule in `expected-hard-rules`.
  A console that silently lost its HARD holds is not publishable -- the
  whole point of the page is that the governor refuses things.

  Usage: `clojure -M:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jp-go-dds.skin :as skin]
            [langgraph.graph :as g]
            [otmfg.governor :as governor]
            [otmfg.operation :as op]
            [otmfg.phase :as phase]
            [otmfg.store :as store]))

;; ----------------------------- the run -----------------------------

(def ^:private coordinator
  "The plant coordinator this console is rendered for -- phase 3
  (`otmfg.phase/default-phase`), the fully rolled-out posture."
  {:actor-id "coord-1" :actor-role :plant-coordinator :phase phase/default-phase})

(def ^:private phase-1-coordinator
  "The SAME actor early in the rollout. Used once, to show that the
  phase gate holds a write the governor itself was happy with."
  (assoc coordinator :phase 1))

(def expected-hard-rules
  "Every HARD rule `otmfg.governor` can emit. `-main` refuses to write
  the console unless the run actually fired all of them -- so a
  governor check that stops firing breaks the build instead of quietly
  vanishing from the page."
  #{:not-propose-effect
    :unknown-op
    :equipment-control-blocked
    :equipment-actuate-blocked
    :certification-authority-blocked
    :equipment-not-verified
    :already-scheduled
    :batch-not-verified
    :shipment-quantity-exceeded
    :invalid-product-category
    :invalid-weight-capacity
    :invalid-defect-rate})

;; Each graph run's own `:audit` channel carries facts the SSoT ledger
;; never sees -- the advisor's proposal + rationale, the approval
;; request, and the approval grant/rejection (`otmfg.operation`'s
;; `:commit` node persists only its own commit-fact, and its `:hold`
;; node only the hold). Keeping the final state per thread-id (the
;; `:audit` reducer is `into`, so a resumed run's state already carries
;; that thread's earlier entries) is what lets this page show the
;; human-in-the-loop step at all.
(def ^:private runs
  "thread-id -> final graph state, in execution order."
  (atom []))

(defn- record-run! [tid result]
  (swap! runs (fn [v]
                (conj (vec (remove #(= tid (:tid %)) v))
                      {:tid tid :state (:state result) :status (:status result)})))
  result)

(defn- exec! [actor tid request context]
  (record-run! tid (g/run* actor {:request request :context context} {:thread-id tid})))

(defn- approve! [actor tid]
  (record-run! tid (g/run* actor {:approval {:status :approved :by "coord-1"}}
                           {:thread-id tid :resume? true})))

(defn- reject! [actor tid]
  (record-run! tid (g/run* actor {:approval {:status :rejected :by "coord-1"}}
                           {:thread-id tid :resume? true})))

(defn run-demo!
  "Runs a fresh `store/sample-data!`-seeded store through a scenario
  that reaches every disposition this actor has.

  Clean paths (against the seed's verified+registered records):
  `batch-001` takes a clean production-batch patch and AUTO-COMMITS
  (`:log-production-batch` is the only op in phase 3's `:auto` set);
  `mnt-1` schedules assembly-line maintenance on `assembly-jig-001`
  (escalates -- `:schedule-maintenance` is deliberately in NO phase's
  `:auto` set -- then approved); `concern-1` flags a safety concern
  (always escalates, `:coordination/safety-concern` is permanently
  high-stakes -- approved); `ship-1` coordinates 50 units off
  `batch-001`'s 500-unit batch (escalates, approved).

  HARD holds, one request each, exercised directly rather than only via
  a happy path: a caller whose own request `:effect` is not `:propose`;
  an op outside the closed allowlist (which also lands the advisor's
  `:noop` proposal effect outside the closed effect allowlist);
  maintenance against the seed's UNVERIFIED `testbench-002`; a shipment
  against the seed's UNVERIFIED `batch-003`; a shipment that would blow
  through `batch-002`'s own logged 80-unit quantity (75 already
  shipped + 10); a shipment stating no quantity at all (headroom
  un-computable, which is not headroom); a maintenance proposal that
  tries to ACTUATE the jig; a double-schedule of `mnt-1`; and three
  fabricated production-batch facts (an unknown product category, an
  implausible weight capacity, an implausible assembly-defect rate),
  plus a patch trying to self-issue a roadworthiness certification.

  Two more non-governor stops: a shipment a human approver REJECTS, and
  a shipment the phase-1 gate refuses even though the governor cleared
  it.

  Returns the store."
  []
  (reset! runs [])
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]

    ;; ---- clean paths -------------------------------------------------
    (exec! actor "t01"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:product-category :animal-drawn-cart :last-assessed "2026-07-14"}}
           coordinator)

    (exec! actor "t02"
           {:op :schedule-maintenance :effect :propose :subject "mnt-1"
            :value {:equipment-id "assembly-jig-001" :maintenance-type :axle-alignment
                    :scheduled-date "2026-08-01" :actuate-equipment? false}}
           coordinator)
    (approve! actor "t02")

    (exec! actor "t03"
           {:op :flag-safety-concern :effect :propose :subject "concern-1"
            :value {:equipment-id "assembly-jig-001" :severity :moderate
                    :description "車軸取付部の異音、シャーシ接合部の位置ずれ兆候"}}
           coordinator)
    (approve! actor "t03")

    (exec! actor "t04"
           {:op :coordinate-shipment :effect :propose :subject "ship-1"
            :value {:batch-id "batch-001" :units 50.0
                    :destination "buyer-yard-north"}}
           coordinator)
    (approve! actor "t04")

    ;; ---- HARD holds --------------------------------------------------
    (exec! actor "t05"
           {:op :log-production-batch :effect :direct-write :subject "batch-001"
            :patch {:product-category :animal-drawn-cart}}
           coordinator)

    (exec! actor "t06"
           {:op :actuate-assembly-line :effect :propose :subject "batch-001"}
           coordinator)

    (exec! actor "t07"
           {:op :schedule-maintenance :effect :propose :subject "mnt-2"
            :value {:equipment-id "testbench-002" :maintenance-type :load-cell-calibration
                    :scheduled-date "2026-08-01" :actuate-equipment? false}}
           coordinator)

    (exec! actor "t08"
           {:op :coordinate-shipment :effect :propose :subject "ship-2"
            :value {:batch-id "batch-003" :units 100.0
                    :destination "buyer-yard-south"}}
           coordinator)

    (exec! actor "t09"
           {:op :coordinate-shipment :effect :propose :subject "ship-3"
            :value {:batch-id "batch-002" :units 10.0
                    :destination "buyer-yard-east"}}
           coordinator)

    ;; Same rule, different code path: nothing to recompute at all.
    (exec! actor "t10"
           {:op :coordinate-shipment :effect :propose :subject "ship-4"
            :value {:batch-id "batch-001" :destination "buyer-yard-west"}}
           coordinator)

    (exec! actor "t11"
           {:op :schedule-maintenance :effect :propose :subject "mnt-3"
            :value {:equipment-id "assembly-jig-001" :maintenance-type :force-run
                    :scheduled-date "2026-09-01" :actuate-equipment? true}}
           coordinator)

    (exec! actor "t12"
           {:op :schedule-maintenance :effect :propose :subject "mnt-1"
            :value {:equipment-id "assembly-jig-001" :maintenance-type :axle-alignment
                    :scheduled-date "2026-08-01" :actuate-equipment? false}}
           coordinator)

    (exec! actor "t13"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:product-category :hoverboard}}
           coordinator)

    (exec! actor "t14"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:weight-capacity-kg 999999.0}}
           coordinator)

    (exec! actor "t15"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:assembly-defect-rate-percent 999.0}}
           coordinator)

    (exec! actor "t16"
           {:op :log-production-batch :effect :propose :subject "batch-001"
            :patch {:issue-certification? true}}
           coordinator)

    ;; ---- human says no, and the phase gate says not yet ---------------
    (exec! actor "t17"
           {:op :coordinate-shipment :effect :propose :subject "ship-5"
            :value {:batch-id "batch-001" :units 10.0
                    :destination "buyer-yard-central"}}
           coordinator)
    (reject! actor "t17")

    (exec! actor "t18"
           {:op :coordinate-shipment :effect :propose :subject "ship-6"
            :value {:batch-id "batch-001" :units 10.0
                    :destination "buyer-yard-north"}}
           phase-1-coordinator)

    db))

;; ----------------------------- derived views -----------------------------

(defn- graph-audit
  "The concatenated `:audit` channels of every thread, in execution
  order -- advisor proposals, approval requests, approval grants and
  rejections. Distinct from `store/ledger`, which is only what actually
  reached the SSoT."
  []
  (into [] (mapcat #(get-in % [:state :audit])) @runs))

(defn- approval-grants
  "The `:approval-granted` facts -- the human-in-the-loop step. These
  live in the graph audit channel, NOT in the store ledger."
  []
  (filter #(= :approval-granted (:t %)) (graph-audit)))

(defn- hold-facts [ledger]
  (filter #(= :governor-hold (:t %)) ledger))

(defn- hard-rules-fired
  "The distinct HARD rule names the run's own governor holds carry."
  [ledger]
  (into #{} (mapcat :basis) (hold-facts ledger)))

(defn- holds-by-rule
  "Group the run's HARD holds by rule, keeping the governor's OWN
  `:detail` text and the subjects it fired on. Nothing here is typed by
  hand -- if a governor check changes its wording, this table changes."
  [ledger]
  (->> (hold-facts ledger)
       (mapcat (fn [f] (map #(assoc % :subject (:subject f) :op (:op f)) (:violations f))))
       (group-by :rule)
       (sort-by (comp name key))))

(defn- approver-retention
  "MEASURED, not asserted.

  The graph's `:request-approval` node builds its commit record with
  `:payload (assoc (:value proposal) :approved-by ...)`. Whether that
  identity survives into the store is a property of `store/commit-record!`,
  which may or may not read `:payload` -- so this function does not
  claim either way. For every `:approval-granted` fact the run produced
  it looks the committed record back up out of the store's own
  registers and reports (a) whether `:approved-by` is present, and (b)
  if the approver's name IS somewhere in the record, under which key.

  If someone later fixes the store, this flips on its own and the
  rendered disclosure follows -- there is no hardcoded verdict to go
  stale."
  [db]
  (let [concern-by-id (into {} (map (juxt :id identity)) (store/safety-concerns db))]
    (vec
     (for [{:keys [op subject by]} (approval-grants)
           :let [record (case op
                          :schedule-maintenance (store/maintenance db subject)
                          :coordinate-shipment (store/shipment db subject)
                          :flag-safety-concern (get concern-by-id subject)
                          nil)
                 found-under (some (fn [[k v]] (when (= v by) k)) record)]]
       {:op op
        :subject subject
        :ledger-approver by
        :record-approver (:approved-by record)
        :found-under found-under
        :retained? (some? (:approved-by record))}))))

(defn- committed-subjects
  "Subjects of `:committed` facts for `op`, in ledger order."
  [ledger op]
  (->> ledger
       (filter #(and (= :committed (:t %)) (= op (:op %))))
       (map :subject)
       distinct
       vec))

(defn- op-gate
  "The phase posture of `op`, computed from `otmfg.phase/phases` --
  which phases let it write at all, and which (if any) let it commit
  without a human."
  [op]
  (let [writes (sort (keep (fn [[p {:keys [writes]}]] (when (contains? writes op) p)) phase/phases))
        autos (sort (keep (fn [[p {:keys [auto]}]] (when (contains? auto op) p)) phase/phases))]
    {:op op :write-phases (vec writes) :auto-phases (vec autos)}))

;; ----------------------------- html -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw-list [xs]
  (if (seq xs) (str/join ", " (map #(str (if (keyword? %) (name %) %)) xs)) "—"))

(defn- yes-no [b ok-label bad-label]
  (if b
    (str "<span class=\"ok\">" (esc ok-label) "</span>")
    (str "<span class=\"critical\">" (esc bad-label) "</span>")))

(defn- fmt-num [n]
  (cond
    (nil? n) "—"
    (and (number? n) (== (double n) (Math/rint (double n)))) (str (long n))
    :else (str n)))

(defn- last-fact-for [ledger subject]
  (last (filter #(= subject (:subject %)) ledger)))

(defn- status-cell [ledger subject]
  (let [f (last-fact-for ledger subject)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-rejected (:t f)) "<span class=\"warn\">rejected by approver</span>"
      (= :governor-hold (:t f))
      (str "<span class=\"critical\">HARD hold · "
           (esc (kw-list (:basis f)))
           (when (:phase-reason f) (str " · " (esc (name (:phase-reason f))))) "</span>")
      :else (str "<span class=\"muted\">" (esc (name (:t f))) "</span>"))))

(defn- table [caption headers rows]
  (str "    <table>\n"
       (when caption (str "      <caption>" caption "</caption>\n"))
       "      <thead><tr>"
       (str/join (map #(str "<th>" % "</th>") headers))
       "</tr></thead>\n"
       "      <tbody>\n"
       (if (seq rows) (str (str/join "\n" rows) "\n") "")
       "      </tbody>\n"
       "    </table>\n"))

(defn- tr [& cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- section [title lead body]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       (when lead (str "    <p class=\"muted\">" lead "</p>\n"))
       body
       "  </section>\n"))

;; ----------------------------- sections -----------------------------

(defn- batches-section [db ledger]
  (section
   "Production batches"
   (str "Seeded by <code>otmfg.store/sample-data!</code>; "
        "<code>shipped-units</code> is moved only by a committed "
        "<code>:shipment/propose</code>, so it is this run's own ground truth.")
   (table nil
          ["Batch" "Product category" "Frame" "Rated capacity (kg)"
           "Quantity (units)" "Shipped (units)" "Defect rate" "Verified" "Registered" "Last op"]
          (for [b (store/all-batches db)]
            (tr (str "<code>" (esc (:id b)) "</code>")
                (esc (name (:product-category b)))
                (esc (:frame-size b))
                (fmt-num (:weight-capacity-kg b))
                (fmt-num (:quantity-units b))
                (fmt-num (:shipped-units b))
                (str (fmt-num (:assembly-defect-rate-percent b)) "%")
                (yes-no (:verified? b) "verified" "UNVERIFIED")
                (yes-no (:registered? b) "registered" "UNREGISTERED")
                (status-cell ledger (:id b)))))))

(defn- equipment-section [db]
  (section
   "Assembly-line equipment"
   (str "<code>:verified?</code> / <code>:registered?</code> are re-read by the governor "
        "from these records on every <code>:schedule-maintenance</code> proposal — the advisor's "
        "own rationale about them is never trusted.")
   (table nil
          ["Equipment" "Kind" "Verified" "Registered" "Last maintenance" "Scheduled by this run"]
          (for [e (store/all-equipment db)]
            (tr (str "<code>" (esc (:id e)) "</code>")
                (esc (name (:kind e)))
                (yes-no (:verified? e) "verified" "UNVERIFIED")
                (yes-no (:registered? e) "registered" "UNREGISTERED")
                (esc (or (:last-maintenance-date e) "—"))
                (esc (or (:last-scheduled-maintenance-date e) "—")))))))

(defn- maintenance-section [db retention]
  (let [retained-by (into {} (map (juxt :subject identity)) retention)]
    (section
     "Maintenance-schedule drafts committed"
     (str "Unsigned drafts built by <code>otmfg.registry/register-maintenance</code>. "
          "<code>:schedule-maintenance</code> is in <em>no</em> phase's <code>:auto</code> set, "
          "so every row here passed through a human.")
     (table nil
            ["Maintenance" "Equipment" "Type" "Scheduled date" "Draft number" "Approver"]
            (for [m (store/all-maintenance db)
                  :let [r (get retained-by (:id m))]]
              (tr (str "<code>" (esc (:id m)) "</code>")
                  (str "<code>" (esc (:equipment-id m)) "</code>")
                  (esc (name (:maintenance-type m)))
                  (esc (:scheduled-date m))
                  (str "<code>" (esc (:maintenance-number m)) "</code>")
                  (cond
                    (nil? r) "<span class=\"muted\">—</span>"
                    (:retained? r) (str "<span class=\"ok\">" (esc (:record-approver r)) "</span>")
                    :else (str "<span class=\"warn\">" (esc (:ledger-approver r))
                               " (audit only — not retained in record)</span>"))))))))

(defn- shipments-section [db ledger retention]
  (let [retained-by (into {} (map (juxt :subject identity)) retention)]
    (section
     "Shipment-coordination drafts committed"
     (str "Every unit count below survived an independent recompute against the batch's own "
          "<code>:quantity-units</code> and <code>:shipped-units</code> — the proposal's own "
          "claim is never taken at face value.")
     (table nil
            ["Shipment" "Batch" "Units" "Destination" "Draft number" "Approver"]
            (for [id (committed-subjects ledger :coordinate-shipment)
                  :let [s (store/shipment db id)
                        r (get retained-by id)]]
              (tr (str "<code>" (esc id) "</code>")
                  (str "<code>" (esc (:batch-id s)) "</code>")
                  (fmt-num (:units s))
                  (esc (:destination s))
                  (str "<code>" (esc (:shipment-number s)) "</code>")
                  (cond
                    (nil? r) "<span class=\"muted\">—</span>"
                    (:retained? r) (str "<span class=\"ok\">" (esc (:record-approver r)) "</span>")
                    :else (str "<span class=\"warn\">" (esc (:ledger-approver r))
                               " (audit only — not retained in record)</span>"))))))))

(defn- concerns-section [db retention]
  (let [retained-by (into {} (map (juxt :subject identity)) retention)]
    (section
     "Safety concerns flagged"
     (str "<code>:flag-safety-concern</code> always carries "
          "<code>:coordination/safety-concern</code>, which the governor treats as permanently "
          "high-stakes — it escalates to a human regardless of confidence, at every phase.")
     (table nil
            ["Concern" "Equipment" "Severity" "Description" "Approver"]
            (for [c (store/safety-concerns db)
                  :let [r (get retained-by (:id c))]]
              (tr (str "<code>" (esc (:id c)) "</code>")
                  (str "<code>" (esc (:equipment-id c)) "</code>")
                  (esc (name (:severity c)))
                  (esc (:description c))
                  (cond
                    (nil? r) "<span class=\"muted\">—</span>"
                    (:retained? r) (str "<span class=\"ok\">" (esc (:record-approver r)) "</span>")
                    :else (str "<span class=\"warn\">" (esc (:ledger-approver r))
                               " (audit only — not retained in record)</span>"))))))))

(defn- hard-holds-section [ledger]
  (let [grouped (holds-by-rule ledger)]
    (section
     (str "HARD holds this run actually fired (" (count grouped) " distinct rules)")
     (str "Grouped from the run's own <code>:governor-hold</code> audit facts. "
          "The rule names and the Japanese explanations are "
          "<code>otmfg.governor</code>'s own output. A HARD hold is never overridable — "
          "it does not reach a human at all.")
     (table nil
            ["Rule" "Times" "Subjects" "Governor's explanation"]
            (for [[rule vs] grouped]
              (tr (str "<code>:" (esc (name rule)) "</code>")
                  (count vs)
                  (str/join ", " (map #(str "<code>" (esc (:subject %)) "</code>")
                                      (distinct (map #(select-keys % [:subject]) vs))))
                  (str/join "<br>" (map #(esc (:detail %)) (distinct (map :detail vs))))))))))

(defn- phase-section []
  (section
   "Phase gate (computed from <code>otmfg.phase/phases</code>)"
   (str "The second, independent layer. Even a governor-clean proposal is held if its op is not "
        "writable in the current phase, and escalated to a human if the op is writable but not "
        "auto-eligible. <code>:schedule-maintenance</code> is absent from every "
        "<code>:auto</code> set on purpose — that is a permanent structural fact, not a "
        "milestone still to come.")
   (str
    (table nil
           ["Phase" "Label" "May write" "May auto-commit"]
           (for [p (sort (keys phase/phases))
                 :let [{:keys [label writes auto]} (get phase/phases p)]]
             (tr p
                 (esc label)
                 (esc (kw-list (sort (map name writes))))
                 (if (seq auto)
                   (str "<span class=\"ok\">" (esc (kw-list (sort (map name auto)))) "</span>")
                   "<span class=\"muted\">—</span>"))))
    (table nil
           ["Op" "Writable at phases" "Auto-commit at phases" "Posture"]
           (for [o (sort-by name governor/allowed-ops)
                 :let [{:keys [write-phases auto-phases]} (op-gate o)]]
             (tr (str "<code>:" (esc (name o)) "</code>")
                 (kw-list write-phases)
                 (if (seq auto-phases)
                   (str "<span class=\"ok\">" (kw-list auto-phases) "</span>")
                   "<span class=\"muted\">never</span>")
                 (if (seq auto-phases)
                   "<span class=\"ok\">auto-commit once governor-clean</span>"
                   "<span class=\"warn\">ALWAYS human approval</span>")))))))

(defn- allowlist-section []
  (section
   "Closed allowlists (<code>otmfg.governor</code>)"
   (str "Both are closed sets. A proposal effect outside the second one is treated as an attempt "
        "at direct assembly-line-equipment control and is blocked permanently.")
   (table nil
          ["Allowlist" "Members"]
          [(tr "<code>allowed-ops</code>"
               (str/join " " (map #(str "<code>:" (esc (name %)) "</code>")
                                  (sort-by name governor/allowed-ops))))
           (tr "<code>allowed-proposal-effects</code>"
               (str/join " " (map #(str "<code>" (esc (str %)) "</code>")
                                  (sort-by str governor/allowed-proposal-effects))))
           (tr "<code>high-stakes</code>"
               (str/join " " (map #(str "<code>" (esc (str %)) "</code>")
                                  (sort-by str governor/high-stakes))))
           (tr "<code>confidence-floor</code>"
               (str "<code>" governor/confidence-floor "</code>"))])))

(defn- retention-section [retention]
  (let [total (count retention)
        kept (count (filter :retained? retention))
        elsewhere (into #{} (keep :found-under) retention)]
    (section
     "Approver attribution — measured, not assumed"
     (str "The graph hands the approver identity to the store on the commit record. "
          "Whether the store keeps it is a property of <code>otmfg.store/commit-record!</code>, "
          "so this page looks it back up out of the store's own registers after the run instead "
          "of claiming an answer.")
     (str
      (table nil
             ["Op" "Subject" "Approver in audit ledger" "Approver in stored record" "Found under key"]
             (for [r retention]
               (tr (str "<code>:" (esc (name (:op r))) "</code>")
                   (str "<code>" (esc (:subject r)) "</code>")
                   (esc (:ledger-approver r))
                   (if (:retained? r)
                     (str "<span class=\"ok\">" (esc (:record-approver r)) "</span>")
                     "<span class=\"warn\">absent</span>")
                   (if (:found-under r)
                     (str "<code>" (esc (str (:found-under r))) "</code>")
                     "<span class=\"muted\">nowhere in the record</span>"))))
      "    <p>"
      (cond
        (zero? total)
        "<strong>No approval path was exercised in this run</strong>, so nothing is claimed here."

        (= kept total)
        (str "<strong>Measured: the approver is retained on all " total
             " approved record(s).</strong> Reading the approver back off the record is safe.")

        (zero? kept)
        (str "<strong>Measured: the approver is retained on 0 of " total
             " approved record(s).</strong> "
             (if (seq elsewhere)
               (str "It does survive under " (kw-list (map str elsewhere))
                    ", which no reader of this actor's records looks at. ")
               "It is not present under any key of the stored record. ")
             "Every approver shown elsewhere on this page therefore comes from the audit ledger "
             "and is labelled <em>(audit only — not retained in record)</em>: a reader must not "
             "confuse that with “nobody approved”. The graph places it on the commit record's "
             "<code>:payload</code>; <code>otmfg.store/commit-record!</code> destructures "
             "<code>:value</code> only. This paragraph is derived from the run — fix the store "
             "and it changes by itself.")

        :else
        (str "<strong>Measured: the approver is retained on " kept " of " total
             " approved record(s)</strong> — retention depends on the effect, so read each row "
             "above rather than generalising."))
      "</p>\n"))))

(defn- graph-audit-section []
  (let [audit (graph-audit)]
    (section
     (str "Graph audit channel (" (count audit) " facts)")
     (str "Everything the StateGraph recorded, including what never reached the SSoT: the "
          "advisor's own proposal and rationale for each request, and the "
          "human-in-the-loop pause/resume. The advisor is <em>untrusted</em> — its rationale is "
          "shown here for the operator, and is deliberately not an input to any governor check.")
     (table nil
            ["#" "Fact" "Op" "Subject" "Confidence" "Advisor rationale / reason"]
            (map-indexed
             (fn [i {:keys [t op subject confidence rationale reason by phase]}]
               (tr (inc i)
                   (str "<code>" (esc (name t)) "</code>")
                   (str "<code>" (esc (str (or op :n-a))) "</code>")
                   (str "<code>" (esc subject) "</code>")
                   (if (some? confidence)
                     (str (if (< (double confidence) governor/confidence-floor)
                            "<span class=\"warn\">" "<span class=\"ok\">")
                          confidence "</span>")
                     "<span class=\"muted\">—</span>")
                   (esc (or rationale
                            (when reason (str (name reason)
                                              (when phase (str " (phase " phase ")"))))
                            (when by (str "approver " by))
                            ""))))
             audit)))))

(defn- ledger-section [ledger]
  (section
   (str "Audit ledger (" (count ledger) " decision facts)")
   "Append-only. Every commit, hold and approval this run produced, in order."
   (table nil
          ["#" "Fact" "Op" "Subject" "Basis / detail"]
          (map-indexed
           (fn [i {:keys [t op subject basis disposition phase-reason by]}]
             (tr (inc i)
                 (str "<code>" (esc (name t)) "</code>")
                 (str "<code>" (esc (str (or op :n-a))) "</code>")
                 (str "<code>" (esc subject) "</code>")
                 (esc (cond
                        (seq basis) (kw-list basis)
                        phase-reason (name phase-reason)
                        by (str "by " by)
                        disposition (name disposition)
                        :else ""))))
           ledger))))

;; ----------------------------- document -----------------------------

(defn render
  "Renders the whole console from a store `db` that has already been run
  through `run-demo!`."
  [db]
  (let [ledger (vec (store/ledger db))
        retention (approver-retention db)]
    (str
     "<!doctype html>\n"
     "<html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
     "<title>cloud-itonami-isic-3099 · other transport equipment n.e.c.</title><style>"
     (skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Manufacture of other transport equipment n.e.c. (ISIC 3099) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · animal-drawn &amp; hand-propelled vehicles</span>\n"
     "</header>\n"
     "<main>\n"
     (section
      "About this page"
      nil
      (str "    <p>Generated at build time by <code>otmfg.render-html</code> "
           "(<code>clojure -M:render-html</code>), which runs the real actor — "
           "<code>otmfg.operation</code>'s langgraph StateGraph, with "
           "<code>otmfg.governor</code> as an independent censor and "
           "<code>otmfg.store</code> as the SSoT — over the seed in "
           "<code>otmfg.store/sample-data!</code>. Every batch id, equipment id, capacity, "
           "quantity, hold reason and ledger row below is that run's output.</p>\n"
           "    <p>This actor coordinates <em>records</em>: it drafts production-batch logs, "
           "maintenance windows, safety-concern flags and shipment proposals. It never actuates "
           "assembly-line equipment, never dispatches a freight carrier, and never issues a "
           "transport-equipment safety/roadworthiness certification — the governor blocks all "
           "three permanently, with no human override.</p>\n"))
     (batches-section db ledger)
     (equipment-section db)
     (maintenance-section db retention)
     (shipments-section db ledger retention)
     (concerns-section db retention)
     (hard-holds-section ledger)
     (phase-section)
     (allowlist-section)
     (retention-section retention)
     (ledger-section ledger)
     (graph-audit-section)
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        ledger (vec (store/ledger db))
        holds (hold-facts ledger)
        fired (hard-rules-fired ledger)
        missing (sort-by name (remove fired expected-hard-rules))]
    ;; Build-time invariant, not a comment: a console with no HARD hold,
    ;; or one that quietly stopped exercising a governor rule, is not
    ;; publishable evidence of anything.
    (when (empty? holds)
      (throw (ex-info "render-html: the run produced ZERO :governor-hold facts -- refusing to write a console that shows no HARD hold"
                      {:ledger-facts (count ledger)})))
    (when (seq missing)
      (throw (ex-info (str "render-html: HARD rules never fired: " (pr-str missing))
                      {:expected expected-hard-rules :fired fired :missing missing})))
    ;; Second evidence floor. The approver-attribution section reports on
    ;; approvals it can find; with zero approvals it would render an
    ;; empty table and a "nothing is claimed here" note that reads like a
    ;; finding. An empty measurement is not a measurement.
    (when (empty? (approval-grants))
      (throw (ex-info "render-html: the run produced ZERO :approval-granted facts -- the human-in-the-loop path was not exercised, so the approver-attribution section would measure nothing"
                      {:graph-audit-facts (count (graph-audit))})))
    (io/make-parents out)
    (spit out (render db))
    (println "wrote" out)
    (println " " (count ledger) "ledger facts,"
             (count holds) "HARD holds over" (count fired) "distinct rules,"
             (count (store/maintenance-history db)) "maintenance drafts,"
             (count (store/shipment-history db)) "shipment drafts,"
             (count (store/safety-concerns db)) "safety concerns")
    (println "  approver retained on"
             (count (filter :retained? (approver-retention db))) "of"
             (count (approver-retention db)) "approved records")))
