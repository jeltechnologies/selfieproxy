package boringproxy

import (
	"crypto/subtle"
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// RestApi exposes every user interaction currently offered by the
// server-rendered web UI (ui_handler.go) as a JSON REST API, so that an
// alternative (eg. single-page) UI can be built against it. It is a thin
// HTTP layer on top of the same *Api business logic used by both the
// existing JSON API (api.go) and the web UI, so authorization/validation
// rules stay in one place.
//
// NOTE: Security hardening (auth checks beyond what Api already does,
// CSRF, rate limiting, etc) is intentionally deferred.
type RestApi struct {
	config        *Config
	db            *Database
	auth          *Auth
	api           *Api
	mux           *http.ServeMux
	internalToken string
}

// NewRestApi wires up the REST API. internalToken is an ephemeral,
// in-memory-only credential (regenerated on every server start, never
// persisted to the database) used exclusively for machine-to-machine calls
// from Selfie Proxy's selfieproxy-portal -- see authenticate() below.
func NewRestApi(config *Config, db *Database, auth *Auth, api *Api, internalToken string) *RestApi {

	mux := http.NewServeMux()

	restApi := &RestApi{config, db, auth, api, mux, internalToken}

	mux.HandleFunc("/login", restApi.handleLogin)
	mux.HandleFunc("/logout", restApi.handleLogout)
	mux.HandleFunc("/session", restApi.handleSession)
	mux.HandleFunc("/takingnames-link", restApi.handleTakingNames)

	mux.HandleFunc("/tunnels", restApi.handleTunnelsCollection)
	mux.Handle("/tunnels/", http.StripPrefix("/tunnels", http.HandlerFunc(restApi.handleTunnelItem)))

	mux.HandleFunc("/users", restApi.handleUsersCollection)
	mux.Handle("/users/", http.StripPrefix("/users", http.HandlerFunc(restApi.handleUserItem)))

	mux.HandleFunc("/tokens", restApi.handleTokensCollection)
	mux.Handle("/tokens/", http.StripPrefix("/tokens", http.HandlerFunc(restApi.handleTokenItem)))

	mux.HandleFunc("/agents", restApi.handleAgentsCollection)
	mux.Handle("/agents/", http.StripPrefix("/agents", http.HandlerFunc(restApi.handleAgentItem)))

	return restApi
}

func (a *RestApi) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	a.mux.ServeHTTP(w, r)
}

func (a *RestApi) authenticate(w http.ResponseWriter, r *http.Request) (TokenData, bool) {

	token, err := extractToken("access_token", r)
	if err != nil {
		writeJSONError(w, 401, "No token provided")
		return TokenData{}, false
	}

	if a.internalToken != "" && subtle.ConstantTimeCompare([]byte(token), []byte(a.internalToken)) == 1 {
		return TokenData{Owner: "admin", Agent: ""}, true
	}

	tokenData, exists := a.db.GetTokenData(token)
	if !exists {
		writeJSONError(w, 403, "Not authorized")
		return TokenData{}, false
	}

	return tokenData, true
}

func (a *RestApi) handleLogin(w http.ResponseWriter, r *http.Request) {

	if r.Method != "POST" {
		writeJSONError(w, 405, "Invalid method for /login")
		return
	}

	values, err := parseJSONBody(r)
	if err != nil {
		writeJSONError(w, 400, err.Error())
		return
	}

	token := values.Get("token")
	if token == "" {
		token, err = extractToken("access_token", r)
		if err != nil {
			writeJSONError(w, 400, "Token required for login")
			return
		}
	}

	if !a.auth.Authorized(token) {
		writeJSONError(w, 403, "Invalid token")
		return
	}

	tokenData, _ := a.db.GetTokenData(token)
	user, _ := a.db.GetUser(tokenData.Owner)

	cookie := &http.Cookie{
		Name:     "access_token",
		Value:    token,
		Secure:   true,
		HttpOnly: true,
		MaxAge:   86400 * 365,
	}
	http.SetCookie(w, cookie)

	writeJSON(w, 200, struct {
		Token   string `json:"token"`
		Owner   string `json:"owner"`
		Agent   string `json:"agent,omitempty"`
		IsAdmin bool   `json:"is_admin"`
	}{token, tokenData.Owner, tokenData.Agent, user.IsAdmin})
}

