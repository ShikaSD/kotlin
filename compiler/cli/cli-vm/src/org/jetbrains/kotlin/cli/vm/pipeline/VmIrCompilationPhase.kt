/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli.vm.pipeline

import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.backend.common.IrBuiltInsForLinker
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContextImpl
import org.jetbrains.kotlin.backend.common.linkage.issues.checkNoUnboundSymbols
import org.jetbrains.kotlin.backend.common.reportLoadingProblemsIfAny
import org.jetbrains.kotlin.backend.common.serialization.DescriptorByIdSignatureFinderImpl
import org.jetbrains.kotlin.backend.common.serialization.DeserializationStrategy
import org.jetbrains.kotlin.backend.common.serialization.signature.IdSignatureDescriptor
import org.jetbrains.kotlin.backend.jvm.JvmIrTypeSystemContext
import org.jetbrains.kotlin.backend.jvm.overrides.IrJavaIncompatibilityRulesOverridabilityCondition
import org.jetbrains.kotlin.ir.backend.jklib.JKlibFieldShadowingOverridabilityCondition
import org.jetbrains.kotlin.builtins.jvm.JvmBuiltInClassDescriptorFactory
import org.jetbrains.kotlin.builtins.jvm.JvmBuiltIns
import org.jetbrains.kotlin.builtins.jvm.JvmBuiltInsPackageFragmentProvider
import org.jetbrains.kotlin.cli.vm.config.klibPaths
import org.jetbrains.kotlin.cli.jvm.compiler.AllJavaSourcesInProjectScope
import org.jetbrains.kotlin.cli.jvm.compiler.NoScopeRecordCliBindingTrace
import org.jetbrains.kotlin.cli.jvm.compiler.VfsBasedProjectEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.toAbstractProjectFileSearchScope
import org.jetbrains.kotlin.cli.pipeline.CheckCompilationErrors
import org.jetbrains.kotlin.cli.pipeline.PipelinePhase
import org.jetbrains.kotlin.config.CommonConfigurationKeys.MODULE_NAME
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.config.zipFileSystemAccessor
import org.jetbrains.kotlin.container.get
import org.jetbrains.kotlin.context.ContextForNewModule
import org.jetbrains.kotlin.context.ProjectContext
import org.jetbrains.kotlin.descriptors.ClassConstructorDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.SimpleFunctionDescriptor
import org.jetbrains.kotlin.descriptors.deserialization.AdditionalClassPartsProvider
import org.jetbrains.kotlin.descriptors.deserialization.ClassDescriptorFactory
import org.jetbrains.kotlin.descriptors.impl.CompositePackageFragmentProvider
import org.jetbrains.kotlin.descriptors.impl.ModuleDescriptorImpl
import org.jetbrains.kotlin.frontend.java.di.createContainerForLazyResolveWithJava
import org.jetbrains.kotlin.idea.MainFunctionDetector
import org.jetbrains.kotlin.incremental.components.EnumWhenTracker
import org.jetbrains.kotlin.incremental.components.ExpectActualTracker
import org.jetbrains.kotlin.incremental.components.InlineConstTracker
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.ir.InternalSymbolFinderAPI
import org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI
import org.jetbrains.kotlin.ir.backend.jklib.JKlibDescriptorMangler
import org.jetbrains.kotlin.ir.backend.jklib.JKlibIrLinker
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.impl.IrFactoryImpl
import org.jetbrains.kotlin.ir.util.ExternalDependenciesGenerator
import org.jetbrains.kotlin.ir.util.IdSignature
import org.jetbrains.kotlin.ir.util.SymbolTable
import org.jetbrains.kotlin.library.KotlinLibrary
import org.jetbrains.kotlin.library.isJklibStdlib
import org.jetbrains.kotlin.library.loader.KlibLoader
import org.jetbrains.kotlin.library.metadata.KlibMetadataFactories
import org.jetbrains.kotlin.load.java.lazy.ModuleClassResolver
import org.jetbrains.kotlin.load.java.structure.JavaClass
import org.jetbrains.kotlin.load.java.structure.impl.VirtualFileBoundJavaClass
import org.jetbrains.kotlin.load.kotlin.JavaFlexibleTypeDeserializer
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi2ir.generators.DeclarationStubGeneratorImpl
import org.jetbrains.kotlin.resolve.BindingTraceContext
import org.jetbrains.kotlin.resolve.CompilerEnvironment
import org.jetbrains.kotlin.resolve.jvm.JavaDescriptorResolver
import org.jetbrains.kotlin.resolve.jvm.multiplatform.OptionalAnnotationPackageFragmentProvider
import org.jetbrains.kotlin.resolve.lazy.declarations.DeclarationProviderFactory
import org.jetbrains.kotlin.storage.StorageManager
import org.jetbrains.kotlin.types.KotlinType
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.builtins.jvm.JavaToKotlinClassMap
import org.jetbrains.kotlin.resolve.descriptorUtil.classId
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import org.jetbrains.kotlin.descriptors.ModuleDescriptor

