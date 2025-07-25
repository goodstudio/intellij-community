// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.execution.junit.codeInspection

import com.intellij.codeInsight.AnnotationUtil
import com.intellij.codeInsight.AnnotationUtil.CHECK_HIERARCHY
import com.intellij.codeInsight.MetaAnnotationUtil
import com.intellij.codeInsight.daemon.impl.analysis.JavaGenericsUtil
import com.intellij.codeInsight.intention.FileModifier.SafeFieldForPreview
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.options.JavaClassValidator
import com.intellij.codeInspection.*
import com.intellij.codeInspection.options.OptPane
import com.intellij.codeInspection.options.OptPane.pane
import com.intellij.codeInspection.options.OptPane.stringList
import com.intellij.codeInspection.util.InspectionMessage
import com.intellij.execution.JUnitBundle
import com.intellij.execution.junit.*
import com.intellij.execution.junit.references.MethodSourceReference
import com.intellij.execution.junit.references.PsiMethodSourceResolveResult
import com.intellij.jvm.analysis.quickFix.CompositeModCommandQuickFix
import com.intellij.jvm.analysis.quickFix.createModifierQuickfixes
import com.intellij.lang.Language
import com.intellij.lang.jvm.JvmMethod
import com.intellij.lang.jvm.JvmModifier
import com.intellij.lang.jvm.JvmModifiersOwner
import com.intellij.lang.jvm.actions.*
import com.intellij.lang.jvm.types.JvmPrimitiveTypeKind
import com.intellij.lang.jvm.types.JvmType
import com.intellij.modcommand.ModPsiUpdater
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.NlsSafe
import com.intellij.psi.*
import com.intellij.psi.CommonClassNames.*
import com.intellij.psi.impl.source.resolve.reference.impl.providers.FileReference
import com.intellij.psi.impl.source.tree.java.PsiNameValuePairImpl
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiUtil
import com.intellij.psi.util.TypeConversionUtil
import com.intellij.psi.util.parentOfType
import com.intellij.uast.UastHintedVisitorAdapter
import com.intellij.util.asSafely
import com.siyeh.ig.junit.JUnitCommonClassNames.*
import com.siyeh.ig.psiutils.TestUtils
import com.siyeh.ig.psiutils.TypeUtils
import org.jetbrains.uast.*
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor
import kotlin.streams.asSequence

class JUnitMalformedDeclarationInspection : AbstractBaseUastLocalInspectionTool() {
  @JvmField
  val ignorableAnnotations: List<String> = listOf("mockit.Mocked", "org.junit.jupiter.api.io.TempDir")

  override fun getOptionsPane(): OptPane = pane(
    stringList(
      "ignorableAnnotations",
      JUnitBundle.message("jvm.inspections.junit.malformed.option.ignore.test.parameter.if.annotated.by"),
      JavaClassValidator().annotationsOnly()
    )
  )

  private fun shouldInspect(file: PsiFile) = isJUnit3InScope(file) || isJUnit4InScope(file) || isJUnit5InScope(file)

  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
    if (!shouldInspect(holder.file)) return PsiElementVisitor.EMPTY_VISITOR
    return UastHintedVisitorAdapter.create(
      holder.file.language,
      JUnitMalformedSignatureVisitor(holder, isOnTheFly, ignorableAnnotations),
      arrayOf(UClass::class.java, UField::class.java, UMethod::class.java),
      directOnly = true
    )
  }
}

