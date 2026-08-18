package pro.sketchware.core.build.dependency

import com.android.tools.r8.CompilationMode
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.GlobalSyntheticsConsumer
import com.android.tools.r8.OutputMode
import com.google.gson.Gson
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.cosmic.ide.dependency.resolver.api.Artifact
import org.cosmic.ide.dependency.resolver.api.EventReciever
import org.cosmic.ide.dependency.resolver.api.Repository // <--- إضافة هذا الاستيراد
import org.cosmic.ide.dependency.resolver.eventReciever
import org.cosmic.ide.dependency.resolver.getArtifact
import org.cosmic.ide.dependency.resolver.repositories
import pro.sketchware.core.build.BuildSettings
import pro.sketchware.core.project.SketchwarePaths
import pro.sketchware.util.Helper
import pro.sketchware.util.library.BuiltInLibraries
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText

class DependencyResolver(
    private val groupId: String,
    private val artifactId: String,
    private val version: String,
    private val skipDependencies: Boolean,
    private val buildSettings: BuildSettings?
) {
    companion object {
        private const val MAX_CHUNK_SIZE_BYTES = 9 * 1024 * 1024L
        private const val MIN_CHUNK_SIZE_BYTES = 2 * 1024 * 1024L
        private const val MAX_JAR_SIZE_BYTES = 12 * 1024 * 1024L

        private val DEFAULT_REPOS = """
          |[
          |    {"url": "https://repo.hortonworks.com/content/repositories/releases", "name": "HortanWorks"},
          |    {"url": "https://maven.atlassian.com/content/repositories/atlassian-public", "name": "Atlassian"},
          |    {"url": "https://jcenter.bintray.com", "name": "JCenter"},
          |    {"url": "https://oss.sonatype.org/content/repositories/releases", "name": "Sonatype"},
          |    {"url": "https://repo.spring.io/plugins-release", "name": "Spring Plugins"},
          |    {"url": "https://repo.spring.io/libs-milestone", "name": "Spring Milestone"},
          |    {"url": "https://repo.maven.apache.org/maven2", "name": "Apache Maven"}
          |]
        """.trimMargin()

        private val BUILT_IN_ANDROIDX_GROUPS = setOf(
            "androidx.activity",
            "androidx.annotation",
            "androidx.appcompat",
            "androidx.arch.core",
            "androidx.asynclayoutinflater",
            "androidx.browser",
            "androidx.cardview",
            "androidx.collection",
            "androidx.concurrent",
            "androidx.constraintlayout",
            "androidx.coordinatorlayout",
            "androidx.core",
            "androidx.cursoradapter",
            "androidx.customview",
            "androidx.documentfile",
            "androidx.drawerlayout",
            "androidx.dynamicanimation",
            "androidx.emoji2",
            "androidx.exifinterface",
            "androidx.fragment",
            "androidx.graphics",
            "androidx.interpolator",
            "androidx.legacy",
            "androidx.lifecycle",
            "androidx.loader",
            "androidx.localbroadcastmanager",
            "androidx.media",
            "androidx.multidex",
            "androidx.recyclerview",
            "androidx.room",
            "androidx.savedstate",
            "androidx.slidingpanelayout",
            "androidx.sqlite",
            "androidx.startup",
            "androidx.swiperefreshlayout",
            "androidx.tracing",
            "androidx.transition",
            "androidx.vectordrawable",
            "androidx.versionedparcelable",
            "androidx.viewpager",
            "androidx.viewpager2",
            "androidx.work",
        )
    }

    private var downloadPath: String = SketchwarePaths.getLocalLibsDir().absolutePath

    private fun isStoragePermissionError(e: Throwable): Boolean {
        var current: Throwable? = e
        while (current != null) {
            val msg = current.message
            if (msg != null && (msg.contains("Operation not permitted") || msg.contains("EPERM"))) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun switchToFallbackPath() {
        downloadPath = SketchwarePaths.getLocalLibsFallbackDir().absolutePath
    }

    private fun resolveExistingLibFile(artifactId: String, version: String, filename: String): File? {
        val primary = File(SketchwarePaths.getLocalLibsDir(), "$artifactId-v$version/$filename")
        if (primary.exists() && primary.length() > 0) return primary
        val fallback = File(SketchwarePaths.getLocalLibsFallbackDir(), "$artifactId-v$version/$filename")
        if (fallback.exists() && fallback.length() > 0) return fallback
        return null
    }

    private val repositoriesJson = Paths.get(SketchwarePaths.getRepositoriesJsonPath())

    init {
        if (Files.notExists(repositoriesJson)) {
            Files.createDirectories(repositoriesJson.parent)
            repositoriesJson.writeText(DEFAULT_REPOS)
        }
        repositories.removeAll { repo ->
            repo !is org.cosmic.ide.dependency.resolver.repository.MavenCentral &&
            repo !is org.cosmic.ide.dependency.resolver.repository.GoogleMaven &&
            repo !is org.cosmic.ide.dependency.resolver.repository.Jitpack &&
            repo !is org.cosmic.ide.dependency.resolver.repository.SonatypeSnapshots
        }
        Gson().fromJson(repositoriesJson.readText(), Helper.TYPE_MAP_LIST).forEach {
            val url: String? = it["url"] as String?
            if (url != null) {
                repositories.add(object : Repository {
                    override fun getName(): String {
                        return it["name"] as String
                    }

                    override fun getURL(): String {
                        return if (url.endsWith("/")) {
                            url.substringBeforeLast("/")
                        } else {
                            url
                        }
                    }
                })
            }
        }
    }

    open class DependencyResolverCallback : EventReciever() {
        override fun artifactFound(artifact: Artifact) {}
        override fun onArtifactNotFound(artifact: Artifact) {}
        override fun onFetchingLatestVersion(artifact: Artifact) {}
        override fun onFetchedLatestVersion(artifact: Artifact, version: String) {}
        override fun onResolving(artifact: Artifact, dependency: Artifact) {}
        override fun onResolutionComplete(artifact: Artifact) {}
        override fun onSkippingResolution(artifact: Artifact) {}
        override fun onVersionNotFound(artifact: Artifact) {}
        override fun onDependenciesNotFound(artifact: Artifact) {}
        override fun onInvalidScope(artifact: Artifact, scope: String) {}
        override fun onInvalidPOM(artifact: Artifact) {}
        override fun onDownloadStart(artifact: Artifact) {}
        override fun onDownloadEnd(artifact: Artifact) {}
        override fun onDownloadError(artifact: Artifact, error: Throwable) {}
        open fun unzipping(artifact: Artifact) {}
        open fun dexing(artifact: Artifact) {}
        open fun onTaskCompleted(artifacts: List<String>) {}
        open fun dexingFailed(artifact: Artifact, e: Exception) {}
        open fun onResolutionTimeout(artifact: Artifact) {}
        open fun invalidPackaging(artifact: Artifact) {}
    }

    fun resolveDependency(callback: DependencyResolverCallback) = runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        eventReciever = callback
        org.cosmic.ide.dependency.resolver.clearSessionCaches()
        val dependency = getArtifact(groupId, artifactId, version) ?: return@runBlocking

        if (dependency.extension != "jar" && dependency.extension != "aar") {
            callback.invalidPackaging(dependency)
            return@runBlocking
        }

        val defaultAndroidJar = BuiltInLibraries.EXTRACTED_COMPILE_ASSETS_PATH.resolve("android.jar").absolutePath
        val libraryJars = listOf(
            BuiltInLibraries.EXTRACTED_COMPILE_ASSETS_PATH.toPath()
                .resolve("core-lambda-stubs.jar"), Paths.get(
                buildSettings?.getValue(
                    BuildSettings.SETTING_ANDROID_JAR_PATH,
                    defaultAndroidJar
                ) ?: defaultAndroidJar
            )
        )
        val dependencyClasspath = mutableListOf<Path>()

        val classpath = buildSettings?.getValue(BuildSettings.SETTING_CLASSPATH, "") ?: ""

        classpath.split(":").forEach {
            if (it.isEmpty()) return@forEach
            dependencyClasspath.add(Paths.get(it))
        }

        val mainCacheCheckFile = if (dependency.extension == "aar") "classes.jar" else "classes.${dependency.extension}"
        val existingMainFile = resolveExistingLibFile(dependency.artifactId, dependency.version, mainCacheCheckFile)
        val mainCached = existingMainFile != null
        if (!mainCached) {
            try {
                dependency.downloadTo(
                    File(downloadPath + "/${dependency.artifactId}-v${dependency.version}/classes.${dependency.extension}")
                        .apply { parentFile?.mkdirs() }
                )
            } catch (e: Exception) {
                if (isStoragePermissionError(e) && downloadPath == SketchwarePaths.getLocalLibsDir().absolutePath) {
                    switchToFallbackPath()
                    try {
                        dependency.downloadTo(
                            File(downloadPath + "/${dependency.artifactId}-v${dependency.version}/classes.${dependency.extension}")
                                .apply {
                                    parentFile?.mkdirs()
                                }
                        )
                    } catch (e2: Exception) {
                        callback.onDownloadError(dependency, e2)
                        return@runBlocking
                    }
                } else {
                    callback.onDownloadError(dependency, e)
                    return@runBlocking
                }
            }
        }

        if (dependency.extension == "aar" && !mainCached) {
            callback.unzipping(dependency)
            try {
                unzip(
                    Paths.get(
                        downloadPath,
                        "${dependency.artifactId}-v${dependency.version}",
                        "classes.aar"
                    )
                )
                Files.delete(
                    Paths.get(
                        downloadPath,
                        "${dependency.artifactId}-v${dependency.version}",
                        "classes.aar"
                    )
                )
                val packageName = findPackageName(
                    Paths.get(downloadPath, "${dependency.artifactId}-v${dependency.version}")
                        .toAbsolutePath().toString(),
                    dependency.groupId
                )
                Paths.get(downloadPath, "${dependency.artifactId}-v${dependency.version}", "config")
                    .writeText(packageName)
            } catch (e: Exception) {
                callback.onDownloadError(dependency, e)
                return@runBlocking
            }
        }

        val existingMainJar = resolveExistingLibFile(dependency.artifactId, dependency.version, "classes.jar")
        val jar = existingMainJar?.toPath() ?: Paths.get(
            downloadPath,
            "${dependency.artifactId}-v${dependency.version}",
            "classes.jar"
        )

        val existingMainDex = resolveExistingLibFile(dependency.artifactId, dependency.version, "classes.dex")
        if (existingMainDex != null) {
            callback.onResolutionComplete(dependency)
        } else {
            callback.dexing(dependency)
            try {
                compileJarWithFallback(jar, dependencyClasspath, libraryJars)
                callback.onResolutionComplete(dependency)
            } catch (t: Throwable) {
                if (t is Exception || t is OutOfMemoryError) {
                    System.gc()
                    val reportException = if (t is OutOfMemoryError)
                        RuntimeException("Out of memory during dexing. The library may be too large for this device.", t)
                    else t as Exception
                    callback.dexingFailed(dependency, reportException)
                    return@runBlocking
                } else throw t
            }
        }

        if (skipDependencies) {
            callback.onSkippingResolution(dependency)
            callback.onTaskCompleted(listOf("${dependency.artifactId}-v${dependency.version}"))
            return@runBlocking
        }
        val cachedDeps = loadDependencyTreeCache(dependency)
        val allDeps: Collection<Artifact>
        if (cachedDeps != null) {
            allDeps = cachedDeps
        } else {
            try {
                allDeps = withTimeout(300_000L) {
                    dependency.resolveDependencyTree(skipFilter = { dep ->
                        isBuiltInDependency(dep.groupId, dep.artifactId, dep.version)
                    })
                    dependency.getAllDependencies()
                }
            } catch (e: TimeoutCancellationException) {
                callback.onResolutionTimeout(dependency)
                callback.onTaskCompleted(listOf("${dependency.artifactId}-v${dependency.version}"))
                return@runBlocking
            } catch (t: Throwable) {
                callback.onDependenciesNotFound(dependency)
                return@runBlocking
            }
        }

        val processedDeps = mutableListOf<Artifact>()
        val builtInKeys = mutableSetOf<String>()

        allDeps.forEach { dep ->
            if (isBuiltInDependency(dep.groupId, dep.artifactId, dep.version)) {
                builtInKeys.add("${dep.groupId}:${dep.artifactId}:${dep.version}")
                callback.onSkippingResolution(dep)
                return@forEach
            }

            if (dep.extension != "jar" && dep.extension != "aar") {
                callback.invalidPackaging(dep)
                return@forEach
            }

            if (dep.version.isEmpty()) {
                callback.onVersionNotFound(dep)
                return@forEach
            }

            var path = Paths.get(
                downloadPath,
                "${dep.artifactId}-v${dep.version}",
                "classes.${dep.extension}"
            )

            val depCacheCheckFile = if (dep.extension == "aar") "classes.jar" else "classes.${dep.extension}"
            val existingDepFile = resolveExistingLibFile(dep.artifactId, dep.version, depCacheCheckFile)
            val depCached = existingDepFile != null
            if (!depCached) {
                try {
                    Files.createDirectories(path.parent)
                    dep.downloadTo(File(path.toString()))
                } catch (e: Exception) { 
                    if (isStoragePermissionError(e) && downloadPath == SketchwarePaths.getLocalLibsDir().absolutePath) {
                        switchToFallbackPath()
                        path = Paths.get(
                            downloadPath,
                            "${dep.artifactId}-v${dep.version}",
                            "classes.${dep.extension}"
                        )
                        try {
                            Files.createDirectories(path.parent)
                            dep.downloadTo(File(path.toString()))
                        } catch (e2: Exception) {
                            callback.onDownloadError(dep, e2)
                            return@forEach
                        }
                    } else {
                        callback.onDownloadError(dep, e)
                        return@forEach
                    }
                }
            }

            if (dep.extension == "aar" && !depCached) {
                callback.unzipping(dep)
                try {
                    unzip(path)
                    Files.delete(path)
                    val packageName =
                        findPackageName(path.parent.toAbsolutePath().toString(), dep.groupId)
                    path.parent.resolve("config").writeText(packageName)
                } catch (e: Exception) {
                    callback.onDownloadError(dep, e)
                    return@forEach
                }
            }

            val depJar = if (existingDepFile != null) {
                existingDepFile.parentFile!!.resolve("classes.jar").toPath()
            } else if (dep.extension == "jar") {
                path
            } else {
                Paths.get(downloadPath, "${dep.artifactId}-v${dep.version}", "classes.jar")
            }
            if (Files.notExists(depJar)) {
                callback.onDependenciesNotFound(dep)
                return@forEach
            }

            dependencyClasspath.add(depJar)
            processedDeps.add(dep)
        }

        if (cachedDeps == null) {
            saveDependencyTreeCache(dependency, allDeps, builtInKeys)
        }

        processedDeps.forEach { dep ->
            val existingDepDex = resolveExistingLibFile(dep.artifactId, dep.version, "classes.dex")
            if (existingDepDex != null) {
                callback.onResolutionComplete(dep)
                return@forEach
            }

            val dexJar = resolveExistingLibFile(dep.artifactId, dep.version, "classes.jar")?.toPath()
                ?: Paths.get(downloadPath, "${dep.artifactId}-v${dep.version}", "classes.jar")

            callback.dexing(dep)
            try {
                compileJarWithFallback(
                    dexJar, dependencyClasspath.toMutableList().apply { remove(dexJar) }, libraryJars
                )
                callback.onResolutionComplete(dep)
            } catch (t: Throwable) {
                if (t is Exception || t is OutOfMemoryError) {
                    System.gc()
                    val reportException = if (t is OutOfMemoryError)
                        RuntimeException("Out of memory during dexing: ${dep.artifactId}", t)
                    else t as Exception
                    callback.dexingFailed(dep, reportException)
                } else throw t
                return@forEach
            }
        }

        val mainDepName = "${dependency.artifactId}-v${dependency.version}"
        val completedNames = buildList {
            add(mainDepName)
            processedDeps.forEach { dep ->
                val name = "${dep.artifactId}-v${dep.version}"
                if (name != mainDepName) add(name)
            }
        }
        callback.onTaskCompleted(completedNames)
    }

    private fun findPackageName(path: String, defaultValue: String): String {
        val manifest =
            File(path).walk().filter { it.isFile && it.name == "AndroidManifest.xml" }.firstOrNull()
        val content = manifest?.readText() ?: return defaultValue
        val p = Pattern.compile("<manifest.*package=\"(.*?)\"", Pattern.DOTALL)
        val m = p.matcher(content)
        if (m.find()) {
            return m.group(1)!!
        }

        return defaultValue
    }

    private fun unzip(path: Path) {
        val zipFile = ZipFile(path.toFile())
        zipFile.use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val entryDestination = path.parent.resolve(entry.name)
                if (entry.isDirectory) {
                    Files.createDirectories(entryDestination)
                } else {
                    Files.createDirectories(entryDestination.parent)
                    zip.getInputStream(entry).use { input ->
                        Files.newOutputStream(entryDestination).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
    }

    private fun saveDependencyTreeCache(
        mainDep: Artifact,
        deps: Collection<Artifact>,
        builtInKeys: Set<String> = emptySet()
    ) {
        try {
            val cacheFile = Paths.get(downloadPath, "${mainDep.artifactId}-v${mainDep.version}", "dependency-tree.json")
            Files.createDirectories(cacheFile.parent)

            val entries = mutableListOf<HashMap<String, Any?>>()
            val visited = mutableSetOf<String>()
            val mainCoord = "${mainDep.groupId}:${mainDep.artifactId}:${mainDep.version}"

            data class QueueEntry(val artifact: Artifact, val parentCoord: String, val depth: Int)
            val queue = ArrayDeque<QueueEntry>()

            val directDeps = mainDep.dependencies
            if (directDeps != null && directDeps.isNotEmpty()) {
                directDeps.forEach { dep -> queue.add(QueueEntry(dep, mainCoord, 1)) }

                while (queue.isNotEmpty()) {
                    val (dep, parentCoord, depth) = queue.removeFirst()
                    val key = "${dep.groupId}:${dep.artifactId}:${dep.version}"
                    if (!visited.add(key)) continue

                    entries.add(hashMapOf(
                        "groupId" to dep.groupId,
                        "artifactId" to dep.artifactId,
                        "version" to dep.version,
                        "extension" to dep.extension,
                        "repoUrl" to dep.repository?.getURL(),
                        "repoName" to dep.repository?.getName(),
                        "builtIn" to builtInKeys.contains(key),
                        "depth" to depth,
                        "parent" to parentCoord
                    ))

                    dep.dependencies?.forEach { childDep ->
                        val childKey = "${childDep.groupId}:${childDep.artifactId}:${childDep.version}"
                        if (childKey !in visited) {
                            queue.add(QueueEntry(childDep, key, depth + 1))
                        }
                    }
                }
            } else {
                deps.forEach { dep ->
                    val key = "${dep.groupId}:${dep.artifactId}:${dep.version}"
                    entries.add(hashMapOf(
                        "groupId" to dep.groupId,
                        "artifactId" to dep.artifactId,
                        "version" to dep.version,
                        "extension" to dep.extension,
                        "repoUrl" to dep.repository?.getURL(),
                        "repoName" to dep.repository?.getName(),
                        "builtIn" to builtInKeys.contains(key),
                        "depth" to 1,
                        "parent" to mainCoord
                    ))
                }
            }
            cacheFile.writeText(Gson().toJson(entries))
        } catch (_: Exception) {
        }
    }

    private fun loadDependencyTreeCache(mainDep: Artifact): List<Artifact>? {
        val cacheFile = resolveExistingLibFile(mainDep.artifactId, mainDep.version, "dependency-tree.json")?.toPath()
            ?: return null
        return try {
            val cached = Gson().fromJson(cacheFile.readText(), Helper.TYPE_MAP_LIST)
                ?: return null
            cached.map { entry ->
                val repoUrl = entry["repoUrl"] as? String
                val repoName = entry["repoName"] as? String
                Artifact(
                    groupId = entry["groupId"] as? String ?: return null,
                    artifactId = entry["artifactId"] as? String ?: return null,
                    version = entry["version"] as? String ?: return null
                ).apply {
                    extension = entry["extension"] as? String ?: "jar"
                    if (repoUrl != null && repoName != null) {
                        repository = object : Repository {
                            override fun getName() = repoName
                            override fun getURL() = repoUrl
                        }
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isBuiltInDependency(groupId: String, artifactId: String, version: String): Boolean {
        if (groupId.startsWith("androidx.") && groupId in BUILT_IN_ANDROIDX_GROUPS) return true
        if (groupId == "com.google.firebase") return true
        if (groupId.startsWith("com.google.android.gms")) return true
        if (groupId.startsWith("com.google.android.datatransport")) return true
        if (groupId == "com.google.android.material") return true
        if (groupId == "com.google.android.play") return true
        if (groupId.startsWith("org.jetbrains.kotlin")) return true
        if (groupId == "org.jetbrains") return true
        if (groupId == "com.google.code.gson") return true
        if (groupId == "com.github.bumptech.glide") return true
        if (groupId == "com.airbnb.android" && artifactId == "lottie") return true

        if (groupId == "com.squareup.okhttp3") {
            return parseMajorVersion(version) <= 5
        }
        if (groupId == "com.squareup.okio") {
            return parseMajorVersion(version) <= 3
        }

        return false
    }

    private fun parseMajorVersion(version: String): Int =
        version.trimStart().split(".", "-").firstOrNull()?.toIntOrNull() ?: 0

    private fun splitJarFile(jarFile: File): List<File> {
        val splitJars = mutableListOf<File>()
        if (!jarFile.exists()) return splitJars
        if (jarFile.length() <= MAX_JAR_SIZE_BYTES) {
            splitJars.add(jarFile)
            return splitJars
        }

        val classEntries = mutableListOf<Pair<String, ByteArray>>()
        val resourceEntries = mutableListOf<Pair<String, ByteArray>>()
        val addedClassNames = mutableSetOf<String>()

        ZipInputStream(FileInputStream(jarFile)).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val bytes = zis.readBytes()
                    if (entry.name.endsWith(".class")) {
                        if (addedClassNames.add(entry.name)) {
                            classEntries.add(entry.name to bytes)
                        }
                    } else {
                        resourceEntries.add(entry.name to bytes)
                    }
                }
                entry = zis.nextEntry
            }
        }

        val chunks = mutableListOf<MutableList<Pair<String, ByteArray>>>()
        var currentChunk = mutableListOf<Pair<String, ByteArray>>()
        var currentChunkSize = 0L

        for (item in classEntries) {
            val itemSize = item.second.size.toLong()
            if (currentChunk.isNotEmpty() && (currentChunkSize + itemSize > MAX_CHUNK_SIZE_BYTES)) {
                chunks.add(currentChunk)
                currentChunk = mutableListOf()
                currentChunkSize = 0L
            }
            currentChunk.add(item)
            currentChunkSize += itemSize
        }
        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk)
        }

        if (chunks.size > 1) {
            val lastChunk = chunks.last()
            val lastChunkTotalBytes = lastChunk.sumOf { it.second.size.toLong() }

            if (lastChunkTotalBytes <= MIN_CHUNK_SIZE_BYTES) {
                val previousChunk = chunks[chunks.size - 2]
                previousChunk.addAll(lastChunk)
                chunks.removeAt(chunks.size - 1)
            }
        }

        chunks.forEachIndexed { index, chunkClasses ->
            val partIndex = index + 1
            val chunkFile = File(jarFile.parentFile, "split_${partIndex}_${jarFile.name}")

            ZipOutputStream(FileOutputStream(chunkFile)).use { zos ->
                for ((name, bytes) in chunkClasses) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(bytes)
                    zos.closeEntry()
                }

                if (partIndex == 1) {
                    for ((name, bytes) in resourceEntries) {
                        zos.putNextEntry(ZipEntry(name))
                        zos.write(bytes)
                        zos.closeEntry()
                    }
                }
            }
            splitJars.add(chunkFile)
        }

        return if (splitJars.isEmpty()) listOf(jarFile) else splitJars
    }

    private fun createGlobalSyntheticsConsumer(outputDir: File): GlobalSyntheticsConsumer {
        return GlobalSyntheticsConsumer { globalSynthetic, _, _ -> // <--- تعديل التوقيع لثلاث معاملات
            try {
                val bytes = globalSynthetic.bytes // <--- استخدام الخاصية الفعلية بدلاً من getBytes()
                val synthFile = File(outputDir, "synthetic_${System.currentTimeMillis()}_${globalSynthetic.hashCode()}.dex")
                FileOutputStream(synthFile).use { fos ->
                    fos.write(bytes)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun cleanupSyntheticFiles(targetDir: File) {
        runCatching {
            targetDir.listFiles()?.forEach { file ->
                if (file.isFile && (file.name.startsWith("synthetic_") || file.name.endsWith(".synthetic"))) {
                    file.delete()
                }
            }
        }
    }

    private fun compileJarWithFallback(jarFile: Path, jars: List<Path>, libraryJars: List<Path>) {
        Files.createDirectories(jarFile.parent)
        val minApi = buildSettings?.minSdkVersion ?: 26
        val targetDir = jarFile.parent.toFile()

        val jarChunks = splitJarFile(jarFile.toFile()).map { it.toPath() }
        val syntheticsConsumer = createGlobalSyntheticsConsumer(targetDir)

        try {
            jarChunks.forEach { chunk ->
                val otherChunksAsClasspath = jarChunks.filter { it != chunk }
                val combinedClasspath = (jars + otherChunksAsClasspath).distinct()

                try {
                    val builder = D8Command.builder()
                        .setIntermediate(true)
                        .setMode(CompilationMode.RELEASE)
                        .setMinApiLevel(minApi)
                        .addProgramFiles(chunk)
                        .addLibraryFiles(libraryJars)
                        .setGlobalSyntheticsConsumer(syntheticsConsumer)
                        .setOutput(jarFile.parent, OutputMode.DexIndexed)

                    D8.run(builder.build())
                } catch (_: Throwable) {
                    System.gc()
                    val builder = D8Command.builder()
                        .setIntermediate(true)
                        .setMode(CompilationMode.RELEASE)
                        .setMinApiLevel(minApi)
                        .addProgramFiles(chunk)
                        .addLibraryFiles(libraryJars)
                        .addClasspathFiles(combinedClasspath)
                        .setGlobalSyntheticsConsumer(syntheticsConsumer)
                        .setOutput(jarFile.parent, OutputMode.DexIndexed)

                    D8.run(builder.build())
                }
            }
        } finally {
            jarChunks.forEach { chunk ->
                if (chunk != jarFile && Files.exists(chunk)) {
                    runCatching { Files.delete(chunk) }
                }
            }
            cleanupSyntheticFiles(targetDir)
            System.gc()
        }
    }
}
