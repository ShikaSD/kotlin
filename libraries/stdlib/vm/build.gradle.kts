description = "Kotlin Stdlib for the VM"

// The standard library the VM runs, built with jklib as the jklib test stdlib is, and growing toward the common flavor
// Wasm and Native build: common sources, with Kotlin implementations where the JVM's delegates to Java.

plugins {
    id("common-configuration")
    id("com.autonomousapps.dependency-analysis")
    kotlin("jvm")
    base
}

jvmToolchains {
    targetBytecodeVersion = JdkMajorVersion.JDK_1_8
}

val stdlibProjectDir = file("$rootDir/libraries/stdlib")

val jklibCompilerClasspath = configurations.create("jklibCompilerClasspath") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

val substrateStdlibCompilerDependencies = configurations.create("substrateStdlibCompilerDependencies") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    // 1. Dependencies to RUN the JKlib Compiler
    jklibCompilerClasspath(project(":compiler:cli-jklib"))
    jklibCompilerClasspath(commonDependency("org.jetbrains.kotlin:kotlin-reflect")) {
        isTransitive = false
    }
    substrateStdlibCompilerDependencies(intellijCore())

    // Transitive dependencies pulled by IntellijCore
    // Required by 'NoopTelemetryManager', queried through 'CoreProgressManager'
    substrateStdlibCompilerDependencies(libs.opentelemetry.api)
    // Used for IR interning and seriliazation and other things
    substrateStdlibCompilerDependencies(libs.intellij.fastutil)
    // Used to read XML metadata files inside META-INF
    substrateStdlibCompilerDependencies(commonDependency("org.codehaus.woodstox:stax2-api"))
    substrateStdlibCompilerDependencies(commonDependency("com.fasterxml:aalto-xml"))
}

// A directory: with a VM output, the compiler writes the klib unpacked.
val outputKlib = layout.buildDirectory.dir("libs/kotlin-stdlib-vm.klib")
val outputVir = layout.buildDirectory.file("libs/kotlin-stdlib-vm.vir")

