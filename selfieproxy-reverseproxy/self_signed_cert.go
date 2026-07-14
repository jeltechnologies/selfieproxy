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

// withSelfSignedFallback wraps certConfig's own GetCertificate: the real
// certificate is always tried first (so this doesn't interfere with actual
// ACME HTTP-01/TLS-ALPN-01 challenge traffic, which also flows through
// certConfig.GetCertificate), and only falls back to a temporary self-signed
// certificate for a domain isCertPending reports as still waiting on a real
// one. Used both by the server (predicate backed by TunnelManager's shared
// Tunnel DB) and by the agent (predicate backed by a per-tunnel flag, since
// the agent has no shared DB to query).
func withSelfSignedFallback(certConfig *certmagic.Config, isCertPending func(domain string) bool,
	selfSigned *SelfSignedCertProvider) func(*tls.ClientHelloInfo) (*tls.Certificate, error) {

	return func(hello *tls.ClientHelloInfo) (*tls.Certificate, error) {
		cert, err := certConfig.GetCertificate(hello)
		if err == nil {
			return cert, nil
		}

		if !isCertPending(hello.ServerName) {
			return nil, err
		}

		return selfSigned.GetOrCreate(hello.ServerName)
	}
}

// SelfSignedCertProvider lazily generates and caches a self-signed
// certificate per domain, used as a fallback so a tunnel whose real
// certificate can't be issued yet (e.g. a Let's Encrypt rate limit) is still
// reachable over HTTPS -- browsers will warn, but the user can click through
// to test their setup while TunnelManager keeps retrying the real cert in
// the background.
type SelfSignedCertProvider struct {
	mutex sync.Mutex
	certs map[string]*tls.Certificate
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

	p.certs[domain] = cert
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
