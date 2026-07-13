package boringproxy

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sync/atomic"
	"time"

	"github.com/coreos/go-oidc/v3/oidc"
	"golang.org/x/oauth2"
)

const (
	ssoSessionCookieName = "_selfieproxy_sso"
	ssoSessionTTL        = 24 * time.Hour
	ssoRelayTokenTTL     = 60 * time.Second
	ssoStateTTL          = 10 * time.Minute
	ssoSigningKeyFile    = "selfieproxy_sso_signing_key"
	ssoDiscoveryTimeout  = 5 * time.Second
	ssoDiscoveryMinWait  = 250 * time.Millisecond
	ssoDiscoveryMaxWait  = 3 * time.Second
)

// oidcAuthHolder is populated asynchronously by StartOidcAuth -- see its
// doc comment for why OIDC discovery can't happen synchronously during
// Listen()'s startup. Every call site treats a nil Load() as "SSO isn't
// ready yet" rather than "SSO is disabled" (that's -oidc-issuer == "").
var oidcAuthHolder atomic.Pointer[OidcAuthenticator]

// OidcAuthenticator is boringproxy's embedded OIDC Relying Party: one
// instance, built once discovery against the configured issuer succeeds,
// shared across every "server"-termination tunnel request and the portal
// domain. RequireAuth is the single check called from the dispatch path.
type OidcAuthenticator struct {
	verifier     *oidc.IDTokenVerifier
	oauth2Config oauth2.Config
	adminDomain  string
	signingKey   []byte
}

// ssoClaims backs both the session cookie and the relay token -- both are
// stateless, self-verifying HMAC-signed {domain, exp} pairs, deliberately
// not sharing a name with ssoStateClaims even though the shape overlaps,
// since the two are signed with the same key but must never be
// interchangeable (a relay token must not be replayable as a state value).
type ssoClaims struct {
	Domain string `json:"domain"`
	Exp    int64  `json:"exp"`
}

// ssoStateClaims is the "state" param round-tripped through the IdP. It
// carries the PKCE code_verifier itself, since there is deliberately no
// server-side session store to stash it in between /oidc/authorize and
// /oidc/callback.
type ssoStateClaims struct {
	Domain   string `json:"domain"`
	ReturnTo string `json:"return_to"`
	Verifier string `json:"verifier"`
	Exp      int64  `json:"exp"`
}

// StartOidcAuth kicks off asynchronous OIDC discovery against issuer and
// returns immediately; it never blocks Listen()'s startup. This matters
// because of a bootstrap ordering problem: discovery is an HTTP call to
// the SSO server, which boringproxy itself proxies to via -sso-domain --
// so it can only succeed once boringproxy's own accept loop is running.
// selfieproxy-sso-server boots concurrently with boringproxy (no
// depends_on between them in docker-compose-server.yaml, precisely so it
// isn't serialized behind boringproxy's healthcheck), but if discovery
// happened synchronously before Listen() starts, the two would still
// deadlock waiting on each other. Discovery is retried with a short
// backoff in the background instead, and every dispatch-path caller
// (RequireAuth's callers in boringproxy.go, HandleAuthorize,
// HandleCallback) treats a not-yet-ready OidcAuthenticator as a transient
// 503, not a hard failure.
// A blank issuer disables SSO entirely -- not expected to happen in the
// Selfie Proxy deployment, where docker-compose-server.yaml always
// computes a default pointing at the bundled server, but kept as an
// escape hatch for anyone running the bare boringproxy binary directly.
func StartOidcAuth(issuer, clientId, clientSecret, adminDomain string) {
	if issuer == "" {
		log.Println("SSO disabled (-oidc-issuer not set)")
		return
	}

	signingKey, err := loadOrCreateSsoSigningKey()
	if err != nil {
		log.Fatal("failed to initialize SSO signing key: ", err)
	}

	go func() {
		backoff := ssoDiscoveryMinWait
		for {
			ctx, cancel := context.WithTimeout(context.Background(), ssoDiscoveryTimeout)
			provider, err := oidc.NewProvider(ctx, issuer)
			cancel()

			if err == nil {
				oidcAuthHolder.Store(&OidcAuthenticator{
					verifier: provider.Verifier(&oidc.Config{ClientID: clientId}),
					oauth2Config: oauth2.Config{
						ClientID:     clientId,
						ClientSecret: clientSecret,
						Endpoint:     provider.Endpoint(),
						RedirectURL:  fmt.Sprintf("https://%s/oidc/callback", adminDomain),
						Scopes:       []string{oidc.ScopeOpenID, "profile", "email"},
					},
					adminDomain: adminDomain,
					signingKey:  signingKey,
				})
				log.Printf("OIDC SSO ready (issuer %s)\n", issuer)
				return
			}

			log.Printf("OIDC discovery against %s failed, retrying in %s: %s\n", issuer, backoff, err)
			time.Sleep(backoff)
			if backoff < ssoDiscoveryMaxWait {
				backoff *= 2
			}
		}
	}()
}

