# Writing Posterboy Plugins

Every destination Posterboy can deliver to — Discord, Gmail, and anything you
add — is a **plugin**. This guide explains the plugin model end to end and walks
through building, registering, configuring, testing, and packaging a new one.

- [The model in one minute](#the-model-in-one-minute)
- [The `DeliveryPlugin` interface](#the-deliveryplugin-interface)
- [What your plugin receives](#what-your-plugin-receives)
- [How a plugin gets selected](#how-a-plugin-gets-selected)
- [Discovery: the ServiceLoader registry](#discovery-the-serviceloader-registry)
- [Lifecycle: initialize, deliver, shutdown](#lifecycle-initialize-deliver-shutdown)
- [Configuration and secrets](#configuration-and-secrets)
- [Worked example: an ntfy plugin](#worked-example-an-ntfy-plugin)
- [Testing your plugin](#testing-your-plugin)
- [Error handling and delivery semantics](#error-handling-and-delivery-semantics)
- [Templates](#templates)
- [Packaging as an external JAR](#packaging-as-an-external-jar)
- [Checklist](#checklist)

## The model in one minute

A plugin is a class that implements `io.posterboy.plugin.DeliveryPlugin`, has a
stable string **`id`**, and knows how to **`deliver`** one email to one
destination. You:

1. Implement the interface.
2. Register the fully-qualified class name in a `ServiceLoader` file.
3. Reference the plugin's `id` from a routing `action` and give it config under
   `plugins.<id>` in `posterboy.yml`.

At startup Posterboy discovers every registered plugin, calls `initialize` with
that plugin's config block, and from then on hands each routed message to the
matching plugin's `deliver`.

## The `DeliveryPlugin` interface

```kotlin
package io.posterboy.plugin

interface DeliveryPlugin {
    /** Stable identifier, referenced by routing actions and the config key. */
    val id: String

    /** Human-readable name (shown in the admin UI / logs). */
    val name: String

    /** The sender identity this plugin sends as, if any (used by templates). */
    val senderAddress: String get() = ""

    /** Called once at startup/reload with this plugin's config map (may be empty). */
    fun initialize(config: Map<String, Any>)

    /** Deliver one message to this destination. Runs in a coroutine. */
    suspend fun deliver(email: InboundEmail, context: RoutingContext)

    /** Release resources on shutdown/reload. Optional. */
    fun shutdown() {}
}
```

- **`id`** is the contract with routing and config. If `id = "ntfy"`, then a rule
  uses `action: ntfy` and its config lives under `plugins.ntfy`.
- **`name`** is cosmetic.
- **`senderAddress`** is only relevant if your plugin sends *as* an address (like
  Gmail's alias); it backs the `{{sender}}` template. Leave it as `""` otherwise.

## What your plugin receives

`deliver` gets two arguments.

### `InboundEmail`

```kotlin
data class InboundEmail(
    val from: String,                 // envelope/header sender
    val to: List<String>,             // recipients this message was addressed to
    val subject: String,
    val bodyPlain: String?,           // text/plain part, if any
    val bodyHtml: String?,            // text/html part, if any
    val attachments: List<AttachmentMeta>,       // filename, mimeType, sizeBytes
    val fullAttachments: List<Attachment> = emptyList()  // meta + raw bytes
)
```

Most plugins use `from`, `to`, `subject`, and `bodyPlain ?: bodyHtml`.
`attachments` carries metadata only; `fullAttachments` (raw bytes) is populated on
paths that retain the original MIME (e.g. the approval queue) — treat it as
possibly empty.

### `RoutingContext`

```kotlin
data class RoutingContext(
    val matchedRule: String?,               // name/description of the rule that fired
    val pluginConfig: Map<String, Any>      // the matched rule's per-route `config`
)
```

`pluginConfig` is the **per-route** `config:` block from the rule that selected
you — distinct from the global config passed to `initialize`. It lets one rule
customize a delivery (e.g. route different senders to different channels) without
a separate plugin instance. Example rule:

```yaml
routing:
  rules:
    - match: { from_domain: "ci.example.com" }
      action: ntfy
      config:
        topic: builds      # -> context.pluginConfig["topic"] == "builds"
```

## How a plugin gets selected

The routing engine evaluates every rule against each message and collects the
`action`/`actions` of all that match (fan-out, deduplicated by plugin id). For
each selected plugin id, Posterboy looks up the initialized plugin and calls
`deliver`. If no rule matches a recipient, `default_action` is used. A plugin id
that isn't an initialized plugin is logged and skipped.

So to be reachable, a plugin must be (1) discovered, (2) initialized (has a
config block, or is configured via the admin UI), and (3) referenced by a routing
action or the default.

## Discovery: the ServiceLoader registry

Posterboy finds plugins with Java's `ServiceLoader`. Register your class by adding
its fully-qualified name (one per line) to:

```
src/main/resources/META-INF/services/io.posterboy.plugin.DeliveryPlugin
```

The shipped file looks like:

```
io.posterboy.plugins.discord.DiscordPlugin
io.posterboy.plugins.gmail.GmailPlugin
```

Add your line:

```
io.posterboy.plugins.ntfy.NtfyPlugin
```

> The `shadowJar` build merges `META-INF/services` files across dependencies
> (`mergeServiceFiles()` in `build.gradle.kts`), so plugins bundled in separate
> JARs are combined correctly in the fat jar.

Your plugin class must have a **public no-argument constructor** (a plain Kotlin
`class NtfyPlugin : DeliveryPlugin { … }` does).

## Lifecycle: initialize, deliver, shutdown

`PluginRegistry.discoverAndInitialize` runs at startup and on every config reload:

1. Every registered plugin is discovered and added to the registry.
2. For each, the config block `plugins.<id>` is looked up.
   - If `enabled: false` is present, the plugin is marked **disabled** and skipped.
   - If the block is **non-empty**, `initialize(config)` is called; on success the
     plugin is marked **initialized** (i.e. eligible to receive deliveries). If
     `initialize` throws, the error is logged and the plugin stays uninitialized
     (it won't receive messages) — the rest of the app keeps running.
   - If the block is **empty/absent**, the plugin is discovered but left
     uninitialized, available to be configured later via the admin UI.
3. On shutdown/reload, `shutdown()` is called on initialized plugins.

Implications for your code:

- Do all config parsing and resource setup in `initialize`. Throw a clear
  exception if required config is missing — it's logged and disables just your
  plugin.
- `deliver` should assume `initialize` succeeded.
- Free resources (HTTP clients, threads, connections) in `shutdown` so reloads
  don't leak.

## Configuration and secrets

Your config block is plain parsed YAML (`Map<String, Any>`), so read values with
Kotlin casts and validate:

```kotlin
override fun initialize(config: Map<String, Any>) {
    baseUrl = (config["base_url"] as? String)?.trimEnd('/')
        ?: throw IllegalArgumentException("ntfy plugin requires 'base_url'")
    defaultTopic = config["default_topic"] as? String ?: "posterboy"
}
```

**Never hard-code secrets.** Put `${ENV_VAR}` placeholders in `posterboy.yml`;
`ConfigLoader` substitutes them from the environment before your plugin sees them:

```yaml
plugins:
  ntfy:
    base_url: https://ntfy.sh
    token: ${NTFY_TOKEN}     # your plugin receives the resolved value
```

Nested maps and lists come through as `Map<String, Any>` / `List<*>`; cast
defensively (`as? Map<*, *>`), exactly as the Discord plugin does for its
`aliases` block.

## Worked example: an ntfy plugin

A complete plugin that pushes each email to an [ntfy](https://ntfy.sh) topic. It
demonstrates config parsing, using the per-route `pluginConfig`, an HTTP call, and
clean shutdown.

**1. The plugin class** — `src/main/kotlin/io/posterboy/plugins/ntfy/NtfyPlugin.kt`:

```kotlin
package io.posterboy.plugins.ntfy

import io.posterboy.plugin.DeliveryPlugin
import io.posterboy.plugin.RoutingContext
import io.posterboy.smtp.InboundEmail
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

class NtfyPlugin : DeliveryPlugin {

    override val id = "ntfy"
    override val name = "ntfy Push"

    private val log = LoggerFactory.getLogger(NtfyPlugin::class.java)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var baseUrl: String = "https://ntfy.sh"
    private var defaultTopic: String = "posterboy"
    private var token: String? = null

    override fun initialize(config: Map<String, Any>) {
        baseUrl = (config["base_url"] as? String)?.trimEnd('/') ?: baseUrl
        defaultTopic = config["default_topic"] as? String ?: defaultTopic
        token = (config["token"] as? String)?.takeIf { it.isNotBlank() }
        log.info("ntfy plugin initialized (base_url={}, default_topic={})", baseUrl, defaultTopic)
    }

    override suspend fun deliver(email: InboundEmail, context: RoutingContext) {
        // Per-route override wins over the configured default.
        val topic = (context.pluginConfig["topic"] as? String) ?: defaultTopic
        val body = (email.bodyPlain ?: email.bodyHtml ?: "").take(4000)

        val builder = Request.Builder()
            .url("$baseUrl/$topic")
            .header("Title", email.subject.ifBlank { "(no subject)" })
            .header("Tags", "email")
            .post(body.toRequestBody())
        token?.let { builder.header("Authorization", "Bearer $it") }

        client.newCall(builder.build()).execute().use { resp ->
            if (resp.isSuccessful) {
                log.info("Delivered to ntfy topic '{}' (from={})", topic, email.from)
            } else {
                // Throwing marks THIS delivery failed; other plugins are unaffected.
                throw IllegalStateException("ntfy returned ${resp.code}")
            }
        }
    }

    override fun shutdown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
```

**2. Register it** — append to
`src/main/resources/META-INF/services/io.posterboy.plugin.DeliveryPlugin`:

```
io.posterboy.plugins.ntfy.NtfyPlugin
```

**3. Configure it** — in `config/posterboy.yml`:

```yaml
plugins:
  ntfy:
    base_url: https://ntfy.sh
    default_topic: home-alerts
    token: ${NTFY_TOKEN}          # optional

routing:
  rules:
    # CI mail -> the 'builds' topic; everything else -> the default topic.
    - match: { from_domain: "ci.example.com" }
      action: ntfy
      config: { topic: builds }
    - action: ntfy                # universal rule: all other mail to ntfy too
```

That's a complete, working destination — no changes to the core were needed.

## Testing your plugin

Posterboy uses `kotlin-test` + JUnit 5. Unit-test parsing and payload building
without any network. For HTTP plugins, point the client at a local stub server
(e.g. OkHttp's `MockWebServer`, or a tiny Javalin/`HttpServer`), or refactor the
payload construction into a pure function you can assert on — the way
`DiscordFormatterTest` tests the Discord message formatter directly.

```kotlin
class NtfyPluginTest {
    @Test
    fun `missing config falls back to defaults`() {
        val p = NtfyPlugin()
        p.initialize(emptyMap())          // should not throw; uses defaults
        // assert observable defaults via a delivery against a MockWebServer…
    }
}
```

Run with `./gradlew test`.

## Error handling and delivery semantics

- **Isolation.** Posterboy calls each plugin inside its own try/catch. If your
  `deliver` throws, the failure is logged and the *other* destinations for that
  message still run. Throw to signal a failed delivery; return normally on success.
- **No automatic retry.** A throw does not re-queue the message. If you need
  retries, implement them inside `deliver` (with your own bounds/backoff).
- **Blocking work.** `deliver` is a `suspend` function invoked via `runBlocking`
  per message. Keep network timeouts tight (see the OkHttp setup above) so one
  slow destination doesn't stall the pipeline.
- **Idempotency.** The same message may be delivered again if you build retry
  logic; make external effects safe to repeat where you can.
- **MX cache.** On a plugin exception, Posterboy evicts the recipient domains from
  the MX cache so `to_domain_has_mx` routing self-heals — you don't manage this.

## Templates

Before `deliver`, Posterboy resolves simple templates in the subject and body.
Currently `{{sender}}` expands to the delivering plugin's `senderAddress`. If your
plugin sends *as* an identity (like Gmail's alias), set `senderAddress` so
operators can put `{{sender}}` in templated content. If not, ignore this.

## Packaging as an external JAR

You don't have to fork the repo. Because discovery is `ServiceLoader`-based, a
plugin can live in its own JAR:

1. Create a JAR that depends on Posterboy's `plugin` API (`DeliveryPlugin`,
   `RoutingContext`, `InboundEmail`) and your libraries.
2. Include your own `META-INF/services/io.posterboy.plugin.DeliveryPlugin` naming
   your class.
3. Put the JAR on Posterboy's classpath (add it to the image / classpath). The
   `shadowJar` `mergeServiceFiles()` step ensures multiple provider files merge.

In-tree plugins (added under `src/main/kotlin/io/posterboy/plugins/…`) are the
simplest path and are what the built-ins use.

## Checklist

- [ ] Class implements `DeliveryPlugin` with a stable `id` and a no-arg constructor.
- [ ] Registered in `META-INF/services/io.posterboy.plugin.DeliveryPlugin`.
- [ ] `initialize` validates config and throws clearly on missing required values.
- [ ] Secrets read via `${ENV_VAR}` placeholders, never hard-coded.
- [ ] `deliver` handles the per-route `context.pluginConfig` where useful.
- [ ] Network calls have timeouts; `shutdown` releases resources.
- [ ] A routing `action: <id>` (and/or `default_action`) references the plugin.
- [ ] Tests cover config parsing and payload building.
```
