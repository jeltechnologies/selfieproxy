package boringproxy

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"github.com/caddyserver/certmagic"
	"math/big"
	"sync"
	"time"
)

// certFallback is what withSelfSignedFallback should do once a domain's real certificate isn't
// available -- decided per domain by the caller-supplied predicate below.
type certFallback int

const (
	// certFallbackNone means propagate the real certmagic error as-is -- e.g. a managed tunnel
	// whose cert genuinely failed for a reason other than "still pending", or a TLS-termination
	// mode that was never supposed to have a managed cert in the first place.
	certFallbackNone certFallback = iota
	// certFallbackSelfSigned serves a self-signed cert cached per domain in SelfSignedCertProvider
	// -- for a known tunnel still waiting on real cert issuance, or a domain with no tunnel at
	// all. Must be cached, not regenerated per handshake: a browser's "proceed anyway" exception
	// is keyed to the exact certificate, so a fresh key/serial on every connection would make it
	// re-prompt on the very next request (reload, sub-resource, anything) instead of ever
	// sticking. See SelfSignedCertProvider's size cap for how the no-tunnel-at-all case (fully
	// visitor/attacker-controlled, since a wildcard DNS record makes any SNI hit this server)
	// still can't grow this cache without bound.
	certFallbackSelfSigned
)

// withSelfSignedFallback wraps certConfig's own GetCertificate: the real certificate is always
// tried first (so this doesn't interfere with actual ACME HTTP-01/TLS-ALPN-01 challenge traffic,
// which also flows through certConfig.GetCertificate), and only falls back to selfSigned's cached
// self-signed certificate once that fails, per decide's answer. Used both by the server (decide
// backed by TunnelManager's shared Tunnel DB) and by the agent (decide backed by a per-tunnel
// flag, since the agent has no shared DB to query and its own TLS listener is already scoped to
// one known tunnel/domain -- see agent.go's BoreTunnel).
func withSelfSignedFallback(certConfig *certmagic.Config, decide func(domain string) certFallback,
	selfSigned *SelfSignedCertProvider) func(*tls.ClientHelloInfo) (*tls.Certificate, error) {

	return func(hello *tls.ClientHelloInfo) (*tls.Certificate, error) {
		cert, err := certConfig.GetCertificate(hello)
		if err == nil {
			return cert, nil
		}

		if decide(hello.ServerName) != certFallbackSelfSigned {
			return nil, err
		}

		return selfSigned.GetOrCreate(hello.ServerName)
	}
}

// selfSignedCertCacheLimit bounds SelfSignedCertProvider so a visitor probing arbitrary
// subdomains (any SNI hits this server thanks to a wildcard DNS record) can't grow it without
// limit -- generous enough that it's never hit by legitimate use (a handful of tunnels with a
// pending real cert, plus normal traffic to a few misconfigured/typo'd domains), since eviction
// only matters as a backstop against deliberate probing.
const selfSignedCertCacheLimit = 1000

// SelfSignedCertProvider lazily generates and caches a self-signed
// certificate per domain, used as a fallback so a tunnel whose real
// certificate can't be issued yet (e.g. a Let's Encrypt rate limit) is still
// reachable over HTTPS -- browsers will warn, but the user can click through
// to test their setup while TunnelManager keeps retrying the real cert in
// the background. Capped at selfSignedCertCacheLimit entries, evicting the
// oldest (FIFO, not strict LRU -- simpler, and sufficient since this only
// needs to bound memory, not optimize hit rate) once full.
type SelfSignedCertProvider struct {
	mutex sync.Mutex
	certs map[string]*tls.Certificate
	order []string
}

func NewSelfSignedCertProvider() *SelfSignedCertProvider {
	return &SelfSignedCertProvider{
		certs: map[string]*tls.Certificate{},
	}
}

func (p *SelfSignedCertProvider) GetOrCreate(domain string) (*tls.Certificate, error) {
	p.mutex.Lock()
	defer p.mutex.Unlock()

	if cert, ok := p.certs[domain]; ok {
		return cert, nil
	}

	cert, err := generateSelfSignedCert(domain)
	if err != nil {
		return nil, err
	}

	if len(p.certs) >= selfSignedCertCacheLimit {
		oldest := p.order[0]
		p.order = p.order[1:]
		delete(p.certs, oldest)
	}

	p.certs[domain] = cert
	p.order = append(p.order, domain)
	return cert, nil
}

// Forget evicts a cached self-signed certificate, e.g. once the real
// certificate for domain has been obtained. Not required for correctness
// (GetCertificate always tries the real cert first), just avoids keeping
// stale entries around indefinitely.
func (p *SelfSignedCertProvider) Forget(domain string) {
	p.mutex.Lock()
	defer p.mutex.Unlock()
	delete(p.certs, domain)
	for i, d := range p.order {
		if d == domain {
			p.order = append(p.order[:i], p.order[i+1:]...)
			break
		}
	}
}

func generateSelfSignedCert(domain string) (*tls.Certificate, error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, err
	}

	serialNumber, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return nil, err
	}

	now := time.Now()
	template := &x509.Certificate{
		SerialNumber: serialNumber,
		Subject: pkix.Name{
			CommonName: domain,
		},
		DNSNames:              []string{domain},
		NotBefore:             now.Add(-time.Hour),
		NotAfter:              now.AddDate(1, 0, 0),
		KeyUsage:              x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
	}

	derBytes, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		return nil, err
	}

	return &tls.Certificate{
		Certificate: [][]byte{derBytes},
		PrivateKey:  key,
	}, nil
}
