package com.grardb.thorcuts.core.config

import com.grardb.thorcuts.core.model.Action
import com.grardb.thorcuts.core.model.Control
import com.grardb.thorcuts.core.model.Device
import com.grardb.thorcuts.core.model.EvdevType
import com.grardb.thorcuts.core.model.ModifierKey
import com.grardb.thorcuts.core.model.Scope
import com.grardb.thorcuts.core.model.ScreenTarget
import com.grardb.thorcuts.core.model.Shortcut
import com.grardb.thorcuts.core.model.Trigger
import com.grardb.thorcuts.core.model.TriggerStyle
import com.grardb.thorcuts.core.rules.ConflictDetector
import com.grardb.thorcuts.core.rules.RuleTableFormat
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The whole file, or why not. */
sealed interface DecodeResult {
    data class Ok(
        val config: ThorcutsConfig,
        /** §10: invalid shortcuts are dropped with a warning, not fatal to the file. */
        val dropped: List<Dropped>,
        /** The file's version when it was older than [ConfigCodec.SCHEMA_VERSION]; else null. */
        val migratedFrom: Int?,
    ) : DecodeResult

    /** The file as a whole is unusable: not JSON, not an object, no usable schemaVersion, a
     *  version this app does not know, `shortcuts` not an array. */
    data class Failed(val reason: String) : DecodeResult
}

/** One shortcut the decoder could not accept. [id] is null when the entry had no usable id. */
data class Dropped(val index: Int, val id: String?, val reason: String)

/**
 * JSON in and out for [ThorcutsConfig] (parent spec §10; layout in the config spec, "The file").
 *
 * Reading is forgiving per shortcut and strict per file: any one entry the walk cannot read,
 * or that a model `init` refuses, or that repeats an earlier entry's id or (enabled, where
 * both apply) its trigger, becomes a [Dropped] and the walk goes on; a file whose frame is
 * wrong is [DecodeResult.Failed]. Writing is total and canonical: known members in a fixed
 * order, sets sorted, then the unknown members as they were read.
 */
object ConfigCodec {
    /** Still 1 when `OpenThorcuts` became `OpenApp` (2026-10-18): the owner's device is the
     *  only install, so the old kind was retired without a migration — a file that still
     *  carries it drops that entry with "unknown action kind". */
    const val SCHEMA_VERSION = 1

    private const val SCHEMA_VERSION_KEY = "schemaVersion"
    private const val SHORTCUTS_KEY = "shortcuts"
    private val SHORTCUT_KEYS = setOf("id", "enabled", "trigger", "action", "scope")

    @OptIn(ExperimentalSerializationApi::class)
    private val writer = Json { prettyPrint = true; prettyPrintIndent = "  " }

    private val migrations = Migrations()

    // ── reading ───────────────────────────────────────────────────────────────────────

    fun decode(text: String): DecodeResult = decode(text, migrations)

    /**
     * [decode] with the chain and the target version injected. At [SCHEMA_VERSION] 1 the
     * migration branch is unreachable — a version equal to the current skips it, one above
     * or below 1 is Failed — so its wiring (Failed when the chain throws, migratedFrom when
     * it runs) is tested through this overload with a target of 2. Internal, as Migrations is.
     */
    internal fun decode(
        text: String, migrations: Migrations, current: Int = SCHEMA_VERSION,
    ): DecodeResult {
        // The parser's own reason for an empty input ("unexpected end of the input at path:
        // $") tells a user nothing they can act on, and a 0-byte pick is common enough to name.
        if (text.isBlank()) return DecodeResult.Failed("not JSON: the file is empty")
        val root = try {
            Json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            val detail = e.message?.lineSequence()?.first() ?: "unparseable"
            return DecodeResult.Failed("not JSON: $detail")
        }
        val raw = root as? JsonObject
            ?: return DecodeResult.Failed("the top level is not an object")
        val version = raw[SCHEMA_VERSION_KEY]?.let { (it as? JsonPrimitive)?.plainInt() }
            ?: return DecodeResult.Failed("schemaVersion is missing or not an integer")
        if (version < 1) return DecodeResult.Failed("schemaVersion $version is below 1")
        if (version > current) {
            return DecodeResult.Failed(
                "schemaVersion $version is newer than this app understands ($current)"
            )
        }
        val doc = if (version == current) {
            raw
        } else {
            try {
                migrations.migrate(raw, version, current)
            } catch (e: RuntimeException) {
                // A hole in the chain is an IllegalStateException; a step's own `require` an
                // IllegalArgumentException. Either is this file's failure, not a throw out of
                // a method whose contract is "never throws on input" (T2-1).
                return DecodeResult.Failed(e.message ?: "migration failed")
            }
        }
        val entries = doc[SHORTCUTS_KEY] as? JsonArray
            ?: return DecodeResult.Failed("shortcuts is missing or not an array")

        val shortcuts = ArrayList<Shortcut>(entries.size)
        val dropped = ArrayList<Dropped>()
        val perShortcut = LinkedHashMap<String, Map<String, JsonElement>>()
        for ((index, entry) in entries.withIndex()) {
            val obj = entry as? JsonObject
            if (obj == null) {
                dropped += Dropped(index, null, "not an object")
                continue
            }
            val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.takeIf { it.isNotBlank() }
            val shortcut = try {
                readShortcut(obj)
            } catch (e: IllegalArgumentException) {
                dropped += Dropped(index, id, e.message ?: "invalid")
                continue
            }
            if (shortcuts.any { it.id == shortcut.id }) {
                dropped += Dropped(index, id, "duplicate id")
                continue
            }
            // The config's own invariant (Rule 2), applied here so one repeated trigger costs
            // that entry and not the whole file. The earlier one is kept — the one that fired,
            // except when the later is a PRESS and the earlier a HOLD on the same sets (the
            // matcher prefers the PRESS; ConflictDetector.sameTrigger). Only a file written
            // before Rule 2, or by hand, can hold such a pair.
            val conflict = ConflictDetector.conflict(shortcuts, shortcut)
            if (conflict != null) {
                dropped += Dropped(index, id, conflict.reason)
                continue
            }
            shortcuts += shortcut
            val extra = obj.filterKeys { it !in SHORTCUT_KEYS }
            if (extra.isNotEmpty()) perShortcut[shortcut.id] = extra
        }
        val top = doc.filterKeys { it != SCHEMA_VERSION_KEY && it != SHORTCUTS_KEY }
        val config = ThorcutsConfig(shortcuts, UnknownFields(top, perShortcut))
        val migratedFrom = version.takeIf { it != current }
        return DecodeResult.Ok(config, dropped, migratedFrom)
    }

