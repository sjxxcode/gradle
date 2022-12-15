/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.configurationcache

import org.gradle.api.artifacts.component.BuildIdentifier
import org.gradle.api.cache.Cleanup
import org.gradle.api.file.FileCollection
import org.gradle.api.internal.BuildDefinition
import org.gradle.api.internal.FeaturePreviews
import org.gradle.api.internal.GradleInternal
import org.gradle.api.internal.SettingsInternal.BUILD_SRC
import org.gradle.api.provider.Provider
import org.gradle.api.services.internal.BuildServiceProvider
import org.gradle.api.services.internal.BuildServiceRegistryInternal
import org.gradle.api.services.internal.RegisteredBuildServiceProvider
import org.gradle.caching.configuration.BuildCache
import org.gradle.caching.configuration.internal.BuildCacheServiceRegistration
import org.gradle.configurationcache.extensions.serviceOf
import org.gradle.configurationcache.extensions.unsafeLazy
import org.gradle.configurationcache.problems.DocumentationSection.NotYetImplementedSourceDependencies
import org.gradle.configurationcache.serialization.DefaultReadContext
import org.gradle.configurationcache.serialization.DefaultWriteContext
import org.gradle.configurationcache.serialization.ReadContext
import org.gradle.configurationcache.serialization.WriteContext
import org.gradle.configurationcache.serialization.codecs.Codecs
import org.gradle.configurationcache.serialization.logNotImplemented
import org.gradle.configurationcache.serialization.readCollection
import org.gradle.configurationcache.serialization.readEnum
import org.gradle.configurationcache.serialization.readFile
import org.gradle.configurationcache.serialization.readList
import org.gradle.configurationcache.serialization.readNonNull
import org.gradle.configurationcache.serialization.readStrings
import org.gradle.configurationcache.serialization.withDebugFrame
import org.gradle.configurationcache.serialization.withGradleIsolate
import org.gradle.configurationcache.serialization.writeCollection
import org.gradle.configurationcache.serialization.writeEnum
import org.gradle.configurationcache.serialization.writeFile
import org.gradle.configurationcache.serialization.writeStrings
import org.gradle.configurationcache.services.EnvironmentChangeTracker
import org.gradle.execution.plan.Node
import org.gradle.initialization.BuildIdentifiedProgressDetails
import org.gradle.initialization.BuildStructureOperationProject
import org.gradle.initialization.ProjectsIdentifiedProgressDetails
import org.gradle.initialization.RootBuildCacheControllerSettingsProcessor
import org.gradle.internal.Actions
import org.gradle.internal.build.BuildProjectRegistry
import org.gradle.internal.build.BuildState
import org.gradle.internal.build.BuildStateRegistry
import org.gradle.internal.build.IncludedBuildState
import org.gradle.internal.build.NestedBuildState
import org.gradle.internal.build.PublicBuildPath
import org.gradle.internal.build.RootBuildState
import org.gradle.internal.build.StandAloneNestedBuild
import org.gradle.internal.build.event.BuildEventListenerRegistryInternal
import org.gradle.internal.buildoption.FeatureFlags
import org.gradle.internal.buildtree.BuildTreeWorkGraph
import org.gradle.internal.enterprise.core.GradleEnterprisePluginAdapter
import org.gradle.internal.enterprise.core.GradleEnterprisePluginManager
import org.gradle.internal.execution.BuildOutputCleanupRegistry
import org.gradle.internal.operations.BuildOperationProgressEventEmitter
import org.gradle.plugin.management.internal.PluginRequests
import org.gradle.util.Path
import org.gradle.vcs.internal.VcsMappingsStore
import java.io.File
import java.io.InputStream
import java.io.OutputStream


internal
enum class StateType {
    Work, Model, Entry, BuildFingerprint, ProjectFingerprint, IntermediateModels, ProjectMetadata
}


internal
interface ConfigurationCacheStateFile {
    val exists: Boolean
    fun outputStream(): OutputStream
    fun inputStream(): InputStream
    fun delete()

