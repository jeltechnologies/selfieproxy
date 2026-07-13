package boringproxy

import (
	"crypto/md5"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
)

type Api struct {
	config *Config
	db     *Database
	auth   *Auth
	tunMan *TunnelManager
	mux    *http.ServeMux
}

func NewApi(config *Config, db *Database, auth *Auth, tunMan *TunnelManager) *Api {

	mux := http.NewServeMux()

	api := &Api{config, db, auth, tunMan, mux}

	mux.Handle("/tunnels", http.StripPrefix("/tunnels", http.HandlerFunc(api.handleTunnels)))
	mux.Handle("/users/", http.StripPrefix("/users", http.HandlerFunc(api.handleUsers)))
	mux.Handle("/tokens/", http.StripPrefix("/tokens", http.HandlerFunc(api.handleTokens)))
	mux.Handle("/agents/", http.StripPrefix("/agents", http.HandlerFunc(api.handleAgents)))

	return api
}

func (a *Api) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	a.mux.ServeHTTP(w, r)
}

func (a *Api) handleTunnels(w http.ResponseWriter, r *http.Request) {

	token, err := extractToken("access_token", r)
	if err != nil {
		w.WriteHeader(401)
		w.Write([]byte("No token provided"))
		return
	}

	tokenData, exists := a.db.GetTokenData(token)
	if !exists {
		w.WriteHeader(403)
		w.Write([]byte("Not authorized"))
		return
	}

	switch r.Method {
	case "GET":
		query := r.URL.Query()

		tunnels := a.GetTunnels(tokenData)

		// If the token is limited to a specific agent, filter out
		// tunnels for any other agents.
		if tokenData.Agent != "" {
			for k, tun := range tunnels {
				if tokenData.Agent != tun.AgentName {
					delete(tunnels, k)
				}
			}
		}

		agentName := query.Get("agent-name")
		if agentName != "" && tokenData.Agent != "" && agentName != tokenData.Agent {
			w.WriteHeader(403)
			w.Write([]byte("Token is not valid for this agent"))
			return
		}

		// This handler is what Agent.PollTunnels (agent.go) hits on every
		// poll-interval tick, so a successful GET here from an agent-scoped
		// token is the closest thing boringproxy has to a connection
		// heartbeat -- record it for the "online/offline" status Selfie
		// Proxy's admin portal surfaces per local network. Deliberately
		// placed after the agent-name check above: a request rejected with
		// 403 there (eg. an agent process still configured with a local
		// network's old name after a rename) must not be counted as a
		// successful poll, or the renamed local network would show
		// "Connected" from a request that actually failed.
		if tokenData.Agent != "" {
			a.db.TouchAgentLastSeen(tokenData.Agent)
		}

		if agentName != "" {
			for k, tun := range tunnels {
				if tun.AgentName != agentName {
					delete(tunnels, k)
				} else {
					tun.ServerPort = a.config.SshServerPort
					tunnels[k] = tun
				}
			}
		}

		body, err := json.Marshal(tunnels)
		if err != nil {
			w.WriteHeader(500)
			w.Write([]byte("Error encoding tunnels"))
			return
		}

		hash := md5.Sum(body)
		hashStr := fmt.Sprintf("%x", hash)

		w.Header()["ETag"] = []string{hashStr}

		w.Write([]byte(body))
	case "POST":

		if tokenData.Agent != "" {
			w.WriteHeader(403)
			io.WriteString(w, "Token cannot be used to create tunnels")
			return
		}

		r.ParseForm()
		_, err := a.CreateTunnel(tokenData, r.Form)
		if err != nil {
			w.WriteHeader(500)
			w.Write([]byte(err.Error()))
		}
	case "DELETE":
		if tokenData.Agent != "" {
			w.WriteHeader(403)
			io.WriteString(w, "Token cannot be used to delete tunnels")
			return
		}

		r.ParseForm()
		err := a.DeleteTunnel(tokenData, r.Form)
		if err != nil {
			w.WriteHeader(500)
			w.Write([]byte(err.Error()))
		}
	default:
		w.WriteHeader(405)
		w.Write([]byte("Invalid method for /tunnels"))
	}
}