func loadOrCreateSsoSigningKey() ([]byte, error) {
	path := filepath.Join(DBFolderPath, ssoSigningKeyFile)

	existing, err := os.ReadFile(path)
	if err == nil && len(existing) > 0 {
		return existing, nil
	}
	if err != nil && !os.IsNotExist(err) {
		return nil, fmt.Errorf("failed reading %s: %w", path, err)
	}

	key := make([]byte, 32)
	if _, err := rand.Read(key); err != nil {
		return nil, fmt.Errorf("failed generating SSO signing key: %w", err)
	}
	if err := os.WriteFile(path, key, 0600); err != nil {
		return nil, fmt.Errorf("failed writing %s: %w", path, err)
	}

	return key, nil
}

// requireSsoIfNeeded is the dispatch-path entry point: called for every
// "server"-termination tunnel (tunnel.SsoProtected gates it) and, always,
// for the portal-domain synthetic tunnel. Returns true if the request may
// proceed to proxyRequest as-is; false if it already wrote a response
// (redirect or an error) and the caller must return immediately.
func requireSsoIfNeeded(w http.ResponseWriter, r *http.Request, domain string, protected bool) bool {
	if !protected {
		return true
	}

	oidcAuth := oidcAuthHolder.Load()
	if oidcAuth == nil {
		w.WriteHeader(http.StatusServiceUnavailable)
		fmt.Fprint(w, "Selfieproxy is starting... please retry shortly")
		return false
	}

	return oidcAuth.RequireAuth(w, r, domain)
}

// RequireAuth is the single SSO check: valid session cookie for this exact
// domain proceeds; a valid relay token (?sso_token=) for this domain sets
// that cookie and strips the token from the URL; anything else starts a
// fresh authorization round trip via the admin domain.
func (a *OidcAuthenticator) RequireAuth(w http.ResponseWriter, r *http.Request, domain string) bool {
	if a.validSessionCookie(r, domain) {
		return true
	}

	if ssoToken := r.URL.Query().Get("sso_token"); ssoToken != "" {
		var claims ssoClaims
		if payload, ok := a.verifyToken(ssoToken); ok && json.Unmarshal(payload, &claims) == nil &&
			claims.Domain == domain && time.Now().Unix() <= claims.Exp {

			a.setSessionCookie(w, domain)

			q := r.URL.Query()
			q.Del("sso_token")
			redirectUrl := r.URL.Path
			if encoded := q.Encode(); encoded != "" {
				redirectUrl += "?" + encoded
			}
			http.Redirect(w, r, redirectUrl, http.StatusFound)
			return false
		}
	}

	returnTo := fmt.Sprintf("https://%s%s", r.Host, r.URL.RequestURI())
	authorizeUrl := fmt.Sprintf("https://%s/oidc/authorize?domain=%s&return_to=%s",
		a.adminDomain, url.QueryEscape(domain), url.QueryEscape(returnTo))
	http.Redirect(w, r, authorizeUrl, http.StatusFound)
	return false
}

// HandleAuthorize serves /oidc/authorize on the admin domain: starts a
// fresh PKCE authorization-code flow against the configured issuer,
// encoding everything needed to resume (domain, return_to, the PKCE
// verifier) into the signed state param rather than server-side session
// storage.
func (a *OidcAuthenticator) HandleAuthorize(w http.ResponseWriter, r *http.Request) {
	domain := r.URL.Query().Get("domain")
	returnTo := r.URL.Query().Get("return_to")

	if domain == "" || returnTo == "" {
		http.Error(w, "missing domain or return_to", http.StatusBadRequest)
		return
	}

	verifier := oauth2.GenerateVerifier()

	state := a.signToken(ssoStateClaims{
		Domain:   domain,
		ReturnTo: returnTo,
		Verifier: verifier,
		Exp:      time.Now().Add(ssoStateTTL).Unix(),
	})

	authUrl := a.oauth2Config.AuthCodeURL(state, oauth2.S256ChallengeOption(verifier))
	http.Redirect(w, r, authUrl, http.StatusFound)
}

// HandleCallback serves /oidc/callback on the admin domain: completes the
// authorization-code + PKCE exchange, verifies the ID token, then bounces
// the browser back to the original domain with a short-lived relay token
// (?sso_token=) -- necessary because the session cookie set next is scoped
// to that original domain, not the admin domain this callback runs on.
func (a *OidcAuthenticator) HandleCallback(w http.ResponseWriter, r *http.Request) {
	if errParam := r.URL.Query().Get("error"); errParam != "" {
		http.Error(w, "SSO login failed: "+errParam, http.StatusBadRequest)
		return
	}

	code := r.URL.Query().Get("code")
	state := r.URL.Query().Get("state")

	payload, ok := a.verifyToken(state)
	if !ok {
		http.Error(w, "invalid or tampered SSO state", http.StatusBadRequest)
		return
	}

	var claims ssoStateClaims
	if err := json.Unmarshal(payload, &claims); err != nil || time.Now().Unix() > claims.Exp {
		http.Error(w, "expired or malformed SSO state", http.StatusBadRequest)
		return
	}

	token, err := a.oauth2Config.Exchange(r.Context(), code, oauth2.VerifierOption(claims.Verifier))
	if err != nil {
		http.Error(w, "OIDC token exchange failed: "+err.Error(), http.StatusBadGateway)
		return
	}

	rawIdToken, ok := token.Extra("id_token").(string)
	if !ok {
		http.Error(w, "IdP token response missing id_token", http.StatusBadGateway)
		return
	}

	if _, err := a.verifier.Verify(r.Context(), rawIdToken); err != nil {
		http.Error(w, "id_token verification failed: "+err.Error(), http.StatusForbidden)
		return
	}

	relayToken := a.signToken(ssoClaims{
		Domain: claims.Domain,
		Exp:    time.Now().Add(ssoRelayTokenTTL).Unix(),
	})

	returnUrl, err := url.Parse(claims.ReturnTo)
	if err != nil {
		http.Error(w, "invalid return_to in SSO state", http.StatusBadRequest)
		return
	}
	q := returnUrl.Query()
	q.Set("sso_token", relayToken)
	returnUrl.RawQuery = q.Encode()

	http.Redirect(w, r, returnUrl.String(), http.StatusFound)
}

