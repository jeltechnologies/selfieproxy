package boringproxy

import (
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"strings"
	"time"
)

// upstreamErrorMode controls how proxyRequest reports a failure to reach address:port -- the
// same function backs the portal/sso/console synthetic-tunnel proxies (a local Selfie Proxy
// service that may still be starting up) and ordinary Web Application tunnels (a homelab agent
// that may be disconnected, or whose own dial to the real backend failed -- see tls_proxy.go's
// handleConnection, which closes the tunnel connection with no response on a dial failure,
// surfacing here as the same httpClient.Do error as an agent that's simply not connected).
// Distinguishing the two lets each report a response its own audience can act on, without ever
// exposing the raw dial error (which, for a tunnel, would otherwise reveal the homelab's internal
// address -- see ExposedApp's host field in selfieproxy-portal).
type upstreamErrorMode int

const (
	// upstreamErrorDefault reports the raw error with a 502, e.g. for the admin API/other
	// internal callers where the detail is useful and there's no untrusted end-user audience.
	upstreamErrorDefault upstreamErrorMode = iota
	// upstreamErrorStartingUp reports 200 so a portal/sso/console page's own retry-on-200
	// behavior (rather than treating a non-200 as fatal) can quietly recover once that
	// service finishes booting.
	upstreamErrorStartingUp
	// upstreamErrorAgentUnreachable reports 404 for an ordinary Web Application tunnel whose
	// agent is disconnected, or whose own dial to the homelab backend failed.
	upstreamErrorAgentUnreachable
)

