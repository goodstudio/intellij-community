// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.env.python

import com.intellij.testFramework.ProjectRule
import com.intellij.testFramework.common.timeoutRunBlocking
import com.jetbrains.python.packaging.pip.PipPythonPackageManager
import kotlinx.coroutines.test.runTest
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.empty
import org.hamcrest.Matchers.not
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Tests pip package manager.
 * * Extend this class
 * * Implement [sdkRule]
 * * Use [com.intellij.testFramework.RuleChain] with [projectRule], [sdkRule] e.t.c as you do in JUnit4 test
 */
abstract class PipPackageManagerTestBase {
  protected val projectRule = ProjectRule()

  protected abstract val sdkRule: PySDKRule

  @Test
  fun testList(): Unit =  timeoutRunBlocking(5.minutes) {
    PipPythonPackageManager(projectRule.project, sdkRule.sdk).apply {
      assertThat("No packages return", reloadPackages().successOrNull, not(empty()))
      assertThat("Installed packages shouldn't be empty", listInstalledPackages(), not(empty()))
    }
  }
}