private class JUnitMalformedSignatureVisitor(
  private val holder: ProblemsHolder,
  private val isOnTheFly: Boolean,
  private val ignorableAnnotations: List<String>,
) : AbstractUastNonRecursiveVisitor() {
  override fun visitClass(node: UClass): Boolean {
    checkUnconstructableClass(node)
    checkMalformedNestedClass(node)
    checkMalformedParameterized(node)
    return true
  }

  override fun visitField(node: UField): Boolean {
    checkMalformedCallbackExtension(node)
    dataPoint.check(holder, node)
    ruleSignatureProblem.check(holder, node)
    classRuleSignatureProblem.check(holder, node)
    registeredExtensionProblem.check(holder, node)
    return true
  }

  override fun visitMethod(node: UMethod): Boolean {
    checkMalformedParameterized(node)
    checkRepeatedTestNonPositive(node)
    checkIllegalCombinedAnnotations(node)
    dataPoint.check(holder, node)
    checkSuite(node)
    checkedMalformedSetupTeardown(node)
    beforeAfterProblem.check(holder, node)
    beforeAfterEachProblem.check(holder, node)
    beforeAfterClassProblem.check(holder, node)
    beforeAfterAllProblem.check(holder, node)
    ruleSignatureProblem.check(holder, node)
    classRuleSignatureProblem.check(holder, node)
    checkJUnit3Test(node)
    junit4TestProblem.check(holder, node)
    junit5TestProblem.check(holder, node)
    return true
  }

  private val dataPoint = AnnotatedSignatureProblem(
    annotations = listOf(ORG_JUNIT_EXPERIMENTAL_THEORIES_DATAPOINT, ORG_JUNIT_EXPERIMENTAL_THEORIES_DATAPOINTS),
    shouldBeStatic = true,
    validVisibility = { UastVisibility.PUBLIC },
  )

  private val ruleSignatureProblem = AnnotatedSignatureProblem(
    annotations = listOf(ORG_JUNIT_RULE),
    shouldBeStatic = false,
    shouldBeSubTypeOf = listOf(ORG_JUNIT_RULES_TEST_RULE, ORG_JUNIT_RULES_METHOD_RULE),
    validVisibility = { UastVisibility.PUBLIC }
  )

  private val registeredExtensionProblem = AnnotatedSignatureProblem(
    annotations = listOf(ORG_JUNIT_JUPITER_API_EXTENSION_REGISTER_EXTENSION),
    shouldBeSubTypeOf = listOf(ORG_JUNIT_JUPITER_API_EXTENSION_EXTENSION),
    validVisibility = { decl ->
      val junitVersion = getUJUnitVersion(decl) ?: return@AnnotatedSignatureProblem null
      if (junitVersion < JUnitVersion.V_5_8_0) notPrivate(decl) else null
    }
  )

  private val classRuleSignatureProblem = AnnotatedSignatureProblem(
    annotations = listOf(ORG_JUNIT_CLASS_RULE),
    shouldBeStatic = true,
    shouldBeSubTypeOf = listOf(ORG_JUNIT_RULES_TEST_RULE),
    validVisibility = { UastVisibility.PUBLIC }
  )

  private val beforeAfterProblem = AnnotatedSignatureProblem(
    annotations = listOf(ORG_JUNIT_BEFORE, ORG_JUNIT_AFTER),
    shouldBeStatic = false,
    shouldBeVoidType = true,
    validVisibility = { UastVisibility.PUBLIC },
    validParameters = { method -> method.uastParameters.filter { MetaAnnotationUtil.isMetaAnnotated(it, ignorableAnnotations) } }
  )

  private val beforeAfterEachProblem = AnnotatedSignatureProblem(
    annotations = listOf(ORG_JUNIT_JUPITER_API_BEFORE_EACH, ORG_JUNIT_JUPITER_API_AFTER_EACH),
    shouldBeStatic = false,
    shouldBeVoidType = true,
    validVisibility = ::notPrivate,
    validParameters = { method ->
      if (method.uastParameters.isEmpty()) emptyList()
      else if (method.inParameterResolverContext()) method.uastParameters
      else method.uastParameters.filter { param ->
        param.type.canonicalText == ORG_JUNIT_JUPITER_API_TEST_INFO
        || param.type.canonicalText == ORG_JUNIT_JUPITER_API_REPETITION_INFO
        || param.type.canonicalText == ORG_JUNIT_JUPITER_API_TEST_REPORTER
        || MetaAnnotationUtil.isMetaAnnotated(param, ignorableAnnotations)
        || param.inParameterResolverContext()
      }
    }
  )

  private val beforeAfterClassProblem = AnnotatedSignatureProblem(
    annotations = listOf(ORG_JUNIT_BEFORE_CLASS, ORG_JUNIT_AFTER_CLASS),
    shouldBeStatic = true,
    shouldBeVoidType = true,
    validVisibility = { UastVisibility.PUBLIC },
    validParameters = { method -> method.uastParameters.filter { MetaAnnotationUtil.isMetaAnnotated(it, ignorableAnnotations) } }
  )

  private val beforeAfterAllProblem = AnnotatedSignatureProblem(
    annotations = listOf(ORG_JUNIT_JUPITER_API_BEFORE_ALL, ORG_JUNIT_JUPITER_API_AFTER_ALL),
    shouldBeInTestInstancePerClass = true,
    shouldBeStatic = true,
    shouldBeVoidType = true,
    validVisibility = ::notPrivate,
    validParameters = { method ->
      if (method.uastParameters.isEmpty()) emptyList()
      else if (method.inParameterResolverContext()) method.uastParameters
      else method.uastParameters.filter { param ->
        param.type.canonicalText == ORG_JUNIT_JUPITER_API_TEST_INFO
        || MetaAnnotationUtil.isMetaAnnotated(param, ignorableAnnotations)
        || param.inParameterResolverContext()
      }
    }
  )

  private val junit4TestProblem = AnnotatedSignatureProblem(
    annotations = listOf(ORG_JUNIT_TEST),
    shouldBeStatic = false,
    shouldBeVoidType = true,
    validVisibility = { UastVisibility.PUBLIC },
    validParameters = { method -> method.uastParameters.filter { MetaAnnotationUtil.isMetaAnnotated(it, ignorableAnnotations) } }
  )

  private val junit5TestProblem = AnnotatedSignatureProblem(
    annotations = listOf(ORG_JUNIT_JUPITER_API_TEST),
    shouldBeStatic = false,
    shouldBeVoidType = true,
    validVisibility = ::notPrivate,
    validParameters = { method ->
      if (method.uastParameters.isEmpty()) emptyList()
      else if (MetaAnnotationUtil.isMetaAnnotated(method.javaPsi, listOf(ORG_JUNIT_JUPITER_PARAMS_PROVIDER_ARGUMENTS_SOURCE))) null // handled in parameterized test check
      else if (method.inParameterResolverContext()) method.uastParameters
      else method.uastParameters.filter { param ->
        param.type.canonicalText == ORG_JUNIT_JUPITER_API_TEST_INFO
        || param.type.canonicalText == ORG_JUNIT_JUPITER_API_TEST_REPORTER
        || MetaAnnotationUtil.isMetaAnnotated(param, ignorableAnnotations)
        || param.inParameterResolverContext()
      }
    }
  )

  private val PsiAnnotation.shortName get() = qualifiedName?.substringAfterLast(".")

  private fun notPrivate(method: UDeclaration): UastVisibility? =
    if (method.visibility == UastVisibility.PRIVATE) UastVisibility.PUBLIC else null

  private fun UParameter.inParameterResolverContext(): Boolean = uAnnotations.any { ann -> ann.resolve()?.inParameterResolverContext() == true }

  private fun UMethod.inParameterResolverContext(): Boolean {
    val sourcePsi = this.sourcePsi ?: return false
    val alternatives = UastFacade.convertToAlternatives(sourcePsi, arrayOf(UMethod::class.java))
    return alternatives.any { it.javaPsi.inParameterResolverContext() }
  }

  private fun UDeclaration.isParameterizedTest(): Boolean {
    return when (this) {
      is UMethod -> MetaAnnotationUtil.isMetaAnnotated(javaPsi, listOf(ORG_JUNIT_JUPITER_PARAMS_PARAMETERIZED_TEST))
      is UClass -> MetaAnnotationUtil.isMetaAnnotated(javaPsi, listOf(ORG_JUNIT_JUPITER_PARAMS_PARAMETERIZED_CLASS))
      else -> false
    }
  }

  private fun UDeclaration.inParameterResolverContext(): Boolean = (javaPsi as? PsiModifierListOwner)?.inParameterResolverContext() == true

  private fun PsiModifierListOwner.inParameterResolverContext(): Boolean {
    val extendsWith = MetaAnnotationUtil.findMetaAnnotationsInHierarchy(this, listOf(ORG_JUNIT_JUPITER_API_EXTENSION_EXTEND_WITH))
      .asSequence()
    val extensionsEntries = MetaAnnotationUtil.findMetaAnnotationsInHierarchy(this, listOf(ORG_JUNIT_JUPITER_API_EXTENSION_EXTENSIONS))
      .asSequence()
      .map { annotation -> annotation.flattenedAttributeValues(PsiAnnotation.DEFAULT_REFERENCED_METHOD_NAME) }
      .flatten()
      .filterIsInstance<PsiAnnotation>()
    val hasAnnotation = (extendsWith + extensionsEntries).any { annotation ->
      annotation?.flattenedAttributeValues(PsiAnnotation.DEFAULT_REFERENCED_METHOD_NAME)?.any {
        val uClassLiteral = it.toUElementOfType<UClassLiteralExpression>()
        uClassLiteral != null && InheritanceUtil.isInheritor(uClassLiteral.type, ORG_JUNIT_JUPITER_API_EXTENSION_PARAMETER_RESOLVER)
      } == true
    }
    if (hasAnnotation) return true
    val hasRegisteredExtension = if (this is PsiClass) {
      fields.any { field ->
        field.hasAnnotation(ORG_JUNIT_JUPITER_API_EXTENSION_REGISTER_EXTENSION)
        && InheritanceUtil.isInheritor(field.type, ORG_JUNIT_JUPITER_API_EXTENSION_PARAMETER_RESOLVER)
      }
    }
    else false
    if (hasRegisteredExtension) return true
    if (parentOfType<PsiModifierListOwner>(withSelf = false)?.inParameterResolverContext() == true) return true
    return hasPotentialAutomaticParameterResolver(this)
  }

  private fun hasPotentialAutomaticParameterResolver(element: PsiElement): Boolean {
    val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return false
    val resourceRoots = ModuleRootManager.getInstance(module).getSourceRoots(TestUtils.isInTestCode(element))
    for (resourceRoot in resourceRoots) {
      val directory = PsiManager.getInstance(module.project).findDirectory(resourceRoot)
      val serviceFile = directory
        ?.findSubdirectory("META-INF")
        ?.findSubdirectory("services")
        ?.findFile(ORG_JUNIT_JUPITER_API_EXTENSION_EXTENSION)
      val serviceFqns = serviceFile?.text?.lines() ?: continue
      return serviceFqns.any { serviceFqn ->
        val service = JavaPsiFacade.getInstance(module.project).findClass(serviceFqn, module.moduleContentScope)
        InheritanceUtil.isInheritor(service, ORG_JUNIT_JUPITER_API_EXTENSION_PARAMETER_RESOLVER)
      }
    }
    return false
  }

  private fun checkUnconstructableClass(aClass: UClass) {
    val javaClass = aClass.javaPsi
    if (javaClass.isInterface || javaClass.isEnum || javaClass.isAnnotationType) return
    if (javaClass.hasModifier(JvmModifier.ABSTRACT)) return
    val constructors = javaClass.constructors.toList()
    if (TestUtils.isJUnitTestClass(javaClass)) {
      checkMalformedClass(aClass)
      if (constructors.isNotEmpty()) {
        val compatibleConstr = constructors.firstOrNull {
          val parameters = it.parameterList.parameters
          it.hasModifier(JvmModifier.PUBLIC)
          && (it.parameterList.isEmpty || parameters.size == 1 && TypeUtils.isJavaLangString(parameters.first().type))
        }
        if (compatibleConstr == null) {
          val message = JUnitBundle.message("jvm.inspections.unconstructable.test.case.junit3.descriptor")
          holder.registerUProblem(aClass, message)
          return
        }
      }
    }
    else if (TestUtils.isJUnit4TestClass(javaClass, false)) {
      checkMalformedClass(aClass)
      if (constructors.isNotEmpty()) {
        val publicConstructors = constructors.filter { it.hasModifier(JvmModifier.PUBLIC) }
        if (publicConstructors.size != 1 || !publicConstructors.first().parameterList.isEmpty) {
          val message = JUnitBundle.message("jvm.inspections.unconstructable.test.case.junit4.descriptor")
          holder.registerUProblem(aClass, message)
          return
        }
      }
    }
    return
  }

  private fun checkMalformedClass(aClass: UClass) {
    val javaClass = aClass.javaPsi
    if (!javaClass.hasModifier(JvmModifier.PUBLIC) && !aClass.isAnonymousOrLocal()) {
      val message = JUnitBundle.message("jvm.inspections.unconstructable.test.case.not.public.descriptor")
      val fixes = createModifierQuickfixes(aClass, modifierRequest(JvmModifier.PUBLIC, true))
      holder.registerUProblem(aClass, message, *fixes)
    }
  }

  private fun checkMalformedNestedClass(aClass: UClass) {
    val classHierarchy = aClass.nestedClassHierarchy()
    if (classHierarchy.isEmpty()) return
    if (classHierarchy.all { it.javaPsi.hasModifier(JvmModifier.ABSTRACT) }) return
    val outer = classHierarchy.last()
    checkMalformedJUnit4NestedClass(aClass, outer)
    checkMalformedJUnit5NestedClass(aClass)
  }

  private fun UClass.nestedClassHierarchy(current: List<UClass> = emptyList()): List<UClass> {
    val containingClass = getContainingUClass()
    if (containingClass == null) return current
    return listOf(containingClass) + containingClass.nestedClassHierarchy(current)
  }

  private fun checkMalformedJUnit4NestedClass(aClass: UClass, outerClass: UClass) {
    if (aClass.isInterface || aClass.javaPsi.hasModifier(JvmModifier.ABSTRACT)) return
    if (aClass.methods.none { it.javaPsi.hasAnnotation(ORG_JUNIT_TEST) }) return
    if (outerClass.uAnnotations.firstOrNull { it.qualifiedName == ORG_JUNIT_RUNNER_RUN_WITH } != null) return
    if (aClass.isStatic) return
    val message = JUnitBundle.message("jvm.inspections.junit.malformed.missing.nested.annotation.descriptor")
    holder.registerUProblem(aClass, message, MakeJUnit4InnerClassRunnableFix(aClass))
  }

  private inner class MakeJUnit4InnerClassRunnableFix(aClass: UClass) : ClassSignatureQuickFix(
    aClass.javaPsi.name, true, aClass.visibility != UastVisibility.PRIVATE, null
  ) {
    override fun getName(): String = JUnitBundle.message("jvm.inspections.junit.malformed.fix.class.signature.multi")

    override fun getActions(project: Project): List<(JvmModifiersOwner) -> List<IntentionAction>> {
      val list = super.getActions(project).toMutableList()
      list.add { owner ->
        val outerClass = owner.sourceElement?.toUElementOfType<UClass>()?.nestedClassHierarchy()?.lastOrNull() ?: return@add emptyList()
        val request = annotationRequest(ORG_JUNIT_RUNNER_RUN_WITH, classAttribute(
          PsiAnnotation.DEFAULT_REFERENCED_METHOD_NAME,
          ORG_JUNIT_EXPERIMENTAL_RUNNERS_ENCLOSED
        ))
        createAddAnnotationActions(outerClass.javaPsi, request)
      }
      return list
    }

    override fun getFamilyName(): String = JUnitBundle.message("jvm.inspections.junit.malformed.fix.class.signature.multi")

    override fun applyFix(project: Project, element: PsiElement, updater: ModPsiUpdater) {
      val uClass = getUParentForIdentifier(element)?.asSafely<UClass>() ?: return
      applyFixes(project, uClass.javaPsi.asSafely<PsiClass>() ?: return,
                 element.containingFile ?: return)
    }
  }

  private fun checkMalformedJUnit5NestedClass(aClass: UClass) {
    val javaClass = aClass.javaPsi
    if (aClass.isInterface || aClass.javaPsi.hasModifier(JvmModifier.ABSTRACT)) return
    val hasNestedAnnotation = javaClass.hasAnnotation(ORG_JUNIT_JUPITER_API_NESTED)
    if (!hasNestedAnnotation && !aClass.methods.any { it.javaPsi.hasAnnotation(ORG_JUNIT_JUPITER_API_TEST) ||
                                                      it.javaPsi.hasAnnotation(ORG_JUNIT_JUPITER_PARAMS_PARAMETERIZED_TEST)}) return
    if (!hasNestedAnnotation && aClass.isStatic) return
    if (hasNestedAnnotation && !aClass.isStatic && aClass.visibility != UastVisibility.PRIVATE) return
    val message = JUnitBundle.message("jvm.inspections.junit.malformed.missing.nested.annotation.descriptor")
    val fix = ClassSignatureQuickFix(
      aClass.javaPsi.name ?: return,
      false,
      aClass.visibility == UastVisibility.PRIVATE,
      if (hasNestedAnnotation) null else ORG_JUNIT_JUPITER_API_NESTED
    )
    holder.registerUProblem(aClass, message, fix)
  }

  private fun checkMalformedCallbackExtension(field: UField) {
    val javaField = field.javaPsi?.asSafely<PsiField>() ?: return
    val type = field.javaPsi?.asSafely<PsiField>()?.type ?: return
    if (!field.isStatic
        && javaField.hasAnnotation(ORG_JUNIT_JUPITER_API_EXTENSION_REGISTER_EXTENSION)
        && type.isInheritorOf(ORG_JUNIT_JUPITER_API_EXTENSION_BEFORE_ALL_CALLBACK, ORG_JUNIT_JUPITER_API_EXTENSION_AFTER_ALL_CALLBACK)
    ) {
      val message = JUnitBundle.message("jvm.inspections.junit.malformed.extension.class.level.descriptor", type.presentableText)
      val fixes = createModifierQuickfixes(field, modifierRequest(JvmModifier.STATIC, shouldBePresent = true))
      holder.registerUProblem(field, message, *fixes)
    }
  }

  private fun UMethod.isNoArg(): Boolean = uastParameters.isEmpty() || uastParameters.all { param ->
    param.javaPsi?.asSafely<PsiParameter>()?.let { AnnotationUtil.isAnnotated(it, ignorableAnnotations, 0) } == true
  }

  private fun checkSuspendFunction(method: UMethod): Boolean {
    return if (method.lang == Language.findLanguageByID("kotlin") && method.javaPsi.modifierList.text.contains("suspend")) {
      val message = JUnitBundle.message("jvm.inspections.junit.malformed.suspend.function.descriptor")
      holder.registerUProblem(method, message)
      true
    }
    else false
  }

  private fun checkJUnit3Test(method: UMethod) {
    val sourcePsi = method.sourcePsi ?: return
    val alternatives = UastFacade.convertToAlternatives(sourcePsi, arrayOf(UMethod::class.java))
    val javaMethod = alternatives.firstOrNull { it.isStatic } ?: alternatives.firstOrNull() ?: return
    if (method.isConstructor) return
    if (!TestUtils.isJUnit3TestMethod(javaMethod.javaPsi)) return
    val containingClass = method.javaPsi.containingClass ?: return
    if (AnnotationUtil.isAnnotated(containingClass, TestUtils.RUN_WITH, CHECK_HIERARCHY)) return
    if (checkSuspendFunction(method)) return
    if (PsiTypes.voidType() != method.returnType || method.visibility != UastVisibility.PUBLIC || javaMethod.isStatic
        || (!method.isNoArg() && !method.isParameterizedTest())) {
      val message = JUnitBundle.message("jvm.inspections.junit.malformed.no.arg.descriptor", "public", "non-static", DOUBLE)
      return holder.registerUProblem(method, message, MethodSignatureQuickfix(method.name, false, newVisibility = JvmModifier.PUBLIC))
    }
  }


  private fun checkedMalformedSetupTeardown(method: UMethod) {
    if ("setUp" != method.name && "tearDown" != method.name) return
    if (!InheritanceUtil.isInheritor(method.javaPsi.containingClass, JUNIT_FRAMEWORK_TEST_CASE)) return
    val sourcePsi = method.sourcePsi ?: return
    if (checkSuspendFunction(method)) return
    val alternatives = UastFacade.convertToAlternatives(sourcePsi, arrayOf(UMethod::class.java))
    val javaMethod = alternatives.firstOrNull { it.isStatic } ?: alternatives.firstOrNull() ?: return
    if (PsiTypes.voidType() != method.returnType || method.visibility == UastVisibility.PRIVATE || javaMethod.isStatic || !method.isNoArg()) {
      val message = JUnitBundle.message("jvm.inspections.junit.malformed.no.arg.descriptor", "non-private", "non-static", DOUBLE)
      val quickFix = MethodSignatureQuickfix(
        method.name, newVisibility = JvmModifier.PUBLIC, makeStatic = false, shouldBeVoidType = true, inCorrectParams = emptyMap()
      )
      return holder.registerUProblem(method, message, quickFix)
    }
  }

  private fun checkSuite(method: UMethod) {
    if ("suite" != method.name) return
    if (!InheritanceUtil.isInheritor(method.javaPsi.containingClass, JUNIT_FRAMEWORK_TEST_CASE)) return
    val sourcePsi = method.sourcePsi ?: return
    if (checkSuspendFunction(method)) return
    val alternatives = UastFacade.convertToAlternatives(sourcePsi, arrayOf(UMethod::class.java))
    val javaMethod = alternatives.firstOrNull { it.isStatic } ?: alternatives.firstOrNull() ?: return
    if (method.visibility == UastVisibility.PRIVATE || !javaMethod.isStatic || !method.isNoArg()) {
      val message = JUnitBundle.message("jvm.inspections.junit.malformed.no.arg.descriptor", "non-private", "static", SINGLE)
      val quickFix = MethodSignatureQuickfix(
        method.name, newVisibility = JvmModifier.PUBLIC, makeStatic = true, shouldBeVoidType = false, inCorrectParams = emptyMap()
      )
      return holder.registerUProblem(method, message, quickFix)
    }
  }

  private fun checkIllegalCombinedAnnotations(decl: UDeclaration) {
    val javaPsi = decl.javaPsi.asSafely<PsiModifierListOwner>() ?: return
    val annotatedTest = nonCombinedTests.filter { MetaAnnotationUtil.isMetaAnnotated(javaPsi, listOf(it)) }
    if (annotatedTest.size > 1) {
      val last = annotatedTest.last().substringAfterLast('.')
      val annText = annotatedTest.dropLast(1).joinToString { "'@${it.substringAfterLast('.')}'" }
      val message = JUnitBundle.message("jvm.inspections.junit.malformed.test.combination.descriptor", annText, last)
      return holder.registerUProblem(decl, message)
    }
    else if (annotatedTest.size == 1 && annotatedTest.first() != ORG_JUNIT_JUPITER_PARAMS_PARAMETERIZED_TEST) {
      val annotatedArgSource = parameterizedSources.filter { MetaAnnotationUtil.isMetaAnnotated(javaPsi, listOf(it)) }
      if (annotatedArgSource.isNotEmpty()) {
        val testAnnText = annotatedTest.first().substringAfterLast('.')
        val argAnnText = annotatedArgSource.joinToString { "'@${it.substringAfterLast('.')}'" }
        val message = JUnitBundle.message("jvm.inspections.junit.malformed.test.combination.descriptor", argAnnText, testAnnText)
        return holder.registerUProblem(decl, message)
      }
    }
  }

  private fun checkRepeatedTestNonPositive(method: UMethod) {
    val repeatedAnno = method.findAnnotation(ORG_JUNIT_JUPITER_API_REPEATED_TEST) ?: return
    val repeatedNumber = repeatedAnno.findDeclaredAttributeValue(PsiAnnotation.DEFAULT_REFERENCED_METHOD_NAME) ?: return
    val repeatedSrcPsi = repeatedNumber.sourcePsi ?: return
    val constant = repeatedNumber.evaluate()
    if (constant is Int && constant <= 0) {
      holder.registerProblem(repeatedSrcPsi, JUnitBundle.message("jvm.inspections.junit.malformed.repetition.number.descriptor"))
    }
  }

  private fun checkMalformedParameterized(declaration: UDeclaration) {
    if (!declaration.isParameterizedTest()) return
    val javaPsi = declaration.javaPsi as? PsiModifierListOwner ?: return
    val usedSourceAnnotations = MetaAnnotationUtil.findMetaAnnotations(javaPsi, SOURCE_ANNOTATIONS).toList()
    checkConflictingSourceAnnotations(usedSourceAnnotations, declaration)
    if (declaration is UClass) checkConflictingConstructors(declaration)
    usedSourceAnnotations.forEach { annotation ->
      when (annotation.qualifiedName) {
        ORG_JUNIT_JUPITER_PARAMS_PROVIDER_METHOD_SOURCE -> checkMethodSource(declaration, annotation)
        ORG_JUNIT_JUPITER_PARAMS_PROVIDER_VALUE_SOURCE -> checkValuesSource(declaration, annotation)
        ORG_JUNIT_JUPITER_PARAMS_PROVIDER_ENUM_SOURCE -> checkEnumSource(declaration, annotation)
        ORG_JUNIT_JUPITER_PARAMS_PROVIDER_CSV_FILE_SOURCE -> checkCsvSource(annotation)
        ORG_JUNIT_JUPITER_PARAMS_PROVIDER_NULL_SOURCE -> checkNullSource(declaration, annotation)
        ORG_JUNIT_JUPITER_PARAMS_PROVIDER_EMPTY_SOURCE -> checkEmptySource(declaration, annotation)
        ORG_JUNIT_JUPITER_PARAMS_PROVIDER_NULL_AND_EMPTY_SOURCE -> {
          checkNullSource(declaration, annotation)
          checkEmptySource(declaration, annotation)
        }
      }
    }
  }

  private fun checkConflictingConstructors(uClass: UClass) {
    val psiClass = uClass.javaPsi
    // exclude default constructor
    val constructors = psiClass.constructors.filter { constructor -> constructor.parameters.isNotEmpty() }
    if (constructors.size > 1) {
      val message = JUnitBundle.message("jvm.inspections.junit.malformed.single.constructor.descriptor", psiClass.name)
      for (constructor in constructors) {
        val uConstructor = constructor.toUElement(UMethod::class.java) ?: continue
        holder.registerUProblem(uConstructor, message) // todo create quickFix
      }
    }
  }

  private fun checkConflictingSourceAnnotations(annotations: List<PsiAnnotation>, declaration: UDeclaration) {
    val firstSingleParameterProvider = annotations.firstOrNull { ann ->
      singleParamProviders.contains(ann.qualifiedName)
    }
    val isSingleParameterProvider = firstSingleParameterProvider != null
    val firstMultipleParameterProvider = annotations.firstOrNull { ann ->
      multipleParameterProviders.contains(ann.qualifiedName)
    }
    val isMultipleParameterProvider = firstMultipleParameterProvider != null

    if (!isMultipleParameterProvider && !isSingleParameterProvider && hasCustomProvider(annotations)) return
    if (!isMultipleParameterProvider) {
      val message = if (!isSingleParameterProvider) {
        JUnitBundle.message("jvm.inspections.junit.malformed.param.no.sources.are.provided.descriptor")
      }
      else if (declaration is UMethod && declaration.hasInjectedParameterMismatch()) {
        JUnitBundle.message("jvm.inspections.junit.malformed.param.multiple.parameters.descriptor", firstSingleParameterProvider.shortName)
      }
      else if (declaration is UClass && declaration.hasInjectedParameterMismatch()) {
        JUnitBundle.message("jvm.inspections.junit.malformed.param.multiple.parameters.descriptor", firstSingleParameterProvider.shortName)
      }
      else return
      holder.registerUProblem(declaration, message)
    }
  }

  private fun hasCustomProvider(annotations: List<PsiAnnotation>): Boolean {
    for (ann in annotations) {
      when (ann.qualifiedName) {
        ORG_JUNIT_JUPITER_PARAMS_PROVIDER_ARGUMENTS_SOURCE -> return true
        ORG_JUNIT_JUPITER_PARAMS_PROVIDER_ARGUMENTS_SOURCES -> {
          val attributes = ann.findAttributeValue(PsiAnnotation.DEFAULT_REFERENCED_METHOD_NAME)
          if ((attributes as? PsiArrayInitializerMemberValue)?.initializers?.isNotEmpty() == true) return true
        }
      }
    }
    return false
  }

  private fun checkMethodSource(declaration: UDeclaration, methodSource: PsiAnnotation) {
    val testClass = if (declaration is UMethod) declaration.javaPsi.containingClass else declaration.javaPsi as? PsiClass
    if (testClass == null) return
    val annotationMemberValue = methodSource.flattenedAttributeValues(PsiAnnotation.DEFAULT_REFERENCED_METHOD_NAME)
    if (annotationMemberValue.isEmpty()) {
      when (declaration) {
        is UMethod -> {
          if (methodSource.findAttributeValue(PsiAnnotation.DEFAULT_REFERENCED_METHOD_NAME) == null) return
          val factoryMethod = testClass.findMethodsByName(declaration.name, true).singleOrNull { it.parameters.isEmpty() }
          val uFactoryMethod = factoryMethod.toUElementOfType<UMethod>()
          if (uFactoryMethod != null) {
            checkSourceProvider(uFactoryMethod, testClass, methodSource, declaration)
          }
          else {
            checkAbsentSourceProvider(testClass, methodSource, declaration.javaPsi.name)
          }
        }
        is UClass -> {
          val message = JUnitBundle.message("jvm.inspections.junit.malformed.param.method.source.missing.name.descriptor")
          val anchor = methodSource.toUElementOfType<UAnnotation>()?.uastAnchor?.sourcePsi ?: return
          holder.registerProblem(anchor, message) // todo create quickFix
        }
      }
    }
    else {
      annotationMemberValue.forEach { attributeValue ->
        for (reference in attributeValue.references) {
          if (reference is MethodSourceReference) {
            val factoryMethods = reference.multiResolve(false)
            val factoryMethod = factoryMethods
              .mapNotNull { it as? PsiMethodSourceResolveResult }
              .firstNotNullOfOrNull { it.getSourceMethodForClass(testClass) }
            if (factoryMethod == null) {
              return checkAbsentSourceProvider(testClass, attributeValue, reference.value)
            }
            else {
              val uFactoryMethod = factoryMethod.toUElementOfType<UMethod>() ?: return
              return checkSourceProvider(uFactoryMethod, testClass, attributeValue, declaration)
            }
          }
        }
      }
    }
  }

  private fun PsiMethodSourceResolveResult.getSourceMethodForClass(owner: PsiClass): PsiMethod? {
    val method = element as? PsiMethod ?: return null
    if (owners.isEmpty()) return method // direct link
    return owner.findMethodBySignature(method, true)
  }

  private fun checkAbsentSourceProvider(
    containingClass: PsiClass,
    anchor: PsiElement,
    sourceProviderName: String?,
  ) {
    if (sourceProviderName == null) return
    if (containingClass.isInterface || PsiUtil.isAbstractClass(containingClass)) return
    val message = JUnitBundle.message(
      "jvm.inspections.junit.malformed.param.method.source.unresolved.descriptor",
      sourceProviderName
    )
    return if (isOnTheFly) {
      val modifiers = mutableListOf(JvmModifier.PUBLIC)
      if (!TestUtils.testInstancePerClass(containingClass)) modifiers.add(JvmModifier.STATIC)
      val typeFromText = JavaPsiFacade.getElementFactory(containingClass.project).createTypeFromText(
        METHOD_SOURCE_RETURN_TYPE, containingClass
      )
      val request = methodRequest(containingClass.project, sourceProviderName, modifiers, typeFromText)
      val actions = createMethodActions(containingClass, request)
      val quickFixes = IntentionWrapper.wrapToQuickFixes(actions, containingClass.containingFile).toTypedArray()

      holder.registerProblem(anchor.navigationElement, message, *quickFixes)
    }
    else {
      holder.registerProblem(anchor.navigationElement, message)
    }
  }

  private fun checkSourceProvider(factoryMethod: UMethod, containingClass: PsiClass?, anchor: PsiElement, declaration: UDeclaration) {
    val javaPsi = declaration.javaPsi ?: return
    val factoryMethodName = factoryMethod.name
    if (!factoryMethod.isStatic &&
        containingClass != null && !TestUtils.testInstancePerClass(containingClass) &&
        !implementationsTestInstanceAnnotated(containingClass)
    ) {
      val annotation = JavaPsiFacade.getElementFactory(containingClass.project).createAnnotationFromText(
        TEST_INSTANCE_PER_CLASS, containingClass
      )
      val actions = mutableListOf<IntentionAction>()
      val value = (annotation.attributes.first() as PsiNameValuePairImpl).value
      if (value != null) {
        actions.addAll(createAddAnnotationActions(
          containingClass,
          annotationRequest(
            ORG_JUNIT_JUPITER_API_TEST_INSTANCE,
            constantAttribute(PsiAnnotation.DEFAULT_REFERENCED_METHOD_NAME, value.text)
          )
        ))
      }
      actions.addAll(createModifierActions(factoryMethod, modifierRequest(JvmModifier.STATIC, true)))
      val quickFixes = IntentionWrapper.wrapToQuickFixes(actions, factoryMethod.javaPsi.containingFile).toTypedArray()
      val message = JUnitBundle.message("jvm.inspections.junit.malformed.param.method.source.static.descriptor",
                                        factoryMethodName)
      holder.registerProblem(anchor.navigationElement, message, *quickFixes)
    }
    else if (factoryMethod.uastParameters.isNotEmpty() && !classHasParameterResolverField(containingClass)) {
      val message = JUnitBundle.message(
        "jvm.inspections.junit.malformed.param.method.source.no.params.descriptor", factoryMethodName)
      holder.registerProblem(anchor.navigationElement, message)
    }
    else {
      val componentType = getComponentType(factoryMethod.returnType, javaPsi)
      if (componentType == null) {
        val message = JUnitBundle.message(
          "jvm.inspections.junit.malformed.param.method.source.return.type.descriptor", factoryMethodName
        )
        holder.registerProblem(anchor.navigationElement, message)
      }
      else if (declaration.hasInjectedParameterMismatch()
               && !InheritanceUtil.isInheritor(componentType, ORG_JUNIT_JUPITER_PARAMS_PROVIDER_ARGUMENTS)
               && !componentType.equalsToText(JAVA_LANG_OBJECT)
               && !componentType.deepComponentType.equalsToText(JAVA_LANG_OBJECT)
      ) {
        val message = JUnitBundle.message("jvm.inspections.junit.malformed.param.wrapped.in.arguments.descriptor")
        holder.registerProblem(anchor.navigationElement, message)
      }
    }
  }

  private fun classHasParameterResolverField(aClass: PsiClass?): Boolean {
    if (aClass == null) return false
    if (aClass.isInterface) return false
    return aClass.fields.any { field ->
      AnnotationUtil.isAnnotated(field, ORG_JUNIT_JUPITER_API_EXTENSION_REGISTER_EXTENSION, 0) &&
      field.type.isInheritorOf(ORG_JUNIT_JUPITER_API_EXTENSION_PARAMETER_RESOLVER)
    }
  }

  private fun implementationsTestInstanceAnnotated(containingClass: PsiClass): Boolean =
    ClassInheritorsSearch.search(containingClass, containingClass.resolveScope, true)
      .any { TestUtils.testInstancePerClass(it) }

  private fun getComponentType(returnType: PsiType?, element: PsiElement): PsiType? {
    val collectionItemType = JavaGenericsUtil.getCollectionItemType(returnType, element.resolveScope)
    if (collectionItemType != null) return collectionItemType
    if (InheritanceUtil.isInheritor(returnType, JAVA_UTIL_STREAM_INT_STREAM)) return PsiTypes.intType()
    if (InheritanceUtil.isInheritor(returnType, JAVA_UTIL_STREAM_LONG_STREAM)) return PsiTypes.longType()
    if (InheritanceUtil.isInheritor(returnType, JAVA_UTIL_STREAM_DOUBLE_STREAM)) return PsiTypes.doubleType()
    val streamItemType = PsiUtil.substituteTypeParameter(returnType, JAVA_UTIL_STREAM_STREAM, 0, true)
    if (streamItemType != null) return streamItemType
    return PsiUtil.substituteTypeParameter(returnType, JAVA_UTIL_ITERATOR, 0, true)
  }

  private fun UDeclaration.hasInjectedParameterMismatch(): Boolean {
    return !inParameterResolverContext() && injectedParameters().size > 1
  }

  private fun UDeclaration.injectedParameters(): List<PsiVariable> = when (this) {
    is UMethod -> injectedParameters()
    is UClass -> injectedParameters()
    else -> emptyList()
  }

  private fun UMethod.injectedParameters(): List<PsiParameter> {
    return javaPsi.parameterList.parameters.filter { param ->
      !InheritanceUtil.isInheritor(param.type, ORG_JUNIT_JUPITER_API_TEST_INFO) &&
      !InheritanceUtil.isInheritor(param.type, ORG_JUNIT_JUPITER_API_TEST_REPORTER) &&
      !param.inParameterResolverContext() &&
      !MetaAnnotationUtil.isMetaAnnotated(param, ignorableAnnotations)
    }
  }

  /**
   * Returns parameters of a [UClass], can either be through the constructor or through [ORG_JUNIT_JUPITER_PARAMS_PARAMETER] annotated
   * fields.
   */
  private fun UClass.injectedParameters(): List<PsiVariable> {
    val constructor = javaPsi.constructors.firstOrNull { constructor -> constructor.parameters.isNotEmpty() }
    return constructor?.parameters?.mapNotNull { it as? PsiVariable }
           ?: fields
             .mapNotNull { it.javaPsi as? PsiVariable }
             .filter { MetaAnnotationUtil.isMetaAnnotated(it, setOf(ORG_JUNIT_JUPITER_PARAMS_PARAMETER)) }
  }

  private fun checkNullSource(declaration: UDeclaration, annotation: PsiAnnotation) {
    if (declaration.hasInjectedParameterMismatch()) {
      val message = JUnitBundle.message("jvm.inspections.junit.malformed.param.multiple.parameters.descriptor", annotation.shortName)
      holder.registerProblem(annotation, message)
    }
    if (declaration.injectedParameters().isEmpty()) {
      val message = when (declaration) {
        is UClass -> JUnitBundle.message("jvm.inspections.junit.malformed.source.without.constructor", annotation.shortName, declaration.javaPsi.name)
        is UMethod -> JUnitBundle.message("jvm.inspections.junit.malformed.source.without.params.descriptor", annotation.shortName)
        else -> return
      }
      holder.registerProblem(annotation.navigationElement, message)
    }
  }

  private fun checkEmptySource(declaration: UDeclaration, annotation: PsiAnnotation) {
    if (declaration.hasInjectedParameterMismatch()) {
      val message = JUnitBundle.message("jvm.inspections.junit.malformed.param.multiple.parameters.descriptor", annotation.shortName)
      return holder.registerProblem(annotation.navigationElement, message)
    }
    val injectedParameters = declaration.injectedParameters()
    if (injectedParameters.isEmpty()) {
      val message = when (declaration) {
        is UClass -> JUnitBundle.message("jvm.inspections.junit.malformed.source.without.constructor", annotation.shortName, declaration.javaPsi.name)
        is UMethod -> JUnitBundle.message("jvm.inspections.junit.malformed.source.without.params.descriptor", annotation.shortName)
        else -> return
      }
      holder.registerProblem(annotation.navigationElement, message)
    }
    else {
      val type = injectedParameters.first().type
      if (type is PsiClassType) {
        val psiClass = type.resolve() ?: return
        val version = getUJUnitVersion(declaration) ?: return
        if (version < JUnitVersion.V_5_10_0) {
          if (validEmptySourceTypeBefore510.any { it == psiClass.qualifiedName }) return
        }
        else {
          if (validEmptySourceTypeAfter510.any { it == psiClass.qualifiedName }) return
          val constructors = psiClass.constructors.mapNotNull { it.toUElementOfType<UMethod>() }
          val isCollectionOrMap = InheritanceUtil.isInheritor(psiClass, JAVA_UTIL_COLLECTION)
                                  || InheritanceUtil.isInheritor(psiClass, JAVA_UTIL_MAP)
          if (isCollectionOrMap && constructors.any { it.visibility == UastVisibility.PUBLIC && it.isNoArg() }) return
        }
      }
      if (type is PsiArrayType) return
      val message = JUnitBundle.message(
        "jvm.inspections.junit.malformed.param.empty.source.unsupported.descriptor",
        annotation.shortName, type.presentableText
      )
      holder.registerProblem(annotation.navigationElement, message)
    }
  }

  private fun checkEnumSource(declaration: UDeclaration, enumSource: PsiAnnotation) {
    val value = enumSource.findAttributeValue(PsiAnnotation.DEFAULT_REFERENCED_METHOD_NAME)
    if (value !is PsiClassObjectAccessExpression) return // @EnumSource#value type is Class<?>, not an array
    val enumType = value.operand.type
    checkSourceTypeAndParameterTypeAgree(declaration, value, enumType)
    checkEnumConstants(enumSource, enumType, declaration)
  }

  private fun checkSourceTypeAndParameterTypeAgree(declaration: UDeclaration, attributeValue: PsiAnnotationMemberValue, componentType: PsiType) {
    val parameters = declaration.injectedParameters()
    val param = parameters.singleOrNull() ?: return
    val paramType = param.type
    if (!paramType.isAssignableFrom(componentType) && !InheritanceUtil.isInheritor(
        componentType, ORG_JUNIT_JUPITER_PARAMS_PROVIDER_ARGUMENTS)
    ) {
      if (componentType.equalsToText(JAVA_LANG_STRING)) {
        //implicit conversion to primitive/wrapper
        if (TypeConversionUtil.isPrimitiveAndNotNullOrWrapper(paramType)) return
        val psiClass = PsiUtil.resolveClassInClassTypeOnly(paramType)
        //implicit conversion to enum
        if (psiClass != null) {
          if (psiClass.isEnum && psiClass.findFieldByName((attributeValue as PsiLiteral).value as String?, false) != null) return
          //implicit java time conversion
          val qualifiedName = psiClass.qualifiedName
          if (qualifiedName != null) {
            if (qualifiedName.startsWith("java.time.")) return
            if (qualifiedName == "java.nio.file.Path") return
          }

          val factoryMethod: (PsiMethod) -> Boolean = {
            !it.hasModifier(JvmModifier.PRIVATE) &&
            it.parameterList.parametersCount == 1 &&
            it.parameterList.parameters.first().type.equalsToText(JAVA_LANG_STRING)
          }

          if (!psiClass.hasModifier(JvmModifier.ABSTRACT) && psiClass.constructors.find(factoryMethod) != null) return
          if (psiClass.methods.find { it.hasModifier(JvmModifier.STATIC) && factoryMethod(it) } != null) return
        }
      }
      else if (componentType.equalsToText(ORG_JUNIT_JUPITER_PARAMS_PROVIDER_NULL_ENUM)) {
        val psiClass = PsiUtil.resolveClassInClassTypeOnly(paramType)
        if (psiClass != null && psiClass.isEnum) return
      }
      if (param.annotations.firstOrNull { it.qualifiedName == ORG_JUNIT_JUPITER_PARAMS_CONVERTER_CONVERT_WITH } != null) return
      val message = JUnitBundle.message(
        "jvm.inspections.junit.malformed.param.method.source.assignable.descriptor",
        componentType.presentableText, paramType.presentableText
      )
      holder.registerProblem(attributeValue.navigationElement, message)
    }
  }

  private fun checkValuesSource(declaration: UDeclaration, valuesSource: PsiAnnotation) {
    val psi = declaration.javaPsi ?: return
    val possibleValues = mapOf(
      "strings" to PsiType.getJavaLangString(psi.manager, psi.resolveScope),
      "ints" to PsiTypes.intType(),
      "longs" to PsiTypes.longType(),
      "doubles" to PsiTypes.doubleType(),
      "shorts" to PsiTypes.shortType(),
      "bytes" to PsiTypes.byteType(),
      "floats" to PsiTypes.floatType(),
      "chars" to PsiTypes.charType(),
      "booleans" to PsiTypes.booleanType(),
      "classes" to PsiType.getJavaLangClass(psi.manager, psi.resolveScope)
    )

    possibleValues.keys.forEach { valueKey ->
      valuesSource.flattenedAttributeValues(valueKey).forEach { value ->
        possibleValues[valueKey]?.let { checkSourceTypeAndParameterTypeAgree(declaration, value, it) }
      }
    }

    val attributesNumber = valuesSource.parameterList.attributes.size
    val message = if (attributesNumber == 0) {
      JUnitBundle.message("jvm.inspections.junit.malformed.param.no.value.source.is.defined.descriptor")
    }
    else if (attributesNumber > 1) {
      JUnitBundle.message(
        "jvm.inspections.junit.malformed.param.exactly.one.type.of.input.must.be.provided.descriptor")
    }
    else return
    val anchor = valuesSource.toUElementOfType<UAnnotation>()?.uastAnchor?.sourcePsi ?: return
    return holder.registerProblem(anchor, message)
  }

  private fun checkEnumConstants(enumSource: PsiAnnotation, enumType: PsiType, declaration: UDeclaration) {
    val mode = enumSource.findAttributeValue("mode")
    val uMode = mode.toUElement()
    if (uMode is UReferenceExpression && ("INCLUDE" == uMode.resolvedName || "EXCLUDE" == uMode.resolvedName)) {
      var validType = enumType
      if (enumType.canonicalText == ORG_JUNIT_JUPITER_PARAMS_PROVIDER_NULL_ENUM) {
        val parameters = declaration.injectedParameters()
        if (parameters.isNotEmpty()) validType = parameters.first().type
      }
      val allEnumConstants = (PsiUtil.resolveClassInClassTypeOnly(validType) ?: return).fields
        .filterIsInstance<PsiEnumConstant>()
        .map { it.name }
        .toSet()
      val definedConstants = mutableSetOf<String>()
      enumSource.flattenedAttributeValues("names").forEach { nameLiteral ->
        if (nameLiteral is PsiLiteralExpression) {
          val value = nameLiteral.value
          if (value is String) {
            val message = if (!allEnumConstants.contains(value)) {
              JUnitBundle.message("jvm.inspections.junit.malformed.param.unresolved.enum.descriptor")
            }
            else if (!definedConstants.add(value)) {
              JUnitBundle.message("jvm.inspections.junit.malformed.param.duplicated.enum.descriptor")
            }
            else return@forEach
            holder.registerProblem(nameLiteral.navigationElement, message)
          }
        }
      }
    }
  }

  private fun checkCsvSource(methodSource: PsiAnnotation) {
    methodSource.flattenedAttributeValues("resources").forEach { attributeValue ->
      for (ref in attributeValue.references) {
        if (ref.isSoft) continue
        if (ref is FileReference && ref.multiResolve(false).isEmpty()) {
          val message = JUnitBundle.message("jvm.inspections.junit.malformed.param.file.source.descriptor", attributeValue.text)
          holder.registerProblem(ref.element, message, *ref.quickFixes)
        }
      }
    }
  }

  class AnnotatedSignatureProblem(
    private val annotations: List<String>,
    private val shouldBeStatic: Boolean? = null,
    private val ignoreOnRunWith: Boolean = false,
    private val shouldBeInTestInstancePerClass: Boolean = false,
    private val shouldBeVoidType: Boolean? = null,
    private val shouldBeSubTypeOf: List<String>? = null,
    private val validVisibility: ((UDeclaration) -> UastVisibility?)? = null,
    private val validParameters: ((UMethod) -> List<UParameter>?)? = null,
  ) {
    private fun modifierProblems(
      validVisibility: UastVisibility?, decVisibility: UastVisibility, isStatic: Boolean, isInstancePerClass: Boolean,
    ): List<@NlsSafe String> {
      val problems = mutableListOf<String>()
      if (shouldBeInTestInstancePerClass) {
        if (!isStatic && !isInstancePerClass) problems.add("static")
      }
      else if (shouldBeStatic == true && !isStatic) problems.add("static")
      else if (shouldBeStatic == false && isStatic) problems.add("non-static")
      if (validVisibility != null && validVisibility != decVisibility) problems.add(validVisibility.text)
      return problems
    }

    private fun isApplicable(element: UElement): Boolean {
      if (!ignoreOnRunWith) {
        val containingClass = element.getContainingUClass()?.javaPsi ?: return false
        val annotation = AnnotationUtil.findAnnotationInHierarchy(containingClass, setOf(ORG_JUNIT_RUNNER_RUN_WITH))
        if (annotation != null) {
          val runnerType = annotation.findAttributeValue(PsiAnnotation.DEFAULT_REFERENCED_METHOD_NAME)
                             .toUElement()?.asSafely<UClassLiteralExpression>()
                             ?.type ?: return false
          return checkableRunners.any(runnerType::equalsToText)
        }
      }
      return true
    }

    fun check(holder: ProblemsHolder, element: UField) {
      if (!isApplicable(element)) return
      val javaPsi = element.javaPsi.asSafely<PsiField>() ?: return
      val annotation = annotations
                         .firstOrNull { MetaAnnotationUtil.isMetaAnnotated(javaPsi, annotations) }
                         ?.substringAfterLast(".") ?: return
      val visibility = validVisibility?.invoke(element)
      val problems = modifierProblems(visibility, element.visibility, element.isStatic, false)
      if (shouldBeVoidType == true && element.type != PsiTypes.voidType()) {
        return holder.fieldTypeProblem(element, visibility, annotation, problems, PsiTypes.voidType().name)
      }
      if (shouldBeSubTypeOf?.any { InheritanceUtil.isInheritor(element.type, it) } == false) {
        return holder.fieldTypeProblem(element, visibility, annotation, problems, shouldBeSubTypeOf.first())
      }
      if (problems.isNotEmpty()) return holder.fieldModifierProblem(element, visibility, annotation, problems)
    }

    private fun ProblemsHolder.fieldModifierProblem(
      element: UField, visibility: UastVisibility?, annotation: String, problems: List<@NlsSafe String>,
    ) {
      val message = if (problems.size == 1) {
        JUnitBundle.message("jvm.inspections.junit.malformed.annotated.single.descriptor", FIELD, annotation, problems.first())
      }
      else {
        JUnitBundle.message(
          "jvm.inspections.junit.malformed.annotated.double.descriptor", FIELD, annotation, problems.first(), problems.last()
        )
      }
      reportFieldProblem(message, element, visibility)
    }

    private fun ProblemsHolder.fieldTypeProblem(
      element: UField, visibility: UastVisibility?, annotation: String, problems: List<@NlsSafe String>, type: String,
    ) {
      if (problems.isEmpty()) {
        val message = JUnitBundle.message(
          "jvm.inspections.junit.malformed.annotated.typed.descriptor", FIELD, annotation, type)
        registerUProblem(element, message)
      }
      else if (problems.size == 1) {
        val message = JUnitBundle.message("jvm.inspections.junit.malformed.annotated.single.typed.descriptor", FIELD,
                                          annotation, problems.first(), type
        )
        reportFieldProblem(message, element, visibility)
      }
      else {
        val message = JUnitBundle.message("jvm.inspections.junit.malformed.annotated.double.typed.descriptor", FIELD,
                                          annotation, problems.first(), problems.last(), type
        )
        reportFieldProblem(message, element, visibility)
      }
    }

    private fun ProblemsHolder.reportFieldProblem(message: @InspectionMessage String, element: UField, visibility: UastVisibility?) {
      val quickFix = FieldSignatureQuickfix(element.name, shouldBeStatic, visibilityToModifier[visibility])
      return registerUProblem(element, message, quickFix)
    }

    fun check(holder: ProblemsHolder, element: UMethod) {
      if (!isApplicable(element)) return
      val javaPsi = element.javaPsi.asSafely<PsiMethod>() ?: return
      val sourcePsi = element.sourcePsi ?: return
      val annotation = annotations
                         .firstOrNull { AnnotationUtil.isAnnotated(javaPsi, it, CHECK_HIERARCHY) }
                         ?.substringAfterLast('.') ?: return
      val alternatives = UastFacade.convertToAlternatives(sourcePsi, arrayOf(UMethod::class.java))
      val elementIsStatic = alternatives.any { it.isStatic }
      val visibility = validVisibility?.invoke(element)
      val params = validParameters?.invoke(element)
      val problems = modifierProblems(
        visibility, element.visibility, elementIsStatic, javaPsi.containingClass?.let { cls -> TestUtils.testInstancePerClass(cls) } == true
      )
      if (element.lang == Language.findLanguageByID("kotlin") && element.javaPsi.modifierList.text.contains("suspend")) {
        val message = JUnitBundle.message(
          "jvm.inspections.junit.malformed.annotated.suspend.function.descriptor", annotation
        )
        return holder.registerUProblem(element, message)
      }
      if (params != null && params.size != element.uastParameters.size) {
        if (shouldBeVoidType == true && element.returnType != PsiTypes.voidType()) {
          return holder.methodParameterTypeProblem(element, visibility, annotation, problems, PsiTypes.voidType().name, params)
        }
        if (shouldBeSubTypeOf?.any { InheritanceUtil.isInheritor(element.returnType, it) } == false) {
          return holder.methodParameterTypeProblem(element, visibility, annotation, problems, shouldBeSubTypeOf.first(), params)
        }
        return holder.methodParameterProblem(element, visibility, annotation, problems, params)
      }
      if (shouldBeVoidType == true && element.returnType != PsiTypes.voidType()) {
        return holder.methodTypeProblem(element, visibility, annotation, problems, PsiTypes.voidType().name)
      }
      if (shouldBeSubTypeOf?.any { InheritanceUtil.isInheritor(element.returnType, it) } == false) {
        return holder.methodTypeProblem(element, visibility, annotation, problems, shouldBeSubTypeOf.first())
      }
      if (problems.isNotEmpty()) return holder.methodModifierProblem(element, visibility, annotation, problems)
    }

    private fun ProblemsHolder.methodParameterProblem(
      element: UMethod, visibility: UastVisibility?, annotation: String, problems: List<@NlsSafe String>, parameters: List<UParameter>,
    ) {
      val invalidParams = element.uastParameters.toMutableList().apply { removeAll(parameters) }
      val message = when {
        problems.isEmpty() && invalidParams.size == 1 -> JUnitBundle.message(
          "jvm.inspections.junit.malformed.annotated.method.param.single.descriptor", annotation, invalidParams.first().name
        )
        problems.isEmpty() && invalidParams.size > 1 -> JUnitBundle.message(
          "jvm.inspections.junit.malformed.annotated.method.param.double.descriptor",
          annotation, invalidParams.joinToString { "'${it.name}'" }, invalidParams.last().name
        )
        problems.size == 1 && invalidParams.size == 1 -> JUnitBundle.message(
          "jvm.inspections.junit.malformed.annotated.method.single.param.single.descriptor",
          annotation, problems.first(), invalidParams.first().name
        )
        problems.size == 1 && invalidParams.size > 1 -> JUnitBundle.message(
          "jvm.inspections.junit.malformed.annotated.method.single.param.double.descriptor",
          annotation, problems.first(), invalidParams.joinToString { "'${it.name}'" },
          invalidParams.last().name
        )
        problems.size == 2 && invalidParams.size == 1 -> JUnitBundle.message(
          "jvm.inspections.junit.malformed.annotated.method.double.param.single.descriptor",
          annotation, problems.first(), problems.last(), invalidParams.first().name
        )
        problems.size == 2 && invalidParams.size > 1 -> JUnitBundle.message(
          "jvm.inspections.junit.malformed.annotated.method.double.param.double.descriptor",
          annotation, problems.first(), problems.last(), invalidParams.joinToString { "'${it.name}'" },
          invalidParams.last().name
        )
        else -> error("Non valid problem.")
      }
      reportMethodProblem(message, element, visibility)
    }

    private fun ProblemsHolder.methodParameterTypeProblem(
      element: UMethod, visibility: UastVisibility?, annotation: String, problems: List<@NlsSafe String>, type: String,
      parameters: List<UParameter>,
    ) {
      val invalidParams = element.uastParameters.toMutableList().apply { removeAll(parameters) }
      val message = when {
        problems.isEmpty() && invalidParams.size == 1 -> JUnitBundle.message(
          "jvm.inspections.junit.malformed.annotated.method.typed.param.single.descriptor",
          annotation, type, invalidParams.first().name
        )
        problems.isEmpty() && invalidParams.size > 1 -> JUnitBundle.message(
          "jvm.inspections.junit.malformed.annotated.method.typed.param.double.descriptor",
          annotation, type, invalidParams.joinToString { "'${it.name}'" }, invalidParams.last().name
        )
        problems.size == 1 && invalidParams.size == 1 -> JUnitBundle.message(
          "jvm.inspections.junit.malformed.annotated.method.single.typed.param.single.descriptor",
          annotation, problems.first(), type, invalidParams.first().name
        )
        problems.size == 1 && invalidParams.size > 1 -> JUnitBundle.message(
          "jvm.inspections.junit.malformed.annotated.method.single.typed.param.double.descriptor",
          annotation, problems.first(), type, invalidParams.joinToString { "'${it.name}'" },
          invalidParams.last().name
        )
        problems.size == 2 && invalidParams.size == 1 -> JUnitBundle.message(
          "jvm.inspections.junit.malformed.annotated.method.double.typed.param.single.descriptor",
          annotation, problems.first(), problems.last(), type, invalidParams.first().name
        )
        problems.size == 2 && invalidParams.size > 1 -> JUnitBundle.message(
          "jvm.inspections.junit.malformed.annotated.method.double.typed.param.double.descriptor",
          annotation, problems.first(), problems.last(), type, invalidParams.joinToString { "'${it.name}'" },
          invalidParams.last().name
        )
        else -> error("Non valid problem.")
      }
      reportMethodProblem(message, element, visibility)
    }

    private fun ProblemsHolder.methodTypeProblem(
      element: UMethod, visibility: UastVisibility?, annotation: String, problems: List<@NlsSafe String>, type: String,
    ) {
      val message = if (problems.isEmpty()) {
        JUnitBundle.message("jvm.inspections.junit.malformed.annotated.typed.descriptor", METHOD, annotation, type)
      }
      else if (problems.size == 1) {
        JUnitBundle.message(
          "jvm.inspections.junit.malformed.annotated.single.typed.descriptor", METHOD, annotation, problems.first(), type
        )
      }
      else {
        JUnitBundle.message(
          "jvm.inspections.junit.malformed.annotated.double.typed.descriptor", METHOD, annotation, problems.first(), problems.last(), type
        )
      }
      reportMethodProblem(message, element, visibility)
    }

    private fun ProblemsHolder.methodModifierProblem(
      element: UMethod, visibility: UastVisibility?, annotation: String, problems: List<@NlsSafe String>,
    ) {
      val message = if (problems.size == 1) {
        JUnitBundle.message("jvm.inspections.junit.malformed.annotated.single.descriptor", METHOD, annotation, problems.first())
      }
      else {
        JUnitBundle.message("jvm.inspections.junit.malformed.annotated.double.descriptor", METHOD,
                            annotation, problems.first(), problems.last()
        )
      }
      reportMethodProblem(message, element, visibility)
    }

    private fun ProblemsHolder.reportMethodProblem(
      message: @InspectionMessage String,
      element: UMethod,
      visibility: UastVisibility? = null,
      params: List<UParameter>? = null,
    ) {
      val quickFix = MethodSignatureQuickfix(
        element.name, shouldBeStatic, shouldBeVoidType, visibilityToModifier[visibility],
        params?.associate { it.name to it.type } ?: emptyMap()
      )
      return registerUProblem(element, message, quickFix)
    }
  }

  private open class ClassSignatureQuickFix(
    private val name: @NlsSafe String?,
    private val makeStatic: Boolean? = null,
    private val makePublic: Boolean? = null,
    private val annotation: String? = null,
  ) : CompositeModCommandQuickFix() {
    override fun getFamilyName(): String = JUnitBundle.message("jvm.inspections.junit.malformed.fix.class.signature")

    override fun getName(): String = JUnitBundle.message("jvm.inspections.junit.malformed.fix.class.signature.descriptor", name)

    override fun applyFix(project: Project, element: PsiElement, updater: ModPsiUpdater) {
      val javaDeclaration = getUParentForIdentifier(element)?.asSafely<UClass>() ?: return
      applyFixes(project, javaDeclaration.javaPsi.asSafely<PsiClass>() ?: return,
                 element.containingFile ?: return)
    }

    override fun getActions(project: Project): List<(JvmModifiersOwner) -> List<IntentionAction>> {
      val actions = mutableListOf<(JvmModifiersOwner) -> List<IntentionAction>>()
      if (makeStatic != null) {
        actions.add { jvmClass -> createModifierActions(jvmClass, modifierRequest(JvmModifier.STATIC, makeStatic)) }
      }
      if (makePublic != null) {
        actions.add { jvmClass -> createModifierActions(jvmClass, modifierRequest(JvmModifier.PUBLIC, makePublic)) }
      }
      if (annotation != null) {
        actions.add { jvmClass -> createAddAnnotationActions(jvmClass, annotationRequest(annotation)) }
      }
      return actions
    }
  }

  private class FieldSignatureQuickfix(
    private val name: @NlsSafe String,
    private val makeStatic: Boolean?,
    private val newVisibility: JvmModifier? = null,
  ) : CompositeModCommandQuickFix() {
    override fun getFamilyName(): String = JUnitBundle.message("jvm.inspections.junit.malformed.fix.field.signature")

    override fun getName(): String = JUnitBundle.message("jvm.inspections.junit.malformed.fix.field.signature.descriptor", name)

    override fun applyFix(project: Project, element: PsiElement, updater: ModPsiUpdater) {
      val javaDeclaration = getUParentForIdentifier(element)?.asSafely<UField>() ?: return
      applyFixes(project, javaDeclaration.javaPsi ?: return, element.containingFile ?: return)
    }

    override fun getActions(project: Project): List<(JvmModifiersOwner) -> List<IntentionAction>> {
      val actions = mutableListOf<(JvmModifiersOwner) -> List<IntentionAction>>()
      if (newVisibility != null) {
        actions.add { jvmField -> createModifierActions(jvmField, modifierRequest(newVisibility, true)) }
      }
      if (makeStatic != null) {
        actions.add { jvmField -> createModifierActions(jvmField, modifierRequest(JvmModifier.STATIC, makeStatic)) }
      }
      return actions
    }
  }

  private class MethodSignatureQuickfix(
    private val name: @NlsSafe String,
    private val makeStatic: Boolean?,
    private val shouldBeVoidType: Boolean? = null,
    private val newVisibility: JvmModifier? = null,
    @SafeFieldForPreview private val inCorrectParams: Map<String, JvmType>? = null,
  ) : CompositeModCommandQuickFix() {
    override fun getFamilyName(): String = JUnitBundle.message("jvm.inspections.junit.malformed.fix.method.signature")

    override fun getName(): String = JUnitBundle.message("jvm.inspections.junit.malformed.fix.method.signature.descriptor", name)

    override fun applyFix(project: Project, element: PsiElement, updater: ModPsiUpdater) {
      val javaDeclaration = getUParentForIdentifier(element)?.asSafely<UMethod>() ?: return
      applyFixes(project, javaDeclaration.javaPsi, element.containingFile ?: return)
    }

    override fun getActions(project: Project): List<(JvmModifiersOwner) -> List<IntentionAction>> {
      val actions = mutableListOf<(JvmModifiersOwner) -> List<IntentionAction>>()
      if (shouldBeVoidType == true) {
        actions.add { jvmMethod ->
          createChangeTypeActions(
            jvmMethod.asSafely<JvmMethod>()!!,
            typeRequest(JvmPrimitiveTypeKind.VOID.name, emptyList())
          )
        }
      }
      if (newVisibility != null) {
        actions.add { jvmMethod -> createModifierActions(jvmMethod, modifierRequest(newVisibility, true, false)) }
      }
      if (inCorrectParams != null) {
        actions.add { jvmMethod ->
          createChangeParametersActions(
            jvmMethod.asSafely<JvmMethod>()!!,
            setMethodParametersRequest(inCorrectParams.entries)
          )
        }
      }
      if (makeStatic != null) {
        actions.add { jvmMethod -> createModifierActions(jvmMethod, modifierRequest(JvmModifier.STATIC, makeStatic, false)) }
      }
      return actions
    }
  }

  private companion object {
    // message choices
    const val FIELD = 0
    const val METHOD = 1
    const val SINGLE = 0
    const val DOUBLE = 1

    const val TEST_INSTANCE_PER_CLASS = "@org.junit.jupiter.api.TestInstance(TestInstance.Lifecycle.PER_CLASS)"
    const val METHOD_SOURCE_RETURN_TYPE = "java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments>"

    val checkableRunners = listOf(
      "org.junit.runners.AllTests",
      "org.junit.runners.Parameterized",
      "org.junit.runners.BlockJUnit4ClassRunner",
      "org.junit.runners.JUnit4",
      "org.junit.runners.Suite",
      "org.junit.internal.runners.JUnit38ClassRunner",
      "org.junit.internal.runners.JUnit4ClassRunner",
      "org.junit.experimental.categories.Categories",
      "org.junit.experimental.categories.Enclosed"
    )

    private val validEmptySourceTypeBefore510 = listOf(
      JAVA_LANG_STRING,
      JAVA_UTIL_LIST,
      JAVA_UTIL_SET,
      JAVA_UTIL_MAP
    )

    private val validEmptySourceTypeAfter510 = listOf(
      JAVA_LANG_STRING,
      JAVA_UTIL_LIST,
      JAVA_UTIL_SET,
      JAVA_UTIL_SORTED_SET,
      JAVA_UTIL_NAVIGABLE_SET,
      JAVA_UTIL_SORTED_MAP,
      JAVA_UTIL_NAVIGABLE_MAP,
      JAVA_UTIL_MAP,
      JAVA_UTIL_COLLECTION
    )

    val visibilityToModifier = mapOf(
      UastVisibility.PUBLIC to JvmModifier.PUBLIC,
      UastVisibility.PROTECTED to JvmModifier.PROTECTED,
      UastVisibility.PRIVATE to JvmModifier.PRIVATE,
      UastVisibility.PACKAGE_LOCAL to JvmModifier.PACKAGE_LOCAL
    )

    val singleParamProviders = listOf(
      ORG_JUNIT_JUPITER_PARAMS_PROVIDER_ENUM_SOURCE,
      ORG_JUNIT_JUPITER_PARAMS_PROVIDER_VALUE_SOURCE,
      ORG_JUNIT_JUPITER_PARAMS_PROVIDER_NULL_SOURCE,
      ORG_JUNIT_JUPITER_PARAMS_PROVIDER_EMPTY_SOURCE,
      ORG_JUNIT_JUPITER_PARAMS_PROVIDER_NULL_AND_EMPTY_SOURCE
    )

    val multipleParameterProviders = listOf(
      ORG_JUNIT_JUPITER_PARAMS_PROVIDER_METHOD_SOURCE,
      ORG_JUNIT_JUPITER_PARAMS_PROVIDER_CSV_FILE_SOURCE,
      ORG_JUNIT_JUPITER_PARAMS_PROVIDER_CSV_SOURCE
    )

    val nonCombinedTests = listOf(
      ORG_JUNIT_JUPITER_API_TEST,
      ORG_JUNIT_JUPITER_API_TEST_FACTORY,
      ORG_JUNIT_JUPITER_API_REPEATED_TEST,
      ORG_JUNIT_JUPITER_PARAMS_PARAMETERIZED_TEST
    )

    val parameterizedSources = listOf(
      ORG_JUNIT_JUPITER_PARAMS_PROVIDER_METHOD_SOURCE,
      ORG_JUNIT_JUPITER_PARAMS_PROVIDER_VALUE_SOURCE,
      ORG_JUNIT_JUPITER_PARAMS_PROVIDER_ENUM_SOURCE,
      ORG_JUNIT_JUPITER_PARAMS_PROVIDER_CSV_FILE_SOURCE,
      ORG_JUNIT_JUPITER_PARAMS_PROVIDER_NULL_SOURCE,
      ORG_JUNIT_JUPITER_PARAMS_PROVIDER_EMPTY_SOURCE,
      ORG_JUNIT_JUPITER_PARAMS_PROVIDER_NULL_AND_EMPTY_SOURCE
    )
  }
}