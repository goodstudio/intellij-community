// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.k2.refactoring.move.processor

import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFileHandler
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesProcessor
import com.intellij.refactoring.util.MoveRenameUsageInfo
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.idea.core.getFqNameWithImplicitPrefix
import org.jetbrains.kotlin.idea.core.getImplicitPackagePrefix
import org.jetbrains.kotlin.idea.k2.refactoring.move.descriptor.K2MoveOperationDescriptor
import org.jetbrains.kotlin.idea.k2.refactoring.move.descriptor.K2MoveTargetDescriptor
import org.jetbrains.kotlin.psi.CopyablePsiUserDataProperty
import org.jetbrains.kotlin.psi.KtFile

internal class K2MoveFilesOrDirectoriesRefactoringProcessor(private val descriptor: K2MoveOperationDescriptor.Files) : MoveFilesOrDirectoriesProcessor(
    descriptor.project,
    descriptor.sourceElements.toTypedArray(),
    runWriteAction { descriptor.moveDescriptors.first().target.getOrCreateTarget(descriptor.dirStructureMatchesPkg) as PsiDirectory }, // TODO how to do multi target move?
    descriptor.searchReferences,
    descriptor.searchInComments,
    descriptor.searchForText,
    descriptor.moveCallBack,
    Runnable { }
) {
    private val moveTarget: K2MoveTargetDescriptor.Directory by lazy {
        descriptor.moveDescriptors.first().target
    }

    private fun setForcedPackageIfNeeded() {
        val implicitPackagePrefix = moveTarget.baseDirectory.getImplicitPackagePrefix()?.takeIf { !it.isRoot } ?: return
        if (moveTarget.pkgName.startsWith(implicitPackagePrefix) && !descriptor.isMoveToExplicitPackage) return
        moveTarget.baseDirectory.forcedTargetPackage = moveTarget.pkgName
    }

    private fun cleanForcedPackage() {
        moveTarget.baseDirectory.forcedTargetPackage = null
    }

    private fun <T> withForcedPackageIfNeeded(action: () -> T): T {
        setForcedPackageIfNeeded()
        try {
            return action()
        } finally {
            cleanForcedPackage()
        }
    }

    override fun performRefactoring(_usages: Array<out UsageInfo?>) {
        withForcedPackageIfNeeded {
            super.performRefactoring(_usages)
        }
    }

    /**
     * Setting up a forced package is necessary for correct conflict checking.
     * It's done separately because in case of cancellation [performRefactoring] is not called and uncleaned user data would leak.
     */
    override fun preprocessUsages(refUsages: Ref<Array<out UsageInfo?>?>): Boolean {
        return withForcedPackageIfNeeded {
            super.preprocessUsages(refUsages)
        }
    }
}

class K2MoveFilesHandler : MoveFileHandler() {
    /**
     * Stores whether a package of a file needs to be updated.
     */
    private var KtFile.packageNeedsUpdate: Boolean? by CopyablePsiUserDataProperty(Key.create("PACKAGE_NEEDS_UPDATE"))

    override fun canProcessElement(element: PsiFile): Boolean {
        return element is KtFile
    }

    /**
     * Before moving files that need their package to be updated are marked. When a package doesn't match the directory structure of the
     * project, we don't try to update the package.
     */
    fun markRequiresUpdate(file: KtFile) {
        file.packageNeedsUpdate = true
    }

    fun needsUpdate(file: KtFile): Boolean = file.containingDirectory?.getFqNameWithImplicitPrefix() == file.packageFqName

    override fun findUsages(
        psiFile: PsiFile,
        newParent: PsiDirectory,
        searchInComments: Boolean,
        searchInNonJavaFiles: Boolean
    ): List<UsageInfo> {
        require(psiFile is KtFile) { "Can only find usages from Kotlin files" }
        return if (needsUpdate(psiFile) && ProjectFileIndex.getInstance(psiFile.project).isInSourceContent(newParent.virtualFile)) {
            markRequiresUpdate(psiFile)
            val newPkgName = newParent.getPossiblyForcedPackageFqName()
            psiFile.findUsages(searchInComments, searchInNonJavaFiles, newPkgName)
        } else emptyList() // don't need to update usages when package doesn't change
    }

    override fun detectConflicts(
        conflicts: MultiMap<PsiElement, String>,
        elementsToMove: Array<out PsiElement>,
        usages: Array<out UsageInfo>,
        targetDirectory: PsiDirectory
    ) {
        val targetPkgFqn = targetDirectory.getPossiblyForcedPackageFqName()
        conflicts.putAllValues(findAllMoveConflicts(
            elementsToMove.filterIsInstance<KtFile>().toSet(),
            targetDirectory,
            targetPkgFqn,
            usages.filterIsInstance<MoveRenameUsageInfo>()
        ))
    }

    override fun prepareMovedFile(file: PsiFile, moveDestination: PsiDirectory, oldToNewMap: MutableMap<PsiElement, PsiElement>) {
        require(file is KtFile) { "Can only prepare Kotlin files" }
        val destinationPackage = moveDestination.getPossiblyForcedPackageFqName()
        if (file.packageNeedsUpdate == true && file.packageFqName != destinationPackage) {
            file.updatePackageDirective(destinationPackage)
        }
        file.packageNeedsUpdate = null
        oldToNewMap[file] = file
        val declarations = file.allDeclarationsToUpdate
        declarations.forEach { oldToNewMap[it] = it } // to pass files that are moved through MoveFileHandler API
    }

    override fun updateMovedFile(file: PsiFile) {}

    @OptIn(KaAllowAnalysisOnEdt::class)
    override fun retargetUsages(usageInfos: List<UsageInfo>, oldToNewMap: Map<PsiElement, PsiElement>): Unit = allowAnalysisOnEdt {
        retargetUsagesAfterMove(usageInfos.toList(), oldToNewMap)
    }
}
