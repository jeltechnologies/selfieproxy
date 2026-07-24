package boringproxy

import (
	"encoding/json"
	"errors"
	"fmt"
	"io/ioutil"
	"log"
	"os"
	"sync"
	"time"

	"github.com/takingnames/namedrop-go"
)

var DBFolderPath string

type Database struct {
	AdminDomain string                         `json:"admin_domain"`
	Tokens      map[string]TokenData           `json:"tokens"`
	Tunnels     map[string]Tunnel              `json:"tunnels"`
	Users       map[string]User                `json:"users"`
	dnsRequests map[string]namedrop.DNSRequest `json:"dns_requests"`
	mutex       *sync.Mutex

	// agentLastSeen is deliberately not persisted (unexported, no json tag):
	// it's a live heartbeat derived from each agent's recurring GET
	// /api/tunnels poll (see Agent.PollTunnels in agent.go and the GET case
	// of Api.handleTunnels in api.go), not durable state. Losing it on
	// restart is fine -- a connected agent repopulates it within one poll
	// interval.
	agentLastSeen map[string]time.Time
}

type TokenData struct {
	Owner string `json:"owner"`
	Agent string `json:"agent,omitempty"`
}

type User struct {
	IsAdmin bool               `json:"is_admin"`
	Agents  map[string]DbAgent `json:"agents"`
}

type DbAgent struct {
}

type DNSRecord struct {
	Type     string `json:"type"`
	Value    string `json:"value"`
	TTL      int    `json:"ttl"`
	Priority int    `json:"priority"`
}

type Tunnel struct {
	Domain           string `json:"domain"`
	ServerAddress    string `json:"server_address"`
	ServerPort       int    `json:"server_port"`
	SshTls           bool   `json:"ssh_tls"`
	ServerPublicKey  string `json:"server_public_key"`
	Username         string `json:"username"`
	TunnelPort       int    `json:"tunnel_port"`
	TunnelPrivateKey string `json:"tunnel_private_key"`
	ClientAddress    string `json:"client_address"`
	ClientPort       int    `json:"client_port"`
	AllowExternalTcp bool   `json:"allow_external_tcp"`
	TlsTermination   string `json:"tls_termination"`
	SsoProtected     bool   `json:"sso_protected"`
	CertPending      bool   `json:"cert_pending"`

	// TODO: These are not used by agents and possibly shouldn't be
	// returned in API calls.
	Owner        string `json:"owner"`
	AgentName    string `json:"agent_name"`
	AuthUsername string `json:"auth_username"`
	AuthPassword string `json:"auth_password"`
}

func NewDatabase(path string) (*Database, error) {

	DBFolderPath = path

	dbJson, err := ioutil.ReadFile(DBFolderPath + "boringproxy_db.json")
	if err != nil {
		if !os.IsNotExist(err) {
			// A real read failure (permissions, a concurrent writer caught
			// mid-rename, a transient mount hiccup, etc) must not be treated
			// as "no database yet" -- doing so would fall through to
			// persist() below and silently overwrite existing tokens/tunnels
			// with an empty database. Only a genuinely missing file (first
			// run) is safe to start empty.
			return nil, fmt.Errorf("failed reading %sboringproxy_db.json: %w", DBFolderPath, err)
		}
		log.Printf("no existing %sboringproxy_db.json, starting fresh\n", DBFolderPath)
		dbJson = []byte("{}")
	}

	var db *Database

	err = json.Unmarshal(dbJson, &db)
	if err != nil {
		log.Println(err)
		db = &Database{}
	}

	if db.Tokens == nil {
		db.Tokens = make(map[string]TokenData)
	}

	if db.Tunnels == nil {
		db.Tunnels = make(map[string]Tunnel)
	}

	if db.Users == nil {
		db.Users = make(map[string]User)
	}

	if db.dnsRequests == nil {
		db.dnsRequests = make(map[string]namedrop.DNSRequest)
	}

	db.agentLastSeen = make(map[string]time.Time)

	db.mutex = &sync.Mutex{}

	db.mutex.Lock()
	defer db.mutex.Unlock()
	db.persist()

	return db, nil
}

func (d *Database) SetAdminDomain(adminDomain string) {
	d.mutex.Lock()
	defer d.mutex.Unlock()

	d.AdminDomain = adminDomain

	d.persist()
}
func (d *Database) GetAdminDomain() string {
	d.mutex.Lock()
	defer d.mutex.Unlock()

	return d.AdminDomain
}