func (a *Api) handleUsers(w http.ResponseWriter, r *http.Request) {
	token, err := extractToken("access_token", r)
	if err != nil {
		w.WriteHeader(401)
		io.WriteString(w, "Invalid token")
		return
	}

	tokenData, exists := a.db.GetTokenData(token)
	if !exists {
		w.WriteHeader(401)
		io.WriteString(w, "Failed to get token")
		return
	}

	r.ParseForm()

	if tokenData.Agent != "" {
		w.WriteHeader(403)
		io.WriteString(w, "Token cannot be used to manage users")
		return
	}

	switch r.Method {
	case "GET":
		users := a.GetUsers(tokenData, r.Form)
		json.NewEncoder(w).Encode(users)
	case "POST":
		err := a.CreateUser(tokenData, r.Form)
		if err != nil {
			w.WriteHeader(500)
			io.WriteString(w, err.Error())
			return
		}
	default:
		w.WriteHeader(405)
		io.WriteString(w, "Invalid method for /users")
		return
	}
}

func (a *Api) handleTokens(w http.ResponseWriter, r *http.Request) {
	token, err := extractToken("access_token", r)
	if err != nil {
		w.WriteHeader(401)
		w.Write([]byte("No token provided"))
		return
	}

	tokenData, exists := a.db.GetTokenData(token)
	if !exists {
		w.WriteHeader(403)
		w.Write([]byte("Not authorized"))
		return
	}

	if tokenData.Agent != "" {
		w.WriteHeader(403)
		io.WriteString(w, "Token cannot be used to manage tokens")
		return
	}

	switch r.Method {
	case "GET":
		tokens := a.GetTokens(tokenData, r.Form)
		json.NewEncoder(w).Encode(tokens)
	case "POST":
		r.ParseForm()
		token, err := a.CreateToken(tokenData, r.Form)
		if err != nil {
			w.WriteHeader(500)
			w.Write([]byte(err.Error()))
		}

		io.WriteString(w, token)
	default:
		w.WriteHeader(405)
		fmt.Fprintf(w, "Invalid method for /api/tokens")
	}
}

func (a *Api) handleAgents(w http.ResponseWriter, r *http.Request) {

	r.ParseForm()

	token, err := extractToken("access_token", r)
	if err != nil {
		w.WriteHeader(401)
		w.Write([]byte("No token provided"))
		return
	}

	tokenData, exists := a.db.GetTokenData(token)
	if !exists {
		w.WriteHeader(403)
		w.Write([]byte("Not authorized"))
		return
	}

	agentName := r.Form.Get("agent-name")
	if agentName == "" {
		if tokenData.Agent == "" {
			w.WriteHeader(400)
			w.Write([]byte("Missing agent-name parameter"))
			return
		} else {
			agentName = tokenData.Agent
		}
	}

	if tokenData.Agent != "" && tokenData.Agent != agentName {
		w.WriteHeader(403)
		io.WriteString(w, "Token does not have proper permissions")
		return
	}

	user := r.Form.Get("user")
	if user == "" {
		user = tokenData.Owner
	}

	switch r.Method {
	case "POST":
		err := a.SetAgent(tokenData, r.Form, user, agentName)
		if err != nil {
			w.WriteHeader(500)
			w.Write([]byte(err.Error()))
		}
	case "DELETE":
		err := a.DeleteAgent(tokenData, user, agentName)
		if err != nil {
			w.WriteHeader(500)
			io.WriteString(w, err.Error())
			return
		}
	default:
		w.WriteHeader(405)
		fmt.Fprintf(w, "Invalid method for /api/agents")
	}
}