    // Replace the contents of this state file, by moving the given file to the location of this state file
    fun moveFrom(file: File)
    fun stateFileForIncludedBuild(build: BuildDefinition): ConfigurationCacheStateFile
}


internal
class ConfigurationCacheState(
    private val codecs: Codecs,
    private val stateFile: ConfigurationCacheStateFile,
    private val eventEmitter: BuildOperationProgressEventEmitter,
    private val host: DefaultConfigurationCache.Host
) {
    /**
     * Writes the state for the whole build starting from the given root [build] and returns the set
     * of stored included build directories.
     */
    suspend fun DefaultWriteContext.writeRootBuildState(build: VintageGradleBuild) =
        writeRootBuild(build).also {
            writeInt(0x1ecac8e)
        }

    suspend fun DefaultReadContext.readRootBuildState(graph: BuildTreeWorkGraph, loadAfterStore: Boolean): BuildTreeWorkGraph.FinalizedGraph {
        val builds = readRootBuild()
        require(readInt() == 0x1ecac8e) {
            "corrupt state file"
        }
        if (!loadAfterStore) {
            for (build in builds) {
                identifyBuild(build)
            }
        }
        return calculateRootTaskGraph(builds, graph)
    }

    private
    fun identifyBuild(state: CachedBuildState) {
        val identityPath = state.identityPath.toString()

        eventEmitter.emitNowForCurrent(BuildIdentifiedProgressDetails { identityPath })

        if (state is BuildWithProjects) {
            val projects = convertProjects(state.projects, state.rootProjectName)
            eventEmitter.emitNowForCurrent(object : ProjectsIdentifiedProgressDetails {
                override fun getBuildPath() = identityPath
                override fun getRootProject() = projects
            })
        }
    }

    private
    fun convertProjects(projects: List<CachedProjectState>, rootProjectName: String): ProjectsIdentifiedProgressDetails.Project {
        val children = projects.groupBy { it.path.parent }
        val converted = mutableMapOf<Path, BuildStructureOperationProject>()
        for (project in projects) {
            convertProject(converted, project, rootProjectName, children)
        }
        return converted.getValue(Path.ROOT)
    }

    private
    fun convertProject(
        converted: MutableMap<Path, BuildStructureOperationProject>,
        project: CachedProjectState,
        rootProjectName: String,
        children: Map<Path?, List<CachedProjectState>>
    ): BuildStructureOperationProject {
        val childProjects = children.getOrDefault(project.path, emptyList()).map { convertProject(converted, it, rootProjectName, children) }.toSet()
        return converted.computeIfAbsent(project.path) {
            // Root project name is serialized separately, could perhaps move it to this cached project state object
            val projectName = project.path.name ?: rootProjectName
            BuildStructureOperationProject(projectName, project.path.path, project.path.path, project.projectDir.absolutePath, project.buildFile.absolutePath, childProjects)
        }
    }

    private
    fun calculateRootTaskGraph(builds: List<CachedBuildState>, graph: BuildTreeWorkGraph): BuildTreeWorkGraph.FinalizedGraph {
        return graph.scheduleWork { builder ->
            for (build in builds) {
                if (build is BuildWithWork) {
                    builder.withWorkGraph(build.build.state) {
                        it.setScheduledNodes(build.workGraph)
                    }
                }
            }
        }
    }

    private
    suspend fun DefaultWriteContext.writeRootBuild(rootBuild: VintageGradleBuild) {
        require(rootBuild.gradle.owner is RootBuildState)
        val gradle = rootBuild.gradle
        withDebugFrame({ "Gradle" }) {
            write(gradle.settings.settingsScript.resource.file)
            writeBuildTreeScopedState(gradle)
        }
        val buildEventListeners = buildEventListenersOf(gradle)
        writeBuildsInTree(rootBuild, buildEventListeners)
        writeRootEventListenerSubscriptions(gradle, buildEventListeners)
    }

    private
    suspend fun DefaultReadContext.readRootBuild(): List<CachedBuildState> {
        val settingsFile = read() as File?
        val rootBuild = host.createBuild(settingsFile)
        val gradle = rootBuild.gradle
        readBuildTreeState(gradle)
        val builds = readBuildsInTree(rootBuild)
        readRootEventListenerSubscriptions(gradle)
        return builds
    }

    private
    suspend fun DefaultWriteContext.writeBuildsInTree(rootBuild: VintageGradleBuild, buildEventListeners: List<RegisteredBuildServiceProvider<*, *>>) {
        val requiredBuildServicesPerBuild = buildEventListeners.groupBy { it.buildIdentifier }
        val builds = mutableMapOf<BuildState, BuildToStore>()
        host.visitBuilds { build ->
            val state = build.state
            builds[state] = BuildToStore(build, build.hasScheduledWork, build.isRootBuild)
            if (build.hasScheduledWork && state is StandAloneNestedBuild) {
                // Also require the owner of a buildSrc build
                builds[state.owner] = builds.getValue(state.owner).hasChildren()
            }
        }
        writeCollection(builds.values) { build ->
            writeBuildState(
                build,
                StoredBuildTreeState(
                    requiredBuildServicesPerBuild = requiredBuildServicesPerBuild
                ),
                rootBuild
            )
        }
    }

    private
    suspend fun DefaultReadContext.readBuildsInTree(rootBuild: ConfigurationCacheBuild): List<CachedBuildState> {
        return readList {
            readBuildState(rootBuild)
        }
    }

    private
    suspend fun DefaultWriteContext.writeBuildState(build: BuildToStore, buildTreeState: StoredBuildTreeState, rootBuild: VintageGradleBuild) {
        val state = build.build.state
        if (!build.hasWork && !build.hasChildren) {
            writeEnum(BuildType.BuildWithNoWork)
            writeBuildWithNoWork(state, rootBuild)
        } else if (state is RootBuildState) {
            writeEnum(BuildType.RootBuild)
            writeBuildContent(build.build, buildTreeState)
        } else if (state is IncludedBuildState) {
            writeEnum(BuildType.IncludedBuild)
            writeIncludedBuild(state, buildTreeState)
        } else if (state is StandAloneNestedBuild) {
            writeEnum(BuildType.BuildSrcBuild)
            writeBuildSrcBuild(state, buildTreeState)
        } else {
            throw IllegalArgumentException()
        }
    }

    private
    suspend fun DefaultReadContext.readBuildState(rootBuild: ConfigurationCacheBuild): CachedBuildState {
        val type = readEnum<BuildType>()
        return when (type) {
            BuildType.BuildWithNoWork -> readBuildWithNoWork(rootBuild)
            BuildType.RootBuild -> readBuildContent(rootBuild)
            BuildType.IncludedBuild -> readIncludedBuild(rootBuild)
            BuildType.BuildSrcBuild -> readBuildSrcBuild(rootBuild)
        }
    }

    private
    suspend fun DefaultWriteContext.writeIncludedBuild(state: IncludedBuildState, buildTreeState: StoredBuildTreeState) {
        val gradle = state.mutableModel
        withGradleIsolate(gradle, userTypesCodec) {
            write(gradle.settings.settingsScript.resource.file)
            writeBuildDefinition(state.buildDefinition)
        }
        // Encode the build state using the contextualized IO service for the nested build
        state.projects.withMutableStateOfAllProjects {
            gradle.serviceOf<ConfigurationCacheIO>().writeIncludedBuildStateTo(stateFileFor(state.buildDefinition), buildTreeState)
        }
    }

    private
    suspend fun DefaultReadContext.readIncludedBuild(rootBuild: ConfigurationCacheBuild): CachedBuildState {
        val build = withGradleIsolate(rootBuild.gradle, userTypesCodec) {
            val settingsFile = read() as File?
            val definition = readIncludedBuildDefinition(rootBuild)
            rootBuild.addIncludedBuild(definition, settingsFile)
        }
        // Decode the build state using the contextualized IO service for the build
        return build.gradle.serviceOf<ConfigurationCacheIO>().readIncludedBuildStateFrom(stateFileFor((build.state as NestedBuildState).buildDefinition), build)
    }

    private
    suspend fun DefaultWriteContext.writeBuildSrcBuild(state: StandAloneNestedBuild, buildTreeState: StoredBuildTreeState) {
        val gradle = state.mutableModel
        withGradleIsolate(gradle, userTypesCodec) {
            write(state.owner.buildIdentifier)
        }
        // Encode the build state using the contextualized IO service for the nested build
        state.projects.withMutableStateOfAllProjects {
            gradle.serviceOf<ConfigurationCacheIO>().writeIncludedBuildStateTo(stateFileFor(state.buildDefinition), buildTreeState)
        }
    }

    private
    suspend fun DefaultReadContext.readBuildSrcBuild(rootBuild: ConfigurationCacheBuild): CachedBuildState {
        val build = withGradleIsolate(rootBuild.gradle, userTypesCodec) {
            val ownerIdentifier = readNonNull<BuildIdentifier>()
            rootBuild.getBuildSrcOf(ownerIdentifier)
        }
        // Decode the build state using the contextualized IO service for the build
        return build.gradle.serviceOf<ConfigurationCacheIO>().readIncludedBuildStateFrom(stateFileFor((build.state as NestedBuildState).buildDefinition), build)
    }

    private
    suspend fun DefaultWriteContext.writeBuildWithNoWork(state: BuildState, rootBuild: VintageGradleBuild) {
        withGradleIsolate(rootBuild.gradle, userTypesCodec) {
            writeString(state.identityPath.path)
            if (state.projectsAvailable) {
                writeBoolean(true)
                writeString(state.projects.rootProject.name)
                writeCollection(state.projects.allProjects) { project ->
                    write(ProjectWithNoWork(project.projectPath, project.projectDir, project.mutableModel.buildFile))
                }
            } else {
                writeBoolean(false)
            }
        }
    }

    private
    suspend fun DefaultReadContext.readBuildWithNoWork(rootBuild: ConfigurationCacheBuild) =
        withGradleIsolate(rootBuild.gradle, userTypesCodec) {
            val identityPath = Path.path(readString())
            val hasProjects = readBoolean()
            if (hasProjects) {
                val rootProjectName = readString()
                val projects = readList() as List<ProjectWithNoWork>
                BuildWithNoWork(identityPath, rootProjectName, projects)
            } else {
                BuildWithNoProjects(identityPath)
            }
        }

    internal
    suspend fun DefaultWriteContext.writeBuildContent(build: VintageGradleBuild, buildTreeState: StoredBuildTreeState) {
        val gradle = build.gradle
        val state = build.state
        if (state.projectsAvailable) {
            writeBoolean(true)
            val scheduledNodes = build.scheduledWork
            withDebugFrame({ "Gradle" }) {
                writeGradleState(gradle)
                val projects = collectProjects(state.projects, scheduledNodes, gradle.serviceOf())
                writeProjects(gradle, projects)
                writeRequiredBuildServicesOf(gradle, buildTreeState)
            }
            withDebugFrame({ "Work Graph" }) {
                writeWorkGraphOf(gradle, scheduledNodes)
            }
            withDebugFrame({ "Cleanup registrations" }) {
                writeBuildOutputCleanupRegistrations(gradle)
            }
        } else {
            writeBoolean(false)
        }
    }

    internal
    suspend fun DefaultReadContext.readBuildContent(build: ConfigurationCacheBuild): CachedBuildState {
        val gradle = build.gradle
        if (readBoolean()) {
            readGradleState(build)
            val projects = readProjects(gradle, build)

            build.createProjects()

            initProjectProvider(build::getProject)

            applyProjectStates(projects, gradle)
            readRequiredBuildServicesOf(gradle)

            val workGraph = readWorkGraph(gradle)
            readBuildOutputCleanupRegistrations(gradle)
            return BuildWithWork(build.state.identityPath, build, gradle.rootProject.name, projects, workGraph)
        } else {
            return BuildWithNoProjects(build.state.identityPath)
        }
    }

    private
    suspend fun DefaultWriteContext.writeWorkGraphOf(gradle: GradleInternal, scheduledNodes: List<Node>) {
        workNodeCodec(gradle).run {
            writeWork(scheduledNodes)
        }
    }

    private
    suspend fun DefaultReadContext.readWorkGraph(gradle: GradleInternal) =
        workNodeCodec(gradle).run {
            readWork()
        }

    private
    fun workNodeCodec(gradle: GradleInternal) =
        codecs.workNodeCodecFor(gradle)

    private
    suspend fun DefaultWriteContext.writeRequiredBuildServicesOf(gradle: GradleInternal, buildTreeState: StoredBuildTreeState) {
        withGradleIsolate(gradle, userTypesCodec) {
            write(buildTreeState.requiredBuildServicesPerBuild[buildIdentifierOf(gradle)])
        }
    }

    private
    suspend fun DefaultReadContext.readRequiredBuildServicesOf(gradle: GradleInternal) {
        withGradleIsolate(gradle, userTypesCodec) {
            read()
        }
    }

    private
    fun applyProjectStates(projects: List<CachedProjectState>, gradle: GradleInternal) {
        for (project in projects) {
            if (project is ProjectWithWork && project.normalizationState != null) {
                val projectState = gradle.owner.projects.getProject(project.path)
                projectState.mutableModel.normalization.configureFromCachedState(project.normalizationState)
            }
        }
    }

    private
    suspend fun DefaultWriteContext.writeBuildTreeScopedState(gradle: GradleInternal) {
        withGradleIsolate(gradle, userTypesCodec) {
            withDebugFrame({ "environment state" }) {
                writeCachedEnvironmentState(gradle)
                writePreviewFlags(gradle)
            }
            withDebugFrame({ "gradle enterprise" }) {
                writeGradleEnterprisePluginManager(gradle)
            }
            withDebugFrame({ "build cache" }) {
                writeBuildCacheConfiguration(gradle)
            }
            withDebugFrame({ "cache configurations" }) {
                writeCacheConfigurations(gradle)
            }
        }
    }

    private
    suspend fun DefaultReadContext.readBuildTreeState(gradle: GradleInternal) {
        withGradleIsolate(gradle, userTypesCodec) {
            readCachedEnvironmentState(gradle)
            readPreviewFlags(gradle)
            // It is important that the Gradle Enterprise plugin be read before
            // build cache configuration, as it may contribute build cache configuration.
            readGradleEnterprisePluginManager(gradle)
            readBuildCacheConfiguration(gradle)
            readCacheConfigurations(gradle)
        }
    }

    private
    suspend fun DefaultWriteContext.writeRootEventListenerSubscriptions(gradle: GradleInternal, listeners: List<Provider<*>>) {
        withGradleIsolate(gradle, userTypesCodec) {
            withDebugFrame({ "listener subscriptions" }) {
                writeBuildEventListenerSubscriptions(listeners)
            }
        }
    }

    private
    suspend fun DefaultReadContext.readRootEventListenerSubscriptions(gradle: GradleInternal) {
        withGradleIsolate(gradle, userTypesCodec) {
            readBuildEventListenerSubscriptions(gradle)
        }
    }

    private
    fun DefaultWriteContext.writeGradleState(gradle: GradleInternal) {
        withGradleIsolate(gradle, userTypesCodec) {
            // per build
            writeStartParameterOf(gradle)
            writeChildBuilds(gradle)
        }
    }

    private
    fun DefaultReadContext.readGradleState(
        build: ConfigurationCacheBuild
    ) {
        val gradle = build.gradle
        return withGradleIsolate(gradle, userTypesCodec) {
            // per build
            readStartParameterOf(gradle)
            readChildBuilds()
        }
    }

    private
    fun DefaultWriteContext.writeStartParameterOf(gradle: GradleInternal) {
        val startParameterTaskNames = gradle.startParameter.taskNames
        writeStrings(startParameterTaskNames)
    }

    private
    fun DefaultReadContext.readStartParameterOf(gradle: GradleInternal) {
        // Restore startParameter.taskNames to enable `gradle.startParameter.setTaskNames(...)` idiom in included build scripts
        // See org/gradle/caching/configuration/internal/BuildCacheCompositeConfigurationIntegrationTest.groovy:134
        val startParameterTaskNames = readStrings()
        gradle.startParameter.setTaskNames(startParameterTaskNames)
    }

    private
    fun DefaultWriteContext.writeChildBuilds(gradle: GradleInternal) {
        if (gradle.serviceOf<VcsMappingsStore>().asResolver().hasRules()) {
            logNotImplemented(
                feature = "source dependencies",
                documentationSection = NotYetImplementedSourceDependencies
            )
            writeBoolean(true)
        } else {
            writeBoolean(false)
        }
    }

    private
    fun DefaultReadContext.readChildBuilds() {
        if (readBoolean()) {
            logNotImplemented(
                feature = "source dependencies",
                documentationSection = NotYetImplementedSourceDependencies
            )
        }
    }

    private
    suspend fun DefaultWriteContext.writeBuildDefinition(buildDefinition: BuildDefinition) {
        buildDefinition.run {
            writeString(name!!)
            writeFile(buildRootDir)
            write(fromBuild)
            writeBoolean(isPluginBuild)
        }
    }

    private
    suspend fun DefaultReadContext.readIncludedBuildDefinition(parentBuild: ConfigurationCacheBuild): BuildDefinition {
        val includedBuildName = readString()
        val includedBuildRootDir = readFile()
        val fromBuild = readNonNull<PublicBuildPath>()
        val pluginBuild = readBoolean()
        return BuildDefinition.fromStartParameterForBuild(
            parentBuild.gradle.startParameter,
            includedBuildName,
            includedBuildRootDir,
            PluginRequests.EMPTY,
            Actions.doNothing(),
            fromBuild,
            pluginBuild
        )
    }

    private
    suspend fun DefaultWriteContext.writeBuildCacheConfiguration(gradle: GradleInternal) {
        gradle.settings.buildCache.let { buildCache ->
            write(buildCache.local)
            write(buildCache.remote)
            write(buildCache.registrations)
        }
    }

    private
    suspend fun DefaultReadContext.readBuildCacheConfiguration(gradle: GradleInternal) {
        gradle.settings.buildCache.let { buildCache ->
            buildCache.local = readNonNull()
            buildCache.remote = read() as BuildCache?
            buildCache.registrations = readNonNull<MutableSet<BuildCacheServiceRegistration>>()
        }
        RootBuildCacheControllerSettingsProcessor.process(gradle)
    }

    private
    suspend fun DefaultWriteContext.writeCacheConfigurations(gradle: GradleInternal) {
        gradle.settings.caches.let { cacheConfigurations ->
            write(cacheConfigurations.releasedWrappers.removeUnusedEntriesOlderThan)
            write(cacheConfigurations.snapshotWrappers.removeUnusedEntriesOlderThan)
            write(cacheConfigurations.downloadedResources.removeUnusedEntriesOlderThan)
            write(cacheConfigurations.createdResources.removeUnusedEntriesOlderThan)
            write(cacheConfigurations.cleanup)
        }
    }

    private
    suspend fun DefaultReadContext.readCacheConfigurations(gradle: GradleInternal) {
        gradle.settings.caches.let { cacheConfigurations ->
            cacheConfigurations.releasedWrappers.removeUnusedEntriesOlderThan.value(readNonNull<Provider<Long>>())
            cacheConfigurations.snapshotWrappers.removeUnusedEntriesOlderThan.value(readNonNull<Provider<Long>>())
            cacheConfigurations.downloadedResources.removeUnusedEntriesOlderThan.value(readNonNull<Provider<Long>>())
            cacheConfigurations.createdResources.removeUnusedEntriesOlderThan.value(readNonNull<Provider<Long>>())
            cacheConfigurations.cleanup.value(readNonNull<Provider<Cleanup>>())
        }
    }

    private
    suspend fun DefaultWriteContext.writeBuildEventListenerSubscriptions(listeners: List<Provider<*>>) {
        writeCollection(listeners) { listener ->
            when (listener) {
                is RegisteredBuildServiceProvider<*, *> -> {
                    writeBoolean(true)
                    write(listener.buildIdentifier)
                    writeString(listener.name)
                }

                else -> {
                    writeBoolean(false)
                    write(listener)
                }
            }
        }
    }

    private
    suspend fun DefaultReadContext.readBuildEventListenerSubscriptions(gradle: GradleInternal) {
        val eventListenerRegistry by unsafeLazy {
            gradle.serviceOf<BuildEventListenerRegistryInternal>()
        }
        val buildStateRegistry by unsafeLazy {
            gradle.serviceOf<BuildStateRegistry>()
        }
        readCollection {
            when (readBoolean()) {
                true -> {
                    val buildIdentifier = readNonNull<BuildIdentifier>()
                    val serviceName = readString()
                    val provider = buildStateRegistry.buildServiceRegistrationOf(buildIdentifier).getByName(serviceName)
                    eventListenerRegistry.subscribe(provider.service)
                }

                else -> {
                    val provider = readNonNull<Provider<*>>()
                    eventListenerRegistry.subscribe(provider)
                }
            }
        }
    }

    private
    suspend fun DefaultWriteContext.writeBuildOutputCleanupRegistrations(gradle: GradleInternal) {
        val buildOutputCleanupRegistry = gradle.serviceOf<BuildOutputCleanupRegistry>()
        withGradleIsolate(gradle, userTypesCodec) {
            writeCollection(buildOutputCleanupRegistry.registeredOutputs)
        }
    }

    private
    suspend fun DefaultReadContext.readBuildOutputCleanupRegistrations(gradle: GradleInternal) {
        val buildOutputCleanupRegistry = gradle.serviceOf<BuildOutputCleanupRegistry>()
        withGradleIsolate(gradle, userTypesCodec) {
            readCollection {
                val files = readNonNull<FileCollection>()
                buildOutputCleanupRegistry.registerOutputs(files)
            }
        }
    }

    private
    suspend fun DefaultWriteContext.writeCachedEnvironmentState(gradle: GradleInternal) {
        val environmentChangeTracker = gradle.serviceOf<EnvironmentChangeTracker>()
        write(environmentChangeTracker.getCachedState())
    }

    private
    suspend fun DefaultReadContext.readCachedEnvironmentState(gradle: GradleInternal) {
        val environmentChangeTracker = gradle.serviceOf<EnvironmentChangeTracker>()
        val storedState = read() as EnvironmentChangeTracker.CachedEnvironmentState
        environmentChangeTracker.loadFrom(storedState)
    }

    private
    suspend fun DefaultWriteContext.writePreviewFlags(gradle: GradleInternal) {
        val featureFlags = gradle.serviceOf<FeatureFlags>()
        val enabledFeatures = FeaturePreviews.Feature.values().filter { featureFlags.isEnabledWithApi(it) }
        writeCollection(enabledFeatures)
    }

    private
    suspend fun DefaultReadContext.readPreviewFlags(gradle: GradleInternal) {
        val featureFlags = gradle.serviceOf<FeatureFlags>()
        readCollection {
            val enabledFeature = read() as FeaturePreviews.Feature
            featureFlags.enable(enabledFeature)
        }
    }

    private
    suspend fun DefaultWriteContext.writeGradleEnterprisePluginManager(gradle: GradleInternal) {
        val manager = gradle.serviceOf<GradleEnterprisePluginManager>()
        val adapter = manager.adapter
        val writtenAdapter = adapter?.takeIf {
            it.shouldSaveToConfigurationCache()
        }
        write(writtenAdapter)
    }

    private
    suspend fun DefaultReadContext.readGradleEnterprisePluginManager(gradle: GradleInternal) {
        val adapter = read() as GradleEnterprisePluginAdapter?
        if (adapter != null) {
            val manager = gradle.serviceOf<GradleEnterprisePluginManager>()
            if (manager.adapter == null) {
                // Don't replace the existing adapter. The adapter will be present if the current Gradle invocation wrote this entry.
                adapter.onLoadFromConfigurationCache()
                manager.registerAdapter(adapter)
            }
        }
    }

    private
    fun collectProjects(projects: BuildProjectRegistry, nodes: List<Node>, relevantProjectsRegistry: RelevantProjectsRegistry): List<CachedProjectState> {
        val relevantProjects = relevantProjectsRegistry.relevantProjects(nodes)
        return projects.allProjects.map { project ->
            val mutableModel = project.mutableModel
            if (relevantProjects.contains(project)) {
                mutableModel.layout.buildDirectory.finalizeValue()
                ProjectWithWork(project.projectPath, mutableModel.projectDir, mutableModel.buildFile, mutableModel.buildDir, mutableModel.normalization.computeCachedState())
            } else {
                ProjectWithNoWork(project.projectPath, mutableModel.projectDir, mutableModel.buildFile)
            }
        }
    }

    private
    suspend fun WriteContext.writeProjects(gradle: GradleInternal, projects: List<CachedProjectState>) {
        writeString(gradle.rootProject.name)
        withGradleIsolate(gradle, userTypesCodec) {
            writeCollection(projects)
        }
    }

    private
    suspend fun ReadContext.readProjects(gradle: GradleInternal, build: ConfigurationCacheBuild): List<CachedProjectState> {
        withGradleIsolate(gradle, userTypesCodec) {
            val rootProjectName = readString()
            return readList {
                val project = readNonNull<CachedProjectState>()
                if (project is ProjectWithWork) {
                    if (project.path == Path.ROOT) {
                        build.registerRootProject(rootProjectName, project.projectDir, project.buildDir)
                    } else {
                        build.registerProject(project.path, project.projectDir, project.buildDir)
                    }
                }
                project
            }
        }
    }

    private
    fun stateFileFor(buildDefinition: BuildDefinition) =
        stateFile.stateFileForIncludedBuild(buildDefinition)

    private
    val userTypesCodec
        get() = codecs.userTypesCodec()

    private
    fun buildIdentifierOf(gradle: GradleInternal) =
        gradle.owner.buildIdentifier

    private
    fun buildEventListenersOf(gradle: GradleInternal) =
        gradle.serviceOf<BuildEventListenerRegistryInternal>()
            .subscriptions
            .filterIsInstance<RegisteredBuildServiceProvider<*, *>>()
            .filter(::isRelevantBuildEventListener)

    private
    fun isRelevantBuildEventListener(provider: RegisteredBuildServiceProvider<*, *>) =
        provider.buildIdentifier.name != BUILD_SRC

    private
    fun BuildStateRegistry.buildServiceRegistrationOf(buildId: BuildIdentifier) =
        getBuild(buildId).mutableModel.serviceOf<BuildServiceRegistryInternal>().registrations

    private
    val BuildState.projectsAvailable
        get() = isProjectsLoaded && projects.rootProject.isCreated
}


internal
class StoredBuildTreeState(
    val requiredBuildServicesPerBuild: Map<BuildIdentifier, List<BuildServiceProvider<*, *>>>
)


internal
enum class BuildType {
    BuildWithNoWork, RootBuild, IncludedBuild, BuildSrcBuild
}
