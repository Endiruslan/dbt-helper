package com.dbthelper.codeintel

import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

data class DbtJinjaFilePatterns(
    val refs: List<DbtJinjaUtils.RefCall>,
    val sources: List<DbtJinjaUtils.SourceCall>,
    val macros: List<DbtJinjaUtils.MacroCall>
)

/** Regex scan results for a dbt template file, invalidated when the file changes. */
fun PsiFile.getDbtJinjaPatterns(): DbtJinjaFilePatterns =
    CachedValuesManager.getCachedValue(this) {
        val text = text
        CachedValueProvider.Result.create(
            DbtJinjaFilePatterns(
                DbtJinjaUtils.findRefCalls(text),
                DbtJinjaUtils.findSourceCalls(text),
                DbtJinjaUtils.findMacroCalls(text)
            ),
            this
        )
    }
