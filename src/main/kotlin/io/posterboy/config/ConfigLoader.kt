package io.posterboy.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory
import java.io.File

object ConfigLoader {

    private val log = LoggerFactory.getLogger(ConfigLoader::class.java)

    private val ENV_VAR_PATTERN = Regex("""\$\{(\w+)}""")

    private val yamlFactory = YAMLFactory()
        .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)

    val jsonMapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .registerModule(JavaTimeModule())
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)

    val mapper: ObjectMapper = ObjectMapper(yamlFactory)
        .registerKotlinModule()
        .registerModule(JavaTimeModule())
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(SerializationFeature.INDENT_OUTPUT, true)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)
        .setSerializationInclusion(JsonInclude.Include.NON_EMPTY)

    fun load(path: String): PosterboyConfig {
        val file = File(path)
        require(file.exists()) { "Config file not found: $path" }

        log.info("Loading config from: {}", path)
        val raw = substituteEnvVars(file.readText())
        return mapper.readValue(raw, PosterboyConfig::class.java)
    }

    fun parse(yaml: String): PosterboyConfig {
        val raw = substituteEnvVars(yaml)
        return mapper.readValue(raw, PosterboyConfig::class.java)
    }

    fun toYaml(config: PosterboyConfig): String {
        return mapper.writeValueAsString(config)
    }

    fun save(path: String, config: PosterboyConfig) {
        val file = File(path)
        log.info("Saving config to: {}", path)
        file.writeText(toYaml(config))
    }

    private fun substituteEnvVars(content: String): String {
        return ENV_VAR_PATTERN.replace(content) { match ->
            val envVar = match.groupValues[1]
            System.getenv(envVar) ?: run {
                log.warn("Environment variable not set: {}", envVar)
                match.value
            }
        }
    }
}
