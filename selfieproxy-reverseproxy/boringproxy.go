package boringproxy

import (
	"bufio"
	"context"
	"crypto/tls"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/caddyserver/certmagic"
	"github.com/mdp/qrterminal/v3"

	"github.com/takingnames/namedrop-go"
)

type Config struct {
	SshServerPort  int    `json:"ssh_server_port"`
	PublicIp       string `json:"public_ip"`
	namedropClient *namedrop.Client
	autoCerts      bool
}

type SmtpConfig struct {
	Server   string
	Port     int
	Username string
	Password string
}

type Server struct {
	db           *Database
	tunMan       *TunnelManager
	httpClient   *http.Client
	httpListener *PassthroughListener
}

func Listen() {
	flagSet := flag.NewFlagSet(os.Args[0], flag.ExitOnError)
	newAdminDomain := flagSet.String("admin-domain", "", "Admin Domain")
	portalDomain := flagSet.String("portal-domain", "", "Domain reverse-proxied directly to -portal-port on localhost, without a Tunnel/Agent (eg. Selfie Proxy's own admin portal, which must be reachable before any agent exists)")
	portalPort := flagSet.Int("portal-port", 0, "Local port -portal-domain is proxied to")
	ssoDomain := flagSet.String("sso-domain", "", "Domain reverse-proxied directly to -sso-port on localhost, without a Tunnel/Agent (Selfie Proxy's bundled OIDC IdP, selfieproxy-sso-server, which must be reachable before any agent exists)")
	ssoPort := flagSet.Int("sso-port", 0, "Local port -sso-domain is proxied to")
	consoleDomain := flagSet.String("console-domain", "", "Domain reverse-proxied directly to -console-port on localhost, without a Tunnel/Agent (Selfie Proxy's browser SSH/RDP/VNC console, selfieproxy-remote-console) -- always gated behind single sign on, like -portal-domain")
	consolePort := flagSet.Int("console-port", 0, "Local port -console-domain is proxied to")
	oidcIssuer := flagSet.String("oidc-issuer", "", "OIDC issuer URL boringproxy authenticates the portal domain and any tunnel protected with single sign on against; blank disables single sign on entirely")
	oidcClientId := flagSet.String("oidc-client-id", "", "OIDC client ID boringproxy registers as with -oidc-issuer")
	oidcClientSecret := flagSet.String("oidc-client-secret", "", "OIDC client secret, if the issuer requires one (blank for the bundled selfieproxy-sso-server, which trusts PKCE alone)")
	ssoIdleMinutes := flagSet.Int("sso-idle-minutes", 30, "Minutes of inactivity before a single sign on session (portal domain or a tunnel protected with single sign on) expires; refreshed on every request")
	ssoMaxMinutes := flagSet.Int("sso-max-minutes", 600, "Absolute maximum minutes a single sign on session stays valid, regardless of activity")
	sshServerPort := flagSet.Int("ssh-server-port", 22, "SSH Server Port")
	dbDir := flagSet.String("db-dir", "", "Database file directory")
	certDir := flagSet.String("cert-dir", "", "TLS cert directory")
	runtimeDir := flagSet.String("runtime-dir", "", "Directory for ephemeral internal-only files (eg. the internal REST token)")
	printLogin := flagSet.Bool("print-login", false, "Prints admin login information")
	httpPort := flagSet.Int("http-port", 80, "HTTP (insecure) port")
	httpsPort := flagSet.Int("https-port", 443, "HTTPS (secure) port")
	allowHttp := flagSet.Bool("allow-http", false, "Allow unencrypted (HTTP) requests")
	publicIp := flagSet.String("public-ip", "", "Public IP")
	behindProxy := flagSet.Bool("behind-proxy", false, "Whether we're running behind another reverse proxy")
	acmeEmail := flagSet.String("acme-email", "", "Email for ACME (ie Let's Encrypt)")
	acmeUseStaging := flagSet.Bool("acme-use-staging", false, "Use ACME (ie Let's Encrypt) staging servers")
	acceptCATerms := flagSet.Bool("accept-ca-terms", false, "Automatically accept CA terms")
	acmeCa := flagSet.String("acme-certificate-authority", "", "URI for ACME Certificate Authority")
	debug := flagSet.Bool("debug", false, "Log every request (timestamp, remote IP, method, host, path) to stdout")
	err := flagSet.Parse(os.Args[2:])
	if err != nil {
		fmt.Fprintf(os.Stderr, "%s: parsing flags: %s\n", os.Args[0], err)
	}

	log.Println("Starting up")

	db, err := NewDatabase(*dbDir)
	if err != nil {
		log.Fatal(err)
	}

	namedropClient := namedrop.NewClient(db, db.GetAdminDomain(), "takingnames.io/namedrop")

	var ip string

	if *publicIp != "" {
		ip = *publicIp
	} else {
		ip, err = namedropClient.GetPublicIp("tcp")
		if err != nil {
			fmt.Printf("WARNING: Failed to determine public IP: %s\n", err.Error())
		}
	}

	err = namedrop.CheckPublicAddress(ip, *httpPort)
	if err != nil {
		fmt.Printf("WARNING: Failed to access %s:%d from the internet\n", ip, *httpPort)
	}

	err = namedrop.CheckPublicAddress(ip, *httpsPort)
	if err != nil {
		fmt.Printf("WARNING: Failed to access %s:%d from the internet\n", ip, *httpsPort)
	}

	autoCerts := true
	if *httpPort != 80 || *httpsPort != 443 {
		fmt.Printf("WARNING: LetsEncrypt only supports HTTP/HTTPS ports 80/443. You are using %d/%d. Disabling automatic certificate management\n", *httpPort, *httpsPort)
		autoCerts = false
	}

	if *certDir != "" {
		certmagic.Default.Storage = &certmagic.FileStorage{*certDir}
	}
	//certmagic.DefaultACME.DisableHTTPChallenge = true
	//certmagic.DefaultACME.DisableTLSALPNChallenge = true

	if *acmeEmail != "" {
		certmagic.DefaultACME.Email = *acmeEmail
	}

	if *acceptCATerms {
		certmagic.DefaultACME.Agreed = true
		log.Print(fmt.Sprintf("Automatic agreement to CA terms with email (%s)", *acmeEmail))
	}

	if *acmeUseStaging {
		certmagic.DefaultACME.CA = certmagic.LetsEncryptStagingCA
	}

	if *acmeCa != "" {
		certmagic.DefaultACME.CA = *acmeCa
	}

	certConfig := certmagic.NewDefault()

	if *newAdminDomain != "" {
		db.SetAdminDomain(*newAdminDomain)
	}

	adminDomain := db.GetAdminDomain()

	if adminDomain == "" {

		err = setAdminDomain(certConfig, db, namedropClient, autoCerts)
		if err != nil {
			log.Fatal(err)
		}
	} else {
		if autoCerts {
			err = certConfig.ManageSync(context.Background(), []string{adminDomain})
			if err != nil {
				log.Fatal(err)
			}
			log.Print(fmt.Sprintf("Successfully acquired certificate for admin domain (%s)", adminDomain))
		}
	}

	if *portalDomain != "" && autoCerts {
		err = certConfig.ManageSync(context.Background(), []string{*portalDomain})
		if err != nil {
			log.Fatal(err)
		}
		log.Print(fmt.Sprintf("Successfully acquired certificate for portal domain (%s)", *portalDomain))
	}

	if *ssoDomain != "" && autoCerts {
		err = certConfig.ManageSync(context.Background(), []string{*ssoDomain})
		if err != nil {
			log.Fatal(err)
		}
		log.Print(fmt.Sprintf("Successfully acquired certificate for sso domain (%s)", *ssoDomain))
	}

	if *consoleDomain != "" && autoCerts {
		err = certConfig.ManageSync(context.Background(), []string{*consoleDomain})
		if err != nil {
			log.Fatal(err)
		}
		log.Print(fmt.Sprintf("Successfully acquired certificate for console domain (%s)", *consoleDomain))
	}

	StartOidcAuth(*oidcIssuer, *oidcClientId, *oidcClientSecret, adminDomain, *portalDomain, *consoleDomain,
		time.Duration(*ssoIdleMinutes)*time.Minute, time.Duration(*ssoMaxMinutes)*time.Minute)

	// Add admin user if it doesn't already exist
	users := db.GetUsers()
	if len(users) == 0 {
		db.AddUser("admin", true)
		_, err := db.AddToken("admin", "")
		if err != nil {
			log.Fatal("Failed to initialize admin user")
		}

	}

	if *printLogin {
		for token, tokenData := range db.GetTokens() {
			if tokenData.Owner == "admin" && tokenData.Agent == "" {
				printLoginInfo(token, db.GetAdminDomain(), *httpsPort)
				break
			}
		}
	}

	config := &Config{
		SshServerPort:  *sshServerPort,
		PublicIp:       ip,
		namedropClient: namedropClient,
		autoCerts:      autoCerts,
	}

	selfSignedCerts := NewSelfSignedCertProvider()
	tunMan := NewTunnelManager(config, db, certConfig, selfSignedCerts)

	auth := NewAuth(db)

	api := NewApi(config, db, auth, tunMan)

	internalToken, err := genRandomCode(32)
	if err != nil {
		log.Fatal("Failed to generate internal REST token")
	}
	if *runtimeDir != "" {
		tokenPath := filepath.Join(*runtimeDir, "internal_rest_token")
		err = os.WriteFile(tokenPath, []byte(internalToken), 0600)
		if err != nil {
			log.Fatalf("Failed to write internal REST token to %s: %s", tokenPath, err)
		}
	}

	restApi := NewRestApi(config, db, auth, api, internalToken)

	httpClient := &http.Client{
		// Don't follow redirects
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}

	httpListener := NewPassthroughListener()

	p := &Server{db, tunMan, httpClient, httpListener}

	getCertificate := withSelfSignedFallback(certConfig, tunMan.IsCertPending, selfSignedCerts)

	tlsConfig := &tls.Config{
		GetCertificate: getCertificate,
		NextProtos:     []string{"h2", "acme-tls/1"},
		// The console domain (selfieproxy-remote-console) is the only place in this
		// product that ever needs a browser WebSocket -- proxyWebsocket (http_proxy.go)
		// hijacks the raw TCP connection and only understands classic HTTP/1.1 Upgrade
		// semantics. Over HTTP/2, modern browsers instead try to bootstrap a WebSocket
		// via RFC 8441 extended CONNECT, which Go's stdlib HTTP/2 server doesn't support --
		// the request gets rejected at the HTTP/2 framing layer before it ever reaches
		// this process's own handler, so the browser just hangs with no visible error and
		// nothing is logged. Advertising only "http/1.1" over ALPN for this one domain
		// forces the browser to negotiate classic HTTP/1.1, where the existing Upgrade/
		// hijack proxying already works correctly -- every other domain (portal, admin,
		// ordinary tunnels) is untouched and keeps h2.
		GetConfigForClient: func(hello *tls.ClientHelloInfo) (*tls.Config, error) {
			if *consoleDomain != "" && hello.ServerName == *consoleDomain {
				return &tls.Config{
					GetCertificate: getCertificate,
					NextProtos:     []string{"http/1.1", "acme-tls/1"},
				}, nil
			}
			return nil, nil
		},
	}
	tlsListener := tls.NewListener(httpListener, tlsConfig)

	http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		remoteIp, _, err := net.SplitHostPort(r.RemoteAddr)
		if err != nil {
			w.WriteHeader(500)
			io.WriteString(w, err.Error())
			return
		}
		if *debug {
			timestamp := time.Now().Format(time.RFC3339)
			fmt.Println(fmt.Sprintf("%s %s %s %s %s", timestamp, remoteIp, r.Method, r.Host, r.URL.Path))
		}

		hostParts := strings.Split(r.Host, ":")
		hostDomain := hostParts[0]

		if r.URL.Path == "/oidc/logout" {
			HandleLogout(w, r, *ssoDomain)
			return
		}

		if r.URL.Path == "/namedrop/callback" {
			r.ParseForm()

			errorParam := r.Form.Get("error")
			requestId := r.Form.Get("state")
			code := r.Form.Get("code")

			if errorParam != "" {
				db.DeleteDNSRequest(requestId)

				http.Redirect(w, r, "/alert?message=Domain request failed", 303)
				return
			}

			namedropTokenData, err := namedropClient.GetToken(requestId, code)
			if err != nil {
				w.WriteHeader(500)
				io.WriteString(w, err.Error())
				return
			}

			domain := namedropTokenData.Scopes[0].Domain
			host := namedropTokenData.Scopes[0].Host

			recordType := "AAAA"
			if IsIPv4(config.PublicIp) {
				recordType = "A"
			}

			createRecordReq := namedrop.Record{
				Domain: domain,
				Host:   host,
				Type:   recordType,
				Value:  config.PublicIp,
				TTL:    300,
			}

			err = namedropClient.CreateRecord(createRecordReq)
			if err != nil {
				w.WriteHeader(500)
				io.WriteString(w, err.Error())
				return
			}

			fqdn := host + "." + domain

			if db.GetAdminDomain() == "" {
				db.SetAdminDomain(fqdn)
				namedropClient.SetDomain(fqdn)

				if autoCerts {
					// TODO: Might want to get all certs here, not just the admin domain
					err := certConfig.ManageSync(r.Context(), []string{fqdn})
					if err != nil {
						log.Fatal(err)
					}
				}

				url := fmt.Sprintf("https://%s", fqdn)

				// Automatically log using the first found admin token. This is safe to do here
				// because we know that retrieving the admin domain was initiated from the CLI.
				tokens := db.GetTokens()
				for token, tokenData := range tokens {
					if tokenData.Owner == "admin" {
						url = url + "/login?access_token=" + token
						break
					}
				}

				http.Redirect(w, r, url, 303)
			} else {
				adminDomain := db.GetAdminDomain()
				http.Redirect(w, r, fmt.Sprintf("https://%s/edit-tunnel?domain=%s", adminDomain, fqdn), 303)
			}

		} else if hostDomain == db.GetAdminDomain() {
			if strings.HasPrefix(r.URL.Path, "/api/") {
				http.StripPrefix("/api", api).ServeHTTP(w, r)
			} else if strings.HasPrefix(r.URL.Path, "/rest/") {
				http.StripPrefix("/rest", restApi).ServeHTTP(w, r)
			} else if r.URL.Path == "/oidc/authorize" {
				oidcAuth := oidcAuthHolder.Load()
				if oidcAuth == nil {
					w.WriteHeader(http.StatusServiceUnavailable)
					io.WriteString(w, "Selfieproxy is starting... please retry shortly")
					return
				}
				oidcAuth.HandleAuthorize(w, r)
			} else if r.URL.Path == "/oidc/callback" {
				oidcAuth := oidcAuthHolder.Load()
				if oidcAuth == nil {
					w.WriteHeader(http.StatusServiceUnavailable)
					io.WriteString(w, "Selfieproxy is starting... please retry shortly")
					return
				}
				oidcAuth.HandleCallback(w, r)
			} else {
				// Legacy boringproxy web UI (ui_handler.go), superseded by
				// Selfie Proxy's own admin portal (reached via
				// -portal-domain instead). Disabled at the routing layer
				// rather than removed, since /api/ and /rest/ above still
				// depend on the surrounding admin-domain plumbing.
				w.WriteHeader(http.StatusForbidden)
				io.WriteString(w, "The boringproxy web UI has been disabled. Use the Selfie Proxy admin portal instead.")
			}
		} else if *portalDomain != "" && hostDomain == *portalDomain {
			// Proxied directly to a fixed local address, not through a
			// Tunnel/Agent -- this must work before any agent is ever
			// connected, since it's how the portal used to set one up.
			// Always gated behind single sign on (unlike ordinary tunnels, where
			// it's opt-in via SsoProtected): the portal has no login of its own left,
			// see selfieproxy-portal's SessionInterceptor.
			portalTunnel := Tunnel{Domain: *portalDomain}
			if !requireSsoIfNeeded(w, r, portalTunnel.Domain, true) {
				return
			}
			r.Header.Set("X-Selfieproxy-Sso-Verified", "true")
			proxyRequest(w, r, portalTunnel, httpClient, "localhost", *portalPort, *behindProxy)
		} else if *ssoDomain != "" && hostDomain == *ssoDomain {
			// Proxied directly to selfieproxy-sso-server, same
			// before-any-agent-exists carve-out as -portal-domain. Never gated
			// behind single sign on itself -- it's the IdP the gate calls out to.
			ssoTunnel := Tunnel{Domain: *ssoDomain}
			proxyRequest(w, r, ssoTunnel, httpClient, "localhost", *ssoPort, *behindProxy)
		} else if *consoleDomain != "" && hostDomain == *consoleDomain {
			// Proxied directly to selfieproxy-remote-console, same carve-out
			// shape as -portal-domain (fixed local address, no Tunnel/Agent) --
			// always gated behind single sign on, admin-only just like the portal
			// domain (see oidc_auth.go's is_admin check), since the browser
			// SSH/RDP/VNC console it serves is Homelab-management tooling, not
			// something a login-only User should ever reach.
			consoleTunnel := Tunnel{Domain: *consoleDomain}
			if !requireSsoIfNeeded(w, r, consoleTunnel.Domain, true) {
				return
			}
			r.Header.Set("X-Selfieproxy-Sso-Verified", "true")
			proxyRequest(w, r, consoleTunnel, httpClient, "localhost", *consolePort, *behindProxy)
		} else {

			tunnel, exists := db.GetTunnel(hostDomain)
			if !exists {
				errMessage := fmt.Sprintf("No tunnel attached to %s", hostDomain)
				w.WriteHeader(500)
				io.WriteString(w, errMessage)
				return
			}

			if !requireSsoIfNeeded(w, r, tunnel.Domain, tunnel.SsoProtected) {
				return
			}

			proxyRequest(w, r, tunnel, httpClient, "127.0.0.1", tunnel.TunnelPort, *behindProxy)
		}
	})

	go func() {

		if *allowHttp {
			if err := http.ListenAndServe(fmt.Sprintf(":%d", *httpPort), nil); err != nil {
				log.Fatalf("ListenAndServe error: %v", err)
			}
		} else {
			redirectTLS := func(w http.ResponseWriter, r *http.Request) {
				url := fmt.Sprintf("https://%s:%d%s", r.Host, *httpsPort, r.RequestURI)
				http.Redirect(w, r, url, http.StatusMovedPermanently)
			}

			if err := http.ListenAndServe(fmt.Sprintf(":%d", *httpPort), http.HandlerFunc(redirectTLS)); err != nil {
				log.Fatalf("ListenAndServe error: %v", err)
			}
		}

	}()

	go http.Serve(tlsListener, nil)

	listener, err := net.Listen("tcp", fmt.Sprintf(":%d", *httpsPort))
	if err != nil {
		log.Fatal(err)
	}

	log.Println("Ready")

	for {
		conn, err := listener.Accept()
		if err != nil {
			log.Print(err)
			continue
		}

		go p.handleConnection(conn, getCertificate)
	}
}

