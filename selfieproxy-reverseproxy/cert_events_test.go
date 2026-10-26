package boringproxy

import (
	"context"
	"encoding/json"
	"fmt"
	"testing"

	"github.com/caddyserver/certmagic"
	"github.com/mholt/acmez/v3/acme"
)

const testIssuerKey = "acme-v02.api.letsencrypt.org-directory"

func withTestCertStorage(t *testing.T) *certmagic.FileStorage {
	storage := &certmagic.FileStorage{Path: t.TempDir()}
	previous := certmagic.Default.Storage
	certmagic.Default.Storage = storage
	t.Cleanup(func() { certmagic.Default.Storage = previous })
	return storage
}

func storeTestMeta(t *testing.T, storage *certmagic.FileStorage, domain string, issuerData map[string]any) {
	meta, _ := json.Marshal(map[string]any{"sans": []string{domain}, "issuer_data": issuerData})
	if err := storage.Store(context.Background(), certmagic.StorageKeys.SiteMeta(testIssuerKey, domain), meta); err != nil {
		t.Fatal(err)
	}
}

func loadTestIssuerData(t *testing.T, storage *certmagic.FileStorage, domain string) map[string]any {
	raw, err := storage.Load(context.Background(), certmagic.StorageKeys.SiteMeta(testIssuerKey, domain))
	if err != nil {
		t.Fatal(err)
	}
	var meta struct {
		Sans       []string       `json:"sans"`
		IssuerData map[string]any `json:"issuer_data"`
	}
	if err := json.Unmarshal(raw, &meta); err != nil {
		t.Fatal(err)
	}
	if len(meta.Sans) != 1 || meta.Sans[0] != domain {
		t.Fatalf("sans not preserved: %v", meta.Sans)
	}
	return meta.IssuerData
}

func renewalFailed(domain string, err error) map[string]any {
	return map[string]any{"renewal": true, "identifier": domain, "issuers": []string{testIssuerKey}, "error": err}
}

var ariMismatch = fmt.Errorf("[x] creating new order: %w", acme.Problem{
	Type:   acme.ProblemTypeUnauthorized,
	Status: 403,
	Detail: "Could not validate ARI 'replaces' field :: requester account did not request the certificate being replaced by this order",
})

func TestAriAccountMismatchDropsCaAndIsNotReported(t *testing.T) {
	storage := withTestCertStorage(t)
	storeTestMeta(t, storage, "photos.example.com", map[string]any{
		"url": "https://ca/cert/1", "ca": "https://acme-v02.api.letsencrypt.org/directory",
		"account": map[string]any{"location": "https://ca/acct/1"},
	})
	failures := NewCertRenewalFailures()

	newCertEventHandler(failures)(context.Background(), "cert_failed", renewalFailed("photos.example.com", ariMismatch))

	issuerData := loadTestIssuerData(t, storage, "photos.example.com")
	if _, ok := issuerData["ca"]; ok {
		t.Fatalf("ca should have been removed: %v", issuerData)
	}
	if issuerData["url"] != "https://ca/cert/1" || issuerData["account"] == nil {
		t.Fatalf("other issuer data should be untouched: %v", issuerData)
	}
	if len(failures.All()) != 0 {
		t.Fatalf("a self-healed mismatch should not be reported: %v", failures.All())
	}
}

func TestAriAccountMismatchAlreadyWithoutCaIsReported(t *testing.T) {
	storage := withTestCertStorage(t)
	storeTestMeta(t, storage, "photos.example.com", map[string]any{"url": "https://ca/cert/1"})
	failures := NewCertRenewalFailures()

	newCertEventHandler(failures)(context.Background(), "cert_failed", renewalFailed("photos.example.com", ariMismatch))

	if _, ok := failures.All()["photos.example.com"]; !ok {
		t.Fatalf("a mismatch that couldn't be healed should be reported")
	}
}

func TestOtherRenewalFailureIsReportedUntilObtained(t *testing.T) {
	storage := withTestCertStorage(t)
	storeTestMeta(t, storage, "gitlab.example.com", map[string]any{"ca": "https://acme-v02.api.letsencrypt.org/directory"})
	failures := NewCertRenewalFailures()
	handler := newCertEventHandler(failures)

	rateLimited := fmt.Errorf("wrapped: %w", acme.Problem{Type: acme.ProblemTypeNamespace + "rateLimited", Status: 429, Detail: "too many certificates"})
	handler(context.Background(), "cert_failed", renewalFailed("gitlab.example.com", rateLimited))

	if got := failures.All()["gitlab.example.com"]; got != "too many certificates" {
		t.Fatalf("expected the CA's problem detail, got %q", got)
	}
	if _, ok := loadTestIssuerData(t, storage, "gitlab.example.com")["ca"]; !ok {
		t.Fatalf("ca must only be dropped for an ARI account mismatch")
	}

	handler(context.Background(), "cert_obtained", map[string]any{"renewal": true, "identifier": "gitlab.example.com"})
	if len(failures.All()) != 0 {
		t.Fatalf("a successful renewal should clear the failure: %v", failures.All())
	}
}

func TestFailedFirstIssuanceIsNotARenewalFailure(t *testing.T) {
	withTestCertStorage(t)
	failures := NewCertRenewalFailures()

	newCertEventHandler(failures)(context.Background(), "cert_failed", map[string]any{
		"renewal": false, "identifier": "new.example.com", "error": fmt.Errorf("boom"),
	})

	if len(failures.All()) != 0 {
		t.Fatalf("first issuance failures are CertPending's job, not this one: %v", failures.All())
	}
}

type fakeCertManager struct {
	results []error
	calls   int
}

func (f *fakeCertManager) ManageSync(ctx context.Context, domainNames []string) error {
	err := f.results[f.calls]
	f.calls++
	return err
}

func TestManageSyncRetriesOnceAfterAriAccountMismatch(t *testing.T) {
	manager := &fakeCertManager{results: []error{fmt.Errorf("camunda.example.com: renewing certificate: %w", ariMismatch), nil}}

	if err := manageSync(context.Background(), manager, "camunda.example.com"); err != nil {
		t.Fatalf("expected the immediate retry to succeed, got %v", err)
	}
	if manager.calls != 2 {
		t.Fatalf("expected exactly 2 attempts, got %d", manager.calls)
	}
}

func TestManageSyncDoesNotRetryOtherFailures(t *testing.T) {
	other := fmt.Errorf("wrapped: %w", acme.Problem{Type: acme.ProblemTypeNamespace + "rateLimited", Status: 429})
	manager := &fakeCertManager{results: []error{other}}

	if err := manageSync(context.Background(), manager, "camunda.example.com"); err != other {
		t.Fatalf("expected the original error back, got %v", err)
	}
	if manager.calls != 1 {
		t.Fatalf("expected a single attempt, got %d", manager.calls)
	}
}
