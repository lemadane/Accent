package accent.intellij

import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

object AccentFileType : LanguageFileType(AccentLanguage) {
    override fun getName(): String = "Accent"
    override fun getDescription(): String = "Accent source file"
    override fun getDefaultExtension(): String = "accent"
    override fun getIcon(): Icon = com.intellij.openapi.util.IconLoader.getIcon("/icons/accent.png", AccentFileType::class.java)
}