@OptIn(ObsoleteDescriptorBasedAPI::class)
object VmIrCompilationPhase :
    PipelinePhase<VmSerializationArtifact, VmIrCompilationArtifact>(
        name = "VmIrCompilationPhase",
        postActions = setOf(CheckCompilationErrors.CheckDiagnosticCollector),
    ) {

    override fun executePhase(input: VmSerializationArtifact): VmIrCompilationArtifact {
        val configuration = input.configuration
        val klib = Path(input.outputKlibPath)

        val projectEnvironment = input.projectEnvironment

        val klibFiles = configuration.klibPaths + klib.absolutePathString()
        val projectContext = ProjectContext(projectEnvironment.project, "TopDownAnalyzer for JKlib")
        val storageManager = projectContext.storageManager
        val builtIns = JvmBuiltIns(projectContext.storageManager, JvmBuiltIns.Kind.FROM_DEPENDENCIES)
        val sortedDependencies = loadLibraries(klibFiles, configuration)

        val dependencyDescriptorsByKlib = createKlibDescriptors(sortedDependencies, configuration, storageManager, builtIns)

        val jarDepsModuleDescriptor = createJarDependenciesModuleDescriptor(
            projectEnvironment,
            projectContext,
            configuration,
            builtIns,
        )

        val allDescriptors = dependencyDescriptorsByKlib.values + jarDepsModuleDescriptor
        allDescriptors.forEach { it.setDependencies(allDescriptors) }

        val mainModule = dependencyDescriptorsByKlib.getValue(sortedDependencies.single { it.path == klib })

        val trace = BindingTraceContext(projectContext.project)
        val mangler = JKlibDescriptorMangler(
            MainFunctionDetector(trace.bindingContext, configuration.languageVersionSettings)
        )
        val symbolTable = SymbolTable(IdSignatureDescriptor(mangler), IrFactoryImpl)


        val linker = JKlibIrLinker(
            module = mainModule,
            configuration = configuration,
            symbolTable = symbolTable,
            descriptorMangler = mangler,
            typeSystemContextFactory = ::JvmIrTypeSystemContext,
            externalOverridabilityConditions = listOf(
                IrJavaIncompatibilityRulesOverridabilityCondition(),
                JKlibFieldShadowingOverridabilityCondition,
            ),
        )

        // Only the module being compiled is deserialized whole: the VM backend generates nothing of its dependencies, which
        // the VM links as binary IR, so their declarations are read as the module references them.
        lateinit var mainModuleFragment: IrModuleFragment
        for ([dep, descriptor] in dependencyDescriptorsByKlib) {
            val strategy = if (descriptor == mainModule) {
                DeserializationStrategy.ALL
            } else {
                DeserializationStrategy.WITH_INLINE_BODIES
            }
            val moduleFragment = linker.deserializeIrModuleHeader(descriptor, dep, { strategy })
            if (descriptor == mainModule) {
                mainModuleFragment = moduleFragment
            }
        }

        linker.createAndRegisterModuleDeserializer(
            jarDepsModuleDescriptor,
            null,
            { DeserializationStrategy.ALL },
        )

        @OptIn(InternalSymbolFinderAPI::class) 
        val irBuiltIns = IrBuiltInsForLinker(
            linker, 
            configuration.languageVersionSettings
        )

        val stubGenerator = DeclarationStubGeneratorImpl(
            mainModule,
            symbolTable,
            irBuiltIns,
            DescriptorByIdSignatureFinderImpl(mainModule, mangler),
            JvmGeneratorExtensionsImpl(configuration),
        )
        linker.stubGenerator = stubGenerator

        // A JDK stub names the Kotlin classes its Java types map to, and with stubs made on reference, one the stdlib has not
        // been read for yet would get a stub in place of its declaration. The stdlib is read on demand, so those are read first.
        @OptIn(InternalSymbolFinderAPI::class)
        for (classId in javaMappedClasses(builtIns.builtInsModule)) {
            irBuiltIns.symbolFinder.findClass(classId)?.takeUnless { it.isBound }?.let(linker::getDeclaration)
        }
        stubGenerator.unboundSymbolGeneration = true

        val pluginContext = IrPluginContextImpl(
            mainModule,
            configuration.languageVersionSettings,
            symbolTable,
            irBuiltIns,
            linker = linker,
        )

        ExternalDependenciesGenerator(symbolTable, listOf(linker)).generateUnboundSymbolsAsDependencies()
        linker.postProcess(irBuiltIns, inOrAfterLinkageStep = true)

        // TODO(KT-86160): remove this when we have a proper solution for this issue.
        symbolTable
            .referenceClass(IdSignature.CommonSignature("kotlin", "Int", null, 0, null))
            .owner
            .declarations

        linker.checkNoUnboundSymbols(symbolTable, "Found unbound symbol")

        return VmIrCompilationArtifact(
            pluginContext,
            mainModuleFragment,
            configuration,
            input.hostActuals,
        )
    }

    private fun createJarDependenciesModuleDescriptor(
        projectEnvironment: VfsBasedProjectEnvironment,
        projectContext: ProjectContext,
        configuration: CompilerConfiguration,
        builtIns: JvmBuiltIns,
    ): ModuleDescriptorImpl {
        val languageVersionSettings = configuration.languageVersionSettings
        val platform = JvmPlatforms.defaultJvmPlatform
        val dependenciesContext = ContextForNewModule(
            projectContext,
            Name.special("<dependencies of ${configuration.getNotNull(MODULE_NAME)}>"),
            builtIns,
            platform,
        )

        // Scope for the dependency module contains everything except files present in the scope for the
        // source module
        val scope = AllJavaSourcesInProjectScope(projectContext.project)
        val dependencyScope = GlobalSearchScope.notScope(scope)


        val moduleClassResolver = SourceOrBinaryModuleClassResolver(scope)
        val lookupTracker = LookupTracker.DO_NOTHING
        val expectActualTracker = ExpectActualTracker.DoNothing
        val inlineConstTracker = InlineConstTracker.DoNothing
        val enumWhenTracker = EnumWhenTracker.DoNothing

        val configureJavaClassFinder = null
        val implicitsResolutionFilter = null
        val packagePartProvider = projectEnvironment.getPackagePartProvider(dependencyScope.toAbstractProjectFileSearchScope())
        val trace = NoScopeRecordCliBindingTrace(projectContext.project)
        val dependenciesContainer = createContainerForLazyResolveWithJava(
            platform,
            dependenciesContext,
            trace,
            DeclarationProviderFactory.EMPTY,
            dependencyScope,
            moduleClassResolver,
            CompilerEnvironment,
            lookupTracker,
            expectActualTracker,
            inlineConstTracker,
            enumWhenTracker,
            packagePartProvider,
            languageVersionSettings,
            useBuiltInsProvider = true,
            configureJavaClassFinder = configureJavaClassFinder,
            implicitsResolutionFilter = implicitsResolutionFilter,
        )
        moduleClassResolver.compiledCodeResolver = dependenciesContainer.get()

        dependenciesContext.initializeModuleContents(
            CompositePackageFragmentProvider(
                listOf(
                    moduleClassResolver.compiledCodeResolver.packageFragmentProvider,
                    dependenciesContainer.get<JvmBuiltInsPackageFragmentProvider>(),
                    dependenciesContainer.get<OptionalAnnotationPackageFragmentProvider>(),
                ),
                "CompositeProvider@TopDownAnalyzerForJvm for dependencies ${dependenciesContext.module}",
            )
        )
        return dependenciesContext.module
    }

    private fun createKlibDescriptors(
        sortedDependencies: List<KotlinLibrary>,
        configuration: CompilerConfiguration,
        storageManager: StorageManager,
        builtIns: JvmBuiltIns,
    ): Map<KotlinLibrary, ModuleDescriptorImpl> {
        val klibFactories = KlibMetadataFactories(
            { builtIns },
            JavaFlexibleTypeDeserializer,
            // We need to wire the JvmBuiltInsCustomizer instance to the KlibMetadataFactories. This allows resolving APIs that are not part
            // of the original builtins classes but are present in the mapped Java classes.
            // We cannot directly pass builtIns.customizer because it's a lazy property. It can only be accessed after builtIns has been
            // fully initialized. By the time the deserializer queries this provider, `builtIns` will have been fully initialized.
            additionalClassPartsProvider = object : AdditionalClassPartsProvider {
                private val delegate by lazy { builtIns.customizer }

                override fun getSupertypes(classDescriptor: ClassDescriptor): Collection<KotlinType> =
                    delegate.getSupertypes(classDescriptor)

                override fun getFunctions(name: Name, classDescriptor: ClassDescriptor): Collection<SimpleFunctionDescriptor> =
                    delegate.getFunctions(name, classDescriptor)

                override fun getConstructors(classDescriptor: ClassDescriptor): Collection<ClassConstructorDescriptor> =
                    delegate.getConstructors(classDescriptor)

                override fun getFunctionsNames(classDescriptor: ClassDescriptor): Collection<Name> =
                    delegate.getFunctionsNames(classDescriptor)
            },
            // ClassDescriptorFactories for intrinsic builtin classes.
            fictitiousClassDescriptorFactories = listOf(
                // TODO(KT-86369): Investigate if we need to wire up BuiltInFictitiousFunctionClassFactory in the list. J2CL is currently
                //  passing definitions for these classes and we could remove those and use BuiltInFictitiousFunctionClassFactory instead.
                object : ClassDescriptorFactory {
                    private val delegate by lazy {
                        JvmBuiltInClassDescriptorFactory(storageManager, builtIns.builtInsModule)
                    }

                    override fun shouldCreateClass(packageFqName: FqName, name: Name): Boolean =
                        delegate.shouldCreateClass(packageFqName, name)

                    override fun createClass(classId: ClassId): ClassDescriptor? =
                        delegate.createClass(classId)

                    override fun getAllContributedClassesIfPossible(packageFqName: FqName): Collection<ClassDescriptor> =
                        delegate.getAllContributedClassesIfPossible(packageFqName)
                }
            )
        )

        val dependencyDescriptorsByKlib = sortedDependencies.associateWith { klib ->
            val descriptor = klibFactories.DefaultDeserializedDescriptorFactory.createDescriptorOptionalBuiltIns(
                klib,
                configuration.languageVersionSettings,
                storageManager,
                if (klib.isJklibStdlib) null else builtIns,
                lookupTracker = LookupTracker.DO_NOTHING,
            )

            if (klib.isJklibStdlib) {
                builtIns.initialize(descriptor, true)
            }

            descriptor
        }

        return dependencyDescriptorsByKlib
    }

    /** The classes of the `kotlin` and `kotlin.collections` packages that a JDK class maps to, nested ones included. */
    private fun javaMappedClasses(builtInsModule: ModuleDescriptor): List<ClassId> {
        val classes = mutableListOf<ClassId>()
        fun visit(scope: MemberScope) {
            for (descriptor in scope.getContributedDescriptors(DescriptorKindFilter.CLASSIFIERS)) {
                if (descriptor !is ClassDescriptor) continue
                val classId = descriptor.classId ?: continue
                if (JavaToKotlinClassMap.mapKotlinToJava(classId.asSingleFqName().toUnsafe()) != null) classes += classId
                visit(descriptor.unsubstitutedInnerClassesScope)
            }
        }
        for (pkg in listOf(StandardNames.BUILT_INS_PACKAGE_FQ_NAME, StandardNames.COLLECTIONS_PACKAGE_FQ_NAME)) {
            visit(builtInsModule.getPackage(pkg).memberScope)
        }
        return classes
    }

    private fun loadLibraries(klibFiles: List<String>, configuration: CompilerConfiguration): List<KotlinLibrary> {
        val loadingResult =
            KlibLoader {
                libraryPaths(klibFiles)
                configuration.zipFileSystemAccessor?.let { zipFileSystemAccessor(it) }
            }.load()
        loadingResult.reportLoadingProblemsIfAny(configuration, allAsErrors = true)
        return loadingResult.librariesStdlibFirst
    }
}

private class SourceOrBinaryModuleClassResolver(private val sourceScope: GlobalSearchScope) : ModuleClassResolver {
    lateinit var compiledCodeResolver: JavaDescriptorResolver
    lateinit var sourceCodeResolver: JavaDescriptorResolver

    override fun resolveClass(javaClass: JavaClass): ClassDescriptor? {
        val resolver = if (javaClass is VirtualFileBoundJavaClass && javaClass.isFromSourceCodeInScope(sourceScope)) sourceCodeResolver
        else compiledCodeResolver
        return resolver.resolveClass(javaClass)
    }
}

