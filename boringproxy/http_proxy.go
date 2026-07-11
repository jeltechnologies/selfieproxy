package boringproxy

import (
	"crypto/tls"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"time"
)

func proxyRequest(w http.ResponseWriter, r *http.Request, tunnel Tunnel, httpClient *http.Client, address string, port int, behindProxy bool) {

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

	upstreamReq.Header = downstreamReqHeaders

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
		errMessage := fmt.Sprintf("%s", err)
		w.WriteHeader(502)
		io.WriteString(w, errMessage)
		return
	}
	defer upstreamRes.Body.Close()

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
