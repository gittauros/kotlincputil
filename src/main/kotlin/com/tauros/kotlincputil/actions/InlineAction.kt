/*
 * TestAction.kt
 * Copyright 2023 Qunhe Tech, all rights reserved.
 * Qunhe PROPRIETARY/CONFIDENTIAL, any form of usage is subject to approval.
 */

package com.tauros.kotlincputil.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.openapi.ui.Messages
import com.intellij.psi.*
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.kotlin.idea.base.utils.fqname.getKotlinFqName
import org.jetbrains.kotlin.idea.refactoring.hostEditor
import org.jetbrains.kotlin.nj2k.postProcessing.resolve
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.children
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection


/**
 * @author tauros
 * 2023/9/20
 */
class InlineAction : AnAction() {
    val keepPackagePrefixes = listOf(
        "java",
        "kotlin",
    )

    override fun actionPerformed(e: AnActionEvent) {
        val psiManager = PsiManager.getInstance(e.project!!)
//        val projectScope = ProjectScopeBuilder.getInstance(e.project!!).buildProjectScope()
//        val file = FilenameIndex.getVirtualFilesByName("Main.kt", projectScope).first()
//        val psiFile = psiManager.findFile(file)
//        psiFile?.accept(visitor)
        val editor = e.dataContext.hostEditor
        if (editor !is EditorImpl) {
            return
        }
        val file = editor.virtualFile
        val psiFile = psiManager.findFile(file)
        if (psiFile !is KtFile) {
            return
        }
        val fileSet = mutableSetOf(psiFile)
        val elementSet = mutableSetOf<PsiElement>()
        val fileQueue = ArrayDeque<KtFile>()
        val uncertainImports = mutableSetOf<String>()
        val uncertainElements = ArrayDeque<PsiElement>()
        val keepImports = mutableSetOf<String>()
        val refElements = ArrayDeque<PsiElement>()

        psiFile.importDirectives
            .map { it.importedFqName.toString() }
            .forEach(uncertainImports::add)
        val collectQueue = ArrayDeque<PsiElement>()
        collectQueue.add(psiFile)
        val collectExpression: (PsiElement) -> Unit = { resolved ->
            when (resolved) {
                is KtConstructor<*> -> {
                    var iter = resolved.parent;
                    while (iter !is KtClass) {
                        iter = iter.parent
                    }
                    val ktFile = iter.parent
                    val ktClass = iter
                    if (ktFile is KtFile && ktClass !in elementSet) {
                        uncertainElements.addLast(ktClass)
                        elementSet.add(ktClass)
                        if (ktFile !in fileSet) {
                            fileSet.add(ktFile)
                            fileQueue.addLast(ktFile)
                        }
                        collectQueue.addLast(ktClass)
                    }
                }

                is KtFunction -> {
                    val ktFile = resolved.parent
                    if (ktFile is KtFile && resolved !in elementSet) {
                        uncertainElements.addLast(resolved)
                        elementSet.add(resolved)
                        if (ktFile !in fileSet) {
                            fileSet.add(ktFile)
                            fileQueue.addLast(ktFile)
                        }
                        collectQueue.addLast(resolved)
                    }
                }

                is PsiClass -> {
                    if (resolved !in elementSet) {
                        elementSet.add(resolved)
                        uncertainElements.addLast(resolved)
                    }
                }

                else -> {
                    var iter: PsiElement? = resolved
                    while (iter != null && iter !is PsiClass) {
                        iter = iter.parent
                    }
                    if (iter is PsiClass && iter !in elementSet) {
                        elementSet.add(iter)
                        uncertainElements.addLast(iter)
                    }
                }
            }
        }
        val doubleCheckElements = ArrayDeque<PsiElement>()
        val refFiles = mutableSetOf<KtFile>()
        while (collectQueue.isNotEmpty()) {
            visitAllKtElements(collectQueue.removeFirst(), collectExpression)
            while (fileQueue.isNotEmpty()) {
                val curFile = fileQueue.removeFirst()
                curFile.importDirectives
                    .map { it.importedFqName.toString() }
                    .forEach(uncertainImports::add)
            }
            while (uncertainElements.isNotEmpty()) {
                val curUncertain = uncertainElements.removeLast()
                val name = curUncertain.getKotlinFqName().toString()
                if (name in uncertainImports) {
                    if (keepPackagePrefixes.any { name.startsWith(it) }) {
                        keepImports.add(name)
                    } else {
                        refElements.addFirst(curUncertain)
                        var topFile: PsiElement? = curUncertain
                        while (topFile != null && topFile !is KtFile) {
                            topFile = topFile.parent
                        }
                        if (topFile is KtFile) {
                            refFiles.add(topFile)
                        }
                    }
                } else {
                    doubleCheckElements.addLast(curUncertain)
                }
            }
        }
        doubleCheckElements.filter {
            var eleFile: PsiElement? = it
            while (eleFile != null && eleFile !is KtFile) {
                eleFile = eleFile.parent
            }
            eleFile is KtFile && eleFile in refFiles
        }.forEach(refElements::add)
        val inlinedList = refElements.toList().sortedBy { it.getKotlinFqName().toString() }
        val code = buildString {
            for (child in psiFile.children) {
                if (child is KtPackageDirective) continue
                if (child is KtImportList) {
                    for (import in keepImports) {
                        append("import ")
                        append(import)
                        append('\n')
                    }
                    append('\n')
                    append(buildString {
                        for (inlined in inlinedList) {
                            makeElementText(inlined)
                            append('\n')
                        }
                    }.trimEnd('\n'))
                } else {
                    makeElementText(child)
                }
            }
        }
        val selection = StringSelection(code)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
        Messages.showMessageDialog(
            e.project!!, "copied!", "inlined code generated",
            Messages.getInformationIcon()
        );
    }

    private fun StringBuilder.makeElementText(element: PsiElement) {
        if (element is KtFunction || element is KtClass || element is KtProperty) {
            var allowAppend = false
            for (child in element.node.children().toImmutableList()) {
                when (child) {
                    is PsiWhiteSpace -> {
                        if (allowAppend) {
                            append((child as PsiWhiteSpace).text)
                        }
                    }

                    is PsiComment -> continue
                    else -> {
                        append(child.text)
                        allowAppend = true
                    }
                }
            }
            append('\n')
        } else {
            append(element.text)
        }
    }

    private fun visitAllKtElements(
        psiElement: PsiElement, visitExpression: (PsiElement) -> Unit
    ) {
        val visitor = object : KtVisitorVoid() {
            override fun visitElement(element: PsiElement) {
                when (element) {
                    is KtImportList -> return
                    is KtCallExpression -> {
                        val callee = element.calleeExpression
                        if (callee is KtReferenceExpression) {
                            callee.resolve()?.also(visitExpression)
                        }
                        element.children.forEach { it.accept(this) }
                    }

                    is KtReferenceExpression -> {
                        element.resolve()?.also(visitExpression)
                        element.children.forEach { it.accept(this) }
                    }

                    else -> element.children.forEach { it.accept(this) }
                }
            }
        }
        psiElement.accept(visitor)
    }
}