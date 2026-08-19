package pro.sketchware.core.build;

import pro.sketchware.core.project.BuildConfig;
import pro.sketchware.core.project.BuiltInLibrary;
import pro.sketchware.util.io.EncryptedFileUtil;
import pro.sketchware.core.exception.SimpleException;
import pro.sketchware.core.exception.SketchwareException;
import pro.sketchware.core.project.SketchwarePaths;
import pro.sketchware.util.io.ZipUtil;

import static android.system.OsConstants.S_IRUSR;
import static android.system.OsConstants.S_IWUSR;
import static android.system.OsConstants.S_IXUSR;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import android.os.Build;
import android.os.StrictMode;
import android.system.Os;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.Log;
import android.widget.Toast;

import com.android.sdklib.build.ApkBuilder;
import com.android.sdklib.build.ApkCreationException;
import com.android.sdklib.build.DuplicateFileException;
import com.android.sdklib.build.SealedApkException;
import com.android.tools.r8.CompilationFailedException;
import com.github.megatronking.stringfog.plugin.StringFogClassInjector;
import com.github.megatronking.stringfog.plugin.StringFogMappingPrinter;
import com.iyxan23.zipalignjava.InvalidZipException;
import com.iyxan23.zipalignjava.ZipAlign;

import org.xml.sax.SAXException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.stream.Collectors;

import mod.agus.jcoderz.dex.Dex;
import mod.agus.jcoderz.dex.TableOfContents;
import mod.agus.jcoderz.dx.command.dexer.DxContext;
import mod.agus.jcoderz.dx.command.dexer.Main;
import mod.agus.jcoderz.dx.merge.CollisionPolicy;
import mod.agus.jcoderz.dx.merge.DexMerger;
import pro.sketchware.util.library.ExtLibSelected;
import pro.sketchware.util.library.ManageLocalLibrary;
import pro.sketchware.core.build.BuildSettings;
import pro.sketchware.util.Helper;
import pro.sketchware.R;
import pro.sketchware.core.build.compiler.KotlinCompilerBridge;
import pro.sketchware.core.project.ProjectSettings;
import pro.sketchware.core.project.ProguardHandler;
import pro.sketchware.util.SystemLogPrinter;
import pro.sketchware.core.build.BuildProgressReceiver;
import pro.sketchware.util.library.BuiltInLibraries;
import pro.sketchware.core.build.compiler.DexCompiler;
import pro.sketchware.core.build.compiler.ResourceCompiler;
import pro.sketchware.core.exception.MissingFileException;
import pro.sketchware.util.LogUtil;
import pro.sketchware.util.TestkeySignBridge;
import pro.sketchware.core.build.compiler.JarBuilder;
import pro.sketchware.core.build.compiler.R8Compiler;
import pro.sketchware.core.build.ViewBindingBuilder;
import pro.sketchware.SketchApplication;
import pro.sketchware.util.library.BuiltInLibraryManager;
import pro.sketchware.util.FileUtil;
import pro.sketchware.util.SketchwareUtil;
import proguard.Configuration;
import proguard.ConfigurationParser;
import proguard.ParseException;
import proguard.ProGuard;

/**
 * Orchestrates the entire build pipeline for a Sketchware user project:
 * resource compilation (AAPT2), Java compilation (ECJ), Kotlin compilation,
 * DEX generation (D8/Dx), DEX merging, ProGuard/R8 shrinking, StringFog
 * obfuscation, APK assembly, zipalign, and signing.
 */
public class ProjectBuilder {
    public static final String TAG = "AppBuilder";

    private final File aapt2Binary;
    private final Context context;
    public BuildSettings buildSettings;
    public ProjectFilePaths projectFilePaths;
    public ManageLocalLibrary localLibraryManager;
    public BuiltInLibraryManager builtInLibraryManager;
    public String androidJarPath;
    public ProguardHandler proguard;
    public ProjectSettings settings;
    private BuildProgressReceiver progressReceiver;
    private boolean buildAppBundle = false;
    private ArrayList<File> dexesToAddButNotMerge = new ArrayList<>();
    /** Pre-loaded cache set by the caller to avoid a redundant JSON read in {@link #compileJavaCode()}. */
    public IncrementalBuildCache preloadedBuildCache = null;
    /** Set to false by {@link #compileJavaCode()} when incremental build detects no changes. */
    private boolean classFilesChanged = true;

    private long timestampResourceCompilationStarted;

    public ProjectBuilder(Context context, ProjectFilePaths projectFilePaths) {
        StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build()
        );

