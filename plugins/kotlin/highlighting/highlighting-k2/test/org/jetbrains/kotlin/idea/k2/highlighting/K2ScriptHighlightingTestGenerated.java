// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.k2.highlighting;

import com.intellij.testFramework.TestDataPath;
import org.jetbrains.kotlin.idea.base.plugin.KotlinPluginMode;
import org.jetbrains.kotlin.idea.base.test.TestRoot;
import org.jetbrains.kotlin.idea.test.JUnit3RunnerWithInners;
import org.jetbrains.kotlin.idea.test.KotlinTestUtils;
import org.jetbrains.kotlin.test.TestMetadata;
import org.junit.runner.RunWith;

/**
 * This class is generated by {@link org.jetbrains.kotlin.testGenerator.generator.TestGenerator}.
 * DO NOT MODIFY MANUALLY.
 */
@SuppressWarnings("all")
@TestRoot("highlighting/highlighting-k2")
@TestDataPath("$CONTENT_ROOT")
@RunWith(JUnit3RunnerWithInners.class)
@TestMetadata("../../idea/tests/testData/mainKts/highlighting")
public class K2ScriptHighlightingTestGenerated extends AbstractK2ScriptHighlightingTest {
    @java.lang.Override
    @org.jetbrains.annotations.NotNull
    public final KotlinPluginMode getPluginMode() {
        return KotlinPluginMode.K2;
    }

    private void runTest(String testDataFilePath) throws Exception {
        KotlinTestUtils.runTest(this::doTest, this, testDataFilePath);
    }

    @TestMetadata("definitionDependencies.main.kts")
    public void testDefinitionDependencies() throws Exception {
        runTest("../../idea/tests/testData/mainKts/highlighting/definitionDependencies.main.kts");
    }

    @TestMetadata("fileName.main.kts")
    public void testFileName() throws Exception {
        runTest("../../idea/tests/testData/mainKts/highlighting/fileName.main.kts");
    }

    @TestMetadata("importedScripts.main.kts")
    public void testImportedScripts() throws Exception {
        runTest("../../idea/tests/testData/mainKts/highlighting/importedScripts.main.kts");
    }
}
