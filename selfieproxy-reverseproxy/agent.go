package boringproxy

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"io/ioutil"
	"log"
	"net"
	"net/http"
	"sync"
	"sync/atomic"
	"time"

	"github.com/caddyserver/certmagic"
	"golang.org/x/crypto/ssh"
)

// Bounds how long a single agent<->server control-plane request (registration, tunnel polling)
// may take. c.httpClient has no client-wide Timeout because it's also used to proxy real,
// potentially long-lived backend traffic (see proxyRequest) -- a blanket timeout there would
// cut off legitimate slow requests. Without this, a stalled/half-open connection on the
// control-plane side (registration or the recurring tunnel poll) could hang Agent.Run's request
// indefinitely, silently freezing the whole poll loop -- it never reaches the next tick, logs no
// error, and no tunnel changes (new/renamed/deleted) are ever picked up again until the agent
// process is restarted.
const controlPlaneRequestTimeout = 30 * time.Second

// Bounds how many tunnels may be dialing/handshaking their SSH connection at once
// (see boreSem below). A fresh agent process re-bores every tunnel simultaneously
// (SyncTunnels treats them all as "new"), and firing dozens of concurrent SSH
// connections at the server's sshd trips its own connection-flood protection
// (MaxStartups), which resets a random subset of handshakes mid-flight -- exactly
// what caused a tunnel to sit unreachable for tens of seconds after an agent
// restart. Only the connect+handshake phase holds a slot, so this never delays a
// single tunnel being added/changed in isolation.
const maxConcurrentBores = 4

// BoreTunnel sends an SSH keepalive on this interval and treats a missing reply
// within sshKeepaliveTimeout as a dead connection (see connDead below). x/crypto/ssh
// has no built-in liveness check, and a connection silently dropped by a NAT/
// firewall (no FIN, no RST) otherwise looks "alive" to Go indefinitely.
const (
	sshKeepaliveInterval = 20 * time.Second
	sshKeepaliveTimeout  = 10 * time.Second
)

// boreTunnelWithRetry's backoff when BoreTunnel returns because the SSH connection
// died rather than because the tunnel was changed/removed (ctx cancelled). Kept
// short relative to certRetryBaseInterval/certRetryMaxInterval above -- unlike ACME,
// there's no external rate limit to respect here, just the server's own sshd.
const (
	tunnelReboreBaseInterval = 2 * time.Second
	tunnelReboreMaxInterval  = 60 * time.Second
)

type Agent struct {
	httpClient       *http.Client
	tunnels          map[string]Tunnel
	previousEtag     string
	server           string
	secret           string
	agentName        string
	user             string
	cancelFuncs      map[string]context.CancelFunc
	cancelFuncsMutex *sync.Mutex
	certConfig       *certmagic.Config
	certConfigOnce   sync.Once
	selfSignedCerts  *SelfSignedCertProvider
	pollInterval     int
	boreSem          chan struct{}
}

type AgentConfig struct {
	ServerAddr     string `json:"serverAddr,omitempty"`
	Secret         string `json:"secret,omitempty"`
	AgentName      string `json:"agentName,omitempty"`
	User           string `json:"user,omitempty"`
	CertDir        string `json:"certDir,omitempty"`
	AcmeEmail      string `json:"acmeEmail,omitempty"`
	AcmeUseStaging bool   `json:"acmeUseStaging,omitempty"`
	AcmeCa         string `json:"acmeCa,omitempty"`
	DnsServer      string `json:"dnsServer,omitempty"`
	PollInterval   int    `json:"pollInterval,omitempty"`
}

