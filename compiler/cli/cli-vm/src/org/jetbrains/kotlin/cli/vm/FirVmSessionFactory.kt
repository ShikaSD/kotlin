/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli.vm

import org.jetbrains.kotlin.cli.common.SessionConstructionUtils
import org.jetbrains.kotlin.cli.common.SessionWithSources
import org.jetbrains.kotlin.cli.jvm.compiler.VfsBasedProjectEnvironment
import org.jetbrains.kotlin.config.*
import org.jetbrains.kotlin.fir.*
import org.jetbrains.kotlin.fir.checkers.registerJvmCheckers
import org.jetbrains.kotlin.fir.deserialization.ModuleDataProvider
import org.jetbrains.kotlin.fir.deserialization.SingleModuleDataProvider
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.java.FirJvmTargetProvider
import org.jetbrains.kotlin.fir.java.JavaSymbolProvider
import org.jetbrains.kotlin.fir.java.deserialization.FirJvmBuiltinsSymbolProvider
import org.jetbrains.kotlin.fir.java.deserialization.FirJvmClasspathBuiltinSymbolProvider
import org.jetbrains.kotlin.fir.java.deserialization.JvmClassFileBasedSymbolProvider
import org.jetbrains.kotlin.fir.java.deserialization.OptionalAnnotationClassesProvider
import org.jetbrains.kotlin.fir.resolve.providers.FirSymbolProvider
import org.jetbrains.kotlin.fir.resolve.providers.impl.FirCloneableSymbolProvider
import org.jetbrains.kotlin.fir.resolve.providers.impl.FirFallbackBuiltinSymbolProvider
import org.jetbrains.kotlin.fir.scopes.FirKotlinScopeProvider
import org.jetbrains.kotlin.fir.session.*
import org.jetbrains.kotlin.fir.session.environment.AbstractProjectEnvironment
import org.jetbrains.kotlin.fir.session.environment.AbstractProjectFileSearchScope
import org.jetbrains.kotlin.library.KotlinLibrary
import org.jetbrains.kotlin.load.kotlin.PackagePartProvider
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.utils.addToStdlib.runIf
import org.jetbrains.kotlin.utils.addToStdlib.shouldNotBeCalled

/** Sessions whose built-in collection classes have their common members, without the ones the JVM maps from the JDK. */
@OptIn(SessionConfiguration::class)
object FirVmSessionFactory : FirAbstractSessionFactory<FirVmSessionFactory.Context>() {
    override fun createPlatformSpecificSharedProviders(
        session: FirSession,
        moduleData: FirModuleData,
        scopeProvider: FirKotlinScopeProvider,
        context: Context,
    ): List<FirSymbolProvider> {
        val kotlinClassFinder =
            context.projectEnvironment.getKotlinClassFinder(context.projectEnvironment.getSearchScopeForProjectLibraries())
        return listOf(
            FirCloneableSymbolProvider(session, moduleData, scopeProvider),
            OptionalAnnotationClassesProvider(
                session,
                SingleModuleDataProvider(moduleData),
                scopeProvider,
                context.packagePartProvider
            ),
            FirJvmBuiltinsSymbolProvider(session, FirFallbackBuiltinSymbolProvider(session, moduleData, scopeProvider)) {
                kotlinClassFinder.findBuiltInsData(it)
            }
        )
    }

    // ==================================== Shared library session ====================================

    /**
     * See documentation to [FirAbstractSessionFactory.createSharedLibrarySession]
     */
    fun createSharedLibrarySession(
        mainModuleName: Name,
        projectEnvironment: AbstractProjectEnvironment,
        extensionRegistrars: List<FirExtensionRegistrar>,
        packagePartProvider: PackagePartProvider,
        languageVersionSettings: LanguageVersionSettings,
        predefinedJavaComponents: FirSharableJavaComponents?,
        jvmTarget: JvmTarget?,
    ): FirSession {
        val context = Context(predefinedJavaComponents, projectEnvironment, packagePartProvider, jvmTarget)
        return createSharedLibrarySession(
            mainModuleName,
            context,
            languageVersionSettings,
            extensionRegistrars
        )
    }

    // ==================================== Library session ====================================

