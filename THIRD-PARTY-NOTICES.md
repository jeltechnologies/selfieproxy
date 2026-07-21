# Third-party notices

Selfie Proxy's own code is MIT licensed (see `LICENSE`). This file lists third-party software
distributed alongside it that carries a different license, and how each one is consumed.

## Apache Guacamole

Used by the browser SSH/RDP/VNC console feature (`selfieproxy-remote-console`,
`selfieproxy-guacd` in `docker-compose.yaml`) to bridge a WebSocket connection to a Homelab's
SSH/RDP/VNC service. **Copyright 2026 The Apache Software Foundation**, licensed under the
Apache License, Version 2.0 (a full copy is included at `licenses/apache-2.0.txt`, and
alongside the vendored JS file itself). Project home: <https://guacamole.apache.org/>.

Unlike `selfieproxy-reverseproxy` (a genuine fork of `github.com/boringproxy/boringproxy`, with
its own modified source living permanently in this repository), **Apache Guacamole is consumed
entirely unmodified** here, in three forms:

- **`guacd`** -- the `selfieproxy-guacd` service in `docker-compose.yaml` runs the official,
  unmodified `guacamole/guacd` Docker image directly from Docker Hub. No Dockerfile of ours
  builds it.
- **`guacamole-common`** -- an ordinary, unmodified Maven dependency
  (`org.apache.guacamole:guacamole-common`) of `selfieproxy-remote-console`, resolved from Maven
  Central at build time like any other library in that module's `pom.xml`.
- **`guacamole-common-js`** -- the browser-side client library, vendored unmodified into
  `selfieproxy-remote-console/src/main/resources/static/js/vendor/guacamole-common-js/` as a
  checked-in file (`guacamole-common.min.js`, sourced from the official
  `org.apache.guacamole:guacamole-common-js` distribution -- see the `VERSION` file in that same
  directory for the exact source and version). Vendoring it as a plain static asset -- rather
  than pulling it via a JS package manager -- matches how this repository already has no Node/JS
  build tooling elsewhere (every other page's JS, eg. `selfieproxy-portal`'s
  `static/js/*.js`, is hand-written and checked in directly too).

Because a copy of `guacamole-common-js` is redistributed inside this repository and in published
Docker images, its own `LICENSE` file travels with it
(`selfieproxy-remote-console/.../vendor/guacamole-common-js/LICENSE`), per the Apache License's
own redistribution terms (section 4).
