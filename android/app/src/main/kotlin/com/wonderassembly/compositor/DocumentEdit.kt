package com.wonderassembly.compositor

import com.wonderassembly.compositor.core.ChunkedStrokePatch

internal sealed interface DocumentEdit {
    data class Stroke(val layerId: String, val patch: ChunkedStrokePatch) : DocumentEdit
    data class Append(
        val before: CompProjectReader.Project,
        val after: CompProjectReader.Project,
        val previousLayerId: String?,
        val previousStructureChanged: Boolean
    ) : DocumentEdit
}
