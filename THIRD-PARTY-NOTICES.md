# Third-party notices

Selfie Proxy's own code is MIT licensed (see `LICENSE`). This file lists third-party software
distributed alongside it that carries a different license, and how each one is consumed.

## Apache Guacamole

Used by the browser RDP/VNC console feature (`selfieproxy-remote-console`,
`selfieproxy-guacd` in `docker-compose.yaml`) to bridge a WebSocket connection to a Homelab's
RDP/VNC service. SSH no longer goes through Guacamole/guacd at all -- see Apache MINA SSHD and
xterm.js below. **Copyright 2026 The Apache Software Foundation**, licensed under the
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

## Apache MINA SSHD

Used by `selfieproxy-remote-console`'s direct SSH terminal path (SshWebSocketHandler, paired with
the browser-side xterm.js -- see below) to open the actual SSH session: unlike RDP/VNC, an SSH
console is no longer bridged through Guacamole/guacd at all, this dials the tunnel port directly.
**Copyright The Apache Software Foundation**, licensed under the Apache License, Version 2.0
(`licenses/apache-2.0.txt`). Project home: <https://mina.apache.org/sshd-project/>. Consumed
purely as an ordinary, unmodified Maven dependency (`org.apache.sshd:sshd-core`), resolved from
Maven Central at build time like `guacamole-common` above -- nothing of ours is vendored or
redistributed for this one, so no separate `LICENSE` copy travels with it.

## xterm.js

The browser-side terminal emulator for `selfieproxy-remote-console`'s direct SSH terminal path --
renders the real, selectable terminal text SshWebSocketHandler streams over its own WebSocket,
replacing the canvas-rendered image Guacamole would otherwise produce (RDP/VNC still do use that
canvas rendering, unchanged). **Copyright (c) 2017-2019 The xterm.js authors, 2014-2016
SourceLair Private Company, 2012-2013 Christopher Jeffrey**, licensed under the MIT License (a
full copy is included at `licenses/mit.txt`, and alongside the vendored files themselves). Project
home: <https://xtermjs.org/>.

Consumed entirely unmodified, vendored as plain static assets into
`selfieproxy-remote-console/src/main/resources/static/js/vendor/xterm/` (`xterm.js`, the core
terminal emulator, and `xterm-addon-fit.js`, the addon that fits the terminal to its container) and
`static/css/vendor/xterm/xterm.css` -- see the `VERSION` file in the JS vendor directory for the
exact source and version of each. Vendoring as plain static assets rather than pulling them via a
JS package manager matches how `guacamole-common-js` is already vendored the same way, and how
this repository has no Node/JS build tooling anywhere else either.

Because copies of these files are redistributed inside this repository and in published Docker
images, their own `LICENSE` file travels with them
(`selfieproxy-remote-console/.../vendor/xterm/LICENSE`), per the MIT License's own terms.
