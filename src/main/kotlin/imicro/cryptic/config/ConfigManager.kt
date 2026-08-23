package imicro.cryptic.config

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import imicro.cryptic.gui.Module
import imicro.cryptic.gui.ModuleRegistry
import imicro.cryptic.hud.Hud
import imicro.cryptic.gui.ColorModuleSetting
import imicro.cryptic.gui.DropdownModuleSetting
import imicro.cryptic.gui.RangeModuleSetting
import imicro.cryptic.gui.SliderModuleSetting
import imicro.cryptic.gui.TextModuleSetting
import imicro.cryptic.gui.ToggleModuleSetting
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.awt.Desktop
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.text.Normalizer
import java.util.Base64
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension

data class ProfileEntry(
    val name: String,
    val path: Path,
    val modifiedAt: Long,
    val active: Boolean,
    val builtIn: Boolean,
) {
    /**
     * Dear ImGui addresses widgets by string, and the profile page redraws every
     * card each frame, so a card's identifiers are built once with the entry.
     */
    val widgetIds = ProfileWidgetIds(path.fileName.toString())
}

/** Pre-built Dear ImGui identifiers for one profile card. */
class ProfileWidgetIds(fileName: String) {
    val card = "##profile_$fileName"
    val rename = "##rename_$fileName"
    val export = "##export_$fileName"
    val import = "##import_$fileName"
    val folder = "##folder_$fileName"
    val delete = "##delete_$fileName"
}

/** Human-readable local JSON plus portable, versioned Base64 profile strings. */
object ConfigManager {
    const val EXPORT_PREFIX = "CRYPTIC_PROFILE_V1:"

    private const val FORMAT = "cryptic-profile"
    private const val FORMAT_VERSION = 1
    private const val DEFAULT_PROFILE = "Default"

    /** The profile the jar ships with, used on a first run and to seed "Default". */
    private const val SHIPPED_DEFAULT_PATH = "/assets/cryptic/default_profile.json"
    private const val SAVE_DEBOUNCE_NANOS = 250_000_000L

    private val logger = LoggerFactory.getLogger("cryptic/config")
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val configDirectory = FabricLoader.getInstance().configDir.resolve("cryptic")
    val profileDirectory: Path = configDirectory.resolve("profiles")
    private val liveConfigPath = configDirectory.resolve("config.json")

    private var initialized = false
    private var activeProfile = DEFAULT_PROFILE
    private var lastSavedJson = ""
    private var savedFingerprint = 0L
    private var pendingFingerprint: Long? = null
    private var pendingSince = 0L
    private var cachedProfiles = emptyList<ProfileEntry>()
    private val profileNames = mutableMapOf<Path, CachedProfileName>()

    /** A profile's display name lives inside its file, so it is re-read only when that file changes. */
    private class CachedProfileName(val modifiedAt: Long, val size: Long, val name: String)

    fun initialize() {
        if (initialized) return
        initialized = true

        runCatching {
            Files.createDirectories(profileDirectory)
            val defaultPath = profilePath(DEFAULT_PROFILE)
            val shipped = shippedDefault()
            if (!Files.exists(defaultPath)) {
                writeAtomically(defaultPath, shipped?.let(gson::toJson) ?: encodeCurrent(DEFAULT_PROFILE))
            }

            if (Files.exists(liveConfigPath)) {
                val root = parseAndValidate(Files.readString(liveConfigPath))
                activeProfile = root.string("profile")?.takeIf(String::isNotBlank) ?: DEFAULT_PROFILE
                apply(root)
            } else if (shipped != null) {
                // A first run has nothing to restore, so the settings the jar
                // ships with are what it starts from. Every module is off; what
                // the bundled profile carries is a sensible arrangement of each,
                // so switching one on is all it takes.
                apply(shipped)
            }

            persistNow(encodeCurrent(activeProfile))
            refreshProfiles()
        }.onFailure { error ->
            logger.error("Could not initialize Cryptic's config; using in-memory defaults", error)
        }
    }

    /**
     * Called each frame; disk writes happen only after settings stop changing
     * briefly.
     *
     * Noticing that nothing changed has to be cheap, because that is the answer
     * on almost every frame the menu is open. A fingerprint of the live values
     * answers it without building a JSON document, and the document is only
     * produced once the debounce has actually elapsed.
     */
    fun autosave() {
        initialize()
        val fingerprint = fingerprintCurrent()
        if (fingerprint == savedFingerprint) {
            pendingFingerprint = null
            return
        }

        val now = System.nanoTime()
        if (pendingFingerprint != fingerprint) {
            pendingFingerprint = fingerprint
            pendingSince = now
            return
        }
        if (now - pendingSince < SAVE_DEBOUNCE_NANOS) return

        val current = encodeCurrent(activeProfile)
        if (current == lastSavedJson) {
            // The values moved and came back, so there is nothing to write.
            savedFingerprint = fingerprint
            pendingFingerprint = null
            return
        }
        persistNow(current)
    }