func (a *Api) GetTunnel(tokenData TokenData, params url.Values) (Tunnel, error) {
	domain := params.Get("domain")
	if domain == "" {
		return Tunnel{}, errors.New("Invalid domain parameter")
	}

	tun, exists := a.db.GetTunnel(domain)
	if !exists {
		return Tunnel{}, errors.New("Tunnel doesn't exist for domain")
	}

	user, _ := a.db.GetUser(tokenData.Owner)
	if user.IsAdmin || tun.Owner == tokenData.Owner {
		return tun, nil
	} else {
		return Tunnel{}, errors.New("Unauthorized")
	}
}

func (a *Api) GetTunnels(tokenData TokenData) map[string]Tunnel {

	user, _ := a.db.GetUser(tokenData.Owner)

	var tunnels map[string]Tunnel

	if user.IsAdmin {
		tunnels = a.db.GetTunnels()
	} else {
		tunnels = make(map[string]Tunnel)

		for domain, tun := range a.db.GetTunnels() {
			if tokenData.Owner == tun.Owner {
				tunnels[domain] = tun
			}
		}
	}

	return tunnels
}

func (a *Api) CreateTunnel(tokenData TokenData, params url.Values) (*Tunnel, error) {

	domain := params.Get("domain")
	if domain == "" {
		return nil, errors.New("Invalid domain parameter")
	}

	owner := params.Get("owner")
	if owner == "" {
		return nil, errors.New("Invalid owner parameter")
	}

	// Only admins can create tunnels for other users
	if tokenData.Owner != owner {
		user, _ := a.db.GetUser(tokenData.Owner)
		if !user.IsAdmin {
			return nil, errors.New("Unauthorized")
		}
	}

	agentName := params.Get("agent-name")

	clientPort := 0
	clientPortParam := params.Get("client-port")
	if clientPortParam != "" {
		var err error
		clientPort, err = strconv.Atoi(clientPortParam)
		if err != nil {
			return nil, errors.New("Invalid client-port parameter")
		}
	}

	clientAddr := params.Get("client-addr")
	if clientAddr == "" {
		clientAddr = "127.0.0.1"
	}

	tunnelPort := 0
	tunnelPortParam := params.Get("tunnel-port")
	if tunnelPortParam != "" && tunnelPortParam != "Random" {
		var err error
		tunnelPort, err = strconv.Atoi(tunnelPortParam)
		if err != nil {
			return nil, errors.New("Invalid tunnel-port parameter")
		}
	}

	allowExternalTcp := params.Get("allow-external-tcp") == "on"

	// Only meaningful for "server"-termination tunnels (and the special-cased
	// portal/sso domains) -- those are the only ones boringproxy ever parses
	// HTTP for, so they're the only ones an OIDC redirect/cookie check can be
	// enforced on. See oidc_auth.go's RequireAuth and its call site in
	// boringproxy.go.
	ssoProtect := params.Get("sso-protect") == "on"

	passwordProtect := params.Get("password-protect") == "on"

	var username string
	var password string
	if passwordProtect {
		username = params.Get("username")
		if username == "" {
			return nil, errors.New("Username required")
		}

		password = params.Get("password")
		if password == "" {
			return nil, errors.New("Password required")
		}
	}

	tlsTerm := params.Get("tls-termination")
	if tlsTerm != "server" && tlsTerm != "client" && tlsTerm != "passthrough" && tlsTerm != "client-tls" && tlsTerm != "server-tls" {
		return nil, errors.New("Invalid tls-termination parameter")
	}

	sshServerAddr := a.db.GetAdminDomain()
	sshServerAddrParam := params.Get("ssh-server-addr")
	if sshServerAddrParam != "" {
		sshServerAddr = sshServerAddrParam
	}

	sshServerPort := a.config.SshServerPort
	sshServerPortParam := params.Get("ssh-server-port")
	if sshServerPortParam != "" {
		var err error
		sshServerPort, err = strconv.Atoi(sshServerPortParam)
		if err != nil {
			return nil, errors.New("Invalid ssh-server-port parameter")
		}
	}

	request := Tunnel{
		Domain:           domain,
		Owner:            owner,
		AgentName:        agentName,
		ClientPort:       clientPort,
		ClientAddress:    clientAddr,
		TunnelPort:       tunnelPort,
		AllowExternalTcp: allowExternalTcp,
		AuthUsername:     username,
		AuthPassword:     password,
		TlsTermination:   tlsTerm,
		SsoProtected:     ssoProtect,
		ServerAddress:    sshServerAddr,
		ServerPort:       sshServerPort,
	}

	tunnel, err := a.tunMan.RequestCreateTunnel(request)
	if err != nil {
		return nil, err
	}

	return &tunnel, nil
}

