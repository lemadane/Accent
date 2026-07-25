package io.lemadane.jatot.intellij

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.util.ProcessingContext
import io.lemadane.jatot.tools.core.CompletionProvider as CoreCompletionProvider
import io.lemadane.jatot.tools.core.JotatLanguageService.ProjectContext
import java.nio.file.Paths

class JatotCompletionContributor : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, com.intellij.patterns.PlatformPatterns.psiElement(),
            object : com.intellij.codeInsight.completion.CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    resultSet: CompletionResultSet
                ) {
                    val file = parameters.originalFile
                    val text = file.text ?: ""
                    val offset = parameters.offset
                    
                    var line = 1
                    var col = 1
                    for (i in 0 until offset.coerceAtMost(text.length)) {
                        if (text[i] == '\n') {
                            line++
                            col = 1
                        } else {
                            col++
                        }
                    }

                    try {
                        val path = Paths.get(file.virtualFile?.path ?: "temp.jatot")
                        val projectContext = ProjectContext(emptyList())
                        projectContext.putFile(path, text)
                        val result = projectContext.analyze(path, true)

                        val completions = CoreCompletionProvider.getCompletions(result.unit(), result.analyzer(), line, col)
                        if (completions != null) {
                            for (item in completions) {
                                resultSet.addElement(
                                    LookupElementBuilder.create(item.label())
                                        .withTypeText(item.detail())
                                )
                            }
                        }
                    } catch (e: Exception) {
                        // Fail silently during typing
                    }
                }
            }
        )
    }
}
