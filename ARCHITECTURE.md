# Posterboy — Architecture

Posterboy is a JVM SMTP relay: it receives inbound email and routes each message
to one or more delivery destinations based on configurable rules. This document
describes the components and how a message flows through them. For extending
delivery, see [docs/PLUGINS.md](docs/PLUGINS.md).

## High-level flow

```
             ┌──────────────────────────────┐
   SMTP ───▶ │        SmtpServer            │  receive + parse -> InboundEmail
   :2525     └───────────────┬──────────────┘
                             │
             ┌───────────────▼──────────────┐
             │        RoutingEngine         │  evaluate rules (fan-out)
             └───────────────┬──────────────┘
                             │  decisions (pluginId + per-route config)
             ┌───────────────▼──────────────┐
             │      hold?  ──▶ ApprovalQueue │  optional manual approval
             └───────────────┬──────────────┘
                             │
        ┌────────────────────┼────────────────────┐
        │                    │                     │
 ┌──────▼──────┐     ┌───────▼──────┐      ┌───────▼──────┐
 │  Discord    │     │    Gmail     │      │  your plugin │
 │  plugin     │     │    plugin    │      │  …           │
 └─────────────┘     └──────────────┘      └──────────────┘
```

## Components

### SMTP server (`smtp/`)

An embedded SMTP server (SubEtha SMTP) listens on a single port (default 2525).
Optional AUTH (username/password) can be required. Each accepted message is
parsed by `EmailParser` into an `InboundEmail`:

```
InboundEmail(from, to[list], subject, bodyPlain?, bodyHtml?, attachments[meta], fullAttachments)
```

TLS is intentionally out of scope for the listener — terminate TLS at a reverse
proxy / load balancer in front (or map the container to `:25` on a trusted
network) and forward plain SMTP to the container.

### Routing engine (`routing/`)

Evaluates rules top-to-bottom against each message. **All matching rules fire
(fan-out)** — every matching rule's action(s) are collected and deduplicated by
plugin id, and the message is dispatched to every resulting plugin. Fan-out keeps
one broken destination from starving the others.

Match criteria (all specified fields must match — AND logic):

- `from` — sender address (glob)
- `from_domain` — sender domain (glob)
- `to` — a specific recipient (glob)
- `to_domain` — list of recipient-domain globs (any-match within the list)
- `to_domain_has_mx` — boolean; does the recipient domain resolve MX records?
- `not` — a nested match; the rule matches only when the nested match does **not**
  (lets a broad rule carve out exceptions)

A rule with no/empty `match` matches every recipient (the "universal" idiom). A
rule declares one plugin (`action: discord`) or several (`actions: [discord, gmail]`),
plus an optional per-route `config` map passed to the plugin as
`RoutingContext.pluginConfig`. `default_action` is used only when **no** rule
matched a recipient.

**MX heuristic:** `to_domain_has_mx` resolves and caches the recipient domain's MX
records (configurable TTL). Cache entries are evicted on delivery failure so stale
records self-heal.

### Plugin system (`plugin/`)

Delivery destinations are `DeliveryPlugin` implementations discovered at startup
via Java `ServiceLoader`. Each plugin has a stable `id` (referenced by routing
actions), an `initialize(config)` lifecycle hook, and a `suspend deliver(email,
context)` method. The `PluginRegistry` discovers, configures, enables/disables,
and shuts down plugins. This is the primary extension point — see
[docs/PLUGINS.md](docs/PLUGINS.md).

**Built-in plugins:**

- **Discord** (`plugins/discord/`) — posts a formatted message to a webhook. A
  `default_webhook` handles unmatched traffic; per-recipient `aliases` map a
  local-part (e.g. `alerts@…`) to a specific webhook.
- **Gmail** (`plugins/gmail/`) — forwards email out as real email. Two strategies:
  **SMTP** (Gmail app password, ~500/day) or **API** (OAuth2, ~2000/day),
  selectable in config. The API strategy is authorized interactively through the
  admin UI's OAuth flow; tokens are persisted to the data volume.

### Approval queue (`approval/`)

Optional. A routing action of `hold` (with a `forward_to` target plugin) diverts
matching messages into a SQLite-backed queue instead of delivering them. A human
reviews them — subject, body, and attachments — in the admin UI and approves
(delivers via the target plugin) or dismisses. Old entries are pruned by a
configurable retention window.

### Admin UI & API (`admin/`)

A Javalin web server (default port 8080) serves a static single-page admin UI plus
a REST API to:

- read/update the full config, or import/export it as YAML;
- list plugins, edit per-plugin config, enable/disable them;
- run the Gmail OAuth flow (upload client credentials → authorize → pick alias);
- review and approve/dismiss held messages;
- stream a live debug feed of routing decisions over a WebSocket (`/ws/debug`).

Config changes are persisted and hot-reloaded (`Posterboy.reloadConfig()`), so
most changes take effect without a restart. **The admin UI is unauthenticated by
design — run it on a trusted network or behind your own auth.**

### Configuration (`config/`)

A single YAML file (`ConfigLoader`), with `${ENV_VAR}` substitution so secrets
(webhook URLs, app passwords) live in the environment, not the file. On first run
the bundled default is copied to the data volume so live edits persist across
restarts.

## Message lifecycle

1. `SmtpServer` accepts a message and parses it into an `InboundEmail`.
2. `Posterboy.processEmail` asks the `RoutingEngine` for the set of decisions.
3. Decisions targeting `hold` are diverted to the `ApprovalService`; their
   `forward_to` plugin is suppressed from immediate delivery (it fires on approval).
4. The remaining decisions are grouped by plugin and each plugin's `deliver` is
   invoked (templates like `{{sender}}` are resolved first). A plugin failure is
   logged and isolated — it never blocks the other destinations.

## Project layout

```
src/main/kotlin/io/posterboy/
  Application.kt            entry point + config path resolution
  Posterboy.kt             orchestration: routing + fan-out + hold handling
  config/                  YAML config model + loader (env substitution)
  smtp/                    embedded SMTP server, MIME parsing, InboundEmail
  routing/                 rule evaluation + MX resolver/cache
  plugin/                  DeliveryPlugin interface, RoutingContext, registry
  plugins/discord/         Discord webhook plugin + formatter
  plugins/gmail/           Gmail plugin (SMTP + API send strategies, OAuth)
  approval/                hold-for-approval queue (SQLite)
  admin/                   Javalin REST API + WebSocket debug feed
  debug/                   in-memory debug event buffer
src/main/resources/
  static/                  admin UI (HTML/JS/CSS)
  META-INF/services/io.posterboy.plugin.DeliveryPlugin   ServiceLoader registry
```