func (a *Api) DeleteTunnel(tokenData TokenData, params url.Values) error {

	domain := params.Get("domain")
	if domain == "" {
		return errors.New("Invalid domain parameter")
	}

	tun, exists := a.db.GetTunnel(domain)
	if !exists {
		return errors.New("Tunnel doesn't exist")
	}

	if tokenData.Owner != tun.Owner {
		user, _ := a.db.GetUser(tokenData.Owner)
		if !user.IsAdmin {
			return errors.New("Unauthorized")
		}
	}

	a.tunMan.DeleteTunnel(domain)

	return nil
}

// SetTunnelAgent re-points an existing tunnel at a different agent name, as
// a direct DB field update -- it deliberately does not go through
// TunnelManager (no new SSH keypair, no new tunnel port, no authorized_keys
// churn), since a rename doesn't change the tunnel's connection details at
// all. Used so Selfie Proxy's admin portal can keep every exposed app under
// a local network pointed at it after the local network itself is renamed,
// instead of leaving them orphaned under the old name.
func (a *Api) SetTunnelAgent(tokenData TokenData, domain, agentName string) error {
	tun, exists := a.db.GetTunnel(domain)
	if !exists {
		return errors.New("Tunnel doesn't exist")
	}

	if tokenData.Owner != tun.Owner {
		user, _ := a.db.GetUser(tokenData.Owner)
		if !user.IsAdmin {
			return errors.New("Unauthorized")
		}
	}

	tun.AgentName = agentName
	a.db.SetTunnel(domain, tun)

	return nil
}

func (a *Api) CreateToken(tokenData TokenData, params url.Values) (string, error) {

	ownerId := params.Get("owner")
	if ownerId == "" {
		return "", errors.New("Invalid owner paramater")
	}

	user, _ := a.db.GetUser(tokenData.Owner)

	if tokenData.Owner != ownerId && !user.IsAdmin {
		return "", errors.New("Unauthorized")
	}

	var owner User

	if tokenData.Owner == ownerId {
		owner = user
	} else {
		owner, _ = a.db.GetUser(ownerId)
	}

	agent := params.Get("agent")

	if agent != "any" {
		if _, exists := owner.Agents[agent]; !exists {
			return "", fmt.Errorf("Agent %s does not exist for user %s", agent, ownerId)
		}
	} else {
		agent = ""
	}

	token, err := a.db.AddToken(ownerId, agent)
	if err != nil {
		return "", errors.New("Failed to create token")
	}

	return token, nil
}

func (a *Api) DeleteToken(tokenData TokenData, params url.Values) error {
	token := params.Get("token")
	if token == "" {
		return errors.New("Invalid token parameter")
	}

	delTokenData, exists := a.db.GetTokenData(token)
	if !exists {
		return errors.New("Token doesn't exist")
	}

	if tokenData.Owner != delTokenData.Owner {
		user, _ := a.db.GetUser(tokenData.Owner)
		if !user.IsAdmin {
			return errors.New("Unauthorized")
		}
	}

	a.db.DeleteTokenData(token)

	return nil

}

