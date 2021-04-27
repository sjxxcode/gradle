/*
 * Copyright 2019 the original author or authors.
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

package org.gradle.java.compile.incremental

import org.gradle.api.JavaVersion
import org.gradle.integtests.fixtures.AvailableJavaHomes
import org.gradle.util.TextUtil

class JavaSourceCliIncrementalCompilationIntegrationTest extends BaseJavaSourceIncrementalCompilationIntegrationTest {

    def setup() {
        buildFile << """
            tasks.withType(JavaCompile) {
                options.fork = true
                options.forkOptions.executable = '${TextUtil.escapeString(AvailableJavaHomes.getJdk(JavaVersion.current()).javacExecutable)}'
            }
        """
    }

    def "changing an unused non-private constant incurs full rebuild"() {
        source "class A { int foo() { return 2; } }", "class B { final static int x = 1;}"
        outputs.snapshot { run language.compileTaskName }

        when:
        source "class B { /* change */ }"
        run language.compileTaskName

        then:
        outputs.recompiledClasses 'B', 'A'
    }

}
