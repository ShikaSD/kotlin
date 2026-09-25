description = "Kotlin Test for the VM"

// The kotlin.test assertions the VM has, compiled with jklib against the VM stdlib into a klib programs compile against,
// and by the VM backend into the module they link with. See src/kotlin/test/Assertions.kt for why it is a subset.

plugins {
    id("common-configuration")
    `java-base`
}

val jklibCompilerClasspath = configurations.create("jklibCompilerClasspath") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

val substrateCompilerDependencies = configurations.create("substrateCompilerDependencies") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

val stdlibVm = configurations.create("stdlibVm") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    jklibCompilerClasspath(project(":compiler:cli-jklib"))
    jklibCompilerClasspath(commonDependency("org.jetbrains.kotlin:kotlin-reflect")) {
        isTransitive = false
    }
    substrateCompilerDependencies(intellijCore())
    substrateCompilerDependencies(libs.opentelemetry.api)
    substrateCompilerDependencies(libs.intellij.fastutil)
    substrateCompilerDependencies(commonDependency("org.codehaus.woodstox:stax2-api"))
    substrateCompilerDependencies(commonDependency("com.fasterxml:aalto-xml"))
    stdlibVm(project(":kotlin-stdlib-vm", configuration = "distJKlib"))
}

val vmSources = fileTree("src") { include("**/*.kt") }
val outputKlib = layout.buildDirectory.dir("libs/kotlin-test-vm.klib")
val outputVir = layout.buildDirectory.file("libs/kotlin-test-vm.vir")

val compileKotlinTest = tasks.register("compileKotlinTest", JavaExec::class) {
    val javaToolchains = project.extensions.getByType(JavaToolchainService::class.java)
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(8)) })
    classpath = jklibCompilerClasspath + substrateCompilerDependencies
    mainClass.set("org.jetbrains.kotlin.cli.jklib.K2JKlibCompiler")
    inputs.files(vmSources).withPropertyName("sources")
    inputs.files(stdlibVm).withPropertyName("stdlib")
    inputs.files(jklibCompilerClasspath).withNormalizer(ClasspathNormalizer::class).withPropertyName("jklibCompilerClasspath")
    // jklib writes the klib unpacked, as a directory.
    outputs.dir(outputKlib)
    outputs.file(outputVir)

    // Locals, since the configuration cache cannot keep references to the script's own values.
    val stdlib: FileCollection = stdlibVm
    val sources: FileCollection = vmSources
    val klib = outputKlib
    val vir = outputVir
    doFirst {
        args(
            "-no-stdlib",
            "-Xklib=${stdlib.singleFile.absolutePath}",
            "-module-name", "kotlin-test",
            "-d", klib.get().asFile.absolutePath,
            "-Xvm-output=${vir.get().asFile.absolutePath}",
            "-Xallow-kotlin-package",
        )
        args(sources.files.map { it.absolutePath })
    }
}

val distKotlinTestVm = configurations.create("distKotlinTestVm") {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(distKotlinTestVm.name, outputKlib) { builtBy(compileKotlinTest) }
    add(distKotlinTestVm.name, outputVir) { builtBy(compileKotlinTest) }
}