func (a *RestApi) handleLogout(w http.ResponseWriter, r *http.Request) {

	if r.Method != "POST" {
		writeJSONError(w, 405, "Invalid method for /logout")
		return
	}

	cookie := &http.Cookie{
		Name:     "access_token",
		Value:    "",
		Secure:   true,
		HttpOnly: true,
		MaxAge:   -1,
	}
	http.SetCookie(w, cookie)

	w.WriteHeader(204)
}

func (a *RestApi) handleSession(w http.ResponseWriter, r *http.Request) {

	if r.Method != "GET" {
		writeJSONError(w, 405, "Invalid method for /session")
		return
	}

	tokenData, ok := a.authenticate(w, r)
	if !ok {
		return
	}

	user, _ := a.db.GetUser(tokenData.Owner)

	writeJSON(w, 200, struct {
		Owner   string `json:"owner"`
		Agent   string `json:"agent,omitempty"`
		IsAdmin bool   `json:"is_admin"`
	}{tokenData.Owner, tokenData.Agent, user.IsAdmin})
}

func (a *RestApi) handleTakingNames(w http.ResponseWriter, r *http.Request) {

	if r.Method != "GET" {
		writeJSONError(w, 405, "Invalid method for /takingnames-link")
		return
	}

	if a.config.namedropClient == nil {
		writeJSONError(w, 404, "TakingNames.io integration not configured")
		return
	}

	link := a.config.namedropClient.DomainRequestLink()

	writeJSON(w, 200, struct {
		Url string `json:"url"`
	}{link})
}

func (a *RestApi) handleTunnelsCollection(w http.ResponseWriter, r *http.Request) {

	tokenData, ok := a.authenticate(w, r)
	if !ok {
		return
	}

	switch r.Method {
	case "GET":
		tunnels := a.api.GetTunnels(tokenData)
		writeJSON(w, 200, tunnels)
	case "POST":
		values, err := parseJSONBody(r)
		if err != nil {
			writeJSONError(w, 400, err.Error())
			return
		}

		tunnel, err := a.api.CreateTunnel(tokenData, values)
		if err != nil {
			writeJSONError(w, 400, err.Error())
			return
		}

		writeJSON(w, 201, tunnel)
	default:
		writeJSONError(w, 405, "Invalid method for /tunnels")
	}
}

// handleTunnelItem serves /tunnels/{domain} and /tunnels/{domain}/private-key
// (mounted with the "/tunnels" prefix already stripped).
func (a *RestApi) handleTunnelItem(w http.ResponseWriter, r *http.Request) {

	tokenData, ok := a.authenticate(w, r)
	if !ok {
		return
	}

	parts := strings.Split(strings.Trim(r.URL.Path, "/"), "/")
	if len(parts) == 0 || parts[0] == "" {
		writeJSONError(w, 404, "Domain required")
		return
	}

	domain := parts[0]

	values := url.Values{}
	values.Set("domain", domain)

	if len(parts) == 2 && parts[1] == "private-key" {
		if r.Method != "GET" {
			writeJSONError(w, 405, "Invalid method for /tunnels/{domain}/private-key")
			return
		}

		tunnel, err := a.api.GetTunnel(tokenData, values)
		if err != nil {
			writeJSONError(w, 404, err.Error())
			return
		}

		w.Header().Set("Content-Disposition", "attachment; filename=id_rsa")
		w.Header().Set("Content-Type", "application/x-pem-file")
		io.WriteString(w, tunnel.TunnelPrivateKey)
		return
	}

	switch r.Method {
	case "GET":
		tunnel, err := a.api.GetTunnel(tokenData, values)
		if err != nil {
			writeJSONError(w, 404, err.Error())
			return
		}
		writeJSON(w, 200, tunnel)
	case "PATCH":
		body, err := parseJSONBody(r)
		if err != nil {
			writeJSONError(w, 400, err.Error())
			return
		}

		agentName := body.Get("agent-name")
		if agentName == "" {
			writeJSONError(w, 400, "Invalid agent-name parameter")
			return
		}

		if err := a.api.SetTunnelAgent(tokenData, domain, agentName); err != nil {
			writeJSONError(w, 400, err.Error())
			return
		}
		w.WriteHeader(204)
	case "DELETE":
		err := a.api.DeleteTunnel(tokenData, values)
		if err != nil {
			writeJSONError(w, 400, err.Error())
			return
		}
		w.WriteHeader(204)
	default:
		writeJSONError(w, 405, "Invalid method for /tunnels/{domain}")
	}
}

