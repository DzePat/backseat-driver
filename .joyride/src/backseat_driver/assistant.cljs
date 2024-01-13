(ns backseat-driver.assistant
  (:require ["vscode" :as vscode]
            ["openai" :as openai]
            ["ext://betterthantomorrow.calva$v1" :as calva]
            [backseat-driver.prompts :as prompts]
            [backseat-driver.fs :as bd-fs]
            [backseat-driver.context :as context]
            [joyride.core :as joyride]
            [promesa.core :as p]))

(def assistant-name "Backseat Driver")

(defonce ^:private openai (openai/OpenAI.))

(def ^:private gpt4 "gpt-4-1106-preview")
(def ^:private gpt3 "gpt-3.5-turbo-1106")

(def ^:private default-db {:disposables []
                           :assistant+ nil
                           :thread+ nil
                           :last-message nil
                           :channel nil
                           :interrupted? false})

(defonce ^:private  !db (atom nil))

(defn log-ln [message & messages]
  (let [channel (:channel @!db)]
    (-> channel (.append message))
    (doseq [message messages]
      (-> channel (.append " "))
      (-> channel (.append message)))
    (-> channel (.appendLine ""))))

(defn log-one [message]
  (-> (:channel @!db) (.append message)))

(defn ->clj [o] (js->clj o :keywordize-keys true))

(defn- clear-disposables! []
  (run! (fn [disposable]
          (.dispose disposable))
        (:disposables @!db))
  (swap! !db assoc :disposables []))

(defn- push-disposable [disposable]
  (swap! !db update :disposables conj disposable)
  (-> (joyride/extension-context)
      .-subscriptions
      (.push disposable)))