    private fun readShortcut(o: JsonObject): Shortcut = Shortcut(
        id = o.string("id"),
        trigger = readTrigger(o.obj("trigger")),
        action = readAction(o.obj("action")),
        scope = o["scope"]?.let { readScope(it.asObject("scope")) } ?: Scope.Global(),
        enabled = o["enabled"]?.asBool("enabled") ?: true,
    )

    private fun readTrigger(o: JsonObject): Trigger = Trigger(
        modifiers = o.array("modifiers")
            .mapIndexed { i, m -> m.asEnum<ModifierKey>("modifiers[$i]") }
            .toSet(),
        buttons = (o["buttons"]?.asArray("buttons") ?: emptyList())
            .mapIndexed { i, b -> readControl(b.asObject("buttons[$i]")) }
            .toSet(),
        style = o.enum<TriggerStyle>("style"),
        holdMs = o["holdMs"]?.asInt("holdMs"),
    )

    private fun readControl(o: JsonObject): Control = Control(
        device = o.enum<Device>("device"),
        type = when (val t = o.string("type")) {
            "KEY" -> EvdevType.KEY
            "ABS" -> EvdevType.ABS
            else -> fail("type: unknown name '$t'")
        },
        code = o.int("code"),
        value = o.int("value"),
    )

    private fun readAction(o: JsonObject): Action {
        val kind = o.string("kind")
        val screen = o["screen"]?.asEnum<ScreenTarget>("screen")
        val pkg = o["package"]?.asString("package")
        fun needsScreen(): ScreenTarget = screen ?: fail("$kind needs a screen")
        fun noScreen() { if (screen != null) fail("$kind takes no screen") }
        fun noPackage() { if (pkg != null) fail("$kind takes no package") }
        return when (kind) {
            "SwapScreens" -> { noScreen(); noPackage(); Action.SwapScreens }
            "MoveFocusedToOtherScreen" -> {
                noScreen(); noPackage(); Action.MoveFocusedToOtherScreen
            }
            "CloseScreen" -> { noPackage(); Action.CloseScreen(needsScreen()) }
            "GoHome" -> { noPackage(); Action.GoHome(needsScreen()) }
            "OpenApp" -> Action.OpenApp(pkg ?: fail("$kind needs a package"), needsScreen())
            else -> fail("unknown action kind '$kind'")
        }
    }

    private fun readScope(o: JsonObject): Scope = when (val kind = o.string("kind")) {
        "Global" -> Scope.Global(
            (o["excluded"]?.asArray("excluded") ?: emptyList())
                .mapIndexed { i, p -> p.asString("excluded[$i]") }
                .toSet()
        )
        "Apps" -> Scope.Apps(
            o.array("included").mapIndexed { i, p -> p.asString("included[$i]") }.toSet()
        )
        else -> fail("unknown scope kind '$kind'")
    }

    // ── writing ───────────────────────────────────────────────────────────────────────

    /** Ends with one newline, so the file on disk is an ordinary text file. */
    fun encode(config: ThorcutsConfig): String =
        writer.encodeToString(JsonElement.serializer(), toJson(config)) + "\n"