    fun flush() {
        initialize()
        val current = encodeCurrent(activeProfile)
        if (current != lastSavedJson) persistNow(current)
    }

    fun profiles(): List<ProfileEntry> {
        initialize()
        return cachedProfiles
    }

    fun createProfile(): Result<String> = runCatching {
        flush()
        val name = uniqueProfileName("Profile")
        writeAtomically(profilePath(name), encodeCurrent(name))
        refreshProfiles()
        name
    }

    fun loadProfile(entry: ProfileEntry): Result<Unit> = runCatching {
        flush()
        val root = parseAndValidate(Files.readString(entry.path))
        apply(root)
        activeProfile = entry.name
        persistNow(encodeCurrent(activeProfile))
        refreshProfiles()
    }

    fun deleteProfile(entry: ProfileEntry): Result<Unit> = runCatching {
        require(!entry.builtIn) { "The Default profile cannot be deleted" }
        Files.deleteIfExists(entry.path)
        if (entry.active) {
            val defaultEntry = cachedProfiles.first { it.builtIn }
            val root = parseAndValidate(Files.readString(defaultEntry.path))
            apply(root)
            activeProfile = DEFAULT_PROFILE
            persistNow(encodeCurrent(activeProfile))
        }
        refreshProfiles()
    }

    fun exportProfile(entry: ProfileEntry): Result<String> = runCatching {
        flush()
        // "Default" stays immutable on disk, but exporting its active card must
        // still capture the settings the player is currently looking at.
        val json = if (entry.active) encodeCurrent(entry.name) else Files.readString(entry.path)
        parseAndValidate(json)
        EXPORT_PREFIX + Base64.getEncoder().encodeToString(json.toByteArray(StandardCharsets.UTF_8))
    }

