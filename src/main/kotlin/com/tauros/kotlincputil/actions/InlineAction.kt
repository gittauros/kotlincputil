package com.tauros.kotlincputil.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.openapi.ui.Messages
import com.intellij.psi.*
import kotlinx.collections.immutable.toImmutableList
import org.jetbrains.kotlin.idea.base.utils.fqname.getKotlinFqName
import org.jetbrains.kotlin.idea.refactoring.hostEditor
import org.jetbrains.kotlin.idea.references.resolveMainReferenceToDescriptors
import org.jetbrains.kotlin.load.kotlin.toSourceElement
import org.jetbrains.kotlin.nj2k.postProcessing.resolve
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.children
import org.jetbrains.kotlin.resolve.descriptorUtil.getImportableDescriptor
import org.jetbrains.kotlin.resolve.source.getPsi
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
                is KtTypeAlias -> {
                    if (resolved !in elementSet) {
                        uncertainElements.addLast(resolved)
                        elementSet.add(resolved)
                        var iter = resolved.parent
                        while (iter !is KtFile) iter = iter.parent
                        val ktFile = iter
                        if (ktFile !in fileSet) {
                            fileSet.add(ktFile)
                            fileQueue.addLast(ktFile)
                        }
                        collectQueue.addLast(resolved)
                    }
                }

                is KtClass -> {
                    if (resolved !in elementSet) {
                        uncertainElements.addLast(resolved)
                        elementSet.add(resolved)
                        var iter = resolved.parent
                        while (iter !is KtFile) iter = iter.parent
                        val ktFile = iter
                        if (ktFile !in fileSet) {
                            fileSet.add(ktFile)
                            fileQueue.addLast(ktFile)
                        }
                        collectQueue.addLast(resolved)
                    }
                }

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
                    while (iter != null && (iter !is PsiClass && iter !is KtClass)) {
                        iter = iter.parent
                    }
                    if ((iter is PsiClass || iter is KtClass) && iter !in elementSet) {
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
                    }
                } else {
                    doubleCheckElements.addLast(curUncertain)
                }
                if (keepPackagePrefixes.none { name.startsWith(it) }) {
                    var topFile: PsiElement? = curUncertain
                    while (topFile != null && topFile !is KtFile) {
                        topFile = topFile.parent
                    }
                    if (topFile is KtFile && topFile != psiFile) {
                        refFiles.add(topFile)
                    }
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
            var (inMainContent, afterImport) = false to false
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
                        }
                    })
                    afterImport = true
                } else {
                    if (!inMainContent && afterImport) {
                        inMainContent = true
                        append('\n')
                        append("""
                            /**
                             * generated by kotlincputil@tauros
                             */
                        """.trimIndent())
                        append('\n')
                    }
                    makeElementText(child)
                }
            }
            if (isNotBlank() && last() != '\n') {
                append('\n')
            }
        }.trim('\n')
        val selection = StringSelection(code)
        Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
        Messages.showMessageDialog(
            e.project!!, "Copied!", "Inlined Code",
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
            if (isNotBlank() && last() != '\n') {
                append('\n')
            }
        } else {
            val elementCode = element.text.trimEnd('\n')
            if (elementCode.isNotBlank()) {
                append(elementCode)
                append('\n')
            }
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
                        val descriptors = element.resolveMainReferenceToDescriptors()
                        for (descriptor in descriptors) {
                            val importable = descriptor.getImportableDescriptor()
                            val sourceElement = importable.toSourceElement
                            sourceElement.getPsi()?.also(visitExpression)
                        }
                        element.children.forEach { it.accept(this) }
                    }

                    else -> element.children.forEach {
                        it.accept(this)
                    }
                }
            }
        }
        psiElement.accept(visitor)
    }
}