package io.lemadane.jatot.intellij

import com.intellij.lang.BracePair
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType

class JatotBraceMatcher : PairedBraceMatcher {
    companion object {
        val LBRACE = IElementType("LBRACE", JatotLanguage)
        val RBRACE = IElementType("RBRACE", JatotLanguage)
        val LPAREN = IElementType("LPAREN", JatotLanguage)
        val RPAREN = IElementType("RPAREN", JatotLanguage)
    }

    override fun getPairs(): Array<BracePair> = arrayOf(
        BracePair(LBRACE, RBRACE, true),
        BracePair(LPAREN, RPAREN, false)
    )
    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean = true
    override fun getCodeConstructStart(file: PsiFile?, lbraceOffset: Int): Int = lbraceOffset
}
