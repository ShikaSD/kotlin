description = "Kotlin Test for the VM"

// The kotlin.test assertions the VM has, compiled by the VM compiler against the VM stdlib into a klib programs compile against,
// and by the VM backend into the module they link with. See src/kotlin/test/Assertions.kt for why it is a subset.

plugins {
    id("common-configuration")
    `java-base`
}

val vmCompilerClasspath = configurations.create("vmCompilerClasspath") {
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
    vmCompilerClasspath(project(":compiler:cli-vm"))
    vmCompilerClasspath(commonDependency("org.jetbrains.kotlin:kotlin-reflect")) {
        isTransitive = false
    }
    substrateCompilerDependencies(intellijCore())
    substrateCompilerDependencies(libs.opentelemetry.api)
    substrateCompilerDependencies(libs.intellij.fastutil)
    substrateCompilerDependencies(commonDependency("org.codehaus.woodstox:stax2-api"))
    substrateCompilerDependencies(commonDependency("com.fasterxml:aalto-xml"))
    stdlibVm(project(":kotlin-stdlib-vm", configuration = "distVm"))
}

val vmSources = fileTree("src") { include("**/*.kt") }
val outputKlib = layout.buildDirectory.dir("libs/kotlin-test-vm.klib")
val outputVir = layout.buildDirectory.file("libs/kotlin-test-vm.vir")

val compileKotlinTest = tasks.register("compileKotlinTest", JavaExec::class) {
    val javaToolchains = project.extensions.getByType(JavaToolchainService::class.java)
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(8)) })
    classpath = vmCompilerClasspath + substrateCompilerDependencies
    mainClass.set("org.jetbrains.kotlin.cli.vm.K2VmCompiler")
    inputs.files(vmSources).withPropertyName("sources")
    inputs.files(stdlibVm).withPropertyName("stdlib")
    inputs.files(vmCompilerClasspath).withNormalizer(ClasspathNormalizer::class).withPropertyName("vmCompilerClasspath")
    // The VM compiler writes the klib unpacked, as a directory.
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
            "-Xklib=${stdlib.single { it.name.endsWith(".klib") }.absolutePath}",
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