func (a *RestApi) handleUsersCollection(w http.ResponseWriter, r *http.Request) {

	tokenData, ok := a.authenticate(w, r)
	if !ok {
		return
	}

	switch r.Method {
	case "GET":
		users := a.api.GetUsers(tokenData, url.Values{})
		writeJSON(w, 200, users)
	case "POST":
		values, err := parseJSONBody(r)
		if err != nil {
			writeJSONError(w, 400, err.Error())
			return
		}

		if err := a.api.CreateUser(tokenData, values); err != nil {
			writeJSONError(w, 400, err.Error())
			return
		}

		user, _ := a.db.GetUser(values.Get("username"))
		writeJSON(w, 201, user)
	default:
		writeJSONError(w, 405, "Invalid method for /users")
	}
}

// handleUserItem serves /users/{username} (mounted with "/users" stripped).
func (a *RestApi) handleUserItem(w http.ResponseWriter, r *http.Request) {

	tokenData, ok := a.authenticate(w, r)
	if !ok {
		return
	}

	username := strings.Trim(r.URL.Path, "/")
	if username == "" {
		writeJSONError(w, 404, "Username required")
		return
	}

	switch r.Method {
	case "GET":
		requester, _ := a.db.GetUser(tokenData.Owner)
		if !requester.IsAdmin && tokenData.Owner != username {
			writeJSONError(w, 403, "Unauthorized")
			return
		}

		user, exists := a.db.GetUser(username)
		if !exists {
			writeJSONError(w, 404, "User doesn't exist")
			return
		}
		writeJSON(w, 200, user)
	case "DELETE":
		values := url.Values{}
		values.Set("username", username)

		if err := a.api.DeleteUser(tokenData, values); err != nil {
			writeJSONError(w, 400, err.Error())
			return
		}
		w.WriteHeader(204)
	default:
		writeJSONError(w, 405, "Invalid method for /users/{username}")
	}
}

func (a *RestApi) handleTokensCollection(w http.ResponseWriter, r *http.Request) {

	tokenData, ok := a.authenticate(w, r)
	if !ok {
		return
	}

	switch r.Method {
	case "GET":
		tokens := a.api.GetTokens(tokenData, url.Values{})
		writeJSON(w, 200, tokens)
	case "POST":
		values, err := parseJSONBody(r)
		if err != nil {
			writeJSONError(w, 400, err.Error())
			return
		}

		token, err := a.api.CreateToken(tokenData, values)
		if err != nil {
			writeJSONError(w, 400, err.Error())
			return
		}

		writeJSON(w, 201, struct {
			Token string `json:"token"`
		}{token})
	default:
		writeJSONError(w, 405, "Invalid method for /tokens")
	}
}

// handleTokenItem serves /tokens/{token} (mounted with "/tokens" stripped).
func (a *RestApi) handleTokenItem(w http.ResponseWriter, r *http.Request) {

	tokenData, ok := a.authenticate(w, r)
	if !ok {
		return
	}

	token := strings.Trim(r.URL.Path, "/")
	if token == "" {
		writeJSONError(w, 404, "Token required")
		return
	}

	switch r.Method {
	case "GET":
		td, exists := a.db.GetTokenData(token)
		if !exists {
			writeJSONError(w, 404, "Token doesn't exist")
			return
		}

		requester, _ := a.db.GetUser(tokenData.Owner)
		if !requester.IsAdmin && tokenData.Owner != td.Owner {
			writeJSONError(w, 403, "Unauthorized")
			return
		}

		writeJSON(w, 200, td)
	case "PATCH":
		values, err := parseJSONBody(r)
		if err != nil {
			writeJSONError(w, 400, err.Error())
			return
		}
		values.Set("token", token)

		if err := a.api.SetTokenAgent(tokenData, values); err != nil {
			writeJSONError(w, 400, err.Error())
			return
		}
		w.WriteHeader(204)
	case "DELETE":
		values := url.Values{}
		values.Set("token", token)

		if err := a.api.DeleteToken(tokenData, values); err != nil {
			writeJSONError(w, 400, err.Error())
			return
		}
		w.WriteHeader(204)
	default:
		writeJSONError(w, 405, "Invalid method for /tokens/{token}")
	}
}