    fun exportActiveProfile(): Result<String> = runCatching {
        flush()
        val json = encodeCurrent(activeProfile)
        EXPORT_PREFIX + Base64.getEncoder().encodeToString(json.toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * Imports settings into the profile that is already active. No new profile
     * is created, which makes the GUI workflow explicit and hard to misclick.
     */
    fun importIntoProfile(entry: ProfileEntry, encoded: String): Result<String> = runCatching {
        require(entry.active) { "Select this profile before importing into it" }
        require(!entry.builtIn) { "Create and select a custom profile before importing" }
        flush()
        val imported = decodeExport(encoded)
        val requestedName = imported.string("profile")?.takeIf(String::isNotBlank) ?: "Imported"
        val name = uniqueProfileName(requestedName, entry.path)
        apply(imported)
        activeProfile = name
        val importedJson = encodeCurrent(name)
        val destination = profilePath(name)
        writeAtomically(destination, importedJson)
        writeAtomically(liveConfigPath, importedJson)
        if (destination != entry.path) Files.deleteIfExists(entry.path)
        markSaved(importedJson)
        refreshProfiles()
        name
    }

    /** Imports a new stored profile without changing the active profile. */
    fun importProfile(encoded: String): Result<String> = runCatching {
        flush()
        val imported = decodeExport(encoded)
        val requestedName = imported.string("profile")?.takeIf(String::isNotBlank) ?: "Imported"
        val name = uniqueProfileName(requestedName)
        imported.addProperty("profile", name)
        writeAtomically(profilePath(name), gson.toJson(imported))
        refreshProfiles()
        name
    }

    fun renameProfile(entry: ProfileEntry, requestedName: String): Result<String> = runCatching {
        require(!entry.builtIn) { "The Default profile cannot be renamed" }
        flush()
        val name = uniqueProfileName(requestedName, entry.path)
        val destination = profilePath(name)

        val root = parseAndValidate(Files.readString(entry.path))
        root.addProperty("profile", name)
        writeAtomically(destination, gson.toJson(root))
        if (destination != entry.path) Files.deleteIfExists(entry.path)

        if (entry.active) {
            activeProfile = name
            persistNow(encodeCurrent(name))
        } else {
            refreshProfiles()
        }
        name
    }

    fun openProfileDirectory(): Result<Unit> = runCatching {
        Files.createDirectories(profileDirectory)
        val os = System.getProperty("os.name", "").lowercase()
        val command = when {
            os.contains("win") -> listOf("explorer.exe", profileDirectory.toString())
            os.contains("mac") -> listOf("open", profileDirectory.toString())
            else -> listOf("xdg-open", profileDirectory.toString())
        }
        runCatching { ProcessBuilder(command).start() }.getOrElse { commandError ->
            if (!Desktop.isDesktopSupported()) throw commandError
            Desktop.getDesktop().open(profileDirectory.toFile())
        }
    }

    private fun persistNow(json: String) {
        runCatching {
            Files.createDirectories(profileDirectory)
            writeAtomically(liveConfigPath, json)
            if (activeProfile != DEFAULT_PROFILE) writeAtomically(profilePath(activeProfile), json)
            markSaved(json)
            refreshProfiles()
        }.onFailure { logger.error("Could not save Cryptic config", it) }
    }

    private fun markSaved(json: String) {
        lastSavedJson = json
        savedFingerprint = fingerprintCurrent()
        pendingFingerprint = null
    }

    /**
     * An allocation-free digest of everything [encodeCurrent] writes. Values are
     * mixed in by their exact bits, so any change the profile records also
     * changes the digest.
     */
    private fun fingerprintCurrent(): Long {
        var hash = activeProfile.hashCode().toLong()
        val hudElements = Hud.elements
        for (index in hudElements.indices) {
            val element = hudElements[index]
            hash = hash.mix(element.x.toRawBits()).mix(element.y.toRawBits()).mix(element.scale.toRawBits())
        }
        val modules = ModuleRegistry.modules
        for (index in modules.indices) {
            val module = modules[index]
            if (module.supportsToggle) hash = hash.mix(if (module.enabled) 1L else 0L)
            if (module.hasDemoSettings) {
                hash = hash.mix(module.slider.value.toRawBits())
                hash = hash.mix(module.range.lower.toRawBits())
                hash = hash.mix(module.range.upper.toRawBits())
                hash = hash.mix(module.dropdown.selectedIndex.toLong())
            }
            val settings = module.settings
            for (settingIndex in settings.indices) {
                hash = when (val setting = settings[settingIndex]) {
                    is ToggleModuleSetting -> hash.mix(if (setting.value) 1L else 0L)
                    is SliderModuleSetting -> hash.mix(setting.value.toRawBits())
                    is RangeModuleSetting -> hash.mix(setting.lower.toRawBits()).mix(setting.upper.toRawBits())
                    is ColorModuleSetting -> hash.mix(setting.argb.toLong())
                    is DropdownModuleSetting -> hash.mix(setting.selectedIndex.toLong())
                    is TextModuleSetting -> hash.mix(setting.value.hashCode().toLong())
                    else -> hash
                }
            }
        }
        return hash
    }

    private fun Long.mix(value: Long): Long = this * 31L + value

    private fun refreshProfiles() {
        if (!Files.exists(profileDirectory)) {
            cachedProfiles = emptyList()
            profileNames.clear()
            return
        }
        val entries = Files.list(profileDirectory).use { paths ->
            paths.filter { it.isRegularFile() && it.extension.equals("json", ignoreCase = true) }
                .map { path ->
                    val modifiedAt = Files.getLastModifiedTime(path).toMillis()
                    val name = profileName(path, modifiedAt)
                    ProfileEntry(
                        name = name,
                        path = path,
                        modifiedAt = modifiedAt,
                        active = name == activeProfile,
                        builtIn = name == DEFAULT_PROFILE,
                    )
                }
                .toList()
        }
        profileNames.keys.retainAll(entries.mapTo(mutableSetOf(), ProfileEntry::path))
        cachedProfiles = entries.sortedWith(
            compareByDescending<ProfileEntry> { it.builtIn }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        )
    }

    /**
     * Reading and parsing every profile on each save added up once a player kept
     * more than a handful, so a file is only re-read when its stamp changes.
     */
    private fun profileName(path: Path, modifiedAt: Long): String {
        val size = runCatching { Files.size(path) }.getOrDefault(-1L)
        val cached = profileNames[path]
        if (cached != null && cached.modifiedAt == modifiedAt && cached.size == size) return cached.name

        val name = runCatching {
            parseAndValidate(Files.readString(path)).string("profile")
        }.getOrNull()?.takeIf(String::isNotBlank) ?: path.nameWithoutExtension
        profileNames[path] = CachedProfileName(modifiedAt, size, name)
        return name
    }

    private fun encodeCurrent(profileName: String): String {
        val root = JsonObject().apply {
            addProperty("format", FORMAT)
            addProperty("version", FORMAT_VERSION)
            addProperty("profile", profileName)
        }
        val modules = JsonObject()
        ModuleRegistry.modules.forEach { module ->
            modules.add(module.id, encodeModule(module))
        }
        root.add("modules", modules)

        // Where the player dragged each HUD element belongs with the settings
        // it was arranged alongside, so it travels with the profile.
        if (Hud.elements.isNotEmpty()) {
            val hud = JsonObject()
            Hud.elements.forEach { element ->
                hud.add(element.id, JsonObject().apply {
                    addProperty("x", element.x)
                    addProperty("y", element.y)
                    addProperty("scale", element.scale)
                })
            }
            root.add("hud", hud)
        }
        return gson.toJson(root)
    }

    private fun encodeModule(module: Module) = JsonObject().apply {
        if (module.supportsToggle) addProperty("enabled", module.enabled)
        if (module.hasDemoSettings) {
            add("slider", JsonObject().also { it.addProperty("value", module.slider.value) })
            add("range", JsonObject().also {
                it.addProperty("lower", module.range.lower)
                it.addProperty("upper", module.range.upper)
            })
            add("dropdown", JsonObject().also { it.addProperty("selected", module.dropdown.selected) })
        }
        if (module.settings.isNotEmpty()) {
            add("settings", JsonObject().also { settings ->
                module.settings.forEach { setting ->
                    when (setting) {
                        is ToggleModuleSetting -> settings.addProperty(setting.id, setting.value)
                        is SliderModuleSetting -> settings.addProperty(setting.id, setting.value)
                        is RangeModuleSetting -> settings.add(setting.id, JsonObject().also {
                            it.addProperty("lower", setting.lower)
                            it.addProperty("upper", setting.upper)
                        })
                        is ColorModuleSetting -> settings.addProperty(setting.id, setting.hex)
                        is DropdownModuleSetting -> settings.addProperty(setting.id, setting.selected)
                        is TextModuleSetting -> settings.addProperty(setting.id, setting.value)
                        else -> Unit
                    }
                }
            })
        }
    }

    private fun apply(root: JsonObject) {
        root.objectOrNull("hud")?.let { hud ->
            Hud.elements.forEach { element ->
                val placement = hud.objectOrNull(element.id) ?: return@forEach
                placement.double("x")?.let { element.x = it.coerceIn(0.0, 1.0) }
                placement.double("y")?.let { element.y = it.coerceIn(0.0, 1.0) }
                placement.double("scale")?.let { element.scale = it.coerceIn(0.1, 5.0) }
            }
        }

        val modules = root.objectOrNull("modules") ?: return
        ModuleRegistry.byId.forEach { (id, module) ->
            val value = modules.objectOrNull(id) ?: return@forEach
            if (module.supportsToggle) value.boolean("enabled")?.let { module.enabled = it }
            if (module.hasDemoSettings) {
                value.objectOrNull("slider")?.double("value")?.let {
                    module.slider.value = it.coerceIn(module.slider.min, module.slider.max)
                }
                value.objectOrNull("range")?.let { range ->
                    val lower = range.double("lower")?.coerceIn(module.range.min, module.range.max)
                        ?: module.range.lower
                    val upper = range.double("upper")?.coerceIn(module.range.min, module.range.max)
                        ?: module.range.upper
                    module.range.lower = minOf(lower, upper)
                    module.range.upper = maxOf(lower, upper)
                }
                value.objectOrNull("dropdown")?.string("selected")?.let { selected ->
                    module.dropdown.options.indexOf(selected).takeIf { it >= 0 }?.let {
                        module.dropdown.selectedIndex = it
                    }
                }
            }
            value.objectOrNull("settings")?.let { settings ->
                module.settings.forEach { setting ->
                    when (setting) {
                        is ToggleModuleSetting -> settings.boolean(setting.id)?.let { setting.value = it }
                        is SliderModuleSetting -> settings.double(setting.id)?.let {
                            setting.value = it.coerceIn(setting.min, setting.max)
                        }
                        is RangeModuleSetting -> settings.objectOrNull(setting.id)?.let { range ->
                            val lower = (range.double("lower") ?: setting.lower).coerceIn(setting.min, setting.max)
                            val upper = (range.double("upper") ?: setting.upper).coerceIn(setting.min, setting.max)
                            setting.lower = minOf(lower, upper)
                            setting.upper = maxOf(lower, upper)
                        }
                        is ColorModuleSetting -> settings.string(setting.id)?.let(setting::setHex)
                        is DropdownModuleSetting -> settings.string(setting.id)?.let(setting::select)
                        is TextModuleSetting -> settings.string(setting.id)?.let { setting.value = it }
                        else -> Unit
                    }
                }
            }
        }
    }

    /**
     * The profile bundled in the jar, or null when it cannot be read.
     *
     * Loaded through the class loader rather than Minecraft's resource manager,
     * because the config is read while the client is still starting and the
     * resource manager is not ready to answer yet.
     */
    private fun shippedDefault(): JsonObject? = runCatching {
        val stream = ConfigManager::class.java.getResourceAsStream(SHIPPED_DEFAULT_PATH)
            ?: return@runCatching null
        parseAndValidate(stream.bufferedReader().use { it.readText() })
    }.onFailure {
        logger.error("Could not read Cryptic's bundled default profile", it)
    }.getOrNull()

    private fun parseAndValidate(json: String): JsonObject {
        val root = JsonParser.parseString(json).asJsonObject
        require(root.string("format") == FORMAT) { "Not a Cryptic profile" }
        require(root.int("version") == FORMAT_VERSION) { "Unsupported Cryptic profile version" }
        require(root.objectOrNull("modules") != null) { "Cryptic profile has no modules" }
        return root
    }

    private fun decodeExport(encoded: String): JsonObject {
        val value = encoded.trim()
        require(value.startsWith(EXPORT_PREFIX)) { "Clipboard does not contain a Cryptic profile" }
        require(value.length <= 2_000_000) { "Cryptic profile string is too large" }
        val json = String(
            Base64.getDecoder().decode(value.removePrefix(EXPORT_PREFIX)),
            StandardCharsets.UTF_8,
        )
        return parseAndValidate(json)
    }

    private fun profilePath(name: String): Path = profileDirectory.resolve("${safeFileName(name)}.json")

    private fun uniqueProfileName(base: String, excludedPath: Path? = null): String {
        val cleanBase = cleanProfileName(base)
        var candidate = cleanBase
        var suffix = 2
        while (profileNameExists(candidate, excludedPath)) {
            candidate = "$cleanBase $suffix"
            suffix++
        }
        return candidate
    }

    private fun profileNameExists(candidate: String, excludedPath: Path?): Boolean {
        if (candidate.equals(DEFAULT_PROFILE, ignoreCase = true)) return true
        if (cachedProfiles.any { it.path != excludedPath && it.name.equals(candidate, ignoreCase = true) }) return true
        val candidatePath = profilePath(candidate)
        return candidatePath != excludedPath && Files.exists(candidatePath)
    }

    /**
     * Keeps names pleasant and portable without stripping ordinary spaces,
     * accents, emoji, or other Unicode characters from the displayed name.
     */
    private fun cleanProfileName(name: String): String {
        val normalized = Normalizer.normalize(name, Normalizer.Form.NFKC)
            .filterNot { it.isISOControl() }
            .trim()
            .ifBlank { "Profile" }
        val codePoints = normalized.codePointCount(0, normalized.length)
        val endIndex = normalized.offsetByCodePoints(0, minOf(48, codePoints))
        return normalized.substring(0, endIndex).trim().ifBlank { "Profile" }
    }

    /** Only the filename is sanitized; the full display name remains in JSON. */
    private fun safeFileName(name: String): String {
        val stem = cleanProfileName(name)
            .replace(Regex("[<>:\"/\\\\|?*\\u0000-\\u001F]"), "_")
            .trim(' ', '.')
            .ifBlank { "Profile" }
        val isReservedWindowsName = Regex(
            "(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$",
        ).matches(stem)
        return if (isReservedWindowsName) "_$stem" else stem
    }

    private fun writeAtomically(path: Path, value: String) {
        val temporary = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(temporary, value, StandardCharsets.UTF_8)
        try {
            Files.move(
                temporary,
                path,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun JsonObject.objectOrNull(name: String): JsonObject? =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.boolean(name: String): Boolean? =
        runCatching { get(name)?.asBoolean }.getOrNull()

    private fun JsonObject.double(name: String): Double? =
        runCatching { get(name)?.asDouble }.getOrNull()?.takeIf(Double::isFinite)

    private fun JsonObject.int(name: String): Int? =
        runCatching { get(name)?.asInt }.getOrNull()
}
