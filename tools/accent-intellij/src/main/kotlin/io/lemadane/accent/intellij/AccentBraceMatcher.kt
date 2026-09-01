package io.lemadane.accent.intellij

import com.intellij.lang.BracePair
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType

class AccentBraceMatcher : PairedBraceMatcher {
    companion object {
        val LBRACE = IElementType("LBRACE", AccentLanguage)
        val RBRACE = IElementType("RBRACE", AccentLanguage)
        val LPAREN = IElementType("LPAREN", AccentLanguage)
        val RPAREN = IElementType("RPAREN", AccentLanguage)
    }

    override fun getPairs(): Array<BracePair> = arrayOf(
        BracePair(LBRACE, RBRACE, true),
        BracePair(LPAREN, RPAREN, false)
    )
    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean = true
    override fun getCodeConstructStart(file: PsiFile?, lbraceOffset: Int): Int = lbraceOffset
}
