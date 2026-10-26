package boringproxy

import (
	"context"
	"encoding/json"
	"errors"
	"log"
	"strings"
	"sync"

	"github.com/caddyserver/certmagic"
	"github.com/mholt/acmez/v3/acme"
)

// CertRenewalFailures remembers the most recent renewal error per domain until a later
// renewal (or a fresh obtain) succeeds, so the admin portal can surface a renewal that's
// silently failing in the background long before the certificate actually expires.
// In-memory only: certmagic re-checks every managed certificate shortly after startup, so
// a still-failing renewal re-reports itself after a restart anyway.
type CertRenewalFailures struct {
	mutex    sync.Mutex
	failures map[string]string
}

func NewCertRenewalFailures() *CertRenewalFailures {
	return &CertRenewalFailures{failures: map[string]string{}}
}

func (f *CertRenewalFailures) All() map[string]string {
	f.mutex.Lock()
	defer f.mutex.Unlock()

	all := make(map[string]string, len(f.failures))
	for domain, message := range f.failures {
		all[domain] = message
	}
	return all
}

func (f *CertRenewalFailures) set(domain, message string) {
	f.mutex.Lock()
	defer f.mutex.Unlock()
	f.failures[domain] = message
}

func (f *CertRenewalFailures) clear(domain string) {
	f.mutex.Lock()
	defer f.mutex.Unlock()
	delete(f.failures, domain)
}

// newCertEventHandler builds certmagic's OnEvent hook, shared by the server and the agent
// (which passes nil failures, since nothing reads them on that side). Must be assigned to
// certmagic.Default.OnEvent before the first certmagic.NewDefault() call: background
// renewals run on a fresh NewDefault() config, which copies OnEvent from Default.
func newCertEventHandler(failures *CertRenewalFailures) func(ctx context.Context, event string, data map[string]any) error {
	return func(ctx context.Context, event string, data map[string]any) error {
		identifier, _ := data["identifier"].(string)

		switch event {
		case "cert_obtained":
			if failures != nil {
				failures.clear(identifier)
			}
		case "cert_failed":
			if renewal, _ := data["renewal"].(bool); !renewal {
				return nil
			}
			err, _ := data["error"].(error)
			issuers, _ := data["issuers"].([]string)

			if isAriAccountMismatch(err) && dropAriReplacesTarget(ctx, identifier, issuers) {
				log.Printf("CertMagic: certificate for %s was issued under a different ACME account, renewing it without ARI's \"replaces\" on the next attempt\n", identifier)
				return nil
			}

			if failures != nil && err != nil {
				failures.set(identifier, renewalErrorMessage(err))
			}
		}

		// Always nil: a non-nil return from some events (eg. cert_obtaining) aborts the operation.
		return nil
	}
}

// isAriAccountMismatch matches Let's Encrypt rejecting an ARI renewal because the stored
// certificate was issued under a different ACME account than the one renewing it -- eg.
// LETSENCRYPT_EMAIL changed or was removed (certmagic keeps one account per email address),
// or the account files were lost. certmagic has its own fallback (one retry without
// "replaces"), but it's unreachable in practice: its staging-CA test retry succeeds, the
// following production retry sets "replaces" again, fails, and ends the job with ErrNoRetry.
func isAriAccountMismatch(err error) bool {
	var problem acme.Problem
	return errors.As(err, &problem) &&
		problem.Type == acme.ProblemTypeUnauthorized &&
		strings.Contains(problem.Detail, "'replaces'")
}

// dropAriReplacesTarget removes the "ca" field from the certificate's stored ACME metadata.
// certmagic only sends ARI's "replaces" when that field matches the issuer's CA, and it
// reloads the metadata from storage on every renewal attempt, so the next attempt orders a
// plain renewal under the current account. Everything else (the certificate and key
// currently being served, ARI's renewal window) is left untouched, and the renewed
// certificate's own metadata gets a fresh "ca" again, so ARI works normally from then on.
func dropAriReplacesTarget(ctx context.Context, identifier string, issuerKeys []string) bool {
	storage := certmagic.Default.Storage
	if storage == nil {
		return false
	}

	dropped := false
	for _, issuerKey := range issuerKeys {
		key := certmagic.StorageKeys.SiteMeta(issuerKey, identifier)

		raw, err := storage.Load(ctx, key)
		if err != nil {
			continue
		}

		var meta map[string]json.RawMessage
		if err := json.Unmarshal(raw, &meta); err != nil {
			continue
		}
		var issuerData map[string]json.RawMessage
		if err := json.Unmarshal(meta["issuer_data"], &issuerData); err != nil {
			continue
		}
		if _, ok := issuerData["ca"]; !ok {
			continue
		}

		delete(issuerData, "ca")
		meta["issuer_data"], err = json.Marshal(issuerData)
		if err != nil {
			continue
		}
		updated, err := json.Marshal(meta)
		if err != nil {
			continue
		}
		if err := storage.Store(ctx, key, updated); err != nil {
			log.Printf("CertMagic: could not update certificate metadata for %s: %v\n", identifier, err)
			continue
		}
		dropped = true
	}
	return dropped
}

// renewalErrorMessage prefers the CA's own problem detail over certmagic's wrapped error,
// which repeats the identifier several times and includes the full order URL.
func renewalErrorMessage(err error) string {
	var problem acme.Problem
	if errors.As(err, &problem) && problem.Detail != "" {
		return problem.Detail
	}
	return err.Error()
}
