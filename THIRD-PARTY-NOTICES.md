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

## JetBrains Mono, Fira Code, Cascadia Code, Source Code Pro

Four selectable font-family options in the SSH terminal's Settings menu (`settings.js`'s `FONTS`
list), self-hosted so the choice actually renders the same way regardless of what's installed on
the client machine, rather than silently falling back to generic `monospace` when it isn't.
Each is licensed under the **SIL Open Font License, Version 1.1** (a full copy is included at
`licenses/ofl-1.1.txt`, and alongside each vendored font itself):

- **JetBrains Mono** -- Copyright 2020 The JetBrains Mono Project Authors. Project home:
  <https://www.jetbrains.com/lp/mono/>.
- **Fira Code** -- Copyright (c) 2014, The Fira Code Project Authors. Project home:
  <https://github.com/tonsky/FiraCode>.
- **Cascadia Code** -- Copyright (c) 2019 - Present, Microsoft Corporation. Project home:
  <https://github.com/microsoft/cascadia-code>.
- **Source Code Pro** -- Copyright 2023 Adobe. Project home:
  <https://github.com/adobe-fonts/source-code-pro>.

Consumed entirely unmodified (Regular weight only, woff2), vendored as plain static assets into
`selfieproxy-remote-console/src/main/resources/static/fonts/vendor/<font>/` -- see the `VERSION`
file in each font's own directory for the exact source and version, including
`cascadia-code/VERSION`'s note that its `.woff2` is a lossless repackaging of Microsoft's own
`.ttf` release (via `fontTools`, no woff2 build is published upstream), since that's the one
file in this group that isn't served byte-for-byte as downloaded. `settings.js`'s remaining font
options (Menlo, Consolas, Courier New, DejaVu Sans Mono, System Monospace, and the xterm.js
"Default" stack) are plain CSS `font-family` names, not vendored files -- they only render
correctly if the browser's OS happens to already have that font installed, same as any web page
requesting a system font.

Wired into the SSH terminal page (`connect-terminal.html`) only, via a hand-written
`@font-face` stylesheet (`static/css/fonts.css`) -- not RDP/VNC's `connect.html`, which has no
font picker. Because copies of these font files are redistributed inside this repository and in
published Docker images, each one's own `OFL.txt` travels with it
(`selfieproxy-remote-console/.../fonts/vendor/<font>/OFL.txt`), per the OFL's own redistribution
terms.