func NewAgent(config *AgentConfig) (*Agent, error) {

	if config.DnsServer != "" {
		net.DefaultResolver = &net.Resolver{
			PreferGo: true,
			Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
				d := net.Dialer{
					Timeout: time.Millisecond * time.Duration(10000),
				}
				return d.DialContext(ctx, "udp", fmt.Sprintf("%s:53", config.DnsServer))
			},
		}
	}

	// Use random unprivileged port for ACME challenges. This is necessary
	// because of the way certmagic works, in that if it fails to bind
	// HTTPSPort (443 by default) and doesn't detect anything else binding
	// it, it fails. Obviously the boringproxy agent is likely to be
	// running on a machine where 443 isn't bound, so we need a different
	// port to hack around this. See here for more details:
	// https://github.com/caddyserver/certmagic/issues/111
	var err error
	certmagic.HTTPSPort, err = randomOpenPort()
	if err != nil {
		return nil, errors.New("Failed get random port for TLS challenges")
	}

	certmagic.DefaultACME.DisableHTTPChallenge = true

	if config.CertDir != "" {
		certmagic.Default.Storage = &certmagic.FileStorage{config.CertDir}
	}

	if config.AcmeEmail != "" {
		certmagic.DefaultACME.Email = config.AcmeEmail
	}

	if config.AcmeUseStaging {
		certmagic.DefaultACME.CA = certmagic.LetsEncryptStagingCA
	}

	if config.AcmeCa != "" {
		certmagic.DefaultACME.CA = config.AcmeCa
	}

	httpClient := &http.Client{
		// Don't follow redirects
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return http.ErrUseLastResponse
		},
		// Backend addresses prefixed with https:// are dialed directly by
		// proxyRequest, so this only affects those upstream connections --
		// it lets a self-signed backend cert (e.g. Proxmox's default) work,
		// matching the InsecureSkipVerify already used for raw TCP tunnels
		// in tls_proxy.go.
		Transport: &http.Transport{
			TLSClientConfig: &tls.Config{InsecureSkipVerify: true},
		},
	}
	tunnels := make(map[string]Tunnel)
	cancelFuncs := make(map[string]context.CancelFunc)
	cancelFuncsMutex := &sync.Mutex{}

	return &Agent{
		httpClient:       httpClient,
		tunnels:          tunnels,
		previousEtag:     "",
		server:           config.ServerAddr,
		secret:           config.Secret,
		agentName:        config.AgentName,
		user:             config.User,
		cancelFuncs:      cancelFuncs,
		cancelFuncsMutex: cancelFuncsMutex,
		selfSignedCerts:  NewSelfSignedCertProvider(),
		pollInterval:     config.PollInterval,
		boreSem:          make(chan struct{}, maxConcurrentBores),
	}, nil
}

// getCertConfig lazily starts certmagic -- including its background certificate maintenance
// goroutine (the "started background certificate maintenance" log line) -- the first time a
// tunnel actually needs it, rather than unconditionally on agent startup like the server role
// does (boringproxy.go, a separate *certmagic.Config with its own maintenance loop, unaffected
// by any of this). Every ordinary tunnel this product creates (server/passthrough termination)
// never calls ManageSync at all, so for the agent's entire lifetime in the common case this
// never runs and never logs -- only a "client"/"client-tls"-terminated tunnel (not producible
// through the portal today, see selfieproxy-portal/CLAUDE.md's "Agents" section) needs a real
// *certmagic.Config, and only then does it get created, once, via certConfigOnce.
func (c *Agent) getCertConfig(tlsTermination string) *certmagic.Config {
	if tlsTermination != "client" && tlsTermination != "client-tls" {
		return nil
	}
	c.certConfigOnce.Do(func() {
		c.certConfig = certmagic.NewDefault()
	})
	return c.certConfig
}

// Retry intervals for the initial agent registration (POST /api/agents/). Docker's
// restart: unless-stopped policy used to be the only thing retrying this at all -- Run
// returned an error on any registration failure, main.go exited, and the container immediately
// restarted and tried again, hammering the server on every failure including an unrecoverable
// one (wrong -secret/-agent-name, e.g. after the secret was regenerated in the portal) that
// can't self-resolve until the operator fixes it. registerWithRetry below never returns an
// error for a registration failure -- it logs and backs off instead, much more slowly for an
// auth rejection specifically, since nothing about retrying faster helps that case.
const (
	registerRetryInterval     = 5 * time.Second
	registerAuthRetryInterval = 60 * time.Second
)