    /**
     * See documentation to [FirAbstractSessionFactory.createLibrarySession]
     */
    fun createLibrarySession(
        resolvedLibraries: List<KotlinLibrary>,
        sharedLibrarySession: FirSession,
        moduleDataProvider: ModuleDataProvider,
        projectEnvironment: AbstractProjectEnvironment,
        extensionRegistrars: List<FirExtensionRegistrar>,
        scope: AbstractProjectFileSearchScope,
        packagePartProvider: PackagePartProvider,
        languageVersionSettings: LanguageVersionSettings,
        predefinedJavaComponents: FirSharableJavaComponents?,
        jvmTarget: JvmTarget?,
    ): FirSession {
        val kotlinClassFinder = projectEnvironment.getKotlinClassFinder(scope)
        val context = Context(predefinedJavaComponents, projectEnvironment, packagePartProvider, jvmTarget)
        return createLibrarySession(
            context,
            sharedLibrarySession,
            moduleDataProvider,
            languageVersionSettings,
            extensionRegistrars,
            createSeparateSharedProvidersInHmppCompilation = false,
            createProviders = { session, kotlinScopeProvider ->
                listOf(
                    KlibBasedSymbolProvider(
                        session, moduleDataProvider, kotlinScopeProvider, resolvedLibraries,
                    ),
                    JvmClassFileBasedSymbolProvider(
                        session,
                        moduleDataProvider,
                        kotlinScopeProvider,
                        packagePartProvider,
                        kotlinClassFinder,
                        projectEnvironment.getFirJavaFacade(session, moduleDataProvider.allModuleData.last(), scope)
                    ),
                )
            }
        )
    }

    override fun createKotlinScopeProviderForLibrarySession(): FirKotlinScopeProvider {
        return vmScopeProvider()
    }

    override fun FirSession.registerLibrarySessionComponents(c: Context) {
        registerJavaComponents(c.projectEnvironment.getJavaModuleResolver(), c.predefinedJavaComponents)
    }

    // ==================================== Platform session ====================================

    /**
     * See documentation to [FirAbstractSessionFactory.createSourceSession]
     */
    fun createSourceSession(
        moduleData: FirModuleData,
        javaSourcesScope: AbstractProjectFileSearchScope,
        projectEnvironment: AbstractProjectEnvironment,
        createIncrementalCompilationSymbolProviders: (FirSession) -> FirJvmIncrementalCompilationSymbolProviders?,
        extensionRegistrars: List<FirExtensionRegistrar>,
        configuration: CompilerConfiguration,
        predefinedJavaComponents: FirSharableJavaComponents?,
        needRegisterJavaElementFinder: Boolean,
        packagePartProvider: PackagePartProvider,
        kmpModuleKind: KmpModuleKind,
        init: FirSessionConfigurator.() -> Unit,
    ): FirSession {
        val context = Context(
            predefinedJavaComponents,
            projectEnvironment,
            packagePartProvider,
            jvmTarget = configuration.jvmTarget,
        )
        return createSourceSession(
            moduleData,
            context = context,
            extensionRegistrars,
            configuration,
            kmpModuleKind = kmpModuleKind,
            init,
            createProviders = { session, kotlinScopeProvider, symbolProvider, generatedSymbolsProvider ->
                val javaSymbolProvider =
                    JavaSymbolProvider(session, projectEnvironment.getFirJavaFacade(session, moduleData, javaSourcesScope))
                session.register(JavaSymbolProvider::class, javaSymbolProvider)

                val providers = listOfNotNull(
                    symbolProvider,
                    generatedSymbolsProvider,
                    javaSymbolProvider,
                    initializeForStdlibIfNeeded(projectEnvironment, session, kotlinScopeProvider),
                )

                val incrementalCompilationSymbolProviders = createIncrementalCompilationSymbolProviders(session)
                SourceProviders(
                    providers,
                    incrementalProvider = incrementalCompilationSymbolProviders?.symbolProviderForBinariesFromIncrementalCompilation,
                    additionalOptionalAnnotationsProvider = incrementalCompilationSymbolProviders?.optionalAnnotationClassesProviderForBinariesFromIncrementalCompilation
                )
            }
        ).also {
            if (needRegisterJavaElementFinder) {
                projectEnvironment.registerAsJavaElementFinder(it)
            }
        }
    }

    override fun createKotlinScopeProviderForSourceSession(
        moduleData: FirModuleData,
        languageVersionSettings: LanguageVersionSettings,
    ): FirKotlinScopeProvider {
        return vmScopeProvider()
    }

    override fun FirSessionConfigurator.registerPlatformCheckers() {
        registerJvmCheckers()
    }

    override fun FirSessionConfigurator.registerExtraPlatformCheckers() {
    }

    override fun FirSession.registerSourceSessionComponents(c: Context) {
        registerLibrarySessionComponents(c)
        register(FirJvmTargetProvider::class, FirJvmTargetProvider(c.jvmTarget ?: JvmTarget.DEFAULT))
    }

    override val requiresSpecialSetupOfSourceProvidersInHmppCompilation: Boolean
        get() = false
    override val isFactoryForMetadataCompilation: Boolean
        get() = false

