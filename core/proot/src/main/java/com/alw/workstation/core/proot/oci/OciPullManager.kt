package com.alw.workstation.core.proot.oci

import android.util.Log
import com.alw.workstation.core.proot.ProotRunner
import com.alw.workstation.core.proot.RootfsManager
import com.alw.workstation.core.proot.RuntimeDirectories
import com.alw.workstation.core.proot.provisioning.MultiDistroBootstrapper
import com.alw.workstation.core.session.model.StackProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * End-to-end manager for pulling OCI container images, extracting multi-layer
 * filesystems with whiteout handling, and executing zero-terminal multi-distro bootstrapping.
 */
class OciPullManager(
    private val dirs: RuntimeDirectories,
    private val rootfsManager: RootfsManager,
    private val prootRunner: ProotRunner,
    private val registryClient: OciRegistryClient = OciRegistryClient(),
    private val layerExtractor: OciLayerExtractor = OciLayerExtractor(),
    private val bootstrapper: MultiDistroBootstrapper = MultiDistroBootstrapper()
) {

    companion object {
        private const val TAG = "ALW::OciPullManager"
    }

    data class PullProgress(
        val stage: Stage,
        val message: String,
        val currentLayer: Int = 0,
        val totalLayers: Int = 0,
        val progressPercent: Int = 0
    ) {
        enum class Stage {
            RESOLVING_MANIFEST,
            DOWNLOADING_LAYERS,
            EXTRACTING_LAYERS,
            BOOTSTRAPPING_SYSTEM,
            COMPLETE,
            FAILED
        }
    }

    /**
     * Pulls an OCI image by repository and tag, extracts it into [rootfsId], and bootstraps the guest OS.
     */
    suspend fun pullAndProvision(
        repository: String,
        tag: String = "latest",
        rootfsId: String,
        username: String = "workstation",
        profile: StackProfile? = null,
        onProgress: ((PullProgress) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        val rootfsDir = dirs.getRootfsDir(rootfsId)
        val layersCacheDir = File(dirs.filesDir, "downloads/oci_layers")
        if (!layersCacheDir.exists()) layersCacheDir.mkdirs()

        try {
            // Stage 1: Resolve Manifest & filter for arm64
            onProgress?.invoke(
                PullProgress(
                    stage = PullProgress.Stage.RESOLVING_MANIFEST,
                    message = "Resolving OCI manifest for $repository:$tag (linux/arm64)...",
                    progressPercent = 5
                )
            )

            val manifestRes = registryClient.fetchManifest(repository, tag)
            if (manifestRes.isFailure) {
                val err = manifestRes.exceptionOrNull() ?: IOException("Manifest fetch failed")
                onProgress?.invoke(PullProgress(PullProgress.Stage.FAILED, "Manifest error: ${err.message}"))
                return@withContext Result.failure(err)
            }

            val manifest = manifestRes.getOrThrow()
            val layers = manifest.layers
            if (layers.isEmpty()) {
                val err = IllegalStateException("Image manifest for $repository:$tag contains 0 layers")
                onProgress?.invoke(PullProgress(PullProgress.Stage.FAILED, err.message!!))
                return@withContext Result.failure(err)
            }

            Log.i(TAG, "Manifest resolved with ${layers.size} layers for $repository:$tag")

            // Stage 2: Download layer blobs sequentially
            val downloadedLayerFiles = mutableListOf<File>()
            val totalLayers = layers.size

            layers.forEachIndexed { index, layerDesc ->
                val digestName = layerDesc.digest.replace(":", "_")
                val targetBlobFile = File(layersCacheDir, "$digestName.tar")

                val layerPercent = 10 + ((index.toFloat() / totalLayers.toFloat()) * 50).toInt()
                onProgress?.invoke(
                    PullProgress(
                        stage = PullProgress.Stage.DOWNLOADING_LAYERS,
                        message = "Downloading layer ${index + 1}/$totalLayers (${layerDesc.size / 1024 / 1024} MB)...",
                        currentLayer = index + 1,
                        totalLayers = totalLayers,
                        progressPercent = layerPercent
                    )
                )

                val downloadRes = registryClient.downloadLayerBlob(
                    repository = repository,
                    digest = layerDesc.digest,
                    destinationFile = targetBlobFile,
                    onProgress = { bytesRead, totalBytes ->
                        if (totalBytes > 0) {
                            val subPercent = (bytesRead.toDouble() / totalBytes.toDouble() * 100).toInt()
                            Log.v(TAG, "Layer ${index + 1} download: $subPercent%")
                        }
                    }
                )

                if (downloadRes.isFailure) {
                    val err = downloadRes.exceptionOrNull() ?: IOException("Layer download failed")
                    onProgress?.invoke(PullProgress(PullProgress.Stage.FAILED, "Download error: ${err.message}"))
                    return@withContext Result.failure(err)
                }

                downloadedLayerFiles.add(downloadRes.getOrThrow())
            }

            // Stage 3: Extract layer sequence into rootfsDir
            onProgress?.invoke(
                PullProgress(
                    stage = PullProgress.Stage.EXTRACTING_LAYERS,
                    message = "Extracting ${downloadedLayerFiles.size} layers with OCI whiteout resolution...",
                    currentLayer = 0,
                    totalLayers = totalLayers,
                    progressPercent = 65
                )
            )

            if (!rootfsDir.exists()) rootfsDir.mkdirs()
            val extractRes = layerExtractor.extractLayers(
                layerFiles = downloadedLayerFiles,
                targetDir = rootfsDir,
                onProgress = { extractProg ->
                    val extractPercent = 65 + ((extractProg.layerIndex.toFloat() / extractProg.totalLayers.toFloat()) * 20).toInt()
                    onProgress?.invoke(
                        PullProgress(
                            stage = PullProgress.Stage.EXTRACTING_LAYERS,
                            message = "Extracting layer ${extractProg.layerIndex + 1}/$totalLayers: ${extractProg.currentEntry}",
                            currentLayer = extractProg.layerIndex + 1,
                            totalLayers = extractProg.totalLayers,
                            progressPercent = extractPercent
                        )
                    )
                }
            )

            if (extractRes.isFailure) {
                val err = extractRes.exceptionOrNull() ?: IOException("Layer extraction failed")
                onProgress?.invoke(PullProgress(PullProgress.Stage.FAILED, "Extraction error: ${err.message}"))
                return@withContext Result.failure(err)
            }

            // Stage 4: Multi-distro OS-Release detection & bootstrap script execution
            onProgress?.invoke(
                PullProgress(
                    stage = PullProgress.Stage.BOOTSTRAPPING_SYSTEM,
                    message = "Detecting Linux distro & bootstrapping system...",
                    progressPercent = 88
                )
            )

            val detected = bootstrapper.detectDistribution(rootfsDir)
            Log.i(TAG, "Detected distro: ${detected.name} (${detected.family}) in $rootfsId")

            val bootstrapScript = bootstrapper.buildBootstrapScript(
                rootfsDir = rootfsDir,
                username = username,
                installDesktopPackages = false // fast base bootstrap
            )

            val scriptFile = File(rootfsDir, "tmp/alw_bootstrap.sh")
            scriptFile.parentFile?.mkdirs()
            scriptFile.writeText(bootstrapScript)
            scriptFile.setExecutable(true, false)

            val activeProfile = profile ?: StackProfile.defaultArchXiaomiPad8().let { p ->
                p.copy(distro = p.distro.copy(rootfsId = rootfsId))
            }

            val sessionDir = dirs.getRuntimeSocketDir("bootstrap-$rootfsId")
            if (!sessionDir.exists()) sessionDir.mkdirs()

            val launchCmd = prootRunner.buildLaunchCommand(
                profile = activeProfile,
                sessionRuntimeDir = sessionDir,
                customExecCommand = "/bin/sh",
                customArgs = listOf("/tmp/alw_bootstrap.sh")
            )

            val cmdString = (listOf(launchCmd.binary) + launchCmd.arguments).joinToString(" ")
            Log.i(TAG, "Executing bootstrap command: $cmdString")
            val procBuilder = ProcessBuilder(launchCmd.binary, *launchCmd.arguments.toTypedArray()).apply {
                directory(launchCmd.workingDir)
                environment().remove("LD_PRELOAD")
                environment().remove("LD_CONFIG_FILE")
                environment().putAll(launchCmd.environment)
                redirectErrorStream(true)
            }

            val proc = procBuilder.start()
            val output = proc.inputStream.bufferedReader().use { it.readText() }
            val exitCode = proc.waitFor()

            Log.i(TAG, "Bootstrap script finished with exit code $exitCode. Output:\n$output")
            scriptFile.delete()

            rootfsManager.markProvisioned(rootfsId)

            onProgress?.invoke(
                PullProgress(
                    stage = PullProgress.Stage.COMPLETE,
                    message = "Successfully installed ${detected.name} ($rootfsId)!",
                    progressPercent = 100
                )
            )

            Result.success(rootfsDir)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed pulling and provisioning OCI image $repository:$tag", e)
            onProgress?.invoke(PullProgress(PullProgress.Stage.FAILED, "Error: ${e.message}"))
            Result.failure(e)
        }
    }
}
