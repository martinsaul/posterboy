# Extensions & drop-in jars

Posterboy has two extension points:

- **Delivery plugins** (`DeliveryPlugin`) answer *"where does a message go?"* —
  Discord, Gmail, Slack, ntfy, … See [PLUGINS.md](PLUGINS.md).
- **Extensions** (`Extension`) answer *"what else can Posterboy do?"* — add HTTP
  endpoints (custom ingress, auth, admin actions), run background tasks (health /
  heartbeat reporters), or bridge to another system.

Both are discovered the same way (Java `ServiceLoader`) and, crucially, can be
loaded from **jars dropped into a folder** — so a deployment adds capabilities by
dropping a jar next to the app, with **no fork and no rebuild of the core**.

- [Why extensions](#why-extensions)
- [The `Extension` interface](#the-extension-interface)
- [The drop-in plugins directory](#the-drop-in-plugins-directory)
- [Configuration](#configuration)
- [Worked example: an authenticated HTTP ingest extension](#worked-example-an-authenticated-http-ingest-extension)
- [Worked example: a health reporter extension](#worked-example-a-health-reporter-extension)
- [Building an extension as an external jar](#building-an-extension-as-an-external-jar)
- [Lifecycle & ordering](#lifecycle--ordering)
- [Checklist](#checklist)

## Why extensions

Delivery plugins can't express everything. Some capabilities are cross-cutting:
an authenticated HTTP endpoint that injects mail into the pipeline, a background
loop that reports health to a registry, an auth scheme for the admin API. Those
are **extensions**. Keeping them as drop-in jars means environment-specific or
private integrations live entirely outside this repository — you never fork or
patch the core to add them.

## The `Extension` interface

```kotlin
package io.posterboy.plugin

interface Extension {
    /** Stable id; its config lives under `extensions.<id>` in posterboy.yml. */
    val id: String
    val name: String get() = id
    fun initialize(ctx: ExtensionContext)
    fun shutdown() {}
}

interface ExtensionContext {
    /** This extension's `extensions.<id>` config block (may be empty). */
    val config: Map<String, Any>
    /** The admin Javalin app for registering routes; null if admin is disabled. */
    val javalin: Javalin?
    /** The delivery-plugin registry. */
    val pluginRegistry: PluginRegistry
    /** Inject a message into the routing + fan-out pipeline (as if received over SMTP). */
    fun submit(email: InboundEmail)
}
```

`submit` is the key capability: it hands a message to the exact same routing,
hold/approval, and fan-out path the SMTP listener uses — so an extension that
adds alternative ingress gets all of that for free.

## The drop-in plugins directory

At startup Posterboy builds a classloader that layers every `*.jar` in the
plugins directory on top of the application classpath, then runs `ServiceLoader`
over it. Both `DeliveryPlugin` and `Extension` providers in those jars are picked
up alongside the bundled ones.

Directory resolution order:

1. `plugins_dir:` in `posterboy.yml`
2. the `POSTERBOY_PLUGINS_DIR` environment variable
3. `/data/plugins` (default)

With the provided `docker-compose.yml`, drop jars into `./plugins/` on the host
(mounted read-only at `/data/plugins`) and restart:

```bash
cp my-extension.jar ./plugins/
docker compose restart
# log: "Loading 1 drop-in jar(s) from '/data/plugins': my-extension.jar"
#      "Discovered extension: … (my-extension)"
```

## Configuration

Each extension reads its own block under `extensions.<id>`, with the same
`${ENV_VAR}` substitution used everywhere else (keep secrets in the environment):

```yaml
extensions:
  http-ingest:
    token: ${INGEST_TOKEN}
  health-reporter:
    url: https://registry.example.com/report
    interval_seconds: 60
```

## Worked example: an authenticated HTTP ingest extension

Adds `POST /api/ingest` — a bearer-token-guarded endpoint that accepts an email
as JSON and pushes it through the pipeline. (This is exactly the shape of a
private "manager relay" or "agent send" surface: keep it as a drop-in jar instead
of in the core.)

```kotlin
package com.example.posterboy.ingest

import io.posterboy.plugin.Extension
import io.posterboy.plugin.ExtensionContext
import io.posterboy.smtp.InboundEmail
import org.slf4j.LoggerFactory

class HttpIngestExtension : Extension {
    override val id = "http-ingest"
    override val name = "HTTP Ingest"

    private val log = LoggerFactory.getLogger(javaClass)
    private var token: String = ""

    override fun initialize(ctx: ExtensionContext) {
        token = (ctx.config["token"] as? String).orEmpty()
        val app = ctx.javalin ?: run {
            log.warn("[http-ingest] admin server disabled; endpoint not registered")
            return
        }
        app.post("/api/ingest") { c ->
            // Auth: constant-token bearer (swap in JWT/JWKS verification if you like).
            val auth = c.header("Authorization")
            if (token.isEmpty() || auth != "Bearer $token") {
                c.status(401).json(mapOf("error" to "unauthorized")); return@post
            }
            @Suppress("UNCHECKED_CAST")
            val body = c.bodyAsClass(Map::class.java) as Map<String, Any?>
            val to = (body["to"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
            val subject = body["subject"] as? String ?: ""
            if (to.isEmpty() || subject.isBlank()) {
                c.status(400).json(mapOf("error" to "'to' and 'subject' required")); return@post
            }
            ctx.submit(
                InboundEmail(
                    from = body["from"] as? String ?: "ingest@localhost",
                    to = to,
                    subject = subject,
                    bodyPlain = body["body"] as? String,
                    bodyHtml = null,
                    attachments = emptyList(),
                )
            )
            c.json(mapOf("status" to "ok"))
        }
        log.info("[http-ingest] registered POST /api/ingest")
    }
}
```

Register it in the jar's `META-INF/services/io.posterboy.plugin.Extension`:

```
com.example.posterboy.ingest.HttpIngestExtension
```

Configure and run:

```yaml
extensions:
  http-ingest:
    token: ${INGEST_TOKEN}
```

```bash
curl -X POST http://localhost:8080/api/ingest \
  -H "Authorization: Bearer $INGEST_TOKEN" -H "Content-Type: application/json" \
  -d '{"to":["alerts@example.com"],"subject":"deploy done","body":"v1.2.3 is live"}'
```

## Worked example: a health reporter extension

Registers Posterboy with an external service registry on a background loop — no
HTTP routes, just a lifecycle-managed daemon thread. (This is how a private
"report to our monitoring registry" integration stays out of the core.)

```kotlin
package com.example.posterboy.report

import io.posterboy.plugin.Extension
import io.posterboy.plugin.ExtensionContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.concurrent.thread

class HealthReporterExtension : Extension {
    override val id = "health-reporter"

    @Volatile private var running = true
    private var worker: Thread? = null

    override fun initialize(ctx: ExtensionContext) {
        val url = ctx.config["url"] as? String ?: return
        val intervalMs = ((ctx.config["interval_seconds"] as? Number)?.toLong() ?: 60L) * 1000
        val client = HttpClient.newHttpClient()
        worker = thread(name = "health-reporter", isDaemon = true) {
            while (running) {
                runCatching {
                    val req = HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""{"id":"posterboy","status":"up"}"""))
                        .build()
                    client.send(req, HttpResponse.BodyHandlers.discarding())
                }   // errors swallowed — a reporter must never affect the host app
                Thread.sleep(intervalMs)
            }
        }
    }

    override fun shutdown() {
        running = false
        worker?.interrupt()
    }
}
```

## Building an extension as an external jar

An extension jar depends on Posterboy's API **`compileOnly`** — those classes are
provided by the host app at runtime, so don't bundle them.

`build.gradle.kts` for your extension project:

```kotlin
plugins { kotlin("jvm") version "2.1.10" }

repositories { mavenCentral() }

dependencies {
    // Posterboy API — provided by the host at runtime. Point this at the
    // posterboy jar (e.g. a local file or your own published artifact).
    compileOnly(files("libs/posterboy-0.1.0-all.jar"))
    // Javalin is only needed if your extension registers HTTP routes:
    compileOnly("io.javalin:javalin:6.4.0")
    // Bundle anything the core does NOT provide (your own libs) normally.
}

kotlin { jvmToolchain(21) }
```

Include your provider file at
`src/main/resources/META-INF/services/io.posterboy.plugin.Extension` (and/or
`…DeliveryPlugin`), build the jar, and drop it into the plugins directory.

> If your extension bundles its own dependencies, build a fat jar (e.g. the
> `shadow` plugin) so those classes ship inside it — the host app won't provide
> them.

## Lifecycle & ordering

1. Posterboy starts: config loads, delivery plugins are discovered/initialized,
   the SMTP listener starts.
2. Extensions are **discovered** over the drop-in classloader.
3. The admin app is built and all core routes are registered.
4. Extensions are **initialized** with the not-yet-started app — this is where
   HTTP extensions add routes and background extensions start threads.
5. The admin server starts accepting requests.
6. On shutdown, each initialized extension's `shutdown()` is called.

Notes:

- Register routes during `initialize`; the app hasn't started yet, which is
  exactly when Javalin wants routes added.
- A failing extension is logged and skipped — it never takes down the app or the
  other extensions.
- The admin UI is unauthenticated by default; if your extension adds a sensitive
  route, implement its own auth (as the ingest example does).

## Checklist

- [ ] Class implements `Extension` with a stable `id` and a no-arg constructor.
- [ ] Registered in `META-INF/services/io.posterboy.plugin.Extension`.
- [ ] Reads config from `ctx.config`; secrets via `${ENV_VAR}`.
- [ ] Handles `ctx.javalin == null` (admin disabled) gracefully.
- [ ] Background work is a daemon thread stopped in `shutdown()`.
- [ ] Built with the Posterboy API as `compileOnly`; own deps bundled.
- [ ] Jar dropped into the plugins directory; startup log shows it discovered.
```