(defn get-or-create-assistant!+ []
  (p/let [assistant-storage-key "backseat-driver-assistant-id"
          global-state (-> (joyride/extension-context) .-globalState)
          existing-assistant-id (.get global-state assistant-storage-key)
          assistant (if existing-assistant-id
                      (openai.beta.assistants.retrieve existing-assistant-id)
                      (openai.beta.assistants.create (clj->js {:name "Backseat Driver"
                                                               :instructions prompts/system-instructions
                                                               :tools [{:type "code_interpreter"}
                                                                       #_{:type "retrieval"}]
                                                               :model gpt4})))
          assistant-id (.-id assistant)]
    (.update global-state assistant-storage-key assistant-id)
    assistant))


(def ^:private threads-storage-key "backseat-driver-threads")

(defn retrieve-saved-threads []
  (let [workspace-state (-> (joyride/extension-context) .-workspaceState)]
    (-> (.get workspace-state threads-storage-key #js {}) ->clj)))

(defn save-thread!+
  [thread title]
  (let [stored-threads (js->clj (retrieve-saved-threads))
        threads (assoc stored-threads (.-id thread) {:thread-id (.-id thread)
                                                     :created-at (.-created_at thread)
                                                     :updated-at (-> (js/Date.) .getTime)
                                                     :title title})
        workspace-state (-> (joyride/extension-context) .-workspaceState)]
    (.update workspace-state threads-storage-key (clj->js threads))))

(defn- ->vec-sort-vals-by [m f]
  (->> m
       vals
       (sort-by f)
       vec))

(defn retrieve-saved-threads-sorted []
  (-> (retrieve-saved-threads)
      (->vec-sort-vals-by :created-at)))

(comment
  (init!)
  (-> (joyride/extension-context) .-workspaceState (.update "backseat-driver-threads" js/undefined))
  (save-thread!+ #js {:id "foo" :created_at (js/Date.) :something "something too"}
                 "My Foo Thread")
  (save-thread!+ #js {:id "bar" :created_at (js/Date.) :something "something too"}
                 "A BAR THREAD")
  (retrieve-saved-threads)
  (retrieve-saved-threads-sorted)
  :rcf)

(defn init! []
  (clear-disposables!)
  (reset! !db default-db)
  (swap! !db assoc :assistant+ (get-or-create-assistant!+))
  (p/let [thread (openai.beta.threads.create)]
    (swap! !db assoc :thread+ thread)
    (save-thread!+ thread nil))
  (let [channel (vscode/window.createOutputChannel "Backseat Driver" "markdown")]
    (push-disposable channel)
    (swap! !db assoc :channel channel)))

(def ^:private done-statuses #{"completed" "failed" "expired" "cancelled"})
(def ^:private  poll-interval 100)

(defn- retrieve-poller+ [thread run]
  (p/create
   (fn [resolve reject]
     (let [retriever (fn retriever [tries]
                       (log-one ".")
                       (p/let [retrieved-js (openai.beta.threads.runs.retrieve
                                             (.-id thread)
                                             (.-id run))
                               retrieved (->clj retrieved-js)
                               _ (def retrieved retrieved)
                               messages (openai.beta.threads.messages.list (.-id thread))
                               status (:status retrieved)]
                         (if (done-statuses status)
                           (do
                             (log-ln "")
                             (when-not (= "completed" status)
                               (log-ln "status:" status)
                               (println "status:" status)
                               (println retrieved)
                               (bd-fs/append-to-log (pr-str retrieved)))
                             (resolve messages))
                           (if (:interrupted? @!db)
                             (do
                               (swap! !db assoc :interrupted? false)
                               (reject :interrupted))
                             (js/setTimeout
                              #(retriever (inc tries))
                              poll-interval)))))]
       (retriever 0)))))

(defn ask!+ []
  (def instructions (prompts/system-and-context-instructions))
  (-> (:channel @!db) (.show true))
  (swap! !db assoc :interrupted? false)
  (-> (p/let [assistant (:assistant+ @!db)
              thread (:thread+ @!db)
              storage-thread (get (retrieve-saved-threads) (keyword (.-id thread)))
              _ (def thread thread)
              input (vscode/window.showInputBox #js {:prompt "What do you want say to the assistant?"
                                                     :placeHolder "Something something"
                                                     :ignoreFocusOut true})]
        (def input input)
        (when-not (= js/undefined input)
          (p/let
           [_ (log-ln "\nMe:" input)
            _ (when-not (:title storage-thread)
                (save-thread!+ thread (subs input 0 120)))
            augmented-input (prompts/augmented-user-input input)
            _ (def augmented-input augmented-input)
            _message (openai.beta.threads.messages.create (.-id thread)
                                                          (clj->js {:role "user"
                                                                    :content augmented-input}))
            #_#__ (def _message _message)
            run (openai.beta.threads.runs.create
                 (.-id thread)
                 (clj->js {:assistant_id (.-id assistant)
                           :instructions (prompts/system-and-context-instructions)
                           :model gpt4}))
            api-messages (retrieve-poller+ thread run)
            clj-messages (->clj (-> api-messages .-body .-data))
            _ (def clj-messages clj-messages)
            new-messages (if-let [last-created-at (some-> @!db :last-message :created_at)]
                           (filter #(and (> (:created_at %) last-created-at)
                                         (= (:role %) "assistant"))
                                   clj-messages)
                           (filter #(= (:role %) "assistant")
                                   clj-messages))
            _ (def new-messages new-messages)
            _ (swap! !db assoc :next-last-message (:last-message @!db))
            _ (swap! !db assoc :last-message (first clj-messages))
            message-texts (map (fn [m]
                                 (-> m :content first :text :value))
                               new-messages)
            _ (def message-texts message-texts)
            _ (def clj-messages clj-messages)
            pprinted-messages (-> new-messages
                                  pr-str
                                  calva/pprint.prettyPrint
                                  .-value)]
            #_ (def api-messages api-messages)
            (log-ln "")
            (doseq [text message-texts]
              (log-ln (str assistant-name ":"))
              (log-ln text))
            new-messages)))
      (p/catch (fn [e] (println "ERROR: " e "\n")))))

(comment
  (init!)
  (p/let [result (ask!+)]
    (def result result)
    (-> result pr-str calva/pprint.prettyPrint .-value println))
  (swap! !db assoc :interrupted? true)
  (swap! !db assoc :interrupted? false)
  @!db
  (println (-> @!db :next-last-message :content first :text :value))
  :rcf)

(defn- my-main []
  (clear-disposables!)
  #_(push-disposable something)
  #_(push-disposable (something-else)))

(when (= (joyride/invoked-script) joyride/*file*)
  (ask!+))
