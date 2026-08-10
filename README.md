# Posterboy

Posterboy is a small, self-hostable **SMTP relay** for the JVM. It listens for
inbound email and **fans each message out to one or more destinations** —
Discord webhooks, a forwarding mailbox via Gmail, or anything you write a plugin
for — according to configurable routing rules.

It's the piece that lets self-hosted services, cron jobs, appliances, and apps
that only know how to "send an email" reach you wherever you actually read messages,
without every one of them needing its own Discord/Slack/webhook integration.

- **Receive SMTP**, parse the message, evaluate routing rules, dispatch to plugins.
- **Fan-out routing** — a message can land in several destinations at once; a
  broken destination never starves the others.
- **Pluggable delivery** via Java `ServiceLoader` — ship a JAR, drop in a plugin.
  Built-in: **Discord** (webhooks) and **Gmail** (SMTP app-password or OAuth API).
- **Approval queue** — optionally hold matching messages for manual review before
  they're delivered, with attachments, from a web UI.
- **Admin UI + REST API** — manage config, plugins, routing, and the Gmail OAuth
  flow live; changes reload without a restart. Plus a real-time debug feed.

## How it works

```
   inbound SMTP ──▶  parse ──▶  routing engine ──▶  plugin(s)  ──▶  Discord
   (:2525)                       (rules, fan-out)      │              Gmail
                                                        └──▶  your plugin …
```

Every routing rule that matches a recipient contributes its action(s); the
message is delivered to each resulting plugin (deduplicated). A rule with no
`match` block matches everything (the "universal" idiom), and a configurable
`default_action` catches recipients that matched no rule.

## Quick start (Docker)

```bash
git clone https://github.com/martinsaul/posterboy && cd posterboy
cp .env.example .env
# Put your Discord webhook in .env (DISCORD_DEFAULT_WEBHOOK=...).
docker compose up -d --build
```

Send a test message to the relay:

```bash
swaks --server localhost:2525 --to alerts@example.com \
  --from app@example.com --header "Subject: hello" --body "it works"
# (or any SMTP client / your app's SMTP settings pointed at host:2525)
```

The admin UI is on `http://localhost:8080`. **It is unauthenticated — keep it on
a private network or behind your own auth/reverse proxy; never expose it to the
public internet.**

## Run from source

Requires JDK 21+.

```bash
./gradlew run                 # uses config/posterboy.yml
./gradlew shadowJar           # build a fat jar -> build/libs/*-all.jar
./gradlew test                # run the test suite
```

## Configuration

Config is a single YAML file (`config/posterboy.yml`), bind-mounted into the
container and copied to `/data/posterboy.yml` on first run so live edits persist.
`${VAR}` placeholders are substituted from the environment, so secrets stay out
of the file. See [`config/posterboy.yml`](config/posterboy.yml) for a documented
example. Highlights:

```yaml
smtp:
  port: 2525
  auth: { enabled: true, username: relay, password: changeme }

plugins:
  discord:
    default_webhook: ${DISCORD_DEFAULT_WEBHOOK}

routing:
  rules:
    - match: { to_domain: ["example.com", "*.example.com"] }
      action: discord
  default_action: discord
```

Routing match criteria (all specified fields must match — AND):
`from`, `from_domain`, `to`, `to_domain` (list of globs), `to_domain_has_mx`
(does the recipient domain have MX records?), and `not` (a nested match to
exclude). Actions are a single `action:` or multiple `actions: [a, b]`.

## Plugins

Delivery is entirely plugin-driven. Two ship in the box:

- **Discord** — posts each email to a webhook; route different recipients to
  different webhooks via `aliases`.
- **Gmail** — forwards email out as real email, via Gmail SMTP (app password) or
  the Gmail API (OAuth, higher limits; authorize through the admin UI).

Writing your own (Slack, Telegram, ntfy, a database, an HTTP endpoint, …) is the
main way to extend Posterboy. **See the full guide: [docs/PLUGINS.md](docs/PLUGINS.md).**

### Extend without forking — drop-in jars

Beyond delivery plugins, **extensions** can add HTTP endpoints (custom ingress,
auth), background tasks (health/heartbeat reporters), or bridges to other systems.
Both plugins and extensions can be loaded from **jars dropped into a folder**
(`./plugins/` → `/data/plugins`) with no rebuild — so environment-specific or
private integrations live entirely outside this repo. See
**[docs/EXTENSIONS.md](docs/EXTENSIONS.md).**

## Documentation

- **[docs/PLUGINS.md](docs/PLUGINS.md)** — the plugin system in depth, with a
  complete worked example of building and packaging a new plugin.
- **[ARCHITECTURE.md](ARCHITECTURE.md)** — components and message flow.

## License

[MIT](LICENSE).
