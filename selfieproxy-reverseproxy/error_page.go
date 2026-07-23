package boringproxy

import (
	"io"
	"net/http"
)

// errorPageBefore/errorPageAfter bracket the <h1>/body markup writeHtmlError's callers supply,
// mirroring selfieproxy-portal's own page chrome (fragments/layout.html) -- same brand icon/
// "Selfie Proxy" title and footer text, so a visitor sees one consistent brand whether the
// failure was caught here or in the portal itself. Hardcoded to the portal's light theme (no
// dark mode) since this Go-side page has no theme preference to read, and deliberately without
// the portal's nav/settings menus -- there's nothing on this page to navigate to.
const errorPageBefore = `<html><head><title>Selfie Proxy - Configuration error</title><style>
:root{--bg:#f7f7f8;--card-bg:#ffffff;--text:#1b1b1f;--muted:#6b6b70;--border:#d8d8dc;--accent:#2f6fed}
body{margin:0;min-height:100vh;display:flex;flex-direction:column;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;background:var(--bg);color:var(--text)}
.topbar{display:flex;align-items:center;padding:0.75rem 1.5rem;border-bottom:1px solid var(--border);background:var(--card-bg)}
.brand{font-size:1.4rem;font-weight:600;display:inline-flex;align-items:center;gap:0.4em}
.brand-icon{width:1em;height:1em;flex:none;color:#245edb}
main{flex:1 0 auto;width:100%;max-width:1400px;margin:2rem auto;padding:0 1.5rem 3rem}
h1{font-size:32px;margin:0 0 0.75rem}
p{margin:0.5rem 0}
.app-footer{flex-shrink:0;padding:1rem 0;text-align:center;font-size:0.75rem;color:var(--muted)}
</style></head><body>
<header class="topbar"><span class="brand"><svg class="brand-icon" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16" width="16" height="16" fill="currentColor" aria-hidden="true"><path d="M8 6.982C9.664 5.309 13.825 8.236 8 12 2.175 8.236 6.336 5.309 8 6.982"/><path d="M8.707 1.5a1 1 0 0 0-1.414 0L.646 8.146a.5.5 0 0 0 .708.707L2 8.207V13.5A1.5 1.5 0 0 0 3.5 15h9a1.5 1.5 0 0 0 1.5-1.5V8.207l.646.646a.5.5 0 0 0 .708-.707L13 5.793V2.5a.5.5 0 0 0-.5-.5h-1a.5.5 0 0 0-.5.5v1.293zM13 7.207V13.5a.5.5 0 0 1-.5.5h-9a.5.5 0 0 1-.5-.5V7.207l5-5z"/></svg>Selfie Proxy</span></header>
<main><h1>`

const errorPageAfter = `</main>
<footer class="app-footer">Powered by selfieproxy by Jelte Jansons — built on boringproxy by Anders Pitman — MIT Licensed</footer>
</body></html>`

// writeHtmlError renders one of this proxy's own error pages: statusCode as the real HTTP
// status, heading as the <h1> text, and bodyHtml as whatever explanatory markup follows it,
// appearing right under the logotype (the only part that actually differs between call sites).
func writeHtmlError(w http.ResponseWriter, statusCode int, heading string, bodyHtml string) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.WriteHeader(statusCode)
	io.WriteString(w, errorPageBefore+heading+"</h1>"+bodyHtml+errorPageAfter)
}