// HandleLogout serves /oidc/logout -- a top-level carve-out checked on
// every domain (see boringproxy.go's main dispatch), not just the admin
// domain, since the SSO cookie it clears is host-only and scoped to
// whichever domain the browser is currently on. Deliberately a free
// function rather than an OidcAuthenticator method: clearing a cookie
// needs no IdP round trip, so logout must keep working even before OIDC
// discovery completes (unlike HandleAuthorize/HandleCallback, which are
// only reachable once oidcAuthHolder is populated).
// Redirects to the bundled IdP's /logged-out landing page (always
// reachable at ssoDomain regardless of whether -oidc-issuer has been
// pointed at an external IdP instead) so the user gets a clear
// confirmation rather than being silently bounced into a fresh login.
func HandleLogout(w http.ResponseWriter, r *http.Request, ssoDomain string) {
	http.SetCookie(w, &http.Cookie{
		Name:     ssoSessionCookieName,
		Value:    "",
		Path:     "/",
		HttpOnly: true,
		Secure:   true,
		SameSite: http.SameSiteLaxMode,
		MaxAge:   -1,
	})

	returnTo := r.URL.Query().Get("return_to")
	if returnTo == "" {
		returnTo = "/"
	}

	if ssoDomain == "" {
		http.Redirect(w, r, returnTo, http.StatusFound)
		return
	}

	loggedOutUrl := fmt.Sprintf("https://%s/logged-out?return_to=%s", ssoDomain, url.QueryEscape(returnTo))
	http.Redirect(w, r, loggedOutUrl, http.StatusFound)
}

func (a *OidcAuthenticator) setSessionCookie(w http.ResponseWriter, domain string) {
	token := a.signToken(ssoClaims{
		Domain: domain,
		Exp:    time.Now().Add(ssoSessionTTL).Unix(),
	})
	http.SetCookie(w, &http.Cookie{
		Name:     ssoSessionCookieName,
		Value:    token,
		Path:     "/",
		HttpOnly: true,
		Secure:   true,
		SameSite: http.SameSiteLaxMode,
	})
}

func (a *OidcAuthenticator) validSessionCookie(r *http.Request, domain string) bool {
	cookie, err := r.Cookie(ssoSessionCookieName)
	if err != nil {
		return false
	}

	payload, ok := a.verifyToken(cookie.Value)
	if !ok {
		return false
	}

	var claims ssoClaims
	if err := json.Unmarshal(payload, &claims); err != nil {
		return false
	}

	return claims.Domain == domain && time.Now().Unix() <= claims.Exp
}

// signToken/verifyToken implement the stateless, self-verifying token
// scheme shared by the session cookie, the relay token, and the state
// param: base64url(JSON payload) + "." + base64url(HMAC-SHA256 of the raw
// JSON payload), all under one persisted signing key (loadOrCreateSsoSigningKey).
func (a *OidcAuthenticator) signToken(claims any) string {
	payload, err := json.Marshal(claims)
	if err != nil {
		// Only ever the fixed struct types above -- a marshal failure here
		// would mean a programming error, not a runtime condition to
		// recover from.
		panic(err)
	}

	mac := hmac.New(sha256.New, a.signingKey)
	mac.Write(payload)
	sig := mac.Sum(nil)

	return base64.RawURLEncoding.EncodeToString(payload) + "." + base64.RawURLEncoding.EncodeToString(sig)
}

func (a *OidcAuthenticator) verifyToken(token string) ([]byte, bool) {
	dot := -1
	for i := len(token) - 1; i >= 0; i-- {
		if token[i] == '.' {
			dot = i
			break
		}
	}
	if dot < 0 {
		return nil, false
	}

	payload, err := base64.RawURLEncoding.DecodeString(token[:dot])
	if err != nil {
		return nil, false
	}
	sig, err := base64.RawURLEncoding.DecodeString(token[dot+1:])
	if err != nil {
		return nil, false
	}

	mac := hmac.New(sha256.New, a.signingKey)
	mac.Write(payload)
	if !hmac.Equal(sig, mac.Sum(nil)) {
		return nil, false
	}

	return payload, true
}
