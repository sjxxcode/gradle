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

package org.gradle.internal.buildtree;

/**
 * Responsible for preparing the work graph for the build tree.
 */
public interface BuildTreeWorkPreparer {
    /**
     * Prepares the build tree to schedule tasks. May configure the build model, if no cache task graph is available.
     */
    void prepareToScheduleTasks();

    /**
     * Prepares the work graph for execution. May calculate the task graph from the build model, or may load a cached task graph if available.
     */
    void scheduleRequestedTasks();
}