func (p *Server) handleConnection(clientConn net.Conn, getCertificate func(*tls.ClientHelloInfo) (*tls.Certificate, error)) {

	clientHello, clientReader, err := peekClientHello(clientConn)
	if err != nil {
		log.Println("peekClientHello error", err)
		return
	}

	passConn := NewProxyConn(clientConn, clientReader)

	tunnel, exists := p.db.GetTunnel(clientHello.ServerName)

	if exists && (tunnel.TlsTermination == "client" || tunnel.TlsTermination == "passthrough") || tunnel.TlsTermination == "client-tls" {
		p.passthroughRequest(passConn, tunnel)
	} else if exists && tunnel.TlsTermination == "server-tls" {
		useTls := true
		err := ProxyTcp(passConn, "127.0.0.1", tunnel.TunnelPort, useTls, getCertificate, tunnel.Domain)
		if err != nil {
			log.Println(err.Error())
			return
		}
	} else {
		p.httpListener.PassConn(passConn)
	}
}

func (p *Server) passthroughRequest(conn net.Conn, tunnel Tunnel) {

	upstreamAddr := fmt.Sprintf("127.0.0.1:%d", tunnel.TunnelPort)
	upstreamConn, err := net.Dial("tcp", upstreamAddr)

	if err != nil {
		log.Print(err)
		return
	}
	defer upstreamConn.Close()

	var wg sync.WaitGroup
	wg.Add(2)

	go func() {
		io.Copy(conn, upstreamConn)
		conn.(*ProxyConn).CloseWrite()
		wg.Done()
	}()
	go func() {
		io.Copy(upstreamConn, conn)
		upstreamConn.(*net.TCPConn).CloseWrite()
		wg.Done()
	}()

	wg.Wait()
}