// SetTokenAgent re-points an existing token at a different agent name owned
// by the same user, without changing the token string itself. This exists
// so Selfie Proxy's admin portal can rename a local network (which re-creates
// the underlying agent record under the new name, since boringproxy has no
// rename primitive for that) while keeping the agent's secret unchanged --
// only CreateToken/DeleteToken were available before, which would have
// forced a fresh secret on every rename.
func (a *Api) SetTokenAgent(tokenData TokenData, params url.Values) error {
	token := params.Get("token")
	if token == "" {
		return errors.New("Invalid token parameter")
	}

	agent := params.Get("agent")
	if agent == "" {
		return errors.New("Invalid agent parameter")
	}

	existing, exists := a.db.GetTokenData(token)
	if !exists {
		return errors.New("Token doesn't exist")
	}

	if tokenData.Owner != existing.Owner {
		user, _ := a.db.GetUser(tokenData.Owner)
		if !user.IsAdmin {
			return errors.New("Unauthorized")
		}
	}

	owner, _ := a.db.GetUser(existing.Owner)
	if _, exists := owner.Agents[agent]; !exists {
		return fmt.Errorf("Agent %s does not exist for user %s", agent, existing.Owner)
	}

	a.db.SetTokenData(token, TokenData{Owner: existing.Owner, Agent: agent})

	return nil
}

func (a *Api) GetTokens(tokenData TokenData, params url.Values) map[string]TokenData {

	tokens := a.db.GetTokens()

	user, _ := a.db.GetUser(tokenData.Owner)

	for key, tok := range tokens {
		if !user.IsAdmin && tok.Owner != tokenData.Owner {
			delete(tokens, key)
		}
	}

	return tokens
}

func (a *Api) GetUsers(tokenData TokenData, params url.Values) map[string]User {

	user, _ := a.db.GetUser(tokenData.Owner)
	users := a.db.GetUsers()

	if user.IsAdmin {
		return users
	} else {
		return map[string]User{
			tokenData.Owner: user,
		}
	}
}

func (a *Api) CreateUser(tokenData TokenData, params url.Values) error {

	user, _ := a.db.GetUser(tokenData.Owner)
	if !user.IsAdmin {
		return errors.New("Unauthorized")
	}

	username := params.Get("username")
	minUsernameLen := 6
	if len(username) < minUsernameLen {
		errStr := fmt.Sprintf("Username must be at least %d characters", minUsernameLen)
		return errors.New(errStr)
	}

	isAdmin := params.Get("is-admin") == "on"

	err := a.db.AddUser(username, isAdmin)
	if err != nil {
		return err
	}

	return nil
}

func (a *Api) DeleteUser(tokenData TokenData, params url.Values) error {

	user, _ := a.db.GetUser(tokenData.Owner)
	if !user.IsAdmin {
		return errors.New("Unauthorized")
	}

	username := params.Get("username")
	if username == "" {
		return errors.New("Invalid username parameter")
	}

	_, exists := a.db.GetUser(username)
	if !exists {
		return errors.New("User doesn't exist")
	}

	a.db.DeleteUser(username)

	for token, tokenData := range a.db.GetTokens() {
		if tokenData.Owner == username {
			a.db.DeleteTokenData(token)
		}
	}

	return nil
}

func (a *Api) SetAgent(tokenData TokenData, params url.Values, ownerId, agentId string) error {

	if tokenData.Owner != ownerId {
		user, _ := a.db.GetUser(tokenData.Owner)
		if !user.IsAdmin {
			return errors.New("Unauthorized")
		}
	}

	// TODO: what if two users try to get then set at the same time?
	owner, _ := a.db.GetUser(ownerId)
	owner.Agents[agentId] = DbAgent{}
	a.db.SetUser(ownerId, owner)

	return nil
}

func (a *Api) DeleteAgent(tokenData TokenData, ownerId, agentId string) error {

	if tokenData.Owner != ownerId {
		user, _ := a.db.GetUser(tokenData.Owner)
		if !user.IsAdmin {
			return errors.New("Unauthorized")
		}
	}

	owner, _ := a.db.GetUser(ownerId)
	delete(owner.Agents, agentId)
	a.db.SetUser(ownerId, owner)

	return nil
}
