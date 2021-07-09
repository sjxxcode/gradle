/*
 * Copyright 2021 the original author or authors.
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

package org.gradle.internal.build

import org.gradle.BuildListener
import org.gradle.api.internal.GradleInternal
import org.gradle.api.internal.SettingsInternal
import org.gradle.execution.BuildWorkExecutor
import org.gradle.initialization.BuildCompletionListener
import org.gradle.initialization.exception.ExceptionAnalyser
import org.gradle.initialization.internal.InternalBuildFinishedListener
import org.gradle.internal.service.scopes.BuildScopeServices
import org.gradle.test.fixtures.file.TestNameTestDirectoryProvider
import spock.lang.Specification

import java.util.function.Consumer

class DefaultBuildLifecycleControllerTest extends Specification {
    def buildBroadcaster = Mock(BuildListener)
    def workExecutor = Mock(BuildWorkExecutor)
    def workPreparer = Mock(BuildWorkPreparer)

    def settingsMock = Mock(SettingsInternal.class)
    def gradleMock = Mock(GradleInternal.class)

    def buildModelController = Mock(BuildModelController)
    def exceptionAnalyser = Mock(ExceptionAnalyser)
    def buildCompletionListener = Mock(BuildCompletionListener.class)
    def buildFinishedListener = Mock(InternalBuildFinishedListener.class)
    def buildServices = Mock(BuildScopeServices.class)
    public TestNameTestDirectoryProvider tmpDir = new TestNameTestDirectoryProvider(getClass())

    def failure = new RuntimeException("main")
    def transformedException = new RuntimeException("transformed")

    def setup() {
        _ * exceptionAnalyser.transform(failure) >> transformedException
    }

    DefaultBuildLifecycleController controller() {
        return new DefaultBuildLifecycleController(gradleMock, buildModelController, exceptionAnalyser, buildBroadcaster,
            buildCompletionListener, buildFinishedListener, workPreparer, workExecutor, buildServices)
    }

    void testCanFinishBuildWhenNothingHasBeenDone() {
        def controller = controller()

        expect:
        expectBuildFinished("Configure")

        def finishResult = controller.finishBuild(null)
        finishResult.failures.empty
    }

    void testScheduleAndRunRequestedTasks() {
        expect:
        expectTaskGraphBuilt()
        expectTasksRun()
        expectBuildFinished()

        def controller = controller()

        controller.scheduleRequestedTasks()
        def executionResult = controller.executeTasks()
        executionResult.failures.empty

        def finishResult = controller.finishBuild(null)
        finishResult.failures.empty
    }

    void testGetLoadedSettings() {
        when:
        expectSettingsBuilt()

        def controller = controller()
        def result = controller.getLoadedSettings()

        then:
        result == settingsMock

        expect:
        expectBuildFinished("Configure")
        def finishResult = controller.finishBuild(null)
        finishResult.failures.empty
    }

    void testNotifiesListenerOnLoadSettingsFailure() {
        def failure = new RuntimeException()

        when:
        expectSettingsBuiltWithFailure(failure)

        def controller = this.controller()
        controller.getLoadedSettings()

        then:
        def t = thrown RuntimeException
        t == transformedException

        and:
        1 * exceptionAnalyser.transform(failure) >> transformedException

        when:
        def finishResult = controller.finishBuild(null)

        then:
        1 * exceptionAnalyser.transform([transformedException]) >> transformedException
        1 * buildBroadcaster.buildFinished({ it.failure == transformedException })
        finishResult.failures.empty
    }

    void testGetConfiguredBuild() {
        when:
        1 * buildModelController.configuredModel >> gradleMock

        def controller = controller()
        def result = controller.getConfiguredBuild()

        then:
        result == gradleMock

        expect:
        expectBuildFinished("Configure")
        def finishResult = controller.finishBuild(null)
        finishResult.failures.empty
    }

    void testNotifiesListenerOnConfigureBuildFailure() {
        def failure = new RuntimeException()

        when:
        1 * buildModelController.configuredModel >> { throw failure }

        def controller = this.controller()
        controller.getConfiguredBuild()

        then:
        def t = thrown RuntimeException
        t == transformedException

        and:
        1 * exceptionAnalyser.transform(failure) >> transformedException

        when:
        def finishResult = controller.finishBuild(null)

        then:
        1 * exceptionAnalyser.transform([transformedException]) >> transformedException
        1 * buildBroadcaster.buildFinished({ it.failure == transformedException })
        finishResult.failures.empty
    }

    void testCannotExecuteTasksWhenNothingHasBeenScheduled() {
        when:
        def controller = controller()
        controller.executeTasks()

        then:
        def t = thrown IllegalStateException

        when:
        def finishResult = controller.finishBuild(null)

        then:
        1 * buildBroadcaster.buildFinished({ it.failure == null })
        finishResult.failures.empty
    }

    void testNotifiesListenerOnSettingsInitWithFailure() {
        given:
        1 * workPreparer.populateWorkGraph(gradleMock, _) >> { GradleInternal gradle, Consumer consumer -> consumer.accept() }
        1 * buildModelController.scheduleRequestedTasks() >> { throw failure }

        when:
        def controller = this.controller()
        controller.scheduleRequestedTasks()

        then:
        def t = thrown RuntimeException
        t == transformedException

        and:
        1 * exceptionAnalyser.transform(failure) >> transformedException

        when:
        def finishResult = controller.finishBuild(null)

        then:
        1 * exceptionAnalyser.transform([transformedException]) >> transformedException
        1 * buildBroadcaster.buildFinished({ it.failure == transformedException && it.action == "Build" })
        finishResult.failures.empty
    }

    void testNotifiesListenerOnTaskExecutionFailure() {
        given:
        expectTaskGraphBuilt()
        expectTasksRunWithFailure(failure)

        when:
        def controller = this.controller()
        controller.scheduleRequestedTasks()
        def executionResult = controller.executeTasks()

        then:
        executionResult.failures == [failure]

        when:
        def finishResult = controller.finishBuild(null)

        then:
        1 * exceptionAnalyser.transform([failure]) >> transformedException
        1 * buildBroadcaster.buildFinished({ it.failure == transformedException })
        finishResult.failures.empty
    }

    void testNotifiesListenerOnBuildCompleteWithMultipleFailures() {
        def failure2 = new RuntimeException()

        given:
        expectTaskGraphBuilt()
        expectTasksRunWithFailure(failure, failure2)

        when:
        def controller = this.controller()
        controller.scheduleRequestedTasks()
        def executionResult = controller.executeTasks()

        then:
        executionResult.failures == [failure, failure2]

        when:
        def finishResult = controller.finishBuild(null)

        then:
        1 * exceptionAnalyser.transform([failure, failure2]) >> transformedException
        1 * buildBroadcaster.buildFinished({ it.failure == transformedException })
        finishResult.failures.empty
    }

    void testTransformsBuildFinishedListenerFailure() {
        given:
        expectTaskGraphBuilt()
        expectTasksRun()

        and:
        def controller = controller()
        controller.scheduleRequestedTasks()
        controller.executeTasks()

        when:
        def finishResult = controller.finishBuild(null)

        then:
        1 * buildBroadcaster.buildFinished({ it.failure == null }) >> { throw failure }
        finishResult.failures == [failure]
    }

    void testNotifiesListenersOnMultipleBuildFailuresAndBuildListenerFailure() {
        def failure2 = new RuntimeException()
        def failure3 = new RuntimeException()

        given:
        expectTaskGraphBuilt()
        expectTasksRunWithFailure(failure, failure2)

        and:
        def controller = controller()
        controller.scheduleRequestedTasks()

        when:
        def executionResult = controller.executeTasks()

        then:
        executionResult.failures == [failure, failure2]

        when:
        def finishResult = controller.finishBuild(null)

        then:
        1 * exceptionAnalyser.transform([failure, failure2]) >> transformedException
        1 * buildBroadcaster.buildFinished({ it.failure == transformedException }) >> { throw failure3 }
        finishResult.failures == [failure3]
    }

    void testCleansUpOnStop() {
        when:
        def controller = controller()
        controller.stop()

        then:
        1 * buildServices.close()
        1 * buildCompletionListener.completed()
    }

    void testCannotGetModelAfterFinished() {
        given:
        def controller = controller()
        controller.finishBuild(null)

        when:
        controller.gradle

        then:
        thrown IllegalStateException
    }

    void testCannotRunMoreWorkAfterFinished() {
        given:
        def controller = controller()
        controller.finishBuild(null)

        when:
        controller.scheduleRequestedTasks()

        then:
        thrown IllegalStateException
    }

    private void expectSettingsBuilt() {
        1 * buildModelController.loadedSettings >> settingsMock
    }

    private void expectSettingsBuiltWithFailure(Throwable failure) {
        1 * buildModelController.loadedSettings >> { throw failure }
    }

    private void expectTaskGraphBuilt() {
        1 * workPreparer.populateWorkGraph(gradleMock, _) >> { GradleInternal gradle, Consumer consumer -> consumer.accept() }
        1 * buildModelController.prepareToScheduleTasks()
        1 * buildModelController.scheduleRequestedTasks()
    }

    private void expectTasksRun() {
        1 * workExecutor.execute(gradleMock) >> ExecutionResult.succeeded()
    }

    private void expectTasksRunWithFailure(Throwable failure, Throwable other = null) {
        def failures = other == null ? [failure] : [failure, other]
        1 * workExecutor.execute(gradleMock) >> ExecutionResult.maybeFailed(failures)
    }

    private void expectBuildFinished(String action = "Build") {
        1 * buildBroadcaster.buildFinished({ it.failure == null && it.action == action })
        1 * buildFinishedListener.buildFinished(_, false)
    }
}
