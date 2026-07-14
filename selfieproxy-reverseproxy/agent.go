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
	selfSignedCerts  *SelfSignedCertProvider
	behindProxy      bool
	pollInterval     int
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
	BehindProxy    bool   `json:"behindProxy,omitempty"`
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

	certConfig := certmagic.NewDefault()

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
		certConfig:       certConfig,
		selfSignedCerts:  NewSelfSignedCertProvider(),
		behindProxy:      config.BehindProxy,
		pollInterval:     config.PollInterval,
	}, nil
}

func (c *Agent) Run(ctx context.Context) error {

	url := fmt.Sprintf("https://%s/api/agents/?agent-name=%s", c.server, c.agentName)
	if c.user != "" {
		url = url + "&user=" + c.user
	}

	agentReq, err := http.NewRequest("POST", url, nil)
	if err != nil {
		return fmt.Errorf("Failed to create request for URL %s", url)
	}
	if len(c.secret) > 0 {
		agentReq.Header.Add("Authorization", "bearer "+c.secret)
	}
	resp, err := c.httpClient.Do(agentReq)
	if err != nil {
		return fmt.Errorf("Failed to register agent. Ensure the server is running. URL: %s", url)
	}
	defer resp.Body.Close()

	if resp.StatusCode != 200 {
		body, err := ioutil.ReadAll(resp.Body)
		if err != nil {
			return fmt.Errorf("Failed to register agent. HTTP Status code: %d. Failed to read body", resp.StatusCode)
		}

		msg := string(body)
		return fmt.Errorf("Failed to register agent. Are the agent name ('%s') and secret correct? HTTP Status code: %d. Message: %s", c.agentName, resp.StatusCode, msg)
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

func (c *Agent) PollTunnels(ctx context.Context) error {

	//log.Println("PollTunnels")

	url := fmt.Sprintf("https://%s/api/tunnels?agent-name=%s", c.server, c.agentName)

	listenReq, err := http.NewRequest("GET", url, nil)
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

			go func(closureCtx context.Context, tun Tunnel) {
				err := c.BoreTunnel(closureCtx, tun)
				if err != nil {
					log.Println("BoreTunnel error: ", err)
				}
			}(cancelCtx, newTun)
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
	client, err := ssh.Dial("tcp", sshHost, config)
	if err != nil {
		return fmt.Errorf("Failed to dial: %v", err)
	}
	defer client.Close()

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
	getCertificate := withSelfSignedFallback(c.certConfig,
		func(string) bool { return certPending.Load() }, c.selfSignedCerts)

	if tunnel.TlsTermination == "client" {

		tlsConfig := &tls.Config{
			GetCertificate: getCertificate,
			NextProtos:     []string{"h2", "acme-tls/1"},
		}
		tlsListener := tls.NewListener(listener, tlsConfig)

		httpMux := http.NewServeMux()

		httpMux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
			proxyRequest(w, r, tunnel, c.httpClient, tunnel.ClientAddress, tunnel.ClientPort, c.behindProxy)
		})

		httpServer := &http.Server{
			Handler: httpMux,
		}

		// TODO: It seems inefficient to make a separate HTTP server for each TLS-passthrough tunnel,
		// but the code is much simpler. The only alternative I've thought of so far involves storing
		// all the tunnels in a mutexed map and retrieving them from a single HTTP server, same as the
		// boringproxy server does.
		go httpServer.Serve(tlsListener)

	} else {

		go func() {
			for {
				conn, err := listener.Accept()
				if err != nil {
					// TODO: Currently assuming an error means the
					// tunnel was manually deleted, but there
					// could be other errors that we should be
					// attempting to recover from rather than
					// breaking.
					break
					//continue
				}

				var useTls bool
				if tunnel.TlsTermination == "client-tls" {
					useTls = true
				} else {
					useTls = false
				}

				go ProxyTcp(conn, tunnel.ClientAddress, tunnel.ClientPort, useTls, getCertificate)
			}
		}()
	}

	if tunnel.TlsTermination == "client" || tunnel.TlsTermination == "client-tls" {
		err = c.certConfig.ManageSync(ctx, []string{tunnel.Domain})
		certPending.Store(err != nil)
		if err != nil {
			log.Printf("CertMagic error for %s, will keep retrying in the background\n", tunnel.Domain)
			log.Println(err)
			go c.retryCertUntilSuccess(ctx, tunnel.Domain, certPending)
		}
	}

	<-ctx.Done()

	return nil
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
