module github.com/boringproxy/boringproxy

go 1.25.0

//replace github.com/takingnames/namedrop-go => ../namedrop-go

require (
	github.com/caddyserver/certmagic v0.15.2
	github.com/coreos/go-oidc/v3 v3.20.0
	github.com/mdp/qrterminal/v3 v3.0.0
	github.com/skip2/go-qrcode v0.0.0-20200617195104-da1b6568686e
	github.com/takingnames/namedrop-go v0.7.0
	golang.org/x/crypto v0.24.0
	golang.org/x/oauth2 v0.36.0
)

require (
	github.com/go-jose/go-jose/v4 v4.1.4 // indirect
	github.com/klauspost/cpuid/v2 v2.0.9 // indirect
	github.com/libdns/libdns v0.2.1 // indirect
	github.com/mholt/acmez v1.0.1 // indirect
	github.com/miekg/dns v1.1.43 // indirect
	go.uber.org/atomic v1.7.0 // indirect
	go.uber.org/multierr v1.6.0 // indirect
	go.uber.org/zap v1.17.0 // indirect
	golang.org/x/net v0.26.0 // indirect
	golang.org/x/sys v0.21.0 // indirect
	golang.org/x/text v0.16.0 // indirect
	rsc.io/qr v0.2.0 // indirect
)