func (c *Agent) Run(ctx context.Context) error {

	if err := c.registerWithRetry(ctx); err != nil {
		return err
	}

	pollChan := make(chan struct{})

	// A polling interval of 0 disables polling. Basically pollChan will
	// remain blocked and never trigger in the select below.
	if c.pollInterval > 0 {
		go func() {
			for {
				<-time.After(time.Duration(c.pollInterval) * time.Millisecond)
				pollChan <- struct{}{}
			}
		}()
	}

	wasDisconnected := false
	for {
		err := c.PollTunnels(ctx)
		if err != nil {
			log.Print(err)
			wasDisconnected = true
		} else if wasDisconnected {
			log.Println("Reconnected to server")
			wasDisconnected = false
		}

		select {
		case <-ctx.Done():
			return nil
		case <-pollChan:
			// continue
		}
	}
}

// registerWithRetry keeps retrying the initial registration until it succeeds or ctx is
// cancelled -- see the constants above Run for why a bad secret/agent-name backs off far more
// slowly than any other kind of failure. Returns nil either way (ctx cancellation is a clean
// shutdown, not a failure) -- Run treats a non-nil return here as fatal, but that path is only
// reachable if ctx itself is somehow already done before the first attempt.
func (c *Agent) registerWithRetry(ctx context.Context) error {
	url := fmt.Sprintf("https://%s/api/agents/?agent-name=%s", c.server, c.agentName)
	if c.user != "" {
		url = url + "&user=" + c.user
	}

	for {
		statusCode, body, err := c.registerOnce(ctx, url)
		if err == nil && statusCode == 200 {
			log.Printf("Successfully connected to %s", c.server)
			return nil
		}

		wait := registerRetryInterval
		switch {
		case err != nil:
			log.Printf("Failed to register agent: %v. Retrying in %s.", err, wait)
		case statusCode == 401 || statusCode == 403:
			wait = registerAuthRetryInterval
			log.Printf("Registration rejected (HTTP %d): %s -- check that -secret and -agent-name ('%s') match the portal's Homelabs page (a regenerated secret invalidates the old one immediately). Retrying in %s until it's fixed.", statusCode, body, c.agentName, wait)
		default:
			log.Printf("Failed to register agent. HTTP status %d: %s. Retrying in %s.", statusCode, body, wait)
		}

		select {
		case <-ctx.Done():
			return nil
		case <-time.After(wait):
		}
	}
}

// registerOnce performs a single registration attempt. A network-level failure (server
// unreachable, timeout, ...) comes back as err; anything else -- including a rejected auth --
// comes back as a plain HTTP status/body for the caller to interpret.
func (c *Agent) registerOnce(ctx context.Context, url string) (int, string, error) {
	registerCtx, cancel := context.WithTimeout(ctx, controlPlaneRequestTimeout)
	defer cancel()

	agentReq, err := http.NewRequestWithContext(registerCtx, "POST", url, nil)
	if err != nil {
		return 0, "", fmt.Errorf("failed to create request for URL %s: %w", url, err)
	}
	if len(c.secret) > 0 {
		agentReq.Header.Add("Authorization", "bearer "+c.secret)
	}

	resp, err := c.httpClient.Do(agentReq)
	if err != nil {
		return 0, "", err
	}
	defer resp.Body.Close()

	body, err := ioutil.ReadAll(resp.Body)
	if err != nil {
		return resp.StatusCode, "", nil
	}
	return resp.StatusCode, string(body), nil
}

