// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.search.usagesSearch

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.ide.highlighter.XmlFileType
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.impl.source.resolve.reference.impl.PsiMultiReference
import com.intellij.psi.search.*
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.asJava.classes.KtLightClass
import org.jetbrains.kotlin.diagnostics.PsiDiagnosticUtils
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.idea.base.projectStructure.RootKindFilter
import org.jetbrains.kotlin.idea.base.projectStructure.matches
import org.jetbrains.kotlin.idea.base.psi.kotlinFqName
import org.jetbrains.kotlin.idea.base.util.everythingScopeExcludeFileTypes
import org.jetbrains.kotlin.idea.base.util.excludeFileTypes
import org.jetbrains.kotlin.idea.base.util.restrictToKotlinSources
import org.jetbrains.kotlin.idea.base.util.useScope
import org.jetbrains.kotlin.idea.references.KtDestructuringDeclarationReference
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.idea.search.ideaExtensions.KotlinReferencesSearchOptions
import org.jetbrains.kotlin.idea.search.ideaExtensions.KotlinReferencesSearchParameters
import org.jetbrains.kotlin.idea.util.application.isUnitTestMode
import org.jetbrains.kotlin.kdoc.psi.impl.KDocName
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.psiUtil.*
import org.jetbrains.kotlin.utils.KotlinExceptionWithAttachments
import java.util.*

//TODO: check if smart search is too expensive