func proxyRequest(w http.ResponseWriter, r *http.Request, tunnel Tunnel, httpClient *http.Client, address string, port int, behindProxy bool, errorMode upstreamErrorMode) {

	if tunnel.AuthUsername != "" || tunnel.AuthPassword != "" {
		username, password, ok := r.BasicAuth()
		if !ok {
			w.Header()["WWW-Authenticate"] = []string{"Basic"}
			w.WriteHeader(401)
			return
		}

		if username != tunnel.AuthUsername || password != tunnel.AuthPassword {
			w.Header()["WWW-Authenticate"] = []string{"Basic"}
			w.WriteHeader(401)
			// TODO: should probably use a better form of rate limiting
			time.Sleep(2 * time.Second)
			return
		}
	}

	if isWebsocketUpgrade(r) {
		proxyWebsocket(w, r, tunnel, address, port, behindProxy)
		return
	}

	downstreamReqHeaders := r.Header.Clone()

	upstreamScheme := "http"
	upstreamHost := address
	if strings.HasPrefix(address, "https://") {
		upstreamScheme = "https"
		upstreamHost = address[len("https://"):]
	}

	upstreamAddr := fmt.Sprintf("%s:%d", upstreamHost, port)
	upstreamUrl := fmt.Sprintf("%s://%s%s", upstreamScheme, upstreamAddr, r.URL.RequestURI())

	upstreamReq, err := http.NewRequest(r.Method, upstreamUrl, r.Body)
	if err != nil {
		errMessage := fmt.Sprintf("%s", err)
		w.WriteHeader(500)
		io.WriteString(w, errMessage)
		return
	}

	// ContentLength needs to be set manually because otherwise it is
	// stripped by golang. See:
	// https://golang.org/pkg/net/http/#Request.Write
	upstreamReq.ContentLength = r.ContentLength

	// Fix for Proxmox. http.NewRequest only infers a known length from a
	// handful of concrete reader types (*bytes.Buffer/*bytes.Reader/*strings.Reader);
	// r.Body from an incoming request is none of those, so it stays a
	// real (non-nil, non-NoBody) reader even for a zero-length body. Deep
	// in net/http, Request.outgoingLength() treats "ContentLength == 0
	// with a real Body reader" as *unknown* length rather than zero, and
	// falls back to Transfer-Encoding: chunked when writing the request --
	// invisibly, since it doesn't touch ContentLength or TransferEncoding
	// on the struct itself. Some backends (e.g. Proxmox's API daemon)
	// reject chunked request bodies outright, breaking every empty-body
	// POST (VM start/stop, no parameters needed) while non-empty POSTs
	// (e.g. login) go through the normal, unambiguous Content-Length path
	// and work fine. Using the NoBody sentinel for an explicitly empty
	// body avoids the ambiguity.
	if upstreamReq.ContentLength == 0 {
		upstreamReq.Body = http.NoBody
	}

	upstreamReq.Header = downstreamReqHeaders

	// Go's HTTP/2 server lowercases all header names on the wire (mandated
	// by the HTTP/2 spec) and canonicalizes them back on read -- but its
	// canonicalization only capitalizes hyphen-separated words, so a
	// run-together mixed-case name like Proxmox's CSRFPreventionToken
	// becomes "Csrfpreventiontoken". Proxmox's own API server does a
	// case-sensitive lookup for the exact original casing, so over an
	// HTTP/2 downstream connection its CSRF check silently never finds the
	// token and rejects the request (start/stop, and any other POST/PUT/
	// DELETE after login) while GETs and login itself -- which don't
	// carry this header -- work fine. Restore the exact casing backends
	// expect.
	if csrf, ok := upstreamReq.Header["Csrfpreventiontoken"]; ok {
		delete(upstreamReq.Header, "Csrfpreventiontoken")
		upstreamReq.Header["CSRFPreventionToken"] = csrf
	}

	downstreamScheme := "https"
	if r.TLS == nil {
		downstreamScheme = "http"
	}
	upstreamReq.Header["X-Forwarded-Proto"] = []string{downstreamScheme}

	upstreamReq.Header["X-Forwarded-Host"] = []string{r.Host}

	remoteHost, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		errMessage := fmt.Sprintf("%s", err)
		w.WriteHeader(500)
		io.WriteString(w, errMessage)
		return
	}

	xForwardedFor := remoteHost

	if behindProxy {
		xForwardedFor := downstreamReqHeaders.Get("X-Forwarded-For")
		if xForwardedFor != "" {
			xForwardedFor = xForwardedFor + ", " + remoteHost
		}
	}

	upstreamReq.Header.Set("X-Forwarded-For", xForwardedFor)
	upstreamReq.Header.Set("Forwarded", fmt.Sprintf("for=%s", remoteHost))

	// TODO: This might need to be more generic, but using r.Host. However,
	// I think that may have security implications for things like DNS
	// rebinding attacks. Not sure.
	upstreamReq.Host = tunnel.Domain

	upstreamRes, err := httpClient.Do(upstreamReq)
	if err != nil {
		log.Printf("proxyRequest upstream error: %s %s -> %s: %s", r.Method, r.Host, upstreamUrl, err)
		switch errorMode {
		case upstreamErrorStartingUp:
			w.WriteHeader(http.StatusOK)
			io.WriteString(w, "Selfie Proxy is starting, please retry later")
		case upstreamErrorAgentUnreachable:
			var opErr *net.OpError
			if errors.As(err, &opErr) && opErr.Op == "dial" {
				// Dialing 127.0.0.1:<tunnel port> itself failed -- nothing is listening there at
				// all, meaning the agent's own SSH reverse-tunnel bore for this app is gone
				// (agent process down, or its SSH connection to this server dropped).
				writeHtmlError(w, http.StatusNotFound, "404 - Agent Not Found",
					`<p>The Selfie Proxy agent your the homelab is disconnected.</p><p>Please edit the homelab in the Selfie Proxy portal, and verify that name and secrets are correctly configured in docker-compose.yaml of the agent.</p>`)
			} else {
				// The dial succeeded (the agent's tunnel listener accepted the connection) but no
				// response ever came back -- the agent itself is connected, but its own dial to the
				// real backend inside the homelab failed (see tls_proxy.go's handleConnection).
				writeHtmlError(w, http.StatusNotFound, "404 - Server Not Found",
					`<p>The Selfie Proxy agent could not connect to the server in your homelab.</p><p>Please edit your application and check that all settings in 'Address in the homelab' are correct.</p>`)
			}
		default:
			errMessage := fmt.Sprintf("%s", err)
			w.WriteHeader(502)
			io.WriteString(w, errMessage)
		}
		return
	}
	defer upstreamRes.Body.Close()

	if upstreamRes.StatusCode >= 500 {
		log.Printf("proxyRequest upstream status: %s %s -> %s: %d", r.Method, r.Host, upstreamUrl, upstreamRes.StatusCode)
	}

	var forwardHeaders map[string][]string

	if r.ProtoMajor > 1 {
		forwardHeaders = stripConnectionHeaders(upstreamRes.Header)
	} else {
		forwardHeaders = upstreamRes.Header
	}

	downstreamResHeaders := w.Header()

	for k, v := range forwardHeaders {
		downstreamResHeaders[k] = v
	}

	w.WriteHeader(upstreamRes.StatusCode)
	io.Copy(w, upstreamRes.Body)
}

