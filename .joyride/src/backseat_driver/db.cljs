(ns backseat-driver.db)

(def ^:private default-db {:openai nil
                           :disposables []
                           :assistant+ nil
                           :thread+ nil
                           :last-message nil
                           :channel nil
                           :interrupted? false})

(defonce !db (atom nil))

(defn init-db! []
  (reset! !db default-db))