func (a *RestApi) handleAgentsCollection(w http.ResponseWriter, r *http.Request) {

	tokenData, ok := a.authenticate(w, r)
	if !ok {
		return
	}

	switch r.Method {
	case "GET":
		requester, _ := a.db.GetUser(tokenData.Owner)

		var users map[string]User
		if requester.IsAdmin {
			users = a.db.GetUsers()
		} else {
			users = map[string]User{tokenData.Owner: requester}
		}

		type agentStatus struct {
			LastSeen *time.Time `json:"last_seen,omitempty"`
		}

		agents := make(map[string]agentStatus)
		for _, u := range users {
			for agentName := range u.Agents {
				status := agentStatus{}
				if lastSeen, ok := a.db.GetAgentLastSeen(agentName); ok {
					status.LastSeen = &lastSeen
				}
				agents[agentName] = status
			}
		}

		writeJSON(w, 200, agents)
	case "POST":
		values, err := parseJSONBody(r)
		if err != nil {
			writeJSONError(w, 400, err.Error())
			return
		}

		owner := values.Get("owner")
		if owner == "" {
			owner = tokenData.Owner
		}

		agentName := values.Get("agent-name")
		if agentName == "" {
			writeJSONError(w, 400, "Invalid agent-name parameter")
			return
		}

		if err := a.api.SetAgent(tokenData, values, owner, agentName); err != nil {
			writeJSONError(w, 400, err.Error())
			return
		}

		w.WriteHeader(201)
	default:
		writeJSONError(w, 405, "Invalid method for /agents")
	}
}

// handleAgentItem serves /agents/{owner}/{agent-name} (mounted with
// "/agents" stripped).
func (a *RestApi) handleAgentItem(w http.ResponseWriter, r *http.Request) {

	tokenData, ok := a.authenticate(w, r)
	if !ok {
		return
	}

	parts := strings.SplitN(strings.Trim(r.URL.Path, "/"), "/", 2)
	if len(parts) != 2 || parts[0] == "" || parts[1] == "" {
		writeJSONError(w, 400, "Invalid path, expected /agents/{owner}/{agent-name}")
		return
	}

	owner := parts[0]
	agentName := parts[1]

	switch r.Method {
	case "DELETE":
		if err := a.api.DeleteAgent(tokenData, owner, agentName); err != nil {
			writeJSONError(w, 400, err.Error())
			return
		}
		w.WriteHeader(204)
	default:
		writeJSONError(w, 405, "Invalid method for /agents/{owner}/{agent-name}")
	}
}

func writeJSON(w http.ResponseWriter, status int, data interface{}) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(data)
}

func writeJSONError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, struct {
		Error string `json:"error"`
	}{message})
}

// parseJSONBody decodes a JSON object body into url.Values so it can be fed
// directly into the existing Api methods (which were built around
// form-encoded params). Booleans are converted to "on"/"" to match the
// "checkbox" convention those methods expect (eg. "allow-external-tcp").
// An empty body is treated as an empty set of values rather than an error,
// so callers that only rely on path parameters don't need to send a body.
func parseJSONBody(r *http.Request) (url.Values, error) {

	defer r.Body.Close()

	var raw map[string]interface{}

	decoder := json.NewDecoder(r.Body)
	err := decoder.Decode(&raw)
	if err != nil {
		if err == io.EOF {
			return url.Values{}, nil
		}
		return nil, err
	}

	values := url.Values{}
	for key, val := range raw {
		switch v := val.(type) {
		case bool:
			if v {
				values.Set(key, "on")
			}
		case float64:
			values.Set(key, strconv.FormatFloat(v, 'f', -1, 64))
		case string:
			values.Set(key, v)
		case nil:
			// omit
		default:
			b, err := json.Marshal(v)
			if err == nil {
				values.Set(key, string(b))
			}
		}
	}

	return values, nil
}
