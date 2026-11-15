package com.alw.workstation.core.proot.oci

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * High-performance OCI Distribution Spec v2 and Docker Hub Registry v2 client.
 * Supports anonymous OAuth2 Bearer token authentication, multi-arch arm64 manifest index
 * resolution, live tag listings, and SHA256 digest-verified blob streaming.
 */
class OciRegistryClient(
    private val registryBaseUrl: String = DEFAULT_REGISTRY_BASE,
    private val authBaseUrl: String = DEFAULT_AUTH_BASE,
    private val serviceName: String = DEFAULT_SERVICE_NAME,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000
) {

    companion object {
        private const val TAG = "ALW::OciRegistry"
        const val DEFAULT_REGISTRY_BASE = "https://registry-1.docker.io"
        const val DEFAULT_AUTH_BASE = "https://auth.docker.io/token"
        const val DEFAULT_SERVICE_NAME = "registry.docker.io"

        const val MEDIA_TYPE_OCI_INDEX = "application/vnd.oci.image.index.v1+json"
        const val MEDIA_TYPE_DOCKER_MANIFEST_LIST = "application/vnd.docker.distribution.manifest.list.v2+json"
        const val MEDIA_TYPE_OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json"
        const val MEDIA_TYPE_DOCKER_MANIFEST_V2 = "application/vnd.docker.distribution.manifest.v2+json"

        val ACCEPT_MANIFEST_HEADER = listOf(
            MEDIA_TYPE_OCI_INDEX,
            MEDIA_TYPE_DOCKER_MANIFEST_LIST,
            MEDIA_TYPE_OCI_MANIFEST,
            MEDIA_TYPE_DOCKER_MANIFEST_V2
        ).joinToString(", ")
    }

    private val tokenCache = ConcurrentHashMap<String, OciAuthToken>()

    /**
     * Normalizes short repository names (e.g., "ubuntu" -> "library/ubuntu").
     */
    fun normalizeRepository(repo: String): String {
        val trimmed = repo.trim().lowercase()
        return if (!trimmed.contains('/')) {
            "library/$trimmed"
        } else {
            trimmed
        }
    }

    /**
     * Obtains an anonymous Bearer token for the repository with cache invalidation after 50 minutes.
     */
    suspend fun getAuthToken(repository: String, scope: String = "pull"): Result<String> = withContext(Dispatchers.IO) {
        val normalized = normalizeRepository(repository)
        val cacheKey = "$normalized:$scope"
        val cached = tokenCache[cacheKey]
        val now = System.currentTimeMillis()
        if (cached != null && (now - cached.issuedAtEpochMs) < 50 * 60 * 1000L) {
            return@withContext Result.success(cached.token)
        }

        try {
            val urlStr = "$authBaseUrl?service=$serviceName&scope=repository:$normalized:$scope"
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = true
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                return@withContext Result.failure(
                    IOException("Auth endpoint returned HTTP $code: $err")
                )
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val token = json.optString("token").ifEmpty { json.optString("access_token") }
            if (token.isEmpty()) {
                return@withContext Result.failure(IOException("No token field in auth response"))
            }

            tokenCache[cacheKey] = OciAuthToken(token = token, issuedAtEpochMs = now)
            Result.success(token)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed obtaining auth token for $normalized", e)
            Result.failure(e)
        }
    }

    /**
     * Queries the live list of tags available for a repository.
     */
    suspend fun fetchTags(repository: String): Result<List<String>> = withContext(Dispatchers.IO) {
        val normalized = normalizeRepository(repository)
        val tokenRes = getAuthToken(normalized)
        if (tokenRes.isFailure) return@withContext Result.failure(tokenRes.exceptionOrNull()!!)

        try {
            val urlStr = "$registryBaseUrl/v2/$normalized/tags/list"
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer ${tokenRes.getOrThrow()}")
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = true
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                return@withContext Result.failure(
                    IOException("Tags endpoint returned HTTP $code: $err")
                )
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tagsArray = json.optJSONArray("tags") ?: JSONArray()
            val list = mutableListOf<String>()
            for (i in 0 until tagsArray.length()) {
                list.add(tagsArray.getString(i))
            }
            Result.success(list)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed fetching tags for $normalized", e)
            Result.failure(e)
        }
    }

    /**
     * Fetches the image manifest, automatically resolving multi-arch manifest indices
     * to strictly select the linux/arm64 descriptor.
     */
    suspend fun fetchManifest(repository: String, reference: String): Result<OciImageManifest> = withContext(Dispatchers.IO) {
        val normalized = normalizeRepository(repository)
        val tokenRes = getAuthToken(normalized)
        if (tokenRes.isFailure) return@withContext Result.failure(tokenRes.exceptionOrNull()!!)
        val token = tokenRes.getOrThrow()

        fetchManifestInternal(normalized, reference, token, maxDepth = 3)
    }

    private fun fetchManifestInternal(
        normalizedRepo: String,
        reference: String,
        token: String,
        maxDepth: Int
    ): Result<OciImageManifest> {
        if (maxDepth <= 0) {
            return Result.failure(IOException("Maximum manifest recursion depth exceeded for $reference"))
        }

        return try {
            val urlStr = "$registryBaseUrl/v2/$normalizedRepo/manifests/$reference"
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", ACCEPT_MANIFEST_HEADER)
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = true
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                return Result.failure(IOException("Manifest endpoint returned HTTP $code: $err"))
            }

            val contentType = conn.contentType ?: ""
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)

            // Check if this is an Image Index or Manifest List
            val manifestsArray = json.optJSONArray("manifests")
            if (manifestsArray != null && manifestsArray.length() > 0) {
                // Multi-arch index: look for linux/arm64
                var arm64Digest: String? = null
                for (i in 0 until manifestsArray.length()) {
                    val m = manifestsArray.getJSONObject(i)
                    val platform = m.optJSONObject("platform")
                    if (platform != null) {
                        val os = platform.optString("os")
                        val arch = platform.optString("architecture")
                        if (os.equals("linux", ignoreCase = true) &&
                            (arch.equals("arm64", ignoreCase = true) || arch.equals("aarch64", ignoreCase = true))
                        ) {
                            arm64Digest = m.optString("digest")
                            break
                        }
                    }
                }

                if (arm64Digest == null) {
                    return Result.failure(
                        IllegalStateException("OCI image '$normalizedRepo:$reference' does not provide a linux/arm64 (aarch64) build.")
                    )
                }

                Log.i(TAG, "Resolved multi-arch index for $normalizedRepo:$reference -> arm64 digest $arm64Digest")
                return fetchManifestInternal(normalizedRepo, arm64Digest, token, maxDepth - 1)
            }

            // Direct Manifest (Schema 2 or OCI)
            val schemaVersion = json.optInt("schemaVersion", 2)
            val mediaType = json.optString("mediaType", contentType)
            val configObj = json.optJSONObject("config")
            val configDigest = configObj?.optString("digest") ?: ""

            val layersArray = json.optJSONArray("layers") ?: JSONArray()
            val layers = mutableListOf<OciLayerDescriptor>()
            for (i in 0 until layersArray.length()) {
                val l = layersArray.getJSONObject(i)
                layers.add(
                    OciLayerDescriptor(
                        mediaType = l.optString("mediaType"),
                        size = l.optLong("size", 0L),
                        digest = l.optString("digest")
                    )
                )
            }

            Result.success(
                OciImageManifest(
                    schemaVersion = schemaVersion,
                    mediaType = mediaType,
                    configDigest = configDigest,
                    layers = layers
                )
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Error fetching manifest for $normalizedRepo:$reference", e)
            Result.failure(e)
        }
    }

    /**
     * Downloads a layer blob by digest with streamed SHA256 integrity verification.
     */
    suspend fun downloadLayerBlob(
        repository: String,
        digest: String,
        destinationFile: File,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        val normalized = normalizeRepository(repository)
        val tokenRes = getAuthToken(normalized)
        if (tokenRes.isFailure) return@withContext Result.failure(tokenRes.exceptionOrNull()!!)
        val token = tokenRes.getOrThrow()

        val expectedSha = if (digest.startsWith("sha256:")) digest.substringAfter("sha256:") else digest
        val parentDir = destinationFile.parentFile ?: destinationFile
        if (!parentDir.exists()) parentDir.mkdirs()

        // Check if destination already exists and matches expected size
        if (destinationFile.exists() && destinationFile.length() > 0) {
            val existingSha = computeFileSha256(destinationFile)
            if (existingSha.equals(expectedSha, ignoreCase = true)) {
                Log.i(TAG, "Layer blob $digest already downloaded and verified: ${destinationFile.absolutePath}")
                onProgress?.invoke(destinationFile.length(), destinationFile.length())
                return@withContext Result.success(destinationFile)
            }
        }

        val partFile = File(parentDir, "${destinationFile.name}.part")
        try {
            val urlStr = "$registryBaseUrl/v2/$normalized/blobs/$digest"
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $token")
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = true
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                return@withContext Result.failure(IOException("Blob endpoint returned HTTP $code: $err"))
            }

            val totalBytes = conn.contentLengthLong
            val md = MessageDigest.getInstance("SHA-256")
            var bytesReadTotal = 0L

            conn.inputStream.use { input ->
                FileOutputStream(partFile).use { output ->
                    val buffer = ByteArray(65536)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        md.update(buffer, 0, read)
                        bytesReadTotal += read
                        onProgress?.invoke(bytesReadTotal, totalBytes)
                    }
                    output.flush()
                }
            }

            val actualSha = md.digest().joinToString("") { "%02x".format(it) }
            if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                partFile.delete()
                return@withContext Result.failure(
                    SecurityException("Digest mismatch for $digest. Expected: $expectedSha, got: $actualSha")
                )
            }

            if (destinationFile.exists()) destinationFile.delete()
            if (!partFile.renameTo(destinationFile)) {
                partFile.copyTo(destinationFile, overwrite = true)
                partFile.delete()
            }

            Result.success(destinationFile)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed downloading layer blob $digest", e)
            partFile.delete()
            Result.failure(e)
        }
    }

    private fun computeFileSha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(65536)
            var r: Int
            while (input.read(buf).also { r = it } != -1) {
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