        SystemLogPrinter.start();

        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            LogUtil.d(TAG, "Running Sketchware Pro " + info.versionName + " (" + info.versionCode + ")");
            ApplicationInfo applicationInfo = context.getPackageManager().getApplicationInfo(context.getPackageName(), 0);
            long fileSizeInBytes = new File(applicationInfo.sourceDir).length();
            LogUtil.d(TAG, "base.apk's size is " + Formatter.formatFileSize(context, fileSizeInBytes) + " (" + fileSizeInBytes + " B)");
        } catch (PackageManager.NameNotFoundException e) {
            LogUtil.e(TAG, "Somehow failed to get package info about us!", e);
        }

        aapt2Binary = new File(context.getCacheDir(), "aapt2");
        buildSettings = new BuildSettings(projectFilePaths.sc_id);
        this.context = context;
        this.projectFilePaths = projectFilePaths;
        localLibraryManager = new ManageLocalLibrary(projectFilePaths.sc_id);
        builtInLibraryManager = new BuiltInLibraryManager(projectFilePaths.sc_id);
        File defaultAndroidJar = new File(BuiltInLibraries.EXTRACTED_COMPILE_ASSETS_PATH, "android.jar");
        androidJarPath = buildSettings.getValue(BuildSettings.SETTING_ANDROID_JAR_PATH, defaultAndroidJar.getAbsolutePath());
        proguard = new ProguardHandler(projectFilePaths.sc_id);
        settings = new ProjectSettings(projectFilePaths.sc_id);
    }

    public ProjectBuilder(BuildProgressReceiver progressReceiver, Context context, ProjectFilePaths projectFilePaths) {
        this(context, projectFilePaths);
        this.progressReceiver = progressReceiver;
    }

    public static boolean hasFileChanged(String fileInAssets, String targetFile) {
        File compareToFile = new File(targetFile);
        EncryptedFileUtil fileUtil = new EncryptedFileUtil();
        long lengthOfFileInAssets = fileUtil.getAssetFileSize(SketchApplication.getAppContext(), fileInAssets);
        long length = compareToFile.exists() ? compareToFile.length() : 0;
        if (lengthOfFileInAssets == length && hasSameAssetContent(fileInAssets, compareToFile)) {
            return false;
        }

        fileUtil.deleteDirectory(compareToFile);
        fileUtil.copyAssetFile(SketchApplication.getAppContext(), fileInAssets, targetFile);
        return true;
    }

    private static boolean hasSameAssetContent(String fileInAssets, File targetFile) {
        if (!targetFile.exists() || !targetFile.isFile()) {
            return false;
        }
        try (InputStream assetStream = SketchApplication.getAppContext().getAssets().open(fileInAssets);
             FileInputStream targetStream = new FileInputStream(targetFile)) {
            return Arrays.equals(computeSha256(assetStream), computeSha256(targetStream));
        } catch (IOException | NoSuchAlgorithmException e) {
            Log.w(TAG, "Failed to compare extracted asset content: " + fileInAssets, e);
            return false;
        }
    }

    private static byte[] computeSha256(InputStream inputStream) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            digest.update(buffer, 0, bytesRead);
        }
        return digest.digest();
    }

    public void compileResources() throws IOException, SimpleException, MissingFileException {
        timestampResourceCompilationStarted = System.currentTimeMillis();
        ResourceCompiler compiler = new ResourceCompiler(
                this,
                aapt2Binary,
                buildAppBundle,
                progressReceiver);
        compiler.compile();
        LogUtil.d(TAG, "Compiling resources took " + (System.currentTimeMillis() - timestampResourceCompilationStarted) + " ms");
    }

    public void generateViewBinding() throws IOException, SAXException {
        if (settings.getValue(ProjectSettings.SETTING_ENABLE_VIEWBINDING, ProjectSettings.SETTING_GENERIC_VALUE_FALSE)
                .equals(ProjectSettings.SETTING_GENERIC_VALUE_FALSE)) {
            return;
        }
        File outputDirectory = new File(projectFilePaths.javaFilesPath + File.separator + projectFilePaths.packageName.replace(".", File.separator) + File.separator + "databinding");
        outputDirectory.mkdirs();

        List<File> layouts = FileUtil.listFiles(projectFilePaths.layoutFilesPath, "xml").stream()
                .map(File::new)
                .collect(Collectors.toList());

        ViewBindingBuilder builder = new ViewBindingBuilder(layouts, outputDirectory, projectFilePaths.packageName);
        builder.generateBindings();
    }

    public boolean isD8Enabled() {
        return buildSettings.getValue(
                BuildSettings.SETTING_DEXER,
                BuildSettings.SETTING_DEXER_DX
        ).equals(BuildSettings.SETTING_DEXER_D8);
    }

    public String getDxRunningText() {
        return (isD8Enabled() ? "D8" : "Dx") + " is running...";
    }

    public void createDexFilesFromClasses() throws CompilationFailedException, ReflectiveOperationException, IOException {
        FileUtil.makeDir(projectFilePaths.binDirectoryPath + File.separator + "dex");
        if (proguard.isShrinkingEnabled() && proguard.isR8Enabled()) return;
        File dexOutputDir = new File(projectFilePaths.binDirectoryPath, "dex");
        File[] existingDexFiles = dexOutputDir.exists() ? dexOutputDir.listFiles((dir, name) -> name.endsWith(".dex")) : null;

        if (isD8Enabled()) {
            long savedTimeMillis = System.currentTimeMillis();
            if (!classFilesChanged && existingDexFiles != null && existingDexFiles.length > 0) {
                Log.d(TAG, "Skipping D8: no .class files changed (incremental). Saved ~"
                        + (System.currentTimeMillis() - savedTimeMillis) + " ms");
                if (progressReceiver != null) {
                    progressReceiver.onProgress("DEX is up to date (no changes)", 17);
                }
                return;
            }
            try {
                DexCompiler.compileDexFiles(this);
                Log.d(TAG, "D8 took " + (System.currentTimeMillis() - savedTimeMillis) + " ms");
            } catch (CompilationFailedException | RuntimeException e) {
                LogUtil.e(TAG, "D8 failed to process .class files", e);
                throw e;
            }
        } else {
            long savedTimeMillis = System.currentTimeMillis();
            if (!classFilesChanged && existingDexFiles != null && existingDexFiles.length > 0) {
                Log.d(TAG, "Skipping Dx: no .class files changed (incremental). Saved ~"
                        + (System.currentTimeMillis() - savedTimeMillis) + " ms");
                if (progressReceiver != null) {
                    progressReceiver.onProgress("Dx is up to date (no changes)", 17);
                }
                return;
            }
            List<String> args = Arrays.asList(
                    "--debug",
                    "--verbose",
                    "--multi-dex",
                    "--output=" + projectFilePaths.binDirectoryPath + File.separator + "dex",
                    proguard.isShrinkingEnabled() ? projectFilePaths.proguardClassesPath : projectFilePaths.compiledClassesPath
            );

            try {
                Log.d(TAG, "Running Dx with these arguments: " + args);

                Main.clearInternTables();
                Main.Arguments arguments = new Main.Arguments();
                Method parseMethod = Main.Arguments.class.getDeclaredMethod("parse", String[].class);
                parseMethod.setAccessible(true);
                parseMethod.invoke(arguments, (Object) args.toArray(new String[0]));

                Main.run(arguments);
                Log.d(TAG, "Dx took " + (System.currentTimeMillis() - savedTimeMillis) + " ms");
            } catch (ReflectiveOperationException | IOException | RuntimeException e) {
                LogUtil.e(TAG, "Dx failed to process .class files", e);
                throw e;
            }
        }
    }

    public String getClasspath() {
        StringBuilder classpath = new StringBuilder();

        KotlinCompilerBridge.maybeAddKotlinFilesToClasspath(classpath, projectFilePaths);
        classpath.append(androidJarPath);

        if (!buildSettings.getValue(BuildSettings.SETTING_NO_HTTP_LEGACY, BuildSettings.SETTING_GENERIC_VALUE_FALSE)
                .equals(BuildSettings.SETTING_GENERIC_VALUE_TRUE)) {
            classpath.append(":").append(BuiltInLibraries.getLibraryClassesJarPathString(BuiltInLibraries.HTTP_LEGACY_ANDROID));
        }

        if (settings.getMinSdkVersion() < 21) {
            classpath.append(":").append(BuiltInLibraries.getLibraryClassesJarPathString(BuiltInLibraries.ANDROIDX_MULTIDEX));
        }

        if (!buildSettings.getValue(BuildSettings.SETTING_JAVA_VERSION,
                        BuildSettings.SETTING_JAVA_VERSION_1_7)
                .equals(BuildSettings.SETTING_JAVA_VERSION_1_7)) {
            classpath.append(":").append(new File(BuiltInLibraries.EXTRACTED_COMPILE_ASSETS_PATH, "core-lambda-stubs.jar").getAbsolutePath());
        }

        for (BuiltInLibrary library : builtInLibraryManager.getLibraries()) {
            classpath.append(":").append(BuiltInLibraries.getLibraryClassesJarPathString(library.getName()));
        }

        classpath.append(localLibraryManager.getJarLocalLibrary());

        if (!buildSettings.getValue(BuildSettings.SETTING_CLASSPATH, "").isEmpty()) {
            classpath.append(":").append(buildSettings.getValue(BuildSettings.SETTING_CLASSPATH, ""));
        }

        String classpathDirectoryPath = SketchwarePaths.getProjectClasspathPath(projectFilePaths.sc_id) + File.separator;
        ArrayList<String> classpathJarPaths = FileUtil.listFiles(classpathDirectoryPath, "jar");
        classpath.append(":").append(TextUtils.join(":", classpathJarPaths));

        return classpath.toString();
    }

    public String getProguardClasspath() {
        Set<String> localLibraryJarsWithFullModeOn = new HashSet<>();

        for (HashMap<String, Object> localLibrary : localLibraryManager.list) {
            Object nameObject = localLibrary.get("name");
            Object jarPathObject = localLibrary.get("jarPath");

            if (nameObject instanceof String name && jarPathObject instanceof String jarPath) {
                if (proguard.libIsProguardFMEnabled(name)) {
                    localLibraryJarsWithFullModeOn.add(jarPath);
                }
            }
        }

        String normalClasspath = getClasspath();
        StringBuilder classpath = new StringBuilder();
        for (String classpathPart : normalClasspath.split(":")) {
            if (localLibraryJarsWithFullModeOn.contains(classpathPart)) {
                continue;
            }

            if (!classpathPart.equals(projectFilePaths.compiledClassesPath)) {
                classpath.append(classpathPart).append(':');
            }
        }

        classpath.deleteCharAt(classpath.length() - 1);
        return classpath.toString();
    }

    private Collection<File> dexLibraries(File outputDirectory, List<File> dexes) throws IOException {
        int lastDexNumber = 1;
        Collection<File> resultDexFiles = new LinkedList<>();
        LinkedList<Dex> dexObjects = new LinkedList<>();
        Iterator<File> toMergeIterator = dexes.iterator();

        int mergedFieldCount;
        int mergedMethodCount;
        int mergedProtoCount;
        int mergedTypeCount;

        {
            File firstFile = toMergeIterator.next();
            Dex firstDex;
            try (FileInputStream fis = new FileInputStream(firstFile)) {
                firstDex = new Dex(fis);
            }
            dexObjects.add(firstDex);
            TableOfContents toc = firstDex.getTableOfContents();
            mergedFieldCount = toc.fieldIds.size;
            mergedMethodCount = toc.methodIds.size;
            mergedProtoCount = toc.protoIds.size;
            mergedTypeCount = toc.typeIds.size;
        }

        while (toMergeIterator.hasNext()) {
            File dexFile = toMergeIterator.next();
            String nextMergedDexFilename = lastDexNumber == 1 ? "classes.dex" : "classes" + lastDexNumber + ".dex";

            Dex dex;
            try (FileInputStream fis = new FileInputStream(dexFile)) {
                dex = new Dex(fis);
            }
            TableOfContents toc = dex.getTableOfContents();

            boolean canMerge = mergedFieldCount + toc.fieldIds.size <= 0xffff
                    && mergedMethodCount + toc.methodIds.size <= 0xffff
                    && mergedProtoCount + toc.protoIds.size <= 0xffff
                    && mergedTypeCount + toc.typeIds.size <= 0xffff;

            if (!canMerge) {
                LogUtil.d(TAG, "Can't merge " + dexFile.getName() + " into " + nextMergedDexFilename
                        + " (fields=" + mergedFieldCount + "+" + toc.fieldIds.size
                        + ", methods=" + mergedMethodCount + "+" + toc.methodIds.size
                        + ", protos=" + mergedProtoCount + "+" + toc.protoIds.size
                        + ", types=" + mergedTypeCount + "+" + toc.typeIds.size + ")");
            }

            if (canMerge) {
                dexObjects.add(dex);
                mergedFieldCount += toc.fieldIds.size;
                mergedMethodCount += toc.methodIds.size;
                mergedProtoCount += toc.protoIds.size;
                mergedTypeCount += toc.typeIds.size;
            } else {
                File target = new File(outputDirectory, nextMergedDexFilename);
                mergeDexes(target, dexObjects);
                resultDexFiles.add(target);
                dexObjects.clear();
                dexObjects.add(dex);

                mergedFieldCount = toc.fieldIds.size;
                mergedMethodCount = toc.methodIds.size;
                mergedProtoCount = toc.protoIds.size;
                mergedTypeCount = toc.typeIds.size;
                lastDexNumber++;
            }
        }
        if (!dexObjects.isEmpty()) {
            File file = new File(outputDirectory, lastDexNumber == 1 ? "classes.dex" : "classes" + lastDexNumber + ".dex");
            mergeDexes(file, dexObjects);
            resultDexFiles.add(file);
        }

        return resultDexFiles;
    }

    public String getLibraryPackageNames() {
        StringBuilder extraPackages = new StringBuilder();
        for (BuiltInLibrary library : builtInLibraryManager.getLibraries()) {
            if (library.hasResources()) {
                extraPackages.append(library.getPackageName()).append(":");
            }
        }
        return extraPackages + localLibraryManager.getPackageNameLocalLibrary();
    }

    public void compileJavaCode() throws SimpleException, IOException {
        long savedTimeMillis = System.currentTimeMillis();

        IncrementalBuildCache cache = preloadedBuildCache != null
                ? preloadedBuildCache
                : new IncrementalBuildCache(projectFilePaths.binDirectoryPath);
        if (preloadedBuildCache == null) cache.load();

        String currentClasspath = getClasspath();
        boolean classesExist = new File(projectFilePaths.compiledClassesPath).exists()
                && !FileUtil.listFilesRecursively(new File(projectFilePaths.compiledClassesPath), ".class").isEmpty();
        boolean cacheFileExists = cache.hasCacheFile();
        boolean proguardShrinkingEnabled = proguard.isShrinkingEnabled();
        boolean classpathChanged = cache.isClasspathChanged(currentClasspath);
        boolean cacheMigrationRequired = cache.requiresFullRebuildMigration();

        boolean canIncremental = classesExist
                && cacheFileExists
                && !proguardShrinkingEnabled
                && !classpathChanged
                && !cacheMigrationRequired;

        if (!canIncremental) {
            runEclipseCompiler(collectAllSourcePaths(), currentClasspath, savedTimeMillis);
            extractAndMergeMetaInf(); // استخراج وتجميع ملفات META-INF
            updateCacheAfterSuccessfulBuild(cache, currentClasspath);
            return;
        }

        List<File> allJavaFiles = FileUtil.listFilesRecursively(
                new File(projectFilePaths.javaFilesPath), ".java");
        List<File> customJavaFiles = new ArrayList<>();
        for (String customDir : getCustomJavaDirectories()) {
            if (FileUtil.isExistFile(customDir)) {
                customJavaFiles.addAll(FileUtil.listFilesRecursively(new File(customDir), ".java"));
            }
        }

        Set<String> currentJavaPaths = new HashSet<>();
        for (File f : allJavaFiles) currentJavaPaths.add(f.getAbsolutePath());
        for (File f : customJavaFiles) currentJavaPaths.add(f.getAbsolutePath());

        List<String> stalePaths = new ArrayList<>();
        for (String cachedPath : cache.getAllCachedFilePaths()) {
            boolean generatedSourceDeleted = isPathWithin(cachedPath, projectFilePaths.javaFilesPath)
                    && !currentJavaPaths.contains(cachedPath);
            boolean customSourceDeleted = isCustomJavaSourcePath(cachedPath)
                    && !currentJavaPaths.contains(cachedPath);
            if (generatedSourceDeleted || customSourceDeleted) {
                deleteOldClassFiles(cachedPath, cache);
                stalePaths.add(cachedPath);
            }
        }
        for (String p : stalePaths) cache.removeFromCache(p);

        List<File> dirtyCustomJavaFiles = new ArrayList<>();
        for (File customJavaFile : customJavaFiles) {
            if (cache.isDirtyFile(customJavaFile)) {
                dirtyCustomJavaFiles.add(customJavaFile);
            }
        }

        List<String> dirtyFilePaths = new ArrayList<>();
        for (File javaFile : allJavaFiles) {
            if (cache.isDirtyFile(javaFile)) {
                dirtyFilePaths.add(javaFile.getAbsolutePath());
            }
        }

        boolean rJavaChanged = cache.isRJavaChanged(projectFilePaths.rJavaDirectoryPath);
        if (rJavaChanged || !dirtyCustomJavaFiles.isEmpty() || !stalePaths.isEmpty()) {
            for (String dirtyFilePath : dirtyFilePaths) {
                deleteOldClassFiles(dirtyFilePath, cache);
            }
            for (File dirtyCustomJavaFile : dirtyCustomJavaFiles) {
                deleteOldClassFiles(dirtyCustomJavaFile.getAbsolutePath(), cache);
            }
            runEclipseCompiler(collectAllSourcePaths(), currentClasspath, savedTimeMillis);
            extractAndMergeMetaInf(); // استخراج وتجميع ملفات META-INF
            updateCacheAfterSuccessfulBuild(cache, currentClasspath);
            return;
        }

        if (dirtyFilePaths.isEmpty()) {
            classFilesChanged = false;
            extractAndMergeMetaInf(); // التأكد من استخراج وتحديث ملفات META-INF
            if (progressReceiver != null) {
                progressReceiver.onProgress("Java is up to date (incremental build, no changes)", 13);
            }
            return;
        }

        for (String dirtyFilePath : dirtyFilePaths) {
            deleteOldClassFiles(dirtyFilePath, cache);
        }
        if (progressReceiver != null) {
            progressReceiver.onProgress("Java is compiling... (incremental: " + dirtyFilePaths.size()
                    + " of " + allJavaFiles.size() + " file(s) changed)", 13);
        }
        dirtyFilePaths.add(projectFilePaths.rJavaDirectoryPath);
        String incrementalClasspath = projectFilePaths.compiledClassesPath + ":" + currentClasspath;
        runEclipseCompiler(dirtyFilePaths, incrementalClasspath, savedTimeMillis);
        extractAndMergeMetaInf(); // استخراج وتجميع ملفات META-INF
        updateCacheAfterSuccessfulBuild(cache, currentClasspath);
    }

    /**
     * يستخرج ويدمج جميع ملفات ومجلدات META-INF (مثل services, extensions, kotlin_module)
     * من كل ملفات JAR الموجودة في الكلاس باث إلى مجلد compiledClassesPath مع استثناء التواقيع والـ Manifest.
     */
    public void extractAndMergeMetaInf() {
        LogUtil.d(TAG, "Extracting and merging META-INF files from JAR libraries...");
        List<String> jarPaths = new ArrayList<>();

        // 1. جمع مكتبات JAR المضمنة (Built-In Libraries)
        for (BuiltInLibrary library : builtInLibraryManager.getLibraries()) {
            File jarFile = BuiltInLibraries.getLibraryClassesJarPath(library.getName());
            if (jarFile.exists()) {
                jarPaths.add(jarFile.getAbsolutePath());
            }
        }

        // 2. جمع المكتبات المحلية (Local Libraries)
        String localJars = localLibraryManager.getJarLocalLibrary();
        if (!TextUtils.isEmpty(localJars)) {
            for (String jarPath : localJars.split(":")) {
                if (!jarPath.trim().isEmpty()) {
                    jarPaths.add(jarPath.trim());
                }
            }
        }

        // 3. جمع ملفات JAR الخاصة بالـ Classpath المباشر للمشروع
        String classpathDir = SketchwarePaths.getProjectClasspathPath(projectFilePaths.sc_id);
        ArrayList<String> projectJars = FileUtil.listFiles(classpathDir, "jar");
        if (projectJars != null) {
            jarPaths.addAll(projectJars);
        }

        File outputClassesDir = new File(projectFilePaths.compiledClassesPath);

        // 4. استخراج ودمج الملفات والمجلدات
        for (String jarPath : jarPaths) {
            File jarFile = new File(jarPath);
            if (!jarFile.exists() || !jarFile.isFile()) {
                continue;
            }

            try (ZipFile zipFile = new ZipFile(jarFile)) {
                Enumeration<? extends ZipEntry> entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();

                    // معالجة العناصر التي تبدأ بـ META-INF/
                    if (name.startsWith("META-INF/") && !entry.isDirectory()) {
                        String metaName = name.substring("META-INF/".length());

                        // استثناء التواقيع وملف MANIFEST.MF
                        if (isIgnoredMetaInfFile(metaName)) {
                            continue;
                        }

                        File outputFile = new File(outputClassesDir, name);

                        // إنشاء المجلدات الأبوية لملفات Services و Kotlin Modules وغيرها
                        File parentDir = outputFile.getParentFile();
                        if (parentDir != null && !parentDir.exists()) {
                            parentDir.mkdirs();
                        }

                        // نسَخ أو دمج البيانات
                        try (InputStream is = zipFile.getInputStream(entry);
                             FileOutputStream fos = new FileOutputStream(outputFile, true)) { // Append = true لمنع حذف الإدخالات المدمجة
                            byte[] buffer = new byte[8192];
                            int bytesRead;
                            while ((bytesRead = is.read(buffer)) != -1) {
                                fos.write(buffer, 0, bytesRead);
                            }
                        }
                    }
                }
            } catch (IOException e) {
                LogUtil.e(TAG, "Failed to process META-INF from JAR: " + jarPath, e);
            }
        }
    }

    /**
     * يتحقق مما إذا كان ملف META-INF حاصلاً على استثناء (تواقيع أو Manifest)
     */
    private boolean isIgnoredMetaInfFile(String name) {
        String upperName = name.toUpperCase();
        return upperName.startsWith("MANIFEST.MF")
                || upperName.endsWith(".SF")
                || upperName.endsWith(".DSA")
                || upperName.endsWith(".RSA")
                || upperName.endsWith(".EC");
    }

    private void runEclipseCompiler(List<String> sourcePaths, String classpath, long startTime)
            throws SimpleException, IOException {

        class EclipseOutOutputStream extends OutputStream {
            private final StringBuilder mBuffer = new StringBuilder();
            @Override public void write(int b) { mBuffer.append((char) b); }
            String getOut() { return mBuffer.toString(); }
        }
        class EclipseErrOutputStream extends OutputStream {
            private final StringBuilder mBuffer = new StringBuilder();
            @Override public void write(int b) { mBuffer.append((char) b); }
            String getOut() { return mBuffer.toString(); }
        }

        try (EclipseOutOutputStream outOutputStream = new EclipseOutOutputStream();
             PrintWriter outWriter = new PrintWriter(outOutputStream);
             EclipseErrOutputStream errOutputStream = new EclipseErrOutputStream();
             PrintWriter errWriter = new PrintWriter(errOutputStream)) {

            ArrayList<String> args = new ArrayList<>();
            args.add("-" + buildSettings.getValue(BuildSettings.SETTING_JAVA_VERSION,
                    BuildSettings.SETTING_JAVA_VERSION_1_7));
            args.add("-nowarn");
            if (!buildSettings.getValue(BuildSettings.SETTING_NO_WARNINGS,
                    BuildSettings.SETTING_GENERIC_VALUE_TRUE).equals(BuildSettings.SETTING_GENERIC_VALUE_TRUE)) {
                args.add("-deprecation");
            }
            args.add("-d");
            args.add(projectFilePaths.compiledClassesPath);
            args.add("-cp");
            args.add(classpath);
            args.add("-proc:none");
            args.addAll(sourcePaths);

            File rJavaFileWithoutPackage = new File(projectFilePaths.rJavaDirectoryPath, "R.java");
            if (rJavaFileWithoutPackage.exists() && !rJavaFileWithoutPackage.delete()) {
                LogUtil.w(TAG, "Failed to delete file " + rJavaFileWithoutPackage.getAbsolutePath());
            }

            org.eclipse.jdt.internal.compiler.batch.Main main =
                    new org.eclipse.jdt.internal.compiler.batch.Main(outWriter, errWriter, false, null, null);
            LogUtil.d(TAG, "Running Eclipse compiler with these arguments: " + args);
            main.compile(args.toArray(new String[0]));

            LogUtil.d(TAG, "System.out of Eclipse compiler: " + outOutputStream.getOut());
            if (main.globalErrorsCount <= 0) {
                LogUtil.d(TAG, "System.err of Eclipse compiler: " + errOutputStream.getOut());
                LogUtil.d(TAG, "Compiling Java files took " + (System.currentTimeMillis() - startTime) + " ms");
            } else {
                LogUtil.e(TAG, "Failed to compile Java files");
                throw new SimpleException(errOutputStream.getOut());
            }
        }
    }

    private List<String> collectAllSourcePaths() {
        List<String> paths = new ArrayList<>();
        paths.add(projectFilePaths.javaFilesPath);
        paths.add(projectFilePaths.rJavaDirectoryPath);
        String pathJava = SketchwarePaths.getProjectJavaPath(projectFilePaths.sc_id);
        if (FileUtil.isExistFile(pathJava)) paths.add(pathJava);
        String pathBroadcast = SketchwarePaths.getProjectBroadcastPath(projectFilePaths.sc_id);
        if (FileUtil.isExistFile(pathBroadcast)) paths.add(pathBroadcast);
        String pathService = SketchwarePaths.getProjectServicePath(projectFilePaths.sc_id);
        if (FileUtil.isExistFile(pathService)) paths.add(pathService);
        return paths;
    }

    private void updateCacheAfterSuccessfulBuild(IncrementalBuildCache cache, String classpath) {
        cache.clearTrackedJavaSources();
        for (File f : FileUtil.listFilesRecursively(new File(projectFilePaths.javaFilesPath), ".java")) {
            cache.markFileClean(f, getCurrentCompiledClassBasePath(f));
        }
        for (String customDir : getCustomJavaDirectories()) {
            if (FileUtil.isExistFile(customDir)) {
                for (File f : FileUtil.listFilesRecursively(new File(customDir), ".java")) {
                    cache.markFileClean(f, getCurrentCompiledClassBasePath(f));
                }
            }
        }
        cache.storeClasspath(classpath);
        cache.storeRJavaHash(projectFilePaths.rJavaDirectoryPath);
        cache.save();
    }

    private List<String> getCustomJavaDirectories() {
        List<String> dirs = new ArrayList<>();
        dirs.add(SketchwarePaths.getProjectJavaPath(projectFilePaths.sc_id));
        dirs.add(SketchwarePaths.getProjectBroadcastPath(projectFilePaths.sc_id));
        dirs.add(SketchwarePaths.getProjectServicePath(projectFilePaths.sc_id));
        return dirs;
    }

    private boolean isCustomJavaSourcePath(String absolutePath) {
        for (String customDir : getCustomJavaDirectories()) {
            if (isPathWithin(absolutePath, customDir)) {
                return true;
            }
        }
        return false;
    }

    private boolean isPathWithin(String absolutePath, String rootPath) {
        return absolutePath.equals(rootPath) || absolutePath.startsWith(rootPath + File.separator);
    }

    private void deleteOldClassFiles(String sourcePath, IncrementalBuildCache cache) {
        for (String classRel : getCompiledClassBasePathCandidates(sourcePath, cache)) {
            int lastSep = classRel.lastIndexOf(File.separator);
            File classDir = lastSep >= 0
                    ? new File(projectFilePaths.compiledClassesPath + File.separator + classRel.substring(0, lastSep))
                    : new File(projectFilePaths.compiledClassesPath);
            String baseName = lastSep >= 0 ? classRel.substring(lastSep + 1) : classRel;

            if (!classDir.exists()) continue;
            String topLevelClassName = baseName + ".class";
            String innerClassPrefix = baseName + "$";
            File[] toDelete = classDir.listFiles(
                    f -> {
                        String fileName = f.getName();
                        return fileName.equals(topLevelClassName)
                                || (fileName.startsWith(innerClassPrefix) && fileName.endsWith(".class"));
                    });
            if (toDelete != null) {
                for (File f : toDelete) {
                    if (!f.delete()) LogUtil.w(TAG, "Could not delete stale class file: " + f.getAbsolutePath());
                }
            }
        }
    }

    private List<String> getCompiledClassBasePathCandidates(String sourcePath, IncrementalBuildCache cache) {
        ArrayList<String> candidates = new ArrayList<>(3);
        addCompiledClassBasePathCandidate(candidates, cache.getStoredCompiledClassBasePath(sourcePath));
        addCompiledClassBasePathCandidate(candidates, getCurrentCompiledClassBasePath(new File(sourcePath)));
        addCompiledClassBasePathCandidate(candidates, getSourcePathDerivedClassBasePath(sourcePath));
        return candidates;
    }

    private void addCompiledClassBasePathCandidate(List<String> candidates, String candidate) {
        if (candidate != null && !candidate.isEmpty() && !candidates.contains(candidate)) {
            candidates.add(candidate);
        }
    }

    private String getCurrentCompiledClassBasePath(File javaFile) {
        if (!javaFile.exists() || !javaFile.getName().endsWith(".java")) {
            return null;
        }

        String simpleName = javaFile.getName();
        simpleName = simpleName.substring(0, simpleName.length() - 5);
        String packageName = extractPackageName(FileUtil.readFile(javaFile.getAbsolutePath()));
        if (packageName.isEmpty()) {
            return simpleName;
        }
        return packageName.replace(".", File.separator) + File.separator + simpleName;
    }

    private String extractPackageName(String sourceCode) {
        boolean inBlockComment = false;
        for (String line : sourceCode.split("\\R")) {
            String trimmedLine = line.replace("\uFEFF", "").trim();
            if (trimmedLine.isEmpty()) {
                continue;
            }

            if (inBlockComment) {
                int blockCommentEndIndex = trimmedLine.indexOf("*/");
                if (blockCommentEndIndex < 0) {
                    continue;
                }
                trimmedLine = trimmedLine.substring(blockCommentEndIndex + 2).trim();
                inBlockComment = false;
                if (trimmedLine.isEmpty()) {
                    continue;
                }
            }

            while (trimmedLine.startsWith("/*")) {
                int blockCommentEndIndex = trimmedLine.indexOf("*/", 2);
                if (blockCommentEndIndex < 0) {
                    inBlockComment = true;
                    trimmedLine = "";
                    break;
                }
                trimmedLine = trimmedLine.substring(blockCommentEndIndex + 2).trim();
            }

            if (trimmedLine.isEmpty() || trimmedLine.startsWith("//") || trimmedLine.startsWith("*")) {
                continue;
            }

            if (trimmedLine.startsWith("package ")) {
                int semicolonIndex = trimmedLine.indexOf(';');
                String packageName = semicolonIndex >= 0
                        ? trimmedLine.substring("package ".length(), semicolonIndex).trim()
                        : trimmedLine.substring("package ".length()).trim();
                return packageName;
            }
            break;
        }
        return "";
    }

    private String getSourcePathDerivedClassBasePath(String absolutePath) {
        String base = getManagedJavaSourceRoot(absolutePath);
        if (base == null) {
            return null;
        }

        String rel = absolutePath.substring(base.length());
        if (rel.startsWith(File.separator)) rel = rel.substring(1);
        return rel.endsWith(".java") ? rel.substring(0, rel.length() - 5) : rel;
    }

    private String getManagedJavaSourceRoot(String absolutePath) {
        if (isPathWithin(absolutePath, projectFilePaths.javaFilesPath)) {
            return projectFilePaths.javaFilesPath;
        }
        for (String customDir : getCustomJavaDirectories()) {
            if (isPathWithin(absolutePath, customDir)) {
                return customDir;
            }
        }
        return null;
    }

    public void buildApk() throws SketchwareException {
        long savedTimeMillis = System.currentTimeMillis();
        String firstDexPath = dexesToAddButNotMerge.isEmpty() ? projectFilePaths.classesDexPath : dexesToAddButNotMerge.remove(0).getAbsolutePath();
        try {
            ApkBuilder apkBuilder = new ApkBuilder(new File(projectFilePaths.unsignedUnalignedApkPath), new File(projectFilePaths.resourcesApkPath), new File(firstDexPath), null, null, System.out);

            for (BuiltInLibrary library : builtInLibraryManager.getLibraries()) {
                apkBuilder.addResourcesFromJar(BuiltInLibraries.getLibraryClassesJarPath(library.getName()));
            }

            for (String jarPath : localLibraryManager.getJarLocalLibrary().split(":")) {
                if (!jarPath.trim().isEmpty()) {
                    apkBuilder.addResourcesFromJar(new File(jarPath));
                }
            }

            File nativeLibrariesDirectory = new File(SketchwarePaths.getProjectNativeLibsPath(projectFilePaths.sc_id));
            if (nativeLibrariesDirectory.exists()) {
                apkBuilder.addNativeLibraries(nativeLibrariesDirectory);
            }

            for (String nativeLibraryDirectory : localLibraryManager.getNativeLibs()) {
                apkBuilder.addNativeLibraries(new File(nativeLibraryDirectory));
            }

            if (dexesToAddButNotMerge.isEmpty()) {
                List<String> dexFiles = FileUtil.listFiles(projectFilePaths.binDirectoryPath, "dex");
                for (String dexFile : dexFiles) {
                    String dexFileName = new File(dexFile).getName();
                    if (!dexFileName.equals("classes.dex")) {
                        apkBuilder.addFile(new File(dexFile), dexFileName);
                    }
                }
            } else {
                int dexNumber = 2;

                for (File dexFile : dexesToAddButNotMerge) {
                    apkBuilder.addFile(dexFile, "classes" + dexNumber + ".dex");
                    dexNumber++;
                }
            }

            apkBuilder.setDebugMode(false);
            apkBuilder.sealApk();
        } catch (ApkCreationException | SealedApkException e) {
            throw new SketchwareException(e.getMessage());
        } catch (DuplicateFileException e) {
            String message = "Duplicate files from two libraries detected \r\n";
            message += "File1: " + e.getFile1() + " \r\n";
            message += "File2: " + e.getFile2() + " \r\n";
            message += "Archive path: " + e.getArchivePath();
            throw new SketchwareException(message);
        }
        Log.d(TAG, "Building APK took " + (System.currentTimeMillis() - savedTimeMillis) + " ms");
        Log.d(TAG, "Time passed since starting to compile resources until building the unsigned APK: " +
                (System.currentTimeMillis() - timestampResourceCompilationStarted) + " ms");
    }

    public void getDexFilesReady() throws IOException {
        long savedTimeMillis = System.currentTimeMillis();
        ArrayList<File> dexes = new ArrayList<>();

        if (settings.getMinSdkVersion() < 21) {
            dexes.add(BuiltInLibraries.getLibraryDexFile(BuiltInLibraries.ANDROIDX_MULTIDEX));
        }

        if (!buildSettings.getValue(BuildSettings.SETTING_NO_HTTP_LEGACY, ProjectSettings.SETTING_GENERIC_VALUE_FALSE)
                .equals(ProjectSettings.SETTING_GENERIC_VALUE_TRUE)) {
            dexes.add(BuiltInLibraries.getLibraryDexFile(BuiltInLibraries.HTTP_LEGACY_ANDROID));
        }

        for (BuiltInLibrary builtInLibrary : builtInLibraryManager.getLibraries()) {
            dexes.add(BuiltInLibraries.getLibraryDexFile(builtInLibrary.getName()));
        }

        ArrayList<HashMap<String, Object>> list = localLibraryManager.list;
        for (int localLibIdx = 0, listSize = list.size(); localLibIdx < listSize; localLibIdx++) {
            HashMap<String, Object> localLibrary = list.get(localLibIdx);
            Object localLibraryName = localLibrary.get("name");

            if (localLibraryName instanceof String) {
                Object localLibraryDexPath = localLibrary.get("dexPath");

                if (localLibraryDexPath instanceof String) {
                    if (!proguard.libIsProguardFMEnabled((String) localLibraryName)) {
                        dexes.add(new File((String) localLibraryDexPath));
                        File localLibraryDirectory = new File((String) localLibraryDexPath).getParentFile();

                        if (localLibraryDirectory != null) {
                            File[] localLibraryFiles = localLibraryDirectory.listFiles();

                            if (localLibraryFiles != null) {
                                for (File localLibraryFile : localLibraryFiles) {
                                    String filename = localLibraryFile.getName();

                                    if (!filename.equals("classes.dex")
                                            && filename.startsWith("classes") && filename.endsWith(".dex")) {
                                        dexes.add(localLibraryFile);
                                    }
                                }
                            }
                        }
                    }
                } else {
                    SketchwareUtil.toastError(String.format(Helper.getResString(R.string.error_invalid_dex_path), localLibIdx), Toast.LENGTH_LONG);
                }
            } else {
                SketchwareUtil.toastError(String.format(Helper.getResString(R.string.error_invalid_lib_name), localLibIdx), Toast.LENGTH_LONG);
            }
        }

        for (String dexFilePath : FileUtil.listFiles(projectFilePaths.binDirectoryPath + File.separator + "dex", "dex")) {
            dexes.add(new File(dexFilePath));
        }

        LogUtil.d(TAG, "Will merge these " + dexes.size() + " DEX files to classes.dex: " + dexes);

        if (settings.getMinSdkVersion() < 21 || !projectFilePaths.buildConfig.isDebugBuild) {
            String dexFingerprint = computeDexMergeFingerprint(dexes);
            File fingerprintFile = new File(projectFilePaths.binDirectoryPath, "dex_merge_fingerprint");
            File mergedClassesDex = new File(projectFilePaths.binDirectoryPath, "classes.dex");
            if (mergedClassesDex.exists() && fingerprintFile.exists()) {
                try {
                    String cached = new String(java.nio.file.Files.readAllBytes(fingerprintFile.toPath()));
                    if (dexFingerprint.equals(cached)) {
                        Log.d(TAG, "Skipping DEX merge: all input DEX files unchanged (cached). Saved ~"
                                + (System.currentTimeMillis() - savedTimeMillis) + " ms");
                        if (progressReceiver != null) {
                            progressReceiver.onProgress("DEX merge is up to date (cached)", 18);
                        }
                        return;
                    }
                } catch (IOException e) {
                    Log.d(TAG, "Failed to read DEX merge fingerprint, will re-merge: " + e.getMessage());
                }
            }
            dexLibraries(new File(projectFilePaths.binDirectoryPath), dexes);
            try {
                java.nio.file.Files.write(fingerprintFile.toPath(), dexFingerprint.getBytes());
            } catch (IOException e) {
                Log.d(TAG, "Failed to save DEX merge fingerprint: " + e.getMessage());
            }
            Log.d(TAG, "Merging DEX files took " + (System.currentTimeMillis() - savedTimeMillis) + " ms");
        } else {
            dexesToAddButNotMerge = dexes;
            Log.d(TAG, "Skipped merging DEX files due to debug build with minSdkVersion >= 21");
        }
    }

    public void maybeExtractAapt2() throws SketchwareException {
        var abi = Build.SUPPORTED_ABIS[0];
        String assetPath = "aapt/aapt2-" + abi;
        try {
            try (var ignored = context.getAssets().open(assetPath)) {
            } catch (FileNotFoundException e) {
                throw e;
            } catch (IOException e) {
                throw new IOException("Failed to read AAPT2 asset: " + assetPath, e);
            }
            boolean extracted = hasFileChanged(assetPath, aapt2Binary.getAbsolutePath());
            if (!aapt2Binary.exists()) {
                throw new IOException("AAPT2 binary was not extracted to " + aapt2Binary.getAbsolutePath());
            }
            if (extracted) {
                Os.chmod(aapt2Binary.getAbsolutePath(), S_IRUSR | S_IWUSR | S_IXUSR);
            }
        } catch (FileNotFoundException e) {
            LogUtil.e(TAG, "Failed to extract AAPT2 binaries", e);
            throw new SketchwareException(
                    "Looks like the device's architecture (" + abi + ") isn't supported.\n"
                            + Log.getStackTraceString(e)
            );
        } catch (IOException | android.system.ErrnoException | RuntimeException e) {
            LogUtil.e(TAG, "Failed to extract AAPT2 binaries", e);
            throw new SketchwareException(
                    "Couldn't extract AAPT2 binaries! Message: " + e.getMessage()
            );
        }
    }

    public void buildBuiltInLibraryInformation() {
        if (projectFilePaths.buildConfig.isAppCompatEnabled) {
            builtInLibraryManager.addLibrary(BuiltInLibraries.ANDROIDX_APPCOMPAT);
            builtInLibraryManager.addLibrary(BuiltInLibraries.ANDROIDX_COORDINATORLAYOUT);
            builtInLibraryManager.addLibrary(BuiltInLibraries.MATERIAL);
        }
        if (projectFilePaths.buildConfig.isFirebaseEnabled) {
            builtInLibraryManager.addLibrary(BuiltInLibraries.FIREBASE_COMMON);
        }
        if (projectFilePaths.buildConfig.isFirebaseAuthUsed) {
            builtInLibraryManager.addLibrary(BuiltInLibraries.FIREBASE_AUTH);
        }
        if (projectFilePaths.buildConfig.isFirebaseDatabaseUsed) {
            builtInLibraryManager.addLibrary(BuiltInLibraries.FIREBASE_DATABASE);
        }
        if (projectFilePaths.buildConfig.isFirebaseStorageUsed) {
            builtInLibraryManager.addLibrary(BuiltInLibraries.FIREBASE_STORAGE);
        }
        if (projectFilePaths.buildConfig.isMapUsed) {
            builtInLibraryManager.addLibrary(BuiltInLibraries.PLAY_SERVICES_MAPS);
        }
        if (projectFilePaths.buildConfig.isAdMobEnabled) {
            builtInLibraryManager.addLibrary(BuiltInLibraries.PLAY_SERVICES_ADS);
        }
        if (projectFilePaths.buildConfig.isGsonUsed) {
            builtInLibraryManager.addLibrary(BuiltInLibraries.GSON);
        }
        if (projectFilePaths.buildConfig.isGlideUsed) {
            builtInLibraryManager.addLibrary(BuiltInLibraries.GLIDE);
        }
        if (projectFilePaths.buildConfig.isHttp3Used) {
            builtInLibraryManager.addLibrary(BuiltInLibraries.OKHTTP_ANDROID);
        }

        KotlinCompilerBridge.maybeAddKotlinBuiltInLibraryDependenciesIfPossible(this, builtInLibraryManager);
        ExtLibSelected.addUsedDependencies(projectFilePaths.buildConfig.constVarComponent, builtInLibraryManager);
    }

    public BuiltInLibraryManager getBuiltInLibraryManager() {
        return builtInLibraryManager;
    }

    public void signDebugApk() throws GeneralSecurityException, IOException, ClassNotFoundException, IllegalAccessException, InstantiationException {
        long savedTimeMillis = System.currentTimeMillis();
        TestkeySignBridge.signWithTestkey(projectFilePaths.unsignedUnalignedApkPath, projectFilePaths.finalToInstallApkPath);
        Log.d(TAG, "Signing debug APK took " + (System.currentTimeMillis() - savedTimeMillis) + " ms");
    }

    private String computeDexMergeFingerprint(List<File> dexFiles) {
        StringBuilder sb = new StringBuilder(dexFiles.size() * 64);
        for (File f : dexFiles) {
            sb.append(f.getAbsolutePath()).append('|')
              .append(f.length()).append('|')
              .append(f.lastModified()).append('\n');
        }
        return sb.toString();
    }

    private void mergeDexes(File target, List<Dex> dexes) throws IOException {
        DexMerger merger = new DexMerger(dexes.toArray(new Dex[0]), CollisionPolicy.KEEP_FIRST, new DxContext());
        merger.merge().writeTo(target);
    }

    private void proguardAddLibConfigs(List<String> args) {
        for (BuiltInLibrary library : builtInLibraryManager.getLibraries()) {
            File config = BuiltInLibraries.getLibraryProguardConfiguration(library.getName());
            if (config.exists()) {
                args.add("-include");
                args.add(config.getAbsolutePath());
            }
        }
    }

    private void proguardAddRjavaRules(List<String> args) {
        FileUtil.writeFile(projectFilePaths.proguardAutoGeneratedExclusions, getRJavaRules());
        args.add("-include");
        args.add(projectFilePaths.proguardAutoGeneratedExclusions);
    }

    private String getRJavaRules() {
        StringBuilder sb = new StringBuilder("# R.java rules");
        for (BuiltInLibrary jp : builtInLibraryManager.getLibraries()) {
            if (jp.hasResources() && !jp.getPackageName().isEmpty()) {
                sb.append("\n");
                sb.append("-keep class ");
                sb.append(jp.getPackageName());
                sb.append(".** { *; }");
            }
        }
        for (HashMap<String, Object> libEntry : localLibraryManager.list) {
            String obj = String.valueOf(libEntry.get("name"));
            if (libEntry.containsKey("packageName") && !proguard.libIsProguardFMEnabled(obj)) {
                sb.append("\n");
                sb.append("-keep class ");
                sb.append(String.valueOf(libEntry.get("packageName")));
                sb.append(".** { *; }");
            }
        }
        sb.append("\n");
        sb.append("-keep class ").append(projectFilePaths.packageName).append(".R { *; }").append('\n');
        return sb.toString();
    }

    public void runR8() throws IOException {
        long savedTimeMillis = System.currentTimeMillis();

        ArrayList<String> config = new ArrayList<>();
        config.add(ProguardHandler.ANDROID_PROGUARD_RULES_PATH);
        config.add(projectFilePaths.proguardAaptRules);
        config.add(proguard.getCustomProguardRules());
        var rules = new ArrayList<>(Arrays.asList(getRJavaRules().split("\n")));
        for (BuiltInLibrary library : builtInLibraryManager.getLibraries()) {
            File f = BuiltInLibraries.getLibraryProguardConfiguration(library.getName());
            if (f.exists()) {
                config.add(f.getAbsolutePath());
            }
        }
        config.addAll(localLibraryManager.getPgRules());
        ArrayList<String> jars = new ArrayList<>();
        jars.add(projectFilePaths.compiledClassesPath + ".jar");

        for (HashMap<String, Object> libEntry : localLibraryManager.list) {
            String obj = String.valueOf(libEntry.get("name"));
            if (libEntry.containsKey("jarPath") && proguard.libIsProguardFMEnabled(obj)) {
                jars.add(String.valueOf(libEntry.get("jarPath")));
            }
        }
        try {
            JarBuilder.INSTANCE.generateJar(new File(projectFilePaths.compiledClassesPath));
            new R8Compiler(rules, config.toArray(new String[0]), getProguardClasspath().split(":"), jars.toArray(new String[0]), settings.getMinSdkVersion(), projectFilePaths).compile();
        } catch (Exception e) {
            throw new IOException(e);
        }
        LogUtil.d(TAG, "R8 took " + (System.currentTimeMillis() - savedTimeMillis) + " ms");
    }

    public void runProguard() throws IOException {
        long savedTimeMillis = System.currentTimeMillis();

        ArrayList<String> args = new ArrayList<>();
        args.add("-include");
        args.add(ProguardHandler.ANDROID_PROGUARD_RULES_PATH);
        args.add("-include");
        args.add(projectFilePaths.proguardAaptRules);
        args.add("-include");
        args.add(proguard.getCustomProguardRules());

        proguardAddLibConfigs(args);
        proguardAddRjavaRules(args);

        for (String rule : localLibraryManager.getPgRules()) {
            args.add("-include");
            args.add(rule);
        }

        args.add("-injars");
        args.add(projectFilePaths.compiledClassesPath);

        for (HashMap<String, Object> libEntry : localLibraryManager.list) {
            String obj = String.valueOf(libEntry.get("name"));
            if (libEntry.containsKey("jarPath") && proguard.libIsProguardFMEnabled(obj)) {
                args.add("-injars");
                args.add(String.valueOf(libEntry.get("jarPath")));
            }
        }
        args.add("-libraryjars");
        args.add(getProguardClasspath());
        args.add("-outjars");
        args.add(projectFilePaths.proguardClassesPath);
        if (proguard.isDebugFilesEnabled()) {
            args.add("-printseeds");
            args.add(projectFilePaths.proguardSeedsPath);
            args.add("-printusage");
            args.add(projectFilePaths.proguardUsagePath);
            args.add("-printmapping");
            args.add(projectFilePaths.proguardMappingPath);
        }

        Configuration configuration = new Configuration();

        try {
            ConfigurationParser parser = new ConfigurationParser(args.toArray(new String[0]), System.getProperties());
            try {
                parser.parse(configuration);
            } finally {
                parser.close();
            }
        } catch (ParseException e) {
            throw new IOException(e);
        }

        try {
            new ProGuard(configuration).execute();
        } catch (Exception e) {
            throw new IOException(e);
        }

        LogUtil.d(TAG, "ProGuard took " + (System.currentTimeMillis() - savedTimeMillis) + " ms");
    }

    public void runStringfog() {
        try {
            StringFogMappingPrinter stringFogMappingPrinter = new StringFogMappingPrinter(new File(projectFilePaths.binDirectoryPath,
                    "stringFogMapping.txt"));
            StringFogClassInjector stringFogClassInjector = new StringFogClassInjector(new String[0],
                    "UTF-8",
                    "com.github.megatronking.stringfog.xor.StringFogImpl",
                    "com.github.megatronking.stringfog.xor.StringFogImpl",
                    stringFogMappingPrinter);
            stringFogMappingPrinter.startMappingOutput();
            stringFogMappingPrinter.ouputInfo("UTF-8", "com.github.megatronking.stringfog.xor.StringFogImpl");
            stringFogClassInjector.doFog2ClassInDir(new File(projectFilePaths.compiledClassesPath));
            ZipUtil.extractAssetZip(context, "stringfog/stringfog.zip", projectFilePaths.compiledClassesPath);
        } catch (Exception e) {
            LogUtil.e("StringFog", "Failed to run StringFog", e);
        }
    }

    public void runZipalign(String inPath, String outPath) throws SketchwareException {
        LogUtil.d(TAG, "About to zipalign " + inPath + " to " + outPath);
        long savedTimeMillis = System.currentTimeMillis();

        try (RandomAccessFile in = new RandomAccessFile(inPath, "r");
             FileOutputStream out = new FileOutputStream(outPath)) {
            ZipAlign.alignZip(in, out);
        } catch (IOException e) {
            throw new SketchwareException("Couldn't run zipalign on " + inPath + " with output path " + outPath + ": " + Log.getStackTraceString(e));
        } catch (InvalidZipException e) {
            throw new SketchwareException("Failed to zipalign due to the given zip being invalid: " + Log.getStackTraceString(e));
        }

        LogUtil.d(TAG, "zipalign took " + (System.currentTimeMillis() - savedTimeMillis) + " ms");
    }

    public void setBuildAppBundle(boolean buildAppBundle) {
        this.buildAppBundle = buildAppBundle;
    }
}
