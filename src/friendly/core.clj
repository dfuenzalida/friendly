(ns friendly.core
  (:require [clj-http.client :as client]
            [cheshire.core :as cheshire]
            [compojure.core :refer :all]
            [compojure.handler :as handler]
            [compojure.route :as route]
            [cemerick.friend :as friend]
            [friend-oauth2.workflow :as oauth2]
            [friend-oauth2.util :refer [format-config-uri]]
            (cemerick.friend [workflows :as workflows]
                             [credentials :as creds])
            [friendly.feeds :as feeds]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.reload :as reload]
            [ring.util.codec :as codec]
            [ring.util.response :as response])
  (:use [ring.middleware.file-info :only [wrap-file-info]]
        [ring.middleware.json :only [wrap-json-response wrap-json-body]]
        [ring.middleware.params :only [wrap-params]]
        [ring.middleware.resource :only [wrap-resource]]
        [ring.middleware.session :only [wrap-session]]
        [org.httpkit.server :only [run-server]])
  (:import [java.net URL]
           [java.security MessageDigest]))

;; DATABASE ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def default-feeds [{:title "Reddit /r/Clojure" :url "http://www.reddit.com/r/Clojure/.rss"
                     :favicon "http://www.reddit.com/favicon.ico" :unread "?"}
                    {:title "Hacker News" :url "https://news.ycombinator.com/rss"
                     :favicon "https://news.ycombinator.com/favicon.ico" :unread "?"}
                    {:title "Planet Clojure" :url "http://planet.clojure.in/atom.xml"
                     :favicon "http://planet.clojure.in/static/i/favicon.gif" :unread "?"}
                    {:title "Cognitect Blog" :url "http://blog.cognitect.com/blog?format=rss"
                     :favicon "http://blog.cognitect.com/favicon.ico" :unread "?"}
                    {:title "YouTube ClojureTV" :url "http://gdata.youtube.com/feeds/base/users/ClojureTV/uploads?alt=rss&v=2&orderby=published&client=ytapi-youtube-profile"
                     :favicon "http://youtube.com/favicon.ico" :unread "?"}
                    ])

(def subscriptions (atom {}))

(defn subscribe [email feed]
  (let [feed-props {:title (feeds/feed-title feed) :url feed
                    :favicon (feeds/find-favicon feed) :unread "?"}]
    (swap! subscriptions update-in [email] into [feed-props])
    (println @subscriptions)))

(defn set-default-feeds! [email]
  (if-not (@subscriptions email)
    (swap! subscriptions assoc email default-feeds)))

;; HELPERS ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; MD5 helper from https://gist.github.com/jizhang/4325757
(defn md5 [s]
  (let [algorithm (MessageDigest/getInstance "MD5")
        size (* 2 (.getDigestLength algorithm))
        raw (.digest algorithm (.getBytes s))
        sig (.toString (BigInteger. 1 raw) 16)
        padding (apply str (repeat (- size (count sig)) "0"))]
    (str padding sig)))

(defn gravatar [email]
  (str "https://secure.gravatar.com/avatar/" (md5 email) "?s=24"))

(def config
  (read-string (slurp "resources/config.edn")))

(defn in-dev? []
  (= :development (:env config)))

;; Default options for clj-http client
(def clj-http-opts
  {:as :json, :coerce :always, :content-type :json, :throw-exceptions :false})

;; OAUTH2 ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Don't forget to configure the Google consent screen for this app
;; See also http://stackoverflow.com/a/25762444/483566