    private fun toJson(config: ThorcutsConfig): JsonObject = buildJsonObject {
        put(SCHEMA_VERSION_KEY, SCHEMA_VERSION)
        putJsonArray(SHORTCUTS_KEY) {
            for (s in config.shortcuts) {
                add(shortcutJson(s, config.unknown.perShortcut[s.id].orEmpty()))
            }
        }
        // An unknown member named like a known one would overwrite it (T2-3): reachable only
        // through the internal UnknownFields constructor, since decode filters, and refused
        // here as well so that what this writes always reads back.
        for ((key, value) in config.unknown.top) {
            if (key != SCHEMA_VERSION_KEY && key != SHORTCUTS_KEY) put(key, value)
        }
    }

    private fun shortcutJson(s: Shortcut, extra: Map<String, JsonElement>): JsonObject =
        buildJsonObject {
            put("id", s.id)
            put("enabled", s.enabled)
            putJsonObject("trigger") {
                putJsonArray("modifiers") {
                    for (m in ModifierKey.entries) if (m in s.trigger.modifiers) add(m.name)
                }
                putJsonArray("buttons") {
                    for (b in s.trigger.buttons.sortedWith(RuleTableFormat.BUTTON_ORDER)) {
                        add(controlJson(b))
                    }
                }
                put("style", s.trigger.style.name)
                if (s.trigger.style == TriggerStyle.HOLD) put("holdMs", s.trigger.holdMs!!)
            }
            putJsonObject("action") {
                when (val a = s.action) {
                    Action.SwapScreens -> put("kind", "SwapScreens")
                    Action.MoveFocusedToOtherScreen -> put("kind", "MoveFocusedToOtherScreen")
                    is Action.CloseScreen -> screenAction("CloseScreen", a.screen)
                    is Action.GoHome -> screenAction("GoHome", a.screen)
                    is Action.OpenApp -> {
                        put("kind", "OpenApp")
                        put("package", a.packageName)
                        put("screen", a.screen.name)
                    }
                }
            }
            putJsonObject("scope") {
                when (val sc = s.scope) {
                    is Scope.Global -> {
                        put("kind", "Global")
                        putJsonArray("excluded") { for (p in sc.excluded.sorted()) add(p) }
                    }
                    is Scope.Apps -> {
                        put("kind", "Apps")
                        putJsonArray("included") { for (p in sc.included.sorted()) add(p) }
                    }
                }
            }
            for ((key, value) in extra) if (key !in SHORTCUT_KEYS) put(key, value)
        }

    private fun JsonObjectBuilder.screenAction(kind: String, screen: ScreenTarget) {
        put("kind", kind)
        put("screen", screen.name)
    }

    private fun controlJson(c: Control): JsonObject = buildJsonObject {
        put("device", c.device.name)
        put("type", if (c.type == EvdevType.KEY) "KEY" else "ABS")
        put("code", c.code)
        put("value", c.value)
    }

    // ── the walk's vocabulary: every failure is an IllegalArgumentException naming the member ──

    private fun fail(reason: String): Nothing = throw IllegalArgumentException(reason)

    private fun JsonObject.member(key: String): JsonElement = this[key] ?: fail("$key is missing")
    private fun JsonObject.string(key: String): String = member(key).asString(key)
    private fun JsonObject.int(key: String): Int = member(key).asInt(key)
    private fun JsonObject.obj(key: String): JsonObject = member(key).asObject(key)
    private fun JsonObject.array(key: String): JsonArray = member(key).asArray(key)
    private inline fun <reified E : Enum<E>> JsonObject.enum(key: String): E =
        member(key).asEnum<E>(key)

    /** An integer written as a number — `"307"` is a string, and is refused. */
    private fun JsonPrimitive.plainInt(): Int? = if (isString) null else intOrNull

    private fun JsonElement.asString(key: String): String =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail("$key is not a string")

    /** An integral beyond the Int range says so; everything else that is not a plain
     *  integer reads "not an integer". */
    private fun JsonElement.asInt(key: String): Int {
        val p = this as? JsonPrimitive
        return p?.plainInt() ?: fail(
            if (p != null && !p.isString && p.longOrNull != null) "$key is out of range"
            else "$key is not an integer"
        )
    }

    private fun JsonElement.asBool(key: String): Boolean =
        (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            ?: fail("$key is not a boolean")

    private fun JsonElement.asObject(key: String): JsonObject =
        this as? JsonObject ?: fail("$key is not an object")

    private fun JsonElement.asArray(key: String): JsonArray =
        this as? JsonArray ?: fail("$key is not an array")

    private inline fun <reified E : Enum<E>> JsonElement.asEnum(key: String): E {
        val name = asString(key)
        return enumValues<E>().firstOrNull { it.name == name }
            ?: fail("$key: unknown name '$name'")
    }
}
