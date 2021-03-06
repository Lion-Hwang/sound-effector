(ns sound-effector.controllers.auth.facebook-with-friend
  (:require
    [clojure.data.json :as json]
    [clojure.string :as string]
    [crypto.random :as random]
    [clj-http.client :as http]
    [ring.middleware.anti-forgery :refer [*anti-forgery-token*]]
    [ring.util.codec :refer [url-encode]]
    [ring.util.response :refer [redirect response status content-type header]]
    [compojure.core :refer [defroutes context GET]]
    [cemerick.friend :as friend]
    [cemerick.friend.workflows :refer [make-auth]]
    [liberator.core :refer [resource]]
    [sound-effector.models.user :as user]
    [sound-effector.models.role :as role]))

; The OAuth 2.0 Authorization Framework
; http://tools.ietf.org/html/rfc6749

; OAuth2 is easy - illustrated in 50 lines of Clojure
; https://leonid.shevtsov.me/en/oauth2-is-easy

; Easy custom auth in Clojure with Friend
; https://adambard.com/blog/easy-auth-with-friend/

; FIXME: must fetch this from the database.
(def ^:private provider-id 1)

(def ^:pivate client-config
  {:client-id     (System/getenv "FACEBOOK_CLIENT_ID")
   :client-secret (System/getenv "FACEBOOK_CLIENT_SECRET")
   :callback      {:uri  (str (System/getenv "APP_PROTOCOL_HOST_PORT") "/auth/facebook/callback")
                   :path "/auth/facebook/callback"}})

(def ^:pivate urls
  {:authorize-url    {:uri     "https://www.facebook.com/dialog/oauth"
                      :queries {:client_id     (:client-id client-config)
                                :redirect_uri  (get-in client-config [:callback :uri])
                                :response-type "code"
                                :scope         "public_profile,email"}}
   :access-token-url {:uri     "https://graph.facebook.com/v2.5/oauth/access_token"
                      :queries {:client_id     (:client-id client-config)
                                :client_secret (:client-secret client-config)
                                :redirect_uri  (get-in client-config [:callback :uri])}}
   :user-info-url    {:uri     "https://graph.facebook.com/v2.5/me"
                      :queries {:fields "id,name,email"}}})

(defn- put-url-together
  ([uri-and-queries]
   (put-url-together uri-and-queries nil))
  ([uri-and-queries additional-queries]
   (str (:uri uri-and-queries)
        "?"
        (clojure.string/join "&" (map #(str (name (first %)) "=" (url-encode (second %))) (seq (merge (:queries uri-and-queries) additional-queries)))))))

(defn get-access-token [code]
  (try
    (-> (http/get (put-url-together (:access-token-url urls) {:code code}) {:as :json})
        :body
        :access_token)
    ; FIXME: must fix the way to handle and make the logging policy.
    (catch Exception e (do (print e) nil))))

(defn get-user-info [access-token]
  (try
    (-> (http/get (put-url-together (:user-info-url urls) {:access_token access-token}) {:as :json})
        :body)
    ; FIXME: must fix the way to handle and make the logging policy.
    (catch Exception e (do (print e) nil))))

(defn generate-anti-forgery-token []
  (string/replace (random/base64 60) #"[\+=/]" "-"))

(defn redirect-to-authorize-url [request]
  (let [anti-forgery-token (generate-anti-forgery-token)]
    (-> (redirect (put-url-together (:authorize-url urls) {:state anti-forgery-token}))
        (header "Pragma" "no-cache")
        (assoc :flash (assoc (:flash request {}) :state anti-forgery-token)))))

(defn- handle-error-result [body]
  (->
    (response body)
    (status 400)
    (content-type "text/html")
    (header "Pragma" "no-cache")))

(defn calledback [{{code              :code
                    state             :state
                    error             :error
                    error-reason      :error_reason
                    error-description :error_description} :params
                   {state-in-flash :state}                :flash
                   session                                :session
                   :as                                    request}]
  (if (and (not (nil? code))
           (= state state-in-flash))
    (if-let [access-token (get-access-token code)]
      (if-let [facebook-user-info (get-user-info access-token)]
        (if-let [user (user/read provider-id (:id facebook-user-info))]
          (make-auth {:identity (:id user) :user user :roles #{::role/user}})
          (->
            (redirect "/users/new")
            (header "Pragma" "no-cache")
            (assoc :session (assoc session :creating-user
                                           (assoc facebook-user-info :provider-id provider-id)))))
        (handle-error-result (str "Couln't get the user information in Facebook!<br/>Access token : " access-token)))
      (handle-error-result (str "Couln't get the access token!<br/>Code : " code
                                "<br/>State : " state
                                "<br/>State in flash : " state-in-flash)))
    (handle-error-result (str "Invalid request.<br/>Code : " code
                              "<br/>State : " state
                              "<br/>State in session : " state-in-flash
                              "<br/>Error : " error
                              "<br/>Reason : " error-reason
                              "<br/>Description : " error-description
                              "<br/>Request : " request))))

(defn- handle-json-error-result [body]
  (->
    (response (json/write-str body))
    (status 400)
    (content-type "application/json")
    (header "Pragma" "no-cache")))

(defn login-for-mobile-app [request]
  (let [access-token (get-in request [:params :access-token])]
    (if (not (nil? access-token))
      (if-let [facebook-user-info (get-user-info access-token)]
        (if-let [user (user/read provider-id (:id facebook-user-info))]
          (let [authentication (make-auth {:identity (:id user) :user user :roles #{::role/user}})]
            (->
              (response (json/write-str {:message "Login completed. :D"}))
              (status 200)
              (content-type "application/json")
              (header "Pragma" "no-cache")
              (friend/merge-authentication authentication)))
          (->
            (response (json/write-str {:message "Will you join us?" :creating-user (assoc facebook-user-info :provider-id provider-id)}))
            (status 202)
            (content-type "application/json")
            (header "Pragma" "no-cache")
            (header "X-CSRF-Token" *anti-forgery-token*)
            (assoc :session (assoc (:session request) :creating-user
                                                      (assoc facebook-user-info :provider-id provider-id)))))
        (handle-json-error-result {:message "Couln't get the user information in Facebook!" :access-token access-token}))
      (handle-json-error-result {:message "Couln't get the access token!"}))))

(defn is-session-alive? [request]
  (if-let [_ (get-in request [:session ::friend/identity])]
    (->
      {:status 200}
      (content-type "application/json"))
    (->
      {:status 403}
      (content-type "application/json"))))

(defn workflow []
  (fn [request]
    (cond
      (= (:uri request) (get-in request [::friend/auth-config :login-uri]))
      (redirect-to-authorize-url request)

      (= (:uri request) (get-in client-config [:callback :path]))
      (calledback request)

      (= (:uri request) "/auth/facebook/app")
      (let [request-method (:request-method request)]
        (condp = request-method
          :get (login-for-mobile-app request)
          (-> (response "Invalid request.")
              (status 400))))

      (= (:uri request) "/auth")
      (let [request-method (:request-method request)]
        (condp = request-method
          :head (is-session-alive? request)
          (-> (response "Invalid request.")
              (status 400)))))))
