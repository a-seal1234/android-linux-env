package com.alw.workstation.core.proot.model

data class BindMount(
    val hostPath: String,
    val guestPath: String? = null,
    val isReadOnly: Boolean = false
) {
    fun toCliArg(): String {
        val target = if (guestPath != null) "$hostPath:$guestPath" else hostPath
        return if (isReadOnly) "$target:ro" else target
    }
}