func setAdminDomain(certConfig *certmagic.Config, db *Database, namedropClient *namedrop.Client, autoCerts bool) error {
	action := prompt("\nNo admin domain set. Select an option below:\nEnter '1' to input manually\nEnter '2' to configure through TakingNames.io\n")
	switch action {
	case "1":
		adminDomain := prompt("\nEnter admin domain:\n")

		if autoCerts {
			err := certConfig.ManageSync(context.Background(), []string{adminDomain})
			if err != nil {
				log.Fatal(err)
			}
		}

		db.SetAdminDomain(adminDomain)
	case "2":

		log.Println("Get bootstrap domain")

		namedropLink, err := namedropClient.BootstrapLink()
		if err != nil {
			log.Fatal(err)
		}

		qrterminal.GenerateHalfBlock(namedropLink, qrterminal.L, os.Stdout)
		fmt.Println("Use the link below or scan the QR code above to select an admin domain:\n")
		fmt.Printf("%s\n\n", namedropLink)

	default:
		log.Fatal("Invalid option")
	}

	return nil
}

func prompt(promptText string) string {
	reader := bufio.NewReader(os.Stdin)
	fmt.Print(promptText)
	text, _ := reader.ReadString('\n')
	return strings.TrimSpace(text)
}

func printLoginInfo(token, adminDomain string, httpsPort int) {
	var url string
	if httpsPort != 443 {
		url = fmt.Sprintf("https://%s:%d/login?access_token=%s", adminDomain, httpsPort, token)
	} else {
		url = fmt.Sprintf("https://%s/login?access_token=%s", adminDomain, token)
	}
	log.Println(fmt.Sprintf("Admin login link: %s", url))
	qrterminal.GenerateHalfBlock(url, qrterminal.L, os.Stdout)
}

// Taken from https://stackoverflow.com/a/48519490/943814
func IsIPv4(address string) bool {
	return strings.Count(address, ":") < 2
}
