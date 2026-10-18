# Security policy

## Reporting a vulnerability

Please report security issues privately through GitHub's
[private vulnerability reporting](https://github.com/silencelen/huginn/security/advisories/new)
(Security, then Report a vulnerability). Do not open a public issue.

Include what the issue is, how to reproduce it, and its impact. You will get an acknowledgement,
and a fix or a plan, as soon as possible.

## Scope

In scope: the daemon (`server/appd/`), huginn core (`core/`), the client scripts (`client/`), the
server installer (`server/`), and the desktop and Android apps (`mobile/`).

Huginn runs Claude Code as root on a dedicated server, and much of a deployment's security comes
from how it is deployed: the network the server is on, who holds the access token, and SSH. Read
[docs/security.md](docs/security.md) for the security model and a hardening checklist before
reporting a deployment concern.

## Supported versions

Fixes go into the next release of the affected component. Run current releases of the server and
the clients; see [Releases and compatibility](docs/reference/releases.md).
