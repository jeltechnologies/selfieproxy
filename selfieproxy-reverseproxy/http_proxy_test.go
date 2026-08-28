package boringproxy

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

// checkTunnelAuth is the gate that lets an API client reach a Server the single sign on
// redirect would lock it out of, so "wrong credential still gets through" is the failure
// mode worth pinning down.

func request(header, value string) *http.Request {
	r := httptest.NewRequest(http.MethodGet, "https://server.example.com/v1/models", nil)
	if header != "" {
		r.Header.Set(header, value)
	}
	return r
}

func TestUnprotectedTunnelPassesEverythingThrough(t *testing.T) {
	w := httptest.NewRecorder()
	if !checkTunnelAuth(w, request("", ""), Tunnel{}) {
		t.Fatal("a tunnel with no credentials configured must not gate anything")
	}
}

func TestBasicAuth(t *testing.T) {
	tunnel := Tunnel{AuthUsername: "camunda", AuthPassword: "s3cret"}

	tests := []struct {
		name     string
		user     string
		pass     string
		omit     bool
		wantPass bool
	}{
		{name: "correct credentials", user: "camunda", pass: "s3cret", wantPass: true},
		{name: "wrong password", user: "camunda", pass: "wrong"},
		{name: "wrong username", user: "nobody", pass: "s3cret"},
		{name: "no Authorization header at all", omit: true},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			r := request("", "")
			if !tc.omit {
				r.SetBasicAuth(tc.user, tc.pass)
			}
			w := httptest.NewRecorder()

			if got := checkTunnelAuth(w, r, tunnel); got != tc.wantPass {
				t.Fatalf("checkTunnelAuth = %v, want %v", got, tc.wantPass)
			}
			if tc.wantPass {
				// The homelab backend has no use for the credential, and the agent's hop to
				// an http:// backend is cleartext -- it must not travel any further.
				if r.Header.Get("Authorization") != "" {
					t.Error("Authorization must be stripped before the request is forwarded")
				}
				return
			}
			if w.Code != http.StatusUnauthorized {
				t.Errorf("status = %d, want 401", w.Code)
			}
			// Read through the raw map, not Header().Get: the challenge is set by direct map
			// assignment so the wire keeps the exact "WWW-Authenticate" spelling (Go would
			// otherwise canonicalise it to "Www-Authenticate"), which is also why Get, which
			// canonicalises the key it looks up, can't find it.
			if len(w.Header()["WWW-Authenticate"]) == 0 {
				t.Error("a rejected Basic request must carry a WWW-Authenticate challenge")
			}
		})
	}
}

func TestTokenHeaderAuth(t *testing.T) {
	tunnel := Tunnel{AuthTokenValue: "tok_abc123"}

	tests := []struct {
		name     string
		header   string
		value    string
		wantPass bool
	}{
		// Camunda's AI Agent and MCP connectors send an API key this way...
		{name: "bearer form", header: "Authorization", value: "Bearer tok_abc123", wantPass: true},
		// ...while other clients send the raw value; both must work.
		{name: "bare token", header: "Authorization", value: "tok_abc123", wantPass: true},
		{name: "wrong token", header: "Authorization", value: "Bearer nope"},
		{name: "empty prefix only", header: "Authorization", value: "Bearer "},
		{name: "no header at all", header: "", value: ""},
		{name: "right value, wrong header", header: "X-Api-Key", value: "tok_abc123"},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			r := request(tc.header, tc.value)
			w := httptest.NewRecorder()

			if got := checkTunnelAuth(w, r, tunnel); got != tc.wantPass {
				t.Fatalf("checkTunnelAuth = %v, want %v", got, tc.wantPass)
			}
			if tc.wantPass {
				if r.Header.Get("Authorization") != "" {
					t.Error("the matched header must be stripped before forwarding")
				}
				return
			}
			if w.Code != http.StatusUnauthorized {
				t.Errorf("status = %d, want 401", w.Code)
			}
			// A Basic challenge would pop a browser password prompt this method can't
			// satisfy, and a Bearer one sends MCP clients into OAuth discovery.
			if len(w.Header()["WWW-Authenticate"]) != 0 {
				t.Error("the token gate must not send a WWW-Authenticate challenge")
			}
		})
	}
}

func TestTokenHeaderAuthWithCustomHeaderName(t *testing.T) {
	tunnel := Tunnel{AuthTokenHeader: "X-Api-Key", AuthTokenValue: "tok_abc123"}

	r := request("X-Api-Key", "tok_abc123")
	if !checkTunnelAuth(httptest.NewRecorder(), r, tunnel) {
		t.Fatal("a matching custom header must pass")
	}
	if r.Header.Get("X-Api-Key") != "" {
		t.Error("the custom header must be stripped before forwarding")
	}

	// The backend may well do its own Authorization-based auth; the edge gate owns only
	// the header it was configured with and must leave the rest untouched.
	r = request("X-Api-Key", "tok_abc123")
	r.Header.Set("Authorization", "Bearer backend-own-token")
	if !checkTunnelAuth(httptest.NewRecorder(), r, tunnel) {
		t.Fatal("a matching custom header must pass")
	}
	if r.Header.Get("Authorization") != "Bearer backend-own-token" {
		t.Error("Authorization must be forwarded untouched when a custom header is the gate")
	}

	w := httptest.NewRecorder()
	if checkTunnelAuth(w, request("Authorization", "Bearer tok_abc123"), tunnel) {
		t.Error("the right token in the wrong header must be rejected")
	}
	if w.Code != http.StatusUnauthorized {
		t.Errorf("status = %d, want 401", w.Code)
	}
}

func TestBasicTakesPrecedenceAndTokenIsNotAlsoConsulted(t *testing.T) {
	// selfieproxy-portal never sets both (WebAuthMethod is exclusive), but if a
	// hand-edited database ever did, the token must not become a second way in.
	tunnel := Tunnel{AuthUsername: "u", AuthPassword: "p", AuthTokenValue: "tok"}

	w := httptest.NewRecorder()
	if checkTunnelAuth(w, request("Authorization", "Bearer tok"), tunnel) {
		t.Error("the token must not bypass a configured Basic gate")
	}
	if w.Code != http.StatusUnauthorized {
		t.Errorf("status = %d, want 401", w.Code)
	}
}