class ExpressionsOfTypeProcessor(
    private val containsTypeOrDerivedInside: (KtDeclaration) -> Boolean,
    private val classToSearch: PsiElement?,
    private val searchScope: SearchScope,
    private val project: Project,
    private val possibleMatchHandler: (KtExpression) -> Unit,
    private val possibleMatchesInScopeHandler: (SearchScope) -> Unit
) {
    /** For tests only */
    enum class Mode {
        ALWAYS_SMART,
        ALWAYS_PLAIN,
        PLAIN_WHEN_NEEDED // use plain search for LocalSearchScope and when unknown type of reference encountered
    }

    companion object {
        @get:TestOnly
        var mode = if (isUnitTestMode()) Mode.ALWAYS_SMART else Mode.PLAIN_WHEN_NEEDED

        @TestOnly
        fun prodMode() {
            mode = Mode.PLAIN_WHEN_NEEDED
        }

        @TestOnly
        fun resetMode() {
            mode = if (isUnitTestMode()) Mode.ALWAYS_SMART else Mode.PLAIN_WHEN_NEEDED
        }

        @get:TestOnly
        var testLog: MutableCollection<String>? = null

        inline fun testLog(s: () -> String) {
            testLog?.add(s())
        }

        val LOG = Logger.getInstance(ExpressionsOfTypeProcessor::class.java)

        fun logPresentation(element: PsiElement): String? {
            return runReadAction {
                if (element !is KtDeclaration && element !is PsiMember) return@runReadAction element.text
                val fqName = element.kotlinFqName?.asString()
                    ?: (element as? KtNamedDeclaration)?.name
                when (element) {
                    is PsiTypeParameter -> element.name
                    is PsiMethod -> fqName + element.parameterList.text
                    is KtFunction -> fqName + element.valueParameterList!!.text
                    is KtParameter -> {
                        val owner = element.ownerFunction?.let { logPresentation(it) } ?: element.parent.toString()
                        "parameter ${element.name} of $owner"
                    }
                    is KtDestructuringDeclaration -> element.entries.joinToString(", ", prefix = "(", postfix = ")") { it.text }
                    else -> fqName
                }
            }
        }

        private fun PsiModifierListOwner.isPrivate() = hasModifierProperty(PsiModifier.PRIVATE)

        private fun PsiModifierListOwner.isLocal() = parents.any { it is PsiCodeBlock }
    }

    // note: a Task must define equals & hashCode!
    private interface Task {
        fun perform()
    }

    private val tasks = ArrayDeque<Task>()
    private val tasksSet = HashSet<Task>()

    private val scopesToUsePlainSearch = LinkedHashMap<KtFile, ArrayList<PsiElement>>()

    fun run() {
        val usePlainSearch = when (mode) {
            Mode.ALWAYS_SMART -> false
            Mode.ALWAYS_PLAIN -> true
            Mode.PLAIN_WHEN_NEEDED -> searchScope is LocalSearchScope && searchScope.virtualFiles.size < 2 // for local scope it's faster to use plain search
        }
        if (usePlainSearch || classToSearch == null) {
            possibleMatchesInScopeHandler(searchScope)
            return
        }

        // optimization
        if (runReadAction {
                noKotlinFilesInScope(searchScope)
            }) return

        // for class from library always use plain search because we cannot search usages in compiled code (we could though)
        if (!runReadAction {
                classToSearch.isValid && isInProjectScope(classToSearch)
            }) {
            possibleMatchesInScopeHandler(searchScope)
            return
        }

        addClassToProcess(classToSearch)

        processTasks()

        runReadAction {
            val scopeElements = scopesToUsePlainSearch.values
                .flatten()
                .filter { it.isValid }
                .toTypedArray()
            if (scopeElements.isNotEmpty()) {
                possibleMatchesInScopeHandler(LocalSearchScope(scopeElements))
            }
        }
    }

    private fun isInProjectScope(classToSearch: PsiElement): Boolean {
        return RootKindFilter.projectSources.copy(includeScriptsOutsideSourceRoots = false).matches(classToSearch)
    }

    private fun noKotlinFilesInScope(searchScope: SearchScope): Boolean {
        if (searchScope is GlobalSearchScope && !FileTypeIndex.containsFileOfType(KotlinFileType.INSTANCE, searchScope)) {
            return true
        }
        return searchScope is LocalSearchScope && searchScope.virtualFiles.none { it.fileType == KotlinFileType.INSTANCE }
    }

    private fun addTask(task: Task) {
        if (tasksSet.add(task)) {
            tasks.push(task)
        }
    }

    private fun processTasks() {
        while (tasks.isNotEmpty()) {
            tasks.pop().perform()
        }
    }

    private fun downShiftToPlainSearch(reference: PsiReference) {
        val message = getFallbackDiagnosticsMessage(reference)
        LOG.info("ExpressionsOfTypeProcessor: $message")
        testLog { "Downgrade to plain text search: $message" }

        tasks.clear()
        scopesToUsePlainSearch.clear()
        possibleMatchesInScopeHandler(searchScope)
    }

    private fun checkPsiClass(psiClass: PsiClass): Boolean {
        // we don't filter out private classes because we can inherit public class from private inside the same visibility scope
        if (psiClass.isLocal()) {
            return false
        }

        val qualifiedName = runReadAction { psiClass.qualifiedName }
        return !qualifiedName.isNullOrEmpty()
    }

    private fun addNonKotlinClassToProcess(classToSearch: PsiClass) {
        if (!checkPsiClass(classToSearch)) {
            return
        }

        addClassToProcess(classToSearch)
    }

    private fun addClassToProcess(cls: PsiElement) {
        data class ProcessClassUsagesTask(val classToSearch: PsiElement) : Task {
            override fun perform() {
                val debugInfo: StringBuilder? = if (isUnitTestMode()) StringBuilder() else null
                testLog { "Searched references to ${logPresentation(classToSearch)}" }
                debugInfo?.apply { append("Searched references to ").append(logPresentation(classToSearch)) }
                val scope = project.everythingScopeExcludeFileTypes(XmlFileType.INSTANCE) // ignore usages in XML - they don't affect us
                searchReferences(classToSearch, scope) { reference ->
                    val element = reference.element
                    val language = element.language
                    debugInfo?.apply { append(", found reference element [$language]: $element") }
                    val wasProcessed = when (language) {
                        KotlinLanguage.INSTANCE -> processClassUsageInKotlin(element, debugInfo)
                        JavaLanguage.INSTANCE -> processClassUsageInJava(element)
                        else -> {
                            when (language.displayName) {
                                "Groovy" -> {
                                    processClassUsageInLanguageWithPsiClass(element)
                                    true
                                }
                                "Scala" -> false
                                "Clojure" -> false
                                else -> {
                                    // If there's no PsiClass - consider processed
                                    element.getParentOfType<PsiClass>(true) == null
                                }
                            }
                        }
                    }

                    if (wasProcessed) return@searchReferences true

                    if (mode != Mode.ALWAYS_SMART) {
                        downShiftToPlainSearch(reference)
                        return@searchReferences false
                    }

                    throw KotlinExceptionWithAttachments("Unsupported reference")
                        .withPsiAttachment("reference.txt", element)
                        .withAttachment("diagnostic_message.txt", getFallbackDiagnosticsMessage(reference, debugInfo))
                }

                // we must use plain search inside our class (and inheritors) because implicit 'this' can happen anywhere
                ((classToSearch as? KtLightClass)?.kotlinOrigin ?: classToSearch as? KtElement)?.let { usePlainSearch(it) }
            }
        }
        addTask(ProcessClassUsagesTask(cls))
    }

    private fun getFallbackDiagnosticsMessage(reference: PsiReference, debugInfo: StringBuilder? = null): String {
        val element = reference.element
        val document = PsiDocumentManager.getInstance(project).getDocument(element.containingFile)
        val lineAndCol = PsiDiagnosticUtils.offsetToLineAndColumn(document, element.startOffset)
        return "Unsupported reference: '${element.text}' in ${element.containingFile.virtualFile} [${element.language}] " +
                "line ${lineAndCol.line} column ${lineAndCol.column}${debugInfo?.let {" .$it"} ?: ""}"
    }

    private enum class ReferenceProcessor(val handler: (ExpressionsOfTypeProcessor, PsiReference) -> Boolean) {
        CallableOfOurType(ExpressionsOfTypeProcessor::processReferenceToCallableOfOurType),

        ProcessLambdasInCalls({ processor, reference ->
                                  (reference.element as? KtReferenceExpression)?.let { processor.processLambdasForCallableReference(it) }
                                  true
                              })
    }

    private class StaticMemberRequestResultProcessor(val psiMember: PsiMember, classes: List<PsiClass>) :
        RequestResultProcessor(psiMember) {
        val possibleClassesNames: Set<String> = runReadAction { classes.map { it.qualifiedName }.filterNotNullTo(HashSet()) }

        override fun processTextOccurrence(element: PsiElement, offsetInElement: Int, consumer: Processor<in PsiReference>): Boolean {
            when (element) {
                is KtQualifiedExpression -> {
                    val selectorExpression = element.selectorExpression ?: return true
                    val selectorReference = element.findReferenceAt(selectorExpression.startOffsetInParent)

                    val references = when (selectorReference) {
                        is PsiMultiReference -> selectorReference.references.toList()
                        else -> listOf(selectorReference)
                    }.filterNotNull()

                    for (ref in references) {
                        ProgressManager.checkCanceled()

                        if (ref.isReferenceTo(psiMember)) {
                            consumer.process(ref)
                        }
                    }
                }

                is KtImportDirective -> {
                    if (element.isAllUnder) {
                        val fqName = element.importedFqName?.asString()
                        if (fqName != null && fqName in possibleClassesNames) {
                            val ref = element.importedReference
                                ?.getQualifiedElementSelector()
                                ?.references
                                ?.firstOrNull()
                            if (ref != null) {
                                consumer.process(ref)
                            }
                        }
                    }
                }
            }

            return true
        }
    }

    private fun classUseScope(psiClass: PsiClass) = runReadAction {
        if (!psiClass.isValid) {
            throw ProcessCanceledException()
        }

        psiClass.containingFile?.useScope() ?: psiClass.useScope()
    }

    private fun addStaticMemberToProcess(psiMember: PsiMember, scope: SearchScope, processor: ReferenceProcessor) {
        val declarationClass = runReadAction { psiMember.containingClass } ?: return
        val declarationName = runReadAction { psiMember.name } ?: return
        if (declarationName.isEmpty()) return

        data class ProcessStaticCallableUsagesTask(val psiMember: PsiMember, val scope: SearchScope) : Task {
            override fun perform() {
                // This class will look through the whole hierarchy anyway, so shouldn't be a big overhead here
                val inheritanceClasses = ClassInheritorsSearch.search(
                    declarationClass,
                    classUseScope(declarationClass),
                    true, true, false
                ).findAll()

                val classes = (inheritanceClasses + declarationClass).filter { it !is KtLightClass }

                val searchRequestCollector = SearchRequestCollector(SearchSession())
                val resultProcessor = StaticMemberRequestResultProcessor(psiMember, classes)

                val memberName = runReadAction { psiMember.name }
                for (klass in classes) {
                    val request = runReadAction { klass.name } + "." + declarationName

                    testLog { "Searched references to static $memberName in non-Java files by request $request" }
                    searchRequestCollector.searchWord(
                        request,
                        classUseScope(klass).intersectWith(scope), UsageSearchContext.IN_CODE, true, psiMember, resultProcessor
                    )

                    val qualifiedName = runReadAction { klass.qualifiedName }
                    if (qualifiedName != null) {
                        val importAllUnderRequest = "$qualifiedName.*"

                        testLog { "Searched references to static $memberName in non-Java files by request $importAllUnderRequest" }
                        searchRequestCollector.searchWord(
                            importAllUnderRequest,
                            classUseScope(klass).intersectWith(scope), UsageSearchContext.IN_CODE, true, psiMember, resultProcessor
                        )
                    }
                }

                PsiSearchHelper.getInstance(project).processRequests(searchRequestCollector) { reference ->
                    if (reference.element.parents.any { it is KtImportDirective }) {
                        // Found declaration in import - process all file with an ordinal reference search
                        val containingFile = reference.element.containingFile
                        addCallableDeclarationToProcess(psiMember, LocalSearchScope(containingFile), processor)

                        true
                    } else {
                        val processed = processor.handler(this@ExpressionsOfTypeProcessor, reference)
                        if (!processed) { // we don't know how to handle this reference and down-shift to plain search
                            downShiftToPlainSearch(reference)
                        }

                        true
                    }
                }
            }
        }

        addTask(ProcessStaticCallableUsagesTask(psiMember, scope))
    }

    private fun addCallableDeclarationToProcess(declaration: PsiElement, scope: SearchScope, processor: ReferenceProcessor) {
        if (scope !is LocalSearchScope && declaration is PsiMember &&
            (declaration.modifierList?.hasModifierProperty(PsiModifier.STATIC) == true)
        ) {
            addStaticMemberToProcess(declaration, scope, processor)
            return
        }

        data class ProcessCallableUsagesTask(val declaration: PsiElement, val scope: SearchScope) : Task {
            override fun perform() {
                if (scope is LocalSearchScope) {
                    testLog { runReadAction { "Searched imported static member $declaration in ${scope.scope.toList()}" } }
                } else {
                    testLog { runReadAction { "Searched references to ${logPresentation(declaration)} in non-Java files" } }
                }

                val searchParameters = KotlinReferencesSearchParameters(
                    declaration, scope, kotlinOptions = KotlinReferencesSearchOptions(searchNamedArguments = false)
                )
                searchReferences(searchParameters) { reference ->
                    val processed = processor.handler(this@ExpressionsOfTypeProcessor, reference)
                    if (!processed) { // we don't know how to handle this reference and down-shift to plain search
                        downShiftToPlainSearch(reference)
                    }
                    processed
                }
            }
        }
        addTask(ProcessCallableUsagesTask(declaration, scope))
    }

    private fun addPsiMemberTask(member: PsiMember) {
        if (!member.isPrivate() && !member.isLocal()) {
            addCallableDeclarationOfOurType(member)
        }
    }

    private fun addCallableDeclarationOfOurType(declaration: PsiElement) {
        addCallableDeclarationToProcess(declaration, searchScope.restrictToKotlinSources(), ReferenceProcessor.CallableOfOurType)
    }

    /**
     * Process references to declaration which has parameter of functional type with our class used inside
     */
    private fun addCallableDeclarationToProcessLambdasInCalls(declaration: PsiElement) {
        // we don't need to search usages of declarations in Java because Java doesn't have implicitly typed declarations so such usages cannot affect Kotlin code
        val scope = GlobalSearchScope.projectScope(project).excludeFileTypes(project, JavaFileType.INSTANCE, XmlFileType.INSTANCE)
        addCallableDeclarationToProcess(declaration, scope, ReferenceProcessor.ProcessLambdasInCalls)
    }

    /**
     * Process reference to declaration whose type is our class (or our class used anywhere inside that type)
     */
    private fun processReferenceToCallableOfOurType(reference: PsiReference) = when (reference.element.language) {
        KotlinLanguage.INSTANCE -> {
            if (reference is KtDestructuringDeclarationReference) {
                // declaration usage in form of destructuring declaration entry
                addCallableDeclarationOfOurType(reference.element)
            } else {
                (reference.element as? KtReferenceExpression)?.let { processSuspiciousExpression(it) }
            }
            true
        }

        else -> false // reference in unknown language - we don't know how to handle it
    }

    private fun addSamInterfaceToProcess(psiClass: PsiClass) {
        if (!checkPsiClass(psiClass)) {
            return
        }

        data class ProcessSamInterfaceTask(val psiClass: PsiClass) : Task {
            override fun perform() {
                val scope = GlobalSearchScope.projectScope(project).excludeFileTypes(project, KotlinFileType.INSTANCE, XmlFileType.INSTANCE)
                testLog { "Searched references to ${logPresentation(psiClass)} in non-Kotlin files" }
                searchReferences(psiClass, scope) { reference ->
                    // reference in some JVM language can be method parameter (but we don't know)
                    if (reference.element.language != JavaLanguage.INSTANCE) {
                        downShiftToPlainSearch(reference)
                        return@searchReferences false
                    }

                    // check if the reference is method parameter type
                    val parameter = ((reference as? PsiJavaCodeReferenceElement)?.parent as? PsiTypeElement)?.parent as? PsiParameter
                    val method = parameter?.declarationScope as? PsiMethod
                    if (method != null) {
                        addCallableDeclarationToProcessLambdasInCalls(method)
                    }
                    true
                }
            }
        }
        addTask(ProcessSamInterfaceTask(psiClass))
    }

    private fun processClassUsageInKotlin(element: PsiElement, debugInfo: StringBuilder?): Boolean {
        //TODO: type aliases

        when (element) {
            is KtReferenceExpression -> {
                val elementParent = element.parent
                debugInfo?.apply { append(", elementParent: $elementParent") }
                when (elementParent) {
                    is KtUserType -> { // usage in type
                        val userTypeParent = elementParent.parent
                        if (userTypeParent is KtUserType && userTypeParent.qualifier == elementParent) {
                            return true // type qualifier
                        }

                        return processClassUsageInUserType(elementParent)
                    }

                    is KtCallExpression -> {
                        debugInfo?.apply { append(", KtCallExpression condition: ${element == elementParent.calleeExpression}") }
                        if (element == elementParent.calleeExpression) {  // constructor or invoke operator invocation
                            processSuspiciousExpression(element)
                            return true
                        }
                    }

                    is KtContainerNode -> {
                        if (elementParent.node.elementType == KtNodeTypes.LABEL_QUALIFIER) {
                            return true // this@ClassName - it will be handled anyway because members and extensions are processed with plain search
                        }
                    }

                    is KtQualifiedExpression -> {
                        // <class name>.memberName or some.<class name>.memberName or some.<class name>::class
                        if (element == elementParent.receiverExpression ||
                            elementParent.parent is KtQualifiedExpression ||
                            elementParent.parent is KtClassLiteralExpression
                        ) {
                            return true // companion object member or static member access - ignore it
                        }
                    }

                    is KtCallableReferenceExpression -> {
                        when (element) {
                            elementParent.receiverExpression -> { // usage in receiver of callable reference (before "::") - ignore it
                                return true
                            }

                            elementParent.callableReference -> { // usage after "::" in callable reference - should be reference to constructor of our class
                                processSuspiciousExpression(element)
                                return true
                            }
                        }
                    }

                    is KtClassLiteralExpression -> {
                        if (element == elementParent.receiverExpression) { // <class name>::class
                            processSuspiciousExpression(element)
                            return true
                        }
                    }
                }

                if (element.getStrictParentOfType<KtImportDirective>() != null) return true // ignore usage in import

                processSuspiciousExpression(element)
                return true
            }

            is KDocName -> return true // ignore usage in doc-comment
        }

        return false // unsupported type of reference
    }

    private fun processClassUsageInUserType(userType: KtUserType): Boolean {
        val typeRef = userType.parents.lastOrNull { it is KtTypeReference }
        when (val typeRefParent = typeRef?.parent) {
            is KtTypeAlias -> {
                addClassToProcess(typeRefParent)
                return true
            }
            is KtCallableDeclaration -> {
                when (typeRef) {
                    typeRefParent.typeReference -> { // usage in type of callable declaration
                        addCallableDeclarationOfOurType(typeRefParent)

                        if (typeRefParent is KtParameter) { //TODO: what if functional type is declared with "FunctionN<...>"?
                            val usedInsideFunctionalType = userType.parents.takeWhile { it != typeRef }.any { it is KtFunctionType }
                            if (usedInsideFunctionalType) {
                                val function = (typeRefParent.parent as? KtParameterList)?.parent as? KtFunction
                                if (function != null) {
                                    addCallableDeclarationOfOurType(function)
                                }
                            }
                        }

                        return true
                    }

                    typeRefParent.receiverTypeReference -> { // usage in receiver type of callable declaration
                        // we must use plain search inside extensions because implicit 'this' can happen anywhere
                        usePlainSearch(typeRefParent)
                        return true
                    }
                }
            }

            is KtTypeProjection -> { // usage in type arguments of a call
                val callExpression = (typeRefParent.parent as? KtTypeArgumentList)?.parent as? KtCallExpression
                if (callExpression != null) {
                    processSuspiciousExpression(callExpression)
                    return true
                }
            }

            is KtConstructorCalleeExpression -> { // super-class name in the list of bases
                val parent = typeRefParent.parent
                if (parent is KtSuperTypeCallEntry) {
                    addClassToProcess((parent.parent as KtSuperTypeList).parent as KtClassOrObject)
                    return true
                }
            }

            is KtSuperTypeListEntry -> { // super-interface name in the list of bases
                if (typeRef == typeRefParent.typeReference) {
                    addClassToProcess((typeRefParent.parent as KtSuperTypeList).parent as KtClassOrObject)
                    return true
                }
            }

            is KtIsExpression -> { // <expr> is <class name>
                val scopeOfPossibleSmartCast = typeRefParent.getParentOfType<KtDeclarationWithBody>(true)
                scopeOfPossibleSmartCast?.let { usePlainSearch(it) }
                return true
            }

            is KtWhenConditionIsPattern -> { // "is <class name>" or "!is <class name>" in when
                val whenEntry = typeRefParent.parent as KtWhenEntry
                if (typeRefParent.isNegated) {
                    val whenExpression = whenEntry.parent as KtWhenExpression
                    val entriesAfter = whenExpression.entries.dropWhile { it != whenEntry }.drop(1)
                    entriesAfter.forEach { usePlainSearch(it) }
                } else {
                    usePlainSearch(whenEntry)
                }
                return true
            }

            is KtBinaryExpressionWithTypeRHS -> { // <expr> as <class name>
                processSuspiciousExpression(typeRefParent)
                return true
            }

            is KtTypeParameter -> { // <expr> as `<reified T : ClassName>`
                addClassToProcess(typeRefParent)
                return true
            }

            is KtTypeConstraint -> {
                val typeParameter = typeRefParent.subjectTypeParameterName?.mainReference?.resolve() as? KtTypeParameter ?: return true
                addClassToProcess(typeParameter)
                return true
            }
        }

        return false // unsupported case
    }

    private fun processClassUsageInJava(element: PsiElement): Boolean {
        if (element !is PsiJavaCodeReferenceElement) return true // meaningless reference from Java

        var prev = element
        ParentsLoop@
        for (parent in element.parents) {
            when (parent) {
                is PsiCodeBlock,
                is PsiExpression ->
                    break@ParentsLoop // ignore local usages

                is PsiMethod -> {
                    if (prev == parent.returnTypeElement) { // usage in return type of a method
                        addPsiMemberTask(parent)
                    }
                    break@ParentsLoop
                }

                is PsiField -> {
                    if (prev == parent.typeElement) { // usage in type of a field
                        addPsiMemberTask(parent)
                    }
                    break@ParentsLoop
                }

                is PsiReferenceList -> { // usage in extends/implements list
                    if (parent.role == PsiReferenceList.Role.EXTENDS_LIST || parent.role == PsiReferenceList.Role.IMPLEMENTS_LIST) {
                        val psiClass = parent.parent as PsiClass
                        addNonKotlinClassToProcess(psiClass)
                    }
                    break@ParentsLoop
                }

                //TODO: if Java parameter has Kotlin functional type then we should process method usages
                is PsiParameter -> {
                    if (prev == parent.typeElement) { // usage in parameter type - check if the method is in SAM interface
                        processParameterInSamClass(parent)
                    }
                    break@ParentsLoop
                }
            }

            prev = parent
        }

        return true
    }

    private fun processClassUsageInLanguageWithPsiClass(element: PsiElement) {
        fun checkReferenceInTypeElement(typeElement: PsiTypeElement?, element: PsiElement): Boolean {
            val typeTextRange = typeElement?.textRange
            return (typeTextRange != null && element.textRange in typeTextRange)
        }

        fun processParameter(parameter: PsiParameter): Boolean {
            if (checkReferenceInTypeElement(parameter.typeElement, element)) {
                processParameterInSamClass(parameter)
                return true
            }

            return false
        }

        fun processMethod(method: PsiMethod): Boolean {
            if (checkReferenceInTypeElement(method.returnTypeElement, element)) {
                addPsiMemberTask(method)
                return true
            }

            val parameters = method.parameterList.parameters
            for (parameter in parameters) {
                if (processParameter(parameter)) {
                    return true
                }
            }

            return false
        }

        fun processField(field: PsiField): Boolean {
            if (checkReferenceInTypeElement(field.typeElement, element)) {
                addPsiMemberTask(field)
                return true
            }

            return false
        }

        fun processClass(psiClass: PsiClass) {
            if (!checkPsiClass(psiClass)) {
                return
            }

            val elementTextRange: TextRange? = element.textRange
            if (elementTextRange != null) {
                val superList = listOf(psiClass.extendsList, psiClass.implementsList)
                for (psiReferenceList in superList) {
                    val superListRange: TextRange? = psiReferenceList?.textRange
                    if (superListRange != null && elementTextRange in superListRange) {
                        addNonKotlinClassToProcess(psiClass)
                        return
                    }
                }
            }

            if (psiClass.fields.any { processField(it) }) {
                return
            }

            if (psiClass.methods.any { processMethod(it) }) {
                return
            }

            return
        }

        val psiClass = element.getParentOfType<PsiClass>(true)
        if (psiClass != null) {
            processClass(psiClass)
        }
    }

    private fun processParameterInSamClass(psiParameter: PsiParameter): Boolean {
        val method = psiParameter.declarationScope as? PsiMethod ?: return false

        if (method.hasModifierProperty(PsiModifier.ABSTRACT)) {
            val psiClass = method.containingClass
            if (psiClass != null) {
                testLog { "Resolved java class to descriptor: ${psiClass.qualifiedName}" }

                if (LambdaUtil.isFunctionalClass(psiClass)) {
                    addSamInterfaceToProcess(psiClass)
                    return true
                }
            }
        }

        return false
    }

    /**
     * Process expression which may have type of our class (or our class used anywhere inside that type)
     */
    private fun processSuspiciousExpression(expression: KtExpression) {
        var inScope = expression in searchScope
        var affectedScope: PsiElement = expression
        ParentsLoop@
        for (element in expression.parentsWithSelf) {
            affectedScope = element
            if (element !is KtExpression) continue

            if (searchScope is LocalSearchScope) { // optimization to not check every expression
                inScope = inScope && element in searchScope
            }
            if (inScope) {
                possibleMatchHandler(element)
            }

            when (val parent = element.parent) {
                is KtDestructuringDeclaration -> { // "val (x, y) = <expr>"
                    processSuspiciousDeclaration(parent)
                    break@ParentsLoop
                }

                is KtDeclarationWithInitializer -> { // "val x = <expr>" or "fun f() = <expr>"
                    if (element == parent.initializer) {
                        processSuspiciousDeclaration(parent)
                    }
                    break@ParentsLoop
                }

                is KtContainerNode -> {
                    if (parent.node.elementType == KtNodeTypes.LOOP_RANGE) { // "for (x in <expr>) ..."
                        val forExpression = parent.parent as KtForExpression
                        (forExpression.destructuringDeclaration ?: forExpression.loopParameter as KtDeclaration?)?.let {
                            processSuspiciousDeclaration(it)
                        }
                        break@ParentsLoop
                    }
                }

                is KtPropertyDelegate -> {
                    (parent.parent as? KtProperty)?.let {
                        addCallableDeclarationOfOurType(it)
                    }
                    break@ParentsLoop
                }
            }

            if (!element.mayTypeAffectAncestors()) break
        }

        // use plain search in all lambdas and anonymous functions inside because they parameters or receiver can be implicitly typed with our class
        usePlainSearchInLambdas(affectedScope)
    }

    private fun processLambdasForCallableReference(expression: KtReferenceExpression) {
        //TODO: receiver?
        usePlainSearchInLambdas(expression.parent)
    }

    /**
     * Process declaration which may have implicit type of our class (or our class used anywhere inside that type)
     */
    private fun processSuspiciousDeclaration(declaration: KtDeclaration) {
        if (declaration is KtDestructuringDeclaration) {
            declaration.entries.forEach { processSuspiciousDeclaration(it) }
        } else {
            if (!isImplicitlyTyped(declaration)) return

            testLog { "Checked type of ${logPresentation(declaration)}" }

            if (containsTypeOrDerivedInside(declaration)) {
                addCallableDeclarationOfOurType(declaration)
            }
        }
    }

    private fun usePlainSearchInLambdas(scope: PsiElement) {
        scope.forEachDescendantOfType<KtFunction> {
            if (it.nameIdentifier == null) {
                usePlainSearch(it)
            }
        }
    }

    private fun usePlainSearch(scope: KtElement) {
        runReadAction {
            if (!scope.isValid) return@runReadAction

            val file = scope.containingKtFile
            val restricted = LocalSearchScope(scope).intersectWith(searchScope)
            if (restricted is LocalSearchScope) {
                ScopeLoop@
                for (element in restricted.scope) {
                    val prevElements = scopesToUsePlainSearch.getOrPut(file) { ArrayList() }
                    for ((index, prevElement) in prevElements.withIndex()) {
                        if (!prevElement.isValid) continue@ScopeLoop
                        if (prevElement.isAncestor(element, strict = false)) continue@ScopeLoop
                        if (element.isAncestor(prevElement)) {
                            prevElements[index] = element
                            continue@ScopeLoop
                        }
                    }
                    prevElements.add(element)
                }
            } else {
                assert(SearchScope.isEmptyScope(restricted))
            }

        }
    }

    //TODO: code is quite similar to PartialBodyResolveFilter.isValueNeeded
    private fun KtExpression.mayTypeAffectAncestors(): Boolean {
        when (val parent = this.parent) {
            is KtBlockExpression -> {
                return this == parent.statements.last() && parent.mayTypeAffectAncestors()
            }

            is KtDeclarationWithBody -> {
                if (this == parent.bodyExpression) {
                    return !parent.hasBlockBody() && !parent.hasDeclaredReturnType()
                }
            }

            is KtContainerNode -> {
                val grandParent = parent.parent
                return when (parent.node.elementType) {
                    KtNodeTypes.CONDITION, KtNodeTypes.BODY -> false
                    KtNodeTypes.THEN, KtNodeTypes.ELSE -> (grandParent as KtExpression).mayTypeAffectAncestors()
                    KtNodeTypes.LOOP_RANGE, KtNodeTypes.INDICES -> true
                    else -> true // something else unknown
                }
            }
        }
        return true // we don't know
    }

    private fun isImplicitlyTyped(declaration: KtDeclaration): Boolean {
        return when (declaration) {
            is KtFunction -> !declaration.hasDeclaredReturnType()
            is KtVariableDeclaration -> declaration.typeReference == null
            is KtParameter -> declaration.typeReference == null
            else -> false
        }
    }

    private fun searchReferences(element: PsiElement, scope: SearchScope, processor: (PsiReference) -> Boolean) {
        val parameters = ReferencesSearch.SearchParameters(element, scope, false)
        searchReferences(parameters, processor)
    }

    private fun searchReferences(parameters: ReferencesSearch.SearchParameters, processor: (PsiReference) -> Boolean) {
        ReferencesSearch.search(parameters).forEach(Processor { ref ->
            ProgressManager.checkCanceled()
            runReadAction {
                if (ref.element.isValid) {
                    processor(ref)
                } else {
                    true
                }
            }
        })
    }
}