    // ==================================== Common parts ====================================

    // ==================================== Utilities ====================================

    class Context(
        val predefinedJavaComponents: FirSharableJavaComponents?,
        val projectEnvironment: AbstractProjectEnvironment,
        val packagePartProvider: PackagePartProvider,
        val jvmTarget: JvmTarget?,
    )

    private fun initializeForStdlibIfNeeded(
        projectEnvironment: AbstractProjectEnvironment,
        session: FirSession,
        kotlinScopeProvider: FirKotlinScopeProvider,
    ): FirSymbolProvider? {
        return runIf(
            session.languageVersionSettings.getFlag(AnalysisFlags.stdlibCompilation) &&
                    !session.moduleData.isCommon
                    && session.moduleData.dependsOnDependencies.isEmpty()
        ) {
            val kotlinClassFinder = projectEnvironment.getKotlinClassFinder(projectEnvironment.getSearchScopeForProjectLibraries())
            FirJvmClasspathBuiltinSymbolProvider(
                session,
                session.moduleData,
                kotlinScopeProvider
            ) { kotlinClassFinder.findBuiltInsData(it) }
        }
    }
}

/**
 * Creates library session and sources session for klib compilation Number of created session
 * depends on mode of MPP:
 * - disabled
 * - legacy (one platform and one common module)
 * - HMPP (multiple number of modules)
 */
internal fun <F> prepareVmSessions(
    projectEnvironment: VfsBasedProjectEnvironment,
    files: List<F>,
    configuration: CompilerConfiguration,
    rootModuleName: Name,
    resolvedLibraries: List<KotlinLibrary>,
    libraryList: DependencyListForCliModule,
    extensionRegistrars: List<FirExtensionRegistrar>,
    metadataCompilationMode: Boolean,
    librariesScope: AbstractProjectFileSearchScope,
    isCommonSource: (F) -> Boolean,
    fileBelongsToModule: (F, String) -> Boolean,
): List<SessionWithSources<F>> {
    val predefinedJavaComponents = FirSharableJavaComponents(firCachesFactoryForCliMode)
    val packagePartProviderForLibraries = projectEnvironment.getPackagePartProvider(librariesScope)
    val sessionFactory = FirVmSessionFactory

    return SessionConstructionUtils.prepareSessions(
        files,
        configuration,
        rootModuleName,
        JvmPlatforms.unspecifiedJvmPlatform,
        metadataCompilationMode,
        libraryList,
        extensionRegistrars,
        isCommonSource,
        isScript = { false },
        fileBelongsToModule,
        createSharedLibrarySession = {
            sessionFactory.createSharedLibrarySession(
                rootModuleName,
                projectEnvironment,
                extensionRegistrars,
                packagePartProviderForLibraries,
                configuration.languageVersionSettings,
                predefinedJavaComponents = predefinedJavaComponents,
                jvmTarget = configuration.jvmTarget,
            )
        },
        createLibrarySession = { sharedLibrarySession ->
            sessionFactory.createLibrarySession(
                resolvedLibraries,
                sharedLibrarySession,
                libraryList.moduleDataProvider,
                projectEnvironment,
                extensionRegistrars,
                librariesScope,
                packagePartProviderForLibraries,
                configuration.languageVersionSettings,
                predefinedJavaComponents = predefinedJavaComponents,
                jvmTarget = configuration.jvmTarget,
            )
        },
        createSourceSession = { moduleData, kmpModuleKind, sessionConfigurator ->
            sessionFactory.createSourceSession(
                moduleData = moduleData,
                javaSourcesScope = projectEnvironment.getSearchScopeForProjectJavaSources(),
                projectEnvironment = projectEnvironment,
                createIncrementalCompilationSymbolProviders = { null },
                extensionRegistrars = extensionRegistrars,
                configuration = configuration,
                predefinedJavaComponents = predefinedJavaComponents,
                needRegisterJavaElementFinder = true,
                packagePartProvider = packagePartProviderForLibraries,
                init = sessionConfigurator,
                kmpModuleKind = kmpModuleKind,
            )
        },
        createMetadataSessionFactoryContextForHmppCommonLibrarySession = {
            AbstractFirMetadataSessionFactory.Context(
                createJvmContext = { shouldNotBeCalled() },
                createJsContext = { shouldNotBeCalled() }
            )
        }
    )
}

/**
 * Keeps the members of `List` and `Map` marked `@PlatformDependent`, which the default scope hides from a platform without
 * them: the VM's standard library implements them, so a class inheriting one answers its selector.
 */
private fun vmScopeProvider(): FirKotlinScopeProvider = FirKotlinScopeProvider { _, declaredMemberScope, _, _, _ -> declaredMemberScope }
