# Task: Replace RSA SSH Key Generation with Ed25519 in boringproxy

## Background

boringproxy generates an SSH keypair per tunnel to authenticate the client
to the server's sshd. It currently uses RSA with the `ssh-rsa` (SHA-1)
signature algorithm, which modern OpenSSH (8.8+) rejects by default as
insecure. This causes the following error on the client side:

```
ssh: handshake failed: ssh: unable to authenticate, attempted methods
[none publickey], no supported methods remain
```

Switch key generation to Ed25519, which is supported by default in
virtually all current OpenSSH versions and avoids this rejection entirely.

This is a known upstream issue — see
[boringproxy#111](https://github.com/boringproxy/boringproxy/issues/111).

## Steps

### 1. Locate and rewrite `MakeSSHKeyPair()` in `utils.go`

- The current implementation generates an RSA keypair (adapted from a
  StackOverflow RSA example), returning
  `(pubKey string, privKey string, err error)`: `pubKey` in OpenSSH
  `authorized_keys` line format, `privKey` as a PEM-encoded string.
- Replace the key generation with `ed25519.GenerateKey(rand.Reader)` from
  `crypto/ed25519`.
- Build the public key string using `golang.org/x/crypto/ssh`:
  `ssh.NewPublicKey(pubKey)` then `ssh.MarshalAuthorizedKey(...)`, trimming
  the trailing newline to match the existing return format.
- Build the private key string using `ssh.MarshalPrivateKey(privKey, "")`
  (returns a `*pem.Block`) and `pem.EncodeToMemory(...)` to get the PEM
  string.
  - **Check the vendored `golang.org/x/crypto` version first.**
    `go.mod` currently pins
    `golang.org/x/crypto v0.0.0-20220919173607-35f4265a4bc0` (Sept 2022),
    and `ssh.MarshalPrivateKey` was added later. If it's not present in the
    vendored version, bump `golang.org/x/crypto` to a current release in
    `go.mod`, then run `go mod tidy` and `go mod download` to update
    `go.sum`. This can be resolved automatically — no need to hardcode a
    specific version, just pick a current stable release that includes the
    function.
- Keep the function signature identical — no other file should need
  changes to call it.

### 2. Verify `tunnel_manager.go` needs no changes

It calls `MakeSSHKeyPair()` and writes `pubKey` into `authorized_keys`
alongside the `permitopen`/`permitlisten` options — this logic is
key-type agnostic and should work unchanged with Ed25519 output.

### 3. Verify `client.go` needs no changes

It parses the private key string via `ssh.ParsePrivateKey(...)` to build
the SSH auth method — this already supports the OpenSSH Ed25519 private
key format, so no changes should be needed there. Just confirm this after
the change.

### 4. Rebuild both images (server and client)

Use the existing Dockerfiles — no Dockerfile changes needed for this fix.

### 5. Testing

- Deploy the updated server; delete any existing tunnels (their keys are
  still RSA-based and won't work with the new server) and recreate them.
- Check `./data/ssh/authorized_keys` on the server — the new entry should
  start with `ssh-ed25519` instead of `ssh-rsa`.
- Restart the client and confirm the tunnel connects without the
  `unable to authenticate` error.
- Check host sshd logs (`sudo journalctl -u ssh -n 20`) to confirm no
  algorithm-rejection messages appear.