val copyMinimalSources = tasks.register("copyMinimalSources", Sync::class) {
    dependsOn(":prepare:build.version:writeStdlibVersion")
    into(layout.buildDirectory.dir("src/genesis-minimal"))

    from(stdlibProjectDir.resolve("jklib-for-test/src/stubs")) {
        include("kotlin/**")
        // The real ranges and `error` instead, below: the loops lowering reads the range classes, and `Preconditions.kt`
        // declares `error` beside `require`.
        exclude("kotlin/ranges/**", "kotlin/stubs.kt", "kotlin/collections/stubs.kt")
        into("src/common")
    }

    from(stdlibProjectDir.resolve("jvm-minimal-for-test/common-src")) {
        include(
            "EnumEntries.kt",
            "Serializable.kt",
        )
        into("src/common")
    }

    from(stdlibProjectDir.resolve("src")) {
        include(
            "kotlin/Annotation.kt",
            "kotlin/Annotations.kt",
            "kotlin/collections/PrimitiveIterators.kt",
            "kotlin/collections/AbstractCollection.kt",
            "kotlin/collections/AbstractIterator.kt",
            "kotlin/collections/AbstractList.kt",
            "kotlin/collections/AbstractMap.kt",
            "kotlin/collections/AbstractSet.kt",
            "kotlin/collections/Collections.kt",
            "kotlin/collections/IndexedValue.kt",
            "kotlin/collections/Iterables.kt",
            "kotlin/collections/Iterators.kt",
            "kotlin/collections/Maps.kt",
            "kotlin/collections/MutableCollections.kt",
            "kotlin/collections/Sets.kt",
            "kotlin/Any.kt",
            "kotlin/Array.kt",
            "kotlin/ArrayIntrinsics.kt",
            "kotlin/Arrays.kt",
            "kotlin/Boolean.kt",
            "kotlin/Char.kt",
            "kotlin/CharSequence.kt",
            "kotlin/Collections.kt",
            "kotlin/Comparable.kt",
            "kotlin/Enum.kt",
            "kotlin/Function.kt",
            "kotlin/Iterator.kt",
            "kotlin/Library.kt",
            "kotlin/Nothing.kt",
            "kotlin/Number.kt",
            "kotlin/String.kt",
            "kotlin/Throwable.kt",
            "kotlin/Primitives.kt",
            "kotlin/Unit.kt",
            "kotlin/annotation/Annotations.kt",
            "kotlin/annotations/OptIn.kt",
            "kotlin/annotations/Multiplatform.kt",
            "kotlin/annotations/WasExperimental.kt",
            "kotlin/annotations/ReturnValue.kt",
            "kotlin/internal/Annotations.kt",
            "kotlin/internal/AnnotationsBuiltin.kt",
            "kotlin/internal/progressionUtil.kt",
            "kotlin/ranges/PrimitiveRanges.kt",
            "kotlin/ranges/ProgressionIterators.kt",
            "kotlin/ranges/Progressions.kt",
            "kotlin/ranges/Range.kt",
            "kotlin/ranges/Ranges.kt",
            "kotlin/random/Random.kt",
            "kotlin/random/XorWowRandom.kt",
            "kotlin/collections/Grouping.kt",
            "kotlin/collections/Sequence.kt",
            "kotlin/collections/Sequences.kt",
            "kotlin/annotations/Inference.kt",
            "kotlin/comparisons/Comparisons.kt",
            "kotlin/experimental/inferenceMarker.kt",
            "kotlin/experimental/ExperimentalNativeApi.kt",
            "kotlin/util/Tuples.kt",
            "kotlin/concurrent/Volatile.kt",
            "kotlin/collections/UArraySorting.kt",
            "kotlin/collections/Arrays.kt",
            "kotlin/collections/MapWithDefault.kt",
            "kotlin/collections/SlidingWindow.kt",
            "kotlin/collections/SequenceBuilder.kt",
            "kotlin/experimental/bitwiseOperations.kt",
            "kotlin/random/URandom.kt",
            "kotlin/util/HashCode.kt",
            "kotlin/util/Lazy.kt",
            "kotlin/util/Result.kt",
            "kotlin/coroutines/**",
            "kotlin/util/Numbers.kt",
            "kotlin/internal/serializationUtil.kt",
            "kotlin/util/Preconditions.kt",
            "kotlin/text/**",
            "kotlin/internal/throwNoWhenBranchMatchedException.kt",
            "kotlin/concurrent/atomics/AtomicArrays.common.kt",
            "kotlin/concurrent/atomics/Atomics.common.kt",
            "kotlin/contextParameters/Context.kt",
            "kotlin/contextParameters/ContextOf.kt",
            "kotlin/contracts/ContractBuilder.kt",
            "kotlin/contracts/Effect.kt",
            "kotlin/util/Standard.kt",
            "kotlin/annotations/Annotations.kt",
            "kotlin/concurrent/atomics/ExperimentalAtomicApi.kt",
            "kotlin/annotations/ExperimentalStdlibApi.kt",
        )
        into("src/common")
    }

    from(stdlibProjectDir.resolve("unsigned/src")) {
        include("**/*.kt")
        into("src/common/unsigned")
    }

    from(stdlibProjectDir.resolve("common/src")) {
        include(
            "generated/_Ranges.kt",
            "generated/_Arrays.kt",
            "generated/_Collections.kt",
            "generated/_Comparisons.kt",
            "generated/_Maps.kt",
            "generated/_Sets.kt",
            "generated/_Sequences.kt",
            "generated/_Strings.kt",
            "generated/_OneToManyTitlecaseMappings.kt",
            "generated/_UArrays.kt",
            "generated/_UCollections.kt",
            "generated/_UComparisons.kt",
            "generated/_URanges.kt",
            "kotlin/collections/UByteValueSet.kt",
            "kotlin/Comparator.kt",
            "kotlin/KotlinH.kt",
            "kotlin/TextH.kt",
            "kotlin/MathH.kt",
            "kotlin/SequencesH.kt",
            "kotlin/JsAnnotationsH.kt",
            "kotlin/collections/AbstractMutableCollection.kt",
            "kotlin/collections/AbstractMutableList.kt",
            "kotlin/collections/AbstractMutableMap.kt",
            "kotlin/collections/AbstractMutableSet.kt",
            "kotlin/collections/ArrayList.kt",
            "kotlin/collections/CollectionsH.kt",
            "kotlin/collections/HashMap.kt",
            "kotlin/collections/HashSet.kt",
            "kotlin/collections/LinkedHashMap.kt",
            "kotlin/collections/LinkedHashSet.kt",
            "kotlin/ioH.kt",
            "kotlin/ExceptionsH.kt",
            "kotlin/JvmAnnotationsH.kt"
        )
        into("src/common")
    }

    from(stdlibProjectDir.resolve("jklib-for-test/src/stubs/jvm/builtins")) {
        include("**")
        into("src/jvm")
    }

    from(stdlibProjectDir.resolve("jvm")) {
        include(
            "runtime/kotlin/NoWhenBranchMatchedException.kt",
            "runtime/kotlin/UninitializedPropertyAccessException.kt",
            "src/kotlin/ArrayIntrinsics.kt",
            "src/kotlin/Unit.kt",
            "src/kotlin/enums/EnumEntriesJVM.kt",
            "src/kotlin/io/Serializable.kt",
            "builtins/*.kt",
            "src/kotlin/jvm/Annotations.kt",
            "src/kotlin/reflect/KDeclarationContainer.kt",
            "runtime/kotlin/jvm/internal/Lambda.kt",
            "runtime/kotlin/jvm/internal/FunctionBase.kt",
            "runtime/kotlin/jvm/annotations/JvmPlatformAnnotations.kt",
            "src/kotlin/internal/throwNoWhenBranchMatchedException.kt",
        )
        // Wasm's atomics instead, in Kotlin: the JVM's builtins declare them for its compiler to implement.
        exclude("builtins/Atomics.kt", "builtins/AtomicArrays.kt")
        into("src/jvm")
    }

    // What the VM's standard library declares itself, until the common sources it stands in for build: common code, and
    // the VM's half of the platform's, whose externals the VM backend compiles to VM operations.
    from(file("src")) {
        include("**/*.kt")
        into("src/common/vm")
    }
    // Wasm's `kotlin.math`, whose float instructions the VM has as the functions of `kotlin.vm.internal`.
    from(stdlibProjectDir.resolve("wasm/src")) {
        include("kotlin/math/**")
        filter { line -> line.replace("kotlin.wasm.internal.wasm_", "kotlin.vm.internal.") }
        into("src/jvm/wasm-math")
    }
    from(stdlibProjectDir.resolve("native-wasm/wasi")) {
        include("kotlin/text/StringBuilder.kt")
        into("src/native-wasm")
    }
    from(stdlibProjectDir.resolve("wasm/src")) {
        include(
            "kotlin/Comparator.kt",
            "kotlin/Lazy.kt",
            "kotlin/Sequences.kt",
            "kotlin/coroutines/Coroutines.kt",
            "kotlin/concurrent/atomics/*.kt",
            "kotlin/Assertions.kt",
            "kotlin/text/regex/BitSet.kt",
            "kotlin/text/regex/DecompositionHelpers.kt",
            "kotlin/text/regex/DecompositionValues.kt",
            "kotlin/text/regex/ObsoleteNativeApi.kt",
            "kotlin/text/CharacterCodingExceptionWasm.kt",
            "kotlin/text/StringNumberConversionsWasm.kt",
            "kotlin/text/utf8Encoding.kt",
        )
        into("src/jvm/wasm")
    }
    from(file("platform-src")) {
        include("**/*.kt")
        into("src/jvm/vm")
    }

    // The common flavor's parts the compiler uses, from the sources Wasm, Native and JS build with.
    from(stdlibProjectDir.resolve("common-non-jvm/src")) {
        include("kotlin/internal/SharedVariableBox.kt", "kotlin/coroutines/cancellation/CancellationException.kt")
        into("src/jvm/common-non-jvm")
    }
    from(stdlibProjectDir.resolve("native-wasm/src")) {
        include("kotlin/internal/serializationUtil.kt")
        into("src/jvm/common-non-jvm")
    }
    // The part Wasm and Native share, as a fragment of its own between the common sources and the VM's: it declares
    // what each platform defines for it.
    from(stdlibProjectDir.resolve("native-wasm/src")) {
        include(
            "kotlin/native/internal.kt",
            "kotlin/native/BitSet.kt",
            "kotlin/native/ObsoleteNativeApi.kt",
            "kotlin/Assertions.kt",
            "kotlin/internal/dtoa/*.kt",
            "kotlin/text/**",
            "generated/**",
            "kotlin/collections/AbstractMutableCollection.kt",
            "kotlin/collections/AbstractMutableList.kt",
            "kotlin/collections/AbstractMutableMap.kt",
            "kotlin/collections/AbstractMutableSet.kt",
            "kotlin/collections/ArrayList.kt",
            "kotlin/collections/ArraySorting.kt",
            "kotlin/collections/Arrays.kt",
            "kotlin/collections/HashMap.kt",
            "kotlin/collections/HashSet.kt",
            "kotlin/collections/Maps.kt",
            "kotlin/collections/MutableCollections.kt",
            "kotlin/collections/RandomAccess.kt",
            "kotlin/collections/Sets.kt",
        )
        // The JVM frontend the VM compiles with wants every value class marked as the JVM's `@JvmInline`.
        filesMatching("**/internal/dtoa/cbigint.kt") {
            filter { line -> line.replace("private value class", "@kotlin.jvm.JvmInline private value class") }
        }
        into("src/native-wasm")
    }

    duplicatesStrategy = DuplicatesStrategy.FAIL
}