func (c *Agent) PollTunnels(ctx context.Context) error {

	//log.Println("PollTunnels")

	url := fmt.Sprintf("https://%s/api/tunnels?agent-name=%s", c.server, c.agentName)

	pollCtx, cancel := context.WithTimeout(ctx, controlPlaneRequestTimeout)
	defer cancel()

	listenReq, err := http.NewRequestWithContext(pollCtx, "GET", url, nil)
	if err != nil {
		return err
	}

	if len(c.secret) > 0 {
		listenReq.Header.Add("Authorization", "bearer "+c.secret)
	}

	resp, err := c.httpClient.Do(listenReq)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode != 200 {
		return errors.New("Failed to listen (not 200 status)")
	}

	etag := resp.Header["Etag"][0]

	if etag != c.previousEtag {

		body, err := ioutil.ReadAll(resp.Body)

		tunnels := make(map[string]Tunnel)

		err = json.Unmarshal(body, &tunnels)
		if err != nil {
			return err
		}

		c.SyncTunnels(ctx, tunnels)

		c.previousEtag = etag
	}

	return nil
}

func (c *Agent) SyncTunnels(ctx context.Context, serverTunnels map[string]Tunnel) {
	log.Println("SyncTunnels")

	// update tunnels to match server
	for k, newTun := range serverTunnels {

		// assume tunnels exists and hasn't changed
		bore := false

		tun, exists := c.tunnels[k]
		if !exists {
			log.Println("New tunnel", k)
			c.tunnels[k] = newTun
			bore = true
		} else if newTun != tun {
			log.Println("Restart tunnel", k)
			c.cancelFuncsMutex.Lock()
			c.cancelFuncs[k]()
			c.cancelFuncsMutex.Unlock()
			bore = true
		}

		if bore {
			cancelCtx, cancel := context.WithCancel(ctx)

			c.cancelFuncsMutex.Lock()
			c.cancelFuncs[k] = cancel
			c.cancelFuncsMutex.Unlock()

			go c.boreTunnelWithRetry(cancelCtx, newTun)
		}
	}

	// delete any tunnels that no longer exist on server
	for k, _ := range c.tunnels {
		_, exists := serverTunnels[k]
		if !exists {
			log.Println("Kill tunnel", k)
			c.cancelFuncsMutex.Lock()
			c.cancelFuncs[k]()
			c.cancelFuncsMutex.Unlock()

			delete(c.cancelFuncs, k)
			delete(c.tunnels, k)
		}
	}
}

// boreTunnelWithRetry keeps re-dialing a tunnel whenever BoreTunnel returns because its
// SSH connection died on its own (network blip, silently-dropped connection, sshd
// restart) rather than because the tunnel was changed/removed -- SyncTunnels only
// re-invokes BoreTunnel on the latter, so without this a dead connection would
// otherwise sit unreachable until the tunnel's config happens to change. Same
// exponential-backoff shape as retryCertUntilSuccess below; stops as soon as ctx is
// cancelled, i.e. exactly when SyncTunnels would have re-bored it anyway.
func (c *Agent) boreTunnelWithRetry(ctx context.Context, tunnel Tunnel) {
	backoff := tunnelReboreBaseInterval
	for {
		start := time.Now()
		err := c.BoreTunnel(ctx, tunnel)
		if ctx.Err() != nil {
			return
		}

		log.Printf("BoreTunnel %s lost, retrying in %s: %v", tunnel.Domain, backoff, err)

		// A tunnel that stayed up for a while before dying gets a fresh backoff
		// rather than compounding off however long a previous crash loop took.
		if time.Since(start) > tunnelReboreMaxInterval {
			backoff = tunnelReboreBaseInterval
		}

		select {
		case <-ctx.Done():
			return
		case <-time.After(backoff):
		}

		backoff *= 2
		if backoff > tunnelReboreMaxInterval {
			backoff = tunnelReboreMaxInterval
		}
	}
}

