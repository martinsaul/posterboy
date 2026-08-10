# Posterboy - TODO

Items deferred from v1. Revisit and prioritize as needed.

## Plugins

- [ ] Slack plugin
- [ ] Telegram plugin
- [ ] NTFY plugin
- [ ] Gotify plugin
- [ ] Matrix plugin
- [ ] Plugin signing / verification (security hardening for third-party plugins)
- [ ] Plugin hot-reload without service restart

## Discord Plugin Enhancements

- [ ] Rich embeds (colored sidebar, structured fields) instead of plain code blocks
- [ ] Attachment file upload to Discord (images, small files)
- [ ] Thread creation per email conversation (group by subject/message-id)
- [ ] @mention support -- map email addresses to Discord user IDs
- [ ] Reaction-based actions (e.g. react to forward, react to delete)

## Gmail Plugin Enhancements

- [x] Per-rule alias override (send specific routes from specific aliases)
- [ ] Multiple Google account support (send as different accounts)
- [ ] Bounce handling and delivery status notifications
- [ ] DKIM signing for custom domains
- [ ] SPF/DMARC alignment tooling and diagnostics
- [ ] Generic SMTP plugin (non-Gmail, arbitrary relay)

## Routing Engine

- [x] Dynamic rules via REST API (add/remove/reorder without restart)
- [ ] Rule persistence in a database (SQLite or similar)
- [ ] Regex matching on From/To/Subject
- [ ] Content-based routing (body keyword matching)
- [ ] Rate limiting per rule / per destination
- [ ] Rule priority weights (instead of first-match-wins)

## SMTP Server

- [ ] Native TLS termination (bypass the reverse proxy for direct connections)
- [ ] STARTTLS support on the SMTP listener itself
- [ ] DKIM verification of inbound messages
- [ ] SPF checking of inbound senders
- [ ] Spam scoring (basic heuristics or SpamAssassin integration)
- [ ] Message queue with retry logic for failed deliveries
- [ ] Dead letter queue for permanently failed messages

## Configuration

- [x] Web UI for configuration management (admin UI)
- [ ] Config validation on startup with clear error messages
- [x] Config reload without restart (watch file or API endpoint)
- [ ] TOML support as alternative config format
- [ ] Secret management integration (Vaultwarden, 1Password, etc.)
- [ ] Separate runtime config from repo template so deploys don't overwrite runtime changes
- [ ] Re-authorize Gmail OAuth after deploy (StoredCredential not loading after rebuild)

## Observability

- [ ] Structured logging (JSON format option)
- [ ] Metrics endpoint (Prometheus-compatible)
- [ ] Health check endpoint for Uptime Kuma / Docker
- [ ] Delivery audit log (who sent what, where it went, success/failure)
- [ ] Dashboard for message flow visualization

## Infrastructure

- [ ] CI/CD pipeline (GitLab CI on home server)
- [ ] Automated Docker image builds and push to registry
- [ ] GraalVM native-image compilation for faster startup
- [ ] ARM64 image for Raspberry Pi / other ARM hosts
- [ ] Helm chart (overkill but nice for Kubernetes users if FOSSed)

## FOSS Preparation

- [ ] Add GitHub remote as origin
- [ ] LICENSE file (pick license)
- [ ] Contributing guidelines
- [ ] Example configs and docker-compose for new users
- [ ] Documentation site or comprehensive README