fun JavaExec.configureJklibCompilation(
    sourceTask: TaskProvider<Sync>,
    klibOutput: Provider<Directory>,
) {
    dependsOn(sourceTask)

    val jklibCompilerClasspath = project.configurations.getByName("jklibCompilerClasspath")
    val substrateStdlibCompilerDependencies = project.configurations.getByName("substrateStdlibCompilerDependencies")
    classpath = jklibCompilerClasspath + substrateStdlibCompilerDependencies
    mainClass.set("org.jetbrains.kotlin.cli.jklib.K2JKlibCompiler")

    val sourceTree = fileTree(sourceTask.map { it.destinationDir }) {
        include("**/*.kt")
    }
    inputs.files(sourceTree)
    inputs.files(jklibCompilerClasspath)
        .withNormalizer(ClasspathNormalizer::class)
        .withPropertyName("jklibCompilerClasspath")
    outputs.dir(klibOutput)

    doFirst {
        val allFiles = sourceTree.files

        fun fragmentOf(path: String): String = when {
            path.contains("${File.separator}common${File.separator}") -> "common"
            path.contains("${File.separator}native-wasm${File.separator}") -> "native-wasm"
            else -> "platform"
        }
        val paths = allFiles.map { it.absolutePath }
        val (jvmSourceFiles, commonSourceFiles) = paths.partition { fragmentOf(it) == "platform" }

        logger.lifecycle("Compiling ${jvmSourceFiles.size} JVM files and ${commonSourceFiles.size} Common files, total ${allFiles.size}")
        logger.lifecycle("Running K2JKlibCompiler with Java version: ${System.getProperty("java.version")}")

        val outputPath = klibOutput.get().asFile.absolutePath

        args(
            "-no-stdlib",
            "-Xallow-kotlin-package",
            "-Xexpect-actual-classes",
            "-module-name", "kotlin-stdlib",
            "-Xstdlib-compilation",
            "-d", outputPath,
            "-Xmulti-platform",
            "-opt-in=kotlin.contracts.ExperimentalContracts",
            "-opt-in=kotlin.ExperimentalMultiplatform",
            "-opt-in=kotlin.contracts.ExperimentalExtendedContracts",
            "-Xreturn-value-checker=full",
            "-Xcommon-sources=${(commonSourceFiles).joinToString(",")}",
            "-Xfragments=common,native-wasm,platform",
            "-Xfragment-refines=native-wasm:common,platform:native-wasm",
            "-Xfragment-sources=${paths.joinToString(",") { "${fragmentOf(it)}:$it" }}",
        )

        args(jvmSourceFiles)
        args(commonSourceFiles)
    }
}



val compileStdlib = tasks.register("compileStdlib", JavaExec::class) {
    val javaToolchains = project.extensions.getByType(JavaToolchainService::class.java)
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(8))
    })
    configureJklibCompilation(copyMinimalSources, outputKlib)
    // The standard library as a module the VM runs, which a program links beside its own.
    outputs.file(outputVir)
    val vir = outputVir
    doFirst { args("-Xvm-output=${vir.get().asFile.absolutePath}") }

    args("-nowarn")
}

val compileMinimalStdlib = tasks.register("compileMinimalStdlib") {
    dependsOn(compileStdlib)
}

val distJKlib = configurations.create("distJKlib") {
    isCanBeConsumed = true
    isCanBeResolved = false
}
val distMinimalJKlib = configurations.create("distMinimalJKlib") {
    isCanBeConsumed = true
    isCanBeResolved = false
    extendsFrom(distJKlib)
}

artifacts {
    add(distJKlib.name, outputKlib) {
        builtBy(compileStdlib)
    }
    add(distJKlib.name, outputVir) {
        builtBy(compileStdlib)
    }
}