func isWebsocketUpgrade(r *http.Request) bool {
	return strings.EqualFold(r.Header.Get("Upgrade"), "websocket") &&
		strings.Contains(strings.ToLower(r.Header.Get("Connection")), "upgrade")
}

// proxyWebsocket handles WebSocket upgrade requests. Unlike proxyRequest,
// it can't use httpClient.Do, because that performs a single HTTP
// round-trip and gives us no way to keep the underlying TCP connection
// open for bidirectional streaming after the 101 response. Instead we
// hijack the downstream connection, dial the upstream directly, forward
// the (header-rewritten) handshake request ourselves, and then just pipe
// raw bytes both directions -- the 101 response and all subsequent
// WebSocket frames pass through untouched.
func proxyWebsocket(w http.ResponseWriter, r *http.Request, tunnel Tunnel, address string, port int, behindProxy bool) {
	upstreamHost := address
	useTls := false
	if strings.HasPrefix(address, "https://") {
		upstreamHost = address[len("https://"):]
		useTls = true
	}

	upstreamAddr := fmt.Sprintf("%s:%d", upstreamHost, port)

	var upstreamConn net.Conn
	var err error
	if useTls {
		upstreamConn, err = tls.Dial("tcp", upstreamAddr, &tls.Config{InsecureSkipVerify: true})
	} else {
		upstreamConn, err = net.Dial("tcp", upstreamAddr)
	}
	if err != nil {
		log.Printf("proxyWebsocket dial upstream error: %s -> %s: %s", r.URL.Path, upstreamAddr, err)
		errMessage := fmt.Sprintf("%s", err)
		w.WriteHeader(502)
		io.WriteString(w, errMessage)
		return
	}
	defer upstreamConn.Close()

	hijacker, ok := w.(http.Hijacker)
	if !ok {
		w.WriteHeader(500)
		io.WriteString(w, "webserver doesn't support hijacking")
		return
	}

	clientConn, clientBuf, err := hijacker.Hijack()
	if err != nil {
		w.WriteHeader(500)
		io.WriteString(w, err.Error())
		return
	}
	defer clientConn.Close()

	remoteHost, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		remoteHost = r.RemoteAddr
	}

	scheme := "https"
	if r.TLS == nil {
		scheme = "http"
	}
	r.Header.Set("X-Forwarded-Proto", scheme)
	r.Header.Set("X-Forwarded-Host", r.Host)

	xForwardedFor := remoteHost
	if behindProxy {
		if existing := r.Header.Get("X-Forwarded-For"); existing != "" {
			xForwardedFor = existing + ", " + remoteHost
		}
	}
	r.Header.Set("X-Forwarded-For", xForwardedFor)
	r.Header.Set("Forwarded", fmt.Sprintf("for=%s", remoteHost))

	// See proxyRequest for why this is set from the tunnel rather than
	// left as the original Host header.
	r.Host = tunnel.Domain

	if err := r.Write(upstreamConn); err != nil {
		return
	}

	// The hijacked bufio.Reader may already have buffered bytes the
	// client sent right after the handshake request; forward those
	// before starting the raw copy loop.
	if n := clientBuf.Reader.Buffered(); n > 0 {
		if _, err := io.CopyN(upstreamConn, clientBuf, int64(n)); err != nil {
			return
		}
	}

	done := make(chan struct{}, 2)
	go copyAndSignal(done, upstreamConn, clientConn)
	go copyAndSignal(done, clientConn, upstreamConn)
	<-done
}

func copyAndSignal(done chan<- struct{}, dst io.Writer, src io.Reader) {
	io.Copy(dst, src)
	done <- struct{}{}
}

// Need to strip out headers that shouldn't be forwarded from HTTP/1.1 to
// HTTP/2. See https://tools.ietf.org/html/rfc7540#section-8.1.2.2
var connectionHeaders = []string{
	"Connection", "Keep-Alive", "Proxy-Connection", "Transfer-Encoding", "Upgrade",
}

func stripConnectionHeaders(headers map[string][]string) map[string][]string {
	forwardHeaders := make(map[string][]string)

	for k, v := range headers {
		if stringInArray(k, connectionHeaders) {
			continue
		}

		forwardHeaders[k] = v
	}

	return forwardHeaders
}