func (d *Database) SetDNSRequest(requestId string, request namedrop.DNSRequest) {
	d.mutex.Lock()
	defer d.mutex.Unlock()

	d.dnsRequests[requestId] = request

	// Not currently persisting because dnsRequests is only stored in
	// memory. May change in the future.
	//d.persist()
}
func (d *Database) GetDNSRequest(requestId string) (namedrop.DNSRequest, error) {
	d.mutex.Lock()
	defer d.mutex.Unlock()

	if req, ok := d.dnsRequests[requestId]; ok {
		return req, nil
	}

	return namedrop.DNSRequest{}, errors.New("No such DNS Request")
}
func (d *Database) DeleteDNSRequest(requestId string) {
	d.mutex.Lock()
	defer d.mutex.Unlock()

	delete(d.dnsRequests, requestId)
}

func (d *Database) AddToken(owner, agent string) (string, error) {
	d.mutex.Lock()
	defer d.mutex.Unlock()

	_, exists := d.Users[owner]
	if !exists {
		return "", errors.New("Owner doesn't exist")
	}

	token, err := genRandomCode(32)
	if err != nil {
		return "", errors.New("Could not generat token")
	}

	d.Tokens[token] = TokenData{
		Owner: owner,
		Agent: agent,
	}

	d.persist()

	return token, nil
}

func (d *Database) GetTokens() map[string]TokenData {
	d.mutex.Lock()
	defer d.mutex.Unlock()

	tokens := make(map[string]TokenData)

	for k, v := range d.Tokens {
		tokens[k] = v
	}

	return tokens
}

func (d *Database) GetTokenData(token string) (TokenData, bool) {
	d.mutex.Lock()
	defer d.mutex.Unlock()

	tokenData, exists := d.Tokens[token]

	if !exists {
		return TokenData{}, false
	}

	return tokenData, true
}

func (d *Database) SetTokenData(token string, tokenData TokenData) {
	d.mutex.Lock()
	defer d.mutex.Unlock()

	d.Tokens[token] = tokenData
	d.persist()
}

func (d *Database) DeleteTokenData(token string) {
	d.mutex.Lock()
	defer d.mutex.Unlock()

	delete(d.Tokens, token)

	d.persist()
}

func (d *Database) GetTunnels() map[string]Tunnel {
	d.mutex.Lock()
	defer d.mutex.Unlock()

	tunnels := make(map[string]Tunnel)

	for k, v := range d.Tunnels {
		tunnels[k] = v
	}

	return tunnels
}

func (d *Database) GetTunnel(domain string) (Tunnel, bool) {
	d.mutex.Lock()
	defer d.mutex.Unlock()

	tun, exists := d.Tunnels[domain]

	if !exists {
		return Tunnel{}, false
	}

	return tun, true
}

func (d *Database) SetTunnel(domain string, tun Tunnel) {
	d.mutex.Lock()
	defer d.mutex.Unlock()

	d.Tunnels[domain] = tun
	d.persist()
}

func (d *Database) DeleteTunnel(domain string) {
	d.mutex.Lock()
	defer d.mutex.Unlock()

	delete(d.Tunnels, domain)

	d.persist()
}

func (d *Database) GetUsers() map[string]User {
	d.mutex.Lock()
	defer d.mutex.Unlock()

	users := make(map[string]User)

	for k, v := range d.Users {
		users[k] = v
	}

	return users
}

func (d *Database) GetUser(username string) (User, bool) {
	d.mutex.Lock()
	defer d.mutex.Unlock()

	user, exists := d.Users[username]

	if !exists {
		return User{}, false
	}

	return user, true
}

func (d *Database) SetUser(username string, user User) error {
	d.mutex.Lock()
	defer d.mutex.Unlock()

	d.Users[username] = user
	d.persist()

	return nil
}

func (d *Database) AddUser(username string, isAdmin bool) error {
	d.mutex.Lock()
	defer d.mutex.Unlock()

	_, exists := d.Users[username]

	if exists {
		return errors.New("User exists")
	}

	d.Users[username] = User{
		IsAdmin: isAdmin,
		Agents:  make(map[string]DbAgent),
	}

	d.persist()

	return nil
}

func (d *Database) DeleteUser(username string) {
	d.mutex.Lock()
	defer d.mutex.Unlock()

	delete(d.Users, username)

	d.persist()
}

func (d *Database) persist() {
	saveJson(d, DBFolderPath+"boringproxy_db.json")
}

// TouchAgentLastSeen records that agentName was just observed polling for
// tunnels. Not persisted -- see the agentLastSeen field comment.
func (d *Database) TouchAgentLastSeen(agentName string) {
	d.mutex.Lock()
	defer d.mutex.Unlock()

	// UTC so the JSON encoding (RFC3339Nano) always ends in "Z" -- the admin
	// portal parses it with Java's Instant.parse, which only accepts the
	// UTC "Z" suffix, not an arbitrary local offset.
	d.agentLastSeen[agentName] = time.Now().UTC()
}

func (d *Database) GetAgentLastSeen(agentName string) (time.Time, bool) {
	d.mutex.Lock()
	defer d.mutex.Unlock()

	t, exists := d.agentLastSeen[agentName]
	return t, exists
}
