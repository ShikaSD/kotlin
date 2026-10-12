plugins {
    id("common-configuration")
    id("com.autonomousapps.dependency-analysis")
    kotlin("jvm")
    id("test-inputs-check")
    id("java-test-fixtures")
}

dependencies {
    testFixturesApi(testFixtures(project(":generators:test-generator")))
    testFixturesApi(testFixtures(project(":compiler:tests-integration")))
    testFixturesImplementation(project(":compiler:cli-vm"))
    testFixturesImplementation(project(":compiler:backend.vm"))
    testFixturesImplementation(project(":compiler:ir.serialization.jklib"))

    testFixturesApi("org.junit.jupiter:junit-jupiter")

    testImplementation(project(":compiler:cli-vm"))
    testImplementation(project(":compiler:cli-jvm"))
    testImplementation(testFixtures(project(":compiler:tests-integration")))
    testImplementation(testFixtures(project(":compiler:tests-common")))
    testImplementation(testFixtures(project(":compiler:tests-compiler-utils")))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

sourceSets {
    "main" { }
    "test" { projectDefault() }
    "testFixtures" { projectDefault() }
}

projectTests {
    testData(project(":compiler").isolated, "testData/codegen/box")
    // The box tests expected to fail on the VM; see VmExpectedFailures.
    testData(project.isolated, "testData")
    testData(rootProject.isolated, "third-party/mockJDKs/mockJDK")

    withJvmStdlibAndReflect()
    withMockJdkRuntime()
    withMockJdkAnnotationsJar()
    withThirdPartyJava8Annotations()
    withThirdPartyJsr305()

    testTask(
        defineJDKEnvVariables = listOf(JdkMajorVersion.JDK_1_8, JdkMajorVersion.JDK_11_0, JdkMajorVersion.JDK_17_0)
    ) {
        for ((property, extension) in listOf("kotlin.stdlib.vm.for.test" to ".klib", "kotlin.stdlib.vm.vir" to ".vir")) {
            jvmArgumentProviders.add(objects.newInstance<SystemPropertyClasspathProvider>().apply {
                this.property.set(property)
                classpath.from(stdlibVm.elements.map { it.filter { it.asFile.name.endsWith(extension) } })
            })
        }
        for ((property, extension) in listOf("kotlin.test.vm.klib" to ".klib", "kotlin.test.vm.vir" to ".vir")) {
            jvmArgumentProviders.add(objects.newInstance<SystemPropertyClasspathProvider>().apply {
                this.property.set(property)
                classpath.from(kotlinTestVm.elements.map { it.filter { it.asFile.name.endsWith(extension) } })
            })
        }

        // The VM checkout the box tests link and run with, which they skip without. Its binaries and runtime are inputs,
        // so a changed VM reruns them.
        val vmRepository = providers.environmentVariable("KOTLIN_VM_REPO").orNull
        if (vmRepository != null) {
            systemProperty("kotlin.vm.repo", vmRepository)
            inputs.files(
                "$vmRepository/target/box_tests/link_ir", "$vmRepository/target/box_tests/execute", "$vmRepository/target/box_tests/dump_ir",
                "$vmRepository/runtime/src/runtime.ir",
            )
                .withPropertyName("vmRepository")
        }
        if (providers.environmentVariable("KOTLIN_VM_BOX_UPDATE").orNull == "1") {
            systemProperty("kotlin.vm.box.update", "true")
            outputs.upToDateWhen { false }
        }
    }

    testGenerator("org.jetbrains.kotlin.generators.tests.GenerateVmTestsKt", generateTestsInBuildDirectory = true)
}

// The standard library and kotlin.test the VM tests compile against; see VmSourceRootConfigurator.
val stdlibVm = configurations.create("stdlibVm") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val kotlinTestVm = configurations.create("kotlinTestVm") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    stdlibVm(project(":kotlin-stdlib-vm", configuration = "distMinimalVm"))
    testRuntimeOnly(files(stdlibVm))
    kotlinTestVm(project(":kotlin-test-vm", configuration = "distKotlinTestVm"))
    testRuntimeOnly(files(kotlinTestVm))
}

tasks.named("generateTestsWriteClassPath") {
    inputs.files(stdlibVm)
    inputs.files(kotlinTestVm)
}