(defn credential-fn
  "Looks for the user email using the Google+ API after login with Google"
  [token]
  (let [access-token (:access-token token)
        gplus-addr "https://www.googleapis.com/plus/v1/people/me?access_token="
        gplus-info (client/get (str gplus-addr access-token) clj-http-opts)
        email (-> gplus-info :body :emails first :value)]
    (set-default-feeds! email)
    {:identity token :email email :roles #{::user}}))

(defn call-github [endpoint access-token]
  (-> (format "https://api.github.com%s%s&access_token=%s"
        endpoint
        (when-not (.contains endpoint "?") "?")
        access-token)
    client/get
    :body
    (cheshire/parse-string (fn [^String s] (keyword (.replace s \_ \-))))))

(defn github-credential-fn
  "Looks for the user email using the Google+ API after login with Google"
  [token]
  (let [access-token (:access-token token)
        user-data    (call-github "/user" access-token)
        email        (:email user-data)]
    (set-default-feeds! email)
    {:identity token :email email :roles #{::user}}))

(def client-config
  (:google-oauth config))

(def uri-config
  {:authentication-uri {:url "https://accounts.google.com/o/oauth2/auth"
                        :query {:client_id (:client-id client-config)
                                :response_type "code"
                                :redirect_uri (format-config-uri client-config)
                                :scope "https://www.googleapis.com/auth/userinfo#email"
                                }}

   :access-token-uri {:url "https://accounts.google.com/o/oauth2/token"
                      :query {:client_id (:client-id client-config)
                              :client_secret (:client-secret client-config)
                              :grant_type "authorization_code"
                              :redirect_uri (format-config-uri client-config)}}})

(def get-public-repos (memoize (partial call-github "/user/repos?type=public")))
(def get-github-handle (memoize (comp :login (partial call-github "/user"))))

(def github-client-config (:github-oauth config))

(def github-uri-config
  {:authentication-uri {:url "https://github.com/login/oauth/authorize"
                        :query {:client_id (:client-id github-client-config)
                                :response_type "code"
                                :redirect_uri (format-config-uri github-client-config)
                                :scope ""}}

   :access-token-uri {:url "https://github.com/login/oauth/access_token"
                      :query {:client_id (:client-id github-client-config)
                              :client_secret (:client-secret github-client-config)
                              :grant_type "authorization_code"
                              :redirect_uri (format-config-uri github-client-config)
                              :code ""}}})

(defn session-email
  "Find the email stored in the session"
  [request]
  (let [token (get-in request [:session :cemerick.friend/identity :current :access-token])]
    (get-in request [:session :cemerick.friend/identity :authentications {:access-token token} :email])))

;; ROUTES ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defroutes ring-app

  (GET "/" request
       {:status 200
        :body (slurp "resources/public/index.html")})

  (GET "/api/userinfo" request
       (let [token (get-in request [:session :cemerick.friend/identity :current :access-token])]
         (if token
           (let [email    (session-email request)
                 gravatar (gravatar email)
                 feeds    (get-in @subscriptions [email] default-feeds)]
             (friend/authorize #{::user}
                               {:status 200
                                :body {:email email :token token :feeds feeds :gravatar gravatar}}))
           {:status 403
            :headers {"Content-type" "application/json"}
            :body {:error "Not logged in!"}})))

  ;; Just login to obtain your email info in credential-fn and redirect to the root
  (GET "/login/github" request
       (friend/authorize #{::user} (response/redirect "/")))

  (POST "/api/discover" request
        (let [url (get-in request [:body :url])
              feed (feeds/find-rss url)]
          (if feed
            (do
              (let [email (session-email request)]
                (subscribe email feed)
                {:status 200
                 :headers {"Content-type" "application/json"}
                 :body {:url feed}}))
            {:status 404
             :headers {"Content-type" "application/json"}
             :body {:message (str "Can't find a feed for: " url)}})))

  (POST "/api/discover" request
        (let [url (get-in request [:body :url])
              feed (feeds/find-rss url)]
          (if feed
            (do
              (let [email (session-email request)]
                (subscribe email feed)
                {:status 200
                 :headers {"Content-type" "application/json"}
                 :body {:url feed}}))
            {:status 404
             :headers {"Content-type" "application/json"}
             :body {:message (str "Can't find a feed for: " url)}})))

  (GET "/api/feed" request
       (let [url   (get-in request [:params :url])
             posts (feeds/posts url)
             email (session-email request)
             subs  (into [] (map (fn [feed]
                                   (if (= url (:url feed))
                                     (assoc feed :unread (count posts))
                                     feed))
                                 (@subscriptions email)))]
         ;; update the number of posts in this feed
         (swap! subscriptions assoc email subs)
         ;; (println (@subscriptions email))
         {:status 200
          :headers {"Content-type" "application/json"}
          :body {:posts posts}}))

  (POST "/api/delete" request
       (let [url   (get-in request [:body :url])
             email (session-email request)

             ;; All the subscriptions except one by it's URL
             subs  (into [] (filter (fn [feed]
                                      (not (= url (:url feed)))) (@subscriptions email)))]
         (swap! subscriptions assoc email subs)
         (println (@subscriptions email))
         {:status 200
          :headers {"Content-type" "application/json"}
          :body {:message "ok"}}))

  (friend/logout (ANY "/logout" request (ring.util.response/redirect "/"))))

;; MIDDLEWARE ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def friend-configuration
  {:allow-anon? true
   :workflows [
               ;; GOOGLE OAUTH2 Workflow
               ;; (oauth2/workflow
               ;;  {:client-config client-config
               ;;   :uri-config uri-config
               ;;   :credential-fn credential-fn})

               ;; Github OAuth2 Workflow
               (oauth2/workflow
                {:client-config github-client-config
                 :uri-config github-uri-config
                 ;; :config-auth {:roles #{::users/user}}
                 :credential-fn github-credential-fn
                 :access-token-parsefn #(-> % :body codec/form-decode (get "access_token"))})
               ]})

(defn wrap-logging [handler]
  (fn [{:keys [remote-addr request-method uri] :as request}]
    (println remote-addr (.toUpperCase (name request-method)) uri)
    (handler request)))

(def app (-> ring-app
              (friend/authenticate friend-configuration)
              (wrap-resource "public") ;; serve from "resources/public"
              (wrap-resource "/META-INF/resources") ;; resources from WebJars
              (wrap-json-body {:keywords? true})
              wrap-json-response
              wrap-params
              wrap-file-info ;; sends the right headers for static files
              wrap-logging
              wrap-session ;; required fof openid to save data in the session
              handler/site))

;; HTTP-Kit based

(defn -main [& args] ;; entry point, lein run will pick up and start from here
  (let [handler (if (in-dev?)
                  (reload/wrap-reload app) ;; only reload when dev
                  app)]
    (println "Running Friendly HTTP Kit server on port 3000...")
    (run-server app {:port 3000})))

