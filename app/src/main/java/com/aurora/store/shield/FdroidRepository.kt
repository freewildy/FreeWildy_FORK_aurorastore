/*
 * SPDX-FileCopyrightText: 2026 Aurora Shield Hub contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.aurora.store.shield

import android.content.Context
import android.os.Build
import android.util.JsonReader
import android.util.JsonToken
import com.aurora.store.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.jar.JarFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class FdroidRepo(
    val name: String,
    val baseUrl: String,
    val certificateSha256: String,
    val builtIn: Boolean = false
)

data class FdroidApp(
    val repository: FdroidRepo,
    val packageName: String,
    val name: String,
    val summary: String,
    val versionName: String,
    val versionCode: Long,
    val apkName: String,
    val sha256: String,
    val size: Long,
    val categories: List<String>
)

object FdroidRepoStore {
    private const val PREFS = "shield_fdroid_repositories"
    private const val KEY_REPOS = "custom_repositories"

    val official = FdroidRepo(
        name = "F-Droid officiel",
        baseUrl = "https://f-droid.org/repo",
        certificateSha256 = "43238D512C1E5EB2D6569F4A3AFBF5523418B82E0A3ED1552770ABB9A9C9CCAB",
        builtIn = true
    )

    fun list(context: Context): List<FdroidRepo> {
        val values = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_REPOS, emptySet())
            .orEmpty()
        val custom = values.mapNotNull { encoded ->
            runCatching {
                val json = JSONObject(encoded)
                validate(
                    FdroidRepo(
                        name = json.getString("name"),
                        baseUrl = json.getString("url"),
                        certificateSha256 = json.getString("fingerprint")
                    )
                )
            }.getOrNull()
        }.sortedBy { it.name.lowercase(Locale.ROOT) }
        return listOf(official) + custom
    }

    fun add(context: Context, repository: FdroidRepo) {
        val valid = validate(repository)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val values = prefs.getStringSet(KEY_REPOS, emptySet()).orEmpty().toMutableSet()
        values.removeAll { encoded ->
            runCatching { JSONObject(encoded).getString("url") == valid.baseUrl }.getOrDefault(false)
        }
        values += JSONObject()
            .put("name", valid.name)
            .put("url", valid.baseUrl)
            .put("fingerprint", valid.certificateSha256)
            .toString()
        prefs.edit().putStringSet(KEY_REPOS, values).apply()
    }

    private fun validate(repository: FdroidRepo): FdroidRepo {
        val baseUrl = repository.baseUrl.trim().trimEnd('/')
        val uri = URI(baseUrl)
        require(uri.scheme == "https" || uri.scheme == "http") {
            "Le dépôt doit utiliser HTTP ou HTTPS"
        }
        require(!uri.host.isNullOrBlank()) { "Adresse de dépôt invalide" }
        val fingerprint = repository.certificateSha256
            .replace(":", "")
            .replace(" ", "")
            .uppercase(Locale.ROOT)
        require(fingerprint.matches(Regex("[0-9A-F]{64}"))) {
            "L'empreinte SHA-256 doit contenir 64 caractères hexadécimaux"
        }
        return repository.copy(
            name = repository.name.trim().ifBlank { uri.host },
            baseUrl = baseUrl,
            certificateSha256 = fingerprint
        )
    }
}

class FdroidRepository(private val context: Context) {

    private val indexDir = File(context.filesDir, "fdroid-indexes").apply { mkdirs() }
    private val apkDir = File(context.cacheDir, "fdroid-apks").apply { mkdirs() }

    suspend fun refresh(repositories: List<FdroidRepo>) = withContext(Dispatchers.IO) {
        repositories.forEach { ensureIndex(it, force = true) }
    }

    suspend fun search(
        repositories: List<FdroidRepo>,
        query: String
    ): List<FdroidApp> = withContext(Dispatchers.IO) {
        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        if (normalizedQuery.isBlank()) return@withContext emptyList()
        repositories.flatMap { repository ->
            val index = ensureIndex(repository, force = false)
            parseIndex(index, repository, normalizedQuery)
        }.distinctBy { "${it.repository.baseUrl}|${it.packageName}" }
            .sortedWith(compareBy<FdroidApp> { it.name.lowercase(Locale.ROOT) }.thenBy { it.packageName })
    }

    suspend fun download(app: FdroidApp): File = withContext(Dispatchers.IO) {
        require(!app.apkName.startsWith('/') && !app.apkName.contains("..")) {
            "Nom d'APK non sûr dans l'index"
        }
        val destination = File(
            apkDir,
            "${app.packageName}-${app.versionCode}-${app.apkName.substringAfterLast('/')}"
        )
        if (destination.isFile && sha256(destination).equals(app.sha256, ignoreCase = true)) {
            return@withContext destination
        }
        val temporary = File(apkDir, "${destination.name}.part")
        downloadTo("${app.repository.baseUrl}/${app.apkName}", temporary)
        require(sha256(temporary).equals(app.sha256, ignoreCase = true)) {
            temporary.delete()
            "La somme SHA-256 de l'APK ne correspond pas à l'index signé"
        }
        temporary.copyTo(destination, overwrite = true)
        temporary.delete()
        destination
    }

    private fun ensureIndex(repository: FdroidRepo, force: Boolean): File {
        val cacheName = sha256(repository.baseUrl.toByteArray()).take(24)
        val destination = File(indexDir, "$cacheName-index-v1.jar")
        if (!force && destination.isFile) {
            verifyIndex(destination, repository.certificateSha256)
            return destination
        }

        val temporary = File(indexDir, "$cacheName-index-v1.jar.part")
        downloadTo("${repository.baseUrl}/index-v1.jar", temporary)
        verifyIndex(temporary, repository.certificateSha256)
        temporary.copyTo(destination, overwrite = true)
        temporary.delete()
        return destination
    }

    private fun verifyIndex(file: File, expectedFingerprint: String) {
        JarFile(file, true).use { jar ->
            val entry = requireNotNull(jar.getJarEntry("index-v1.json")) {
                "Le dépôt ne contient pas index-v1.json"
            }
            jar.getInputStream(entry).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (input.read(buffer) != -1) {
                    // Reading the complete entry makes JarFile verify its signature.
                }
            }
            val certificate = entry.certificates?.firstOrNull()
                ?: error("L'index F-Droid n'est pas signé")
            val actual = sha256(certificate.encoded)
            require(actual.equals(expectedFingerprint, ignoreCase = true)) {
                "Mauvaise signature du dépôt. Attendue: $expectedFingerprint, reçue: $actual"
            }
        }
    }

    private fun parseIndex(
        indexFile: File,
        repository: FdroidRepo,
        query: String
    ): List<FdroidApp> {
        val matches = linkedMapOf<String, AppMetadata>()
        val versions = mutableMapOf<String, PackageMetadata>()

        JarFile(indexFile, true).use { jar ->
            val entry = requireNotNull(jar.getJarEntry("index-v1.json"))
            JsonReader(InputStreamReader(jar.getInputStream(entry), Charsets.UTF_8)).use { reader ->
                reader.isLenient = true
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "apps" -> readApps(reader, query, matches)
                        "packages" -> readPackages(reader, matches.keys, versions)
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
            }
        }

        return matches.values.mapNotNull { app ->
            val pkg = versions[app.packageName] ?: return@mapNotNull null
            FdroidApp(
                repository = repository,
                packageName = app.packageName,
                name = app.name.ifBlank { app.packageName },
                summary = app.summary,
                versionName = pkg.versionName,
                versionCode = pkg.versionCode,
                apkName = pkg.apkName,
                sha256 = pkg.hash,
                size = pkg.size,
                categories = app.categories
            )
        }
    }

    private fun readApps(
        reader: JsonReader,
        query: String,
        matches: MutableMap<String, AppMetadata>
    ) {
        reader.beginArray()
        while (reader.hasNext()) {
            var packageName = ""
            var name = ""
            var summary = ""
            var categories = emptyList<String>()
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "packageName" -> packageName = reader.readString()
                    "name" -> name = reader.readString()
                    "summary" -> summary = reader.readString()
                    "categories" -> categories = reader.readStringList()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            val haystack = "$packageName\n$name\n$summary".lowercase(Locale.ROOT)
            if (query in haystack && matches.size < 120) {
                matches[packageName] = AppMetadata(packageName, name, summary, categories)
            }
        }
        reader.endArray()
    }

    private fun readPackages(
        reader: JsonReader,
        wantedPackages: Set<String>,
        versions: MutableMap<String, PackageMetadata>
    ) {
        reader.beginObject()
        while (reader.hasNext()) {
            val packageName = reader.nextName()
            if (packageName !in wantedPackages) {
                reader.skipValue()
                continue
            }
            reader.beginArray()
            while (reader.hasNext()) {
                val candidate = readPackage(reader)
                if (candidate.isCompatible() &&
                    candidate.hashType.equals("sha256", ignoreCase = true) &&
                    candidate.apkName.isNotBlank() &&
                    candidate.hash.isNotBlank() &&
                    candidate.versionCode > (versions[packageName]?.versionCode ?: Long.MIN_VALUE)
                ) {
                    versions[packageName] = candidate
                }
            }
            reader.endArray()
        }
        reader.endObject()
    }

    private fun readPackage(reader: JsonReader): PackageMetadata {
        var apkName = ""
        var hash = ""
        var hashType = "sha256"
        var versionName = ""
        var versionCode = 0L
        var minSdk = 1
        var maxSdk = Int.MAX_VALUE
        var size = 0L
        var nativeCode = emptyList<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "apkName" -> apkName = reader.readString()
                "hash" -> hash = reader.readString()
                "hashType" -> hashType = reader.readString()
                "versionName" -> versionName = reader.readString()
                "versionCode" -> versionCode = reader.readLong()
                "minSdkVersion" -> minSdk = reader.readInt(1)
                "maxSdkVersion" -> maxSdk = reader.readInt(Int.MAX_VALUE)
                "size" -> size = reader.readLong()
                "nativecode" -> nativeCode = reader.readStringList()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return PackageMetadata(
            apkName,
            hash,
            hashType,
            versionName,
            versionCode,
            minSdk,
            maxSdk,
            size,
            nativeCode
        )
    }

    private fun PackageMetadata.isCompatible(): Boolean {
        if (Build.VERSION.SDK_INT !in minSdk..maxSdk) return false
        if (nativeCode.isEmpty()) return true
        return nativeCode.any { abi -> Build.SUPPORTED_ABIS.any { it.equals(abi, true) } }
    }

    private fun downloadTo(address: String, destination: File) {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", "KwikStore/${BuildConfig.VERSION_NAME}")
            connection.connect()
            require(connection.responseCode in 200..299) {
                "Téléchargement impossible (${connection.responseCode}) pour $address"
            }
            connection.inputStream.use { input ->
                FileOutputStream(destination, false).use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().toHex()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

    private fun JsonReader.readString(): String = when (peek()) {
        JsonToken.NULL -> {
            nextNull()
            ""
        }
        JsonToken.STRING, JsonToken.NUMBER -> nextString()
        else -> {
            skipValue()
            ""
        }
    }

    private fun JsonReader.readLong(): Long = readString().toLongOrNull() ?: 0L

    private fun JsonReader.readInt(default: Int): Int = readString().toIntOrNull() ?: default

    private fun JsonReader.readStringList(): List<String> {
        if (peek() == JsonToken.NULL) {
            nextNull()
            return emptyList()
        }
        if (peek() != JsonToken.BEGIN_ARRAY) {
            skipValue()
            return emptyList()
        }
        val values = mutableListOf<String>()
        beginArray()
        while (hasNext()) values += readString()
        endArray()
        return values
    }

    private data class AppMetadata(
        val packageName: String,
        val name: String,
        val summary: String,
        val categories: List<String>
    )

    private data class PackageMetadata(
        val apkName: String,
        val hash: String,
        val hashType: String,
        val versionName: String,
        val versionCode: Long,
        val minSdk: Int,
        val maxSdk: Int,
        val size: Long,
        val nativeCode: List<String>
    )
}