func (c *Agent) BoreTunnel(ctx context.Context, tunnel Tunnel) error {

	log.Println("BoreTunnel", tunnel.Domain)

	signer, err := ssh.ParsePrivateKey([]byte(tunnel.TunnelPrivateKey))
	if err != nil {
		return fmt.Errorf("Unable to parse private key: %v", err)
	}

	//var hostKey ssh.PublicKey

	config := &ssh.ClientConfig{
		User: tunnel.Username,
		Auth: []ssh.AuthMethod{
			ssh.PublicKeys(signer),
		},
		//HostKeyCallback: ssh.FixedHostKey(hostKey),
		HostKeyCallback: ssh.InsecureIgnoreHostKey(),
	}

	sshHost := fmt.Sprintf("%s:%d", tunnel.ServerAddress, tunnel.ServerPort)

	// Acquired for the connect+handshake phase only -- released right after
	// ssh.NewClientConn returns, below, regardless of outcome. See maxConcurrentBores.
	c.boreSem <- struct{}{}

	var netConn net.Conn
	if tunnel.SshTls {
		// Stealth mode: disguise this SSH connection as an HTTPS request to the admin
		// domain -- a real TLS handshake against its certmagic-managed cert, marked with
		// a custom ALPN protocol ID the server (Server.handleConnection) uses to route it
		// to the real sshd instead of the ordinary HTTP path. InsecureSkipVerify is safe
		// here: TLS is only a disguise layer, not the trust boundary -- the SSH handshake
		// right below still authenticates with this tunnel's own ephemeral keypair.
		netConn, err = tls.Dial("tcp", sshHost, &tls.Config{
			ServerName:         tunnel.ServerAddress,
			InsecureSkipVerify: true,
			NextProtos:         []string{stealthSshAlpn},
		})
	} else {
		netConn, err = net.Dial("tcp", sshHost)
	}
	if err != nil {
		<-c.boreSem
		return fmt.Errorf("Failed to dial: %v", err)
	}

	sshConn, chans, reqs, err := ssh.NewClientConn(netConn, sshHost, config)
	<-c.boreSem
	if err != nil {
		return fmt.Errorf("Failed to establish SSH connection: %v", err)
	}
	client := ssh.NewClient(sshConn, chans, reqs)
	defer client.Close()

	// connDead is how any part of this tunnel's plumbing (keepalive below, the accept
	// loop, or the client-mode HTTP server further down) reports that the underlying
	// SSH connection is gone, so the final select can return promptly instead of
	// blocking on ctx.Done() forever -- see reportDead's callers.
	connDead := make(chan error, 1)
	reportDead := func(err error) {
		select {
		case connDead <- err:
		default:
		}
	}

	go func() {
		ticker := time.NewTicker(sshKeepaliveInterval)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				replied := make(chan error, 1)
				go func() {
					_, _, err := client.SendRequest("keepalive@openssh.com", true, nil)
					replied <- err
				}()
				select {
				case err := <-replied:
					if err != nil {
						reportDead(fmt.Errorf("keepalive failed: %v", err))
						return
					}
				case <-time.After(sshKeepaliveTimeout):
					reportDead(fmt.Errorf("keepalive timed out"))
					return
				case <-ctx.Done():
					return
				}
			}
		}
	}()

	bindAddr := "127.0.0.1"
	if tunnel.AllowExternalTcp {
		bindAddr = "0.0.0.0"
	}
	tunnelAddr := fmt.Sprintf("%s:%d", bindAddr, tunnel.TunnelPort)
	listener, err := client.Listen("tcp", tunnelAddr)
	if err != nil {
		return fmt.Errorf("Unable to register tcp forward for %s:%d %v", bindAddr, tunnel.TunnelPort, err)
	}
	defer listener.Close()

	// certPending tracks whether this tunnel's own Let's Encrypt certificate (obtained below) is
	// still outstanding, so getCertificate can serve a temporary self-signed certificate in the
	// meantime instead of failing the TLS handshake outright -- same mechanism the server uses,
	// just backed by a per-tunnel flag here since the agent has no shared tunnel DB to query.
	certPending := &atomic.Bool{}
	getCertificate := withSelfSignedFallback(c.getCertConfig(tunnel.TlsTermination),
		func(string) certFallback {
			if certPending.Load() {
				return certFallbackSelfSigned
			}
			return certFallbackNone
		}, c.selfSignedCerts)

	if tunnel.TlsTermination == "client" {

		tlsConfig := &tls.Config{
			GetCertificate: getCertificate,
			NextProtos:     []string{"h2", "acme-tls/1"},
		}
		tlsListener := tls.NewListener(listener, tlsConfig)

		httpMux := http.NewServeMux()

		httpMux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
			proxyRequest(w, r, tunnel, c.httpClient, tunnel.ClientAddress, tunnel.ClientPort, upstreamErrorDefault)
		})

		httpServer := &http.Server{
			Handler: httpMux,
		}

		// TODO: It seems inefficient to make a separate HTTP server for each TLS-passthrough tunnel,
		// but the code is much simpler. The only alternative I've thought of so far involves storing
		// all the tunnels in a mutexed map and retrieving them from a single HTTP server, same as the
		// boringproxy server does.
		go func() {
			err := httpServer.Serve(tlsListener)
			reportDead(err)
		}()

	} else {

		go func() {
			for {
				conn, err := listener.Accept()
				if err != nil {
					// The SSH-forwarded listener only ever errors out when the
					// underlying connection is gone (agent-initiated close on ctx
					// cancellation, or the connection died) -- report it so the
					// final select below returns promptly instead of relying on
					// the keepalive to notice separately.
					reportDead(err)
					break
					//continue
				}

				var useTls bool
				if tunnel.TlsTermination == "client-tls" {
					useTls = true
				} else {
					useTls = false
				}

				go ProxyTcp(conn, tunnel.ClientAddress, tunnel.ClientPort, useTls, getCertificate, tunnel.Domain, []string{"http/1.1", "h2", "acme-tls/1"})
			}
		}()
	}

	if tunnel.TlsTermination == "client" || tunnel.TlsTermination == "client-tls" {
		err = c.getCertConfig(tunnel.TlsTermination).ManageSync(ctx, []string{tunnel.Domain})
		certPending.Store(err != nil)
		if err != nil {
			log.Printf("CertMagic error for %s, will keep retrying in the background\n", tunnel.Domain)
			log.Println(err)
			go c.retryCertUntilSuccess(ctx, tunnel.Domain, certPending)
		}
	}

	select {
	case <-ctx.Done():
		return nil
	case err := <-connDead:
		return fmt.Errorf("ssh connection lost: %v", err)
	}
}

// retryCertUntilSuccess retries certificate issuance for domain with the same exponential
// backoff the server uses (certRetryBaseInterval/certRetryMaxInterval, tunnel_manager.go),
// since certmagic itself has no memory of Let's Encrypt's own rate-limit windows. Stops as soon
// as ctx is cancelled, i.e. when BoreTunnel's tunnel is removed or changed.
func (c *Agent) retryCertUntilSuccess(ctx context.Context, domain string, pending *atomic.Bool) {
	backoff := certRetryBaseInterval
	timer := time.NewTimer(backoff)
	defer timer.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-timer.C:
		}

		if err := c.certConfig.ManageSync(context.Background(), []string{domain}); err == nil {
			pending.Store(false)
			c.selfSignedCerts.Forget(domain)
			log.Printf("CertMagic: successfully obtained certificate for %s after retrying\n", domain)
			return
		}

		backoff *= 2
		if backoff > certRetryMaxInterval {
			backoff = certRetryMaxInterval
		}
		log.Printf("CertMagic: retry failed for %s, next attempt in %s\n", domain, backoff)
		timer.Reset(backoff)
	}
}

func printJson(data interface{}) {
	d, _ := json.MarshalIndent(data, "", "  ")
	fmt.Println(string(d))
}
