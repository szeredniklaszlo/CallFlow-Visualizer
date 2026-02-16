package com.callflow.core.model

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod

/**
 * Properties associated with a call edge (the relationship between caller and callee).
 */
data class EdgeProperties(
    /** Whether this call occurs inside a loop (for, while, foreach) */
    val isInsideLoop: Boolean = false,

    /** PSI element of the call expression — for navigating to the exact call site */
    val callSiteElement: PsiElement? = null,

    /** Whether this call starts a new thread or async task (effectively creating a fork) */
    val isAsync: Boolean = false
)

/**
 * Represents a single node in the call graph.
 * Each node corresponds to a method and contains navigation info plus call relationships.
 */
data class CallNode(
    /** Unique identifier for this node (className#methodName#signature) */
    val id: String,

    /** Method name */
    val methodName: String,

    /** Containing class name (simple name) */
    val className: String,

    /** Full package name */
    val packageName: String,

    /** Node type for visual categorization */
    val type: NodeType,

    /** Reference to PSI element for code navigation (may be null for external refs) */
    val psiElement: PsiElement? = null,

    /** Additional metadata (annotations, async, etc.) */
    val metadata: NodeMetadata = NodeMetadata(),

    /** Methods that call this method (callers/upstream) */
    val callers: MutableList<CallNode> = mutableListOf(),

    /** Methods that this method calls (callees/downstream) */
    val callees: MutableList<CallNode> = mutableListOf(),

    /** Whether this node represents a cyclic reference (prevents infinite loops) */
    val isCyclicRef: Boolean = false,

    /** Depth from the root node */
    val depth: Int = 0,

    /** Edge properties for calls to specific callees (keyed by callee ID) */
    val calleeEdgeProperties: MutableMap<String, EdgeProperties> = mutableMapOf()
) {
    /** Full qualified class name */
    val qualifiedClassName: String
        get() = if (packageName.isNotEmpty()) "$packageName.$className" else className

    /** Display name for UI (ClassName.methodName) */
    val displayName: String
        get() = "$className.$methodName()"

    /** Short display for compact views */
    val shortDisplayName: String
        get() = "$className.${methodName.take(20)}${if (methodName.length > 20) "..." else ""}()"

    /**
     * Navigate to this node's source code in the editor.
     */
    fun navigate(requestFocus: Boolean = true) {
        val navigatable = (psiElement as? com.intellij.pom.Navigatable)
            ?: (psiElement?.navigationElement as? com.intellij.pom.Navigatable)
        navigatable?.navigate(requestFocus)
    }

    /**
     * Check if this node can navigate to source.
     */
    fun canNavigate(): Boolean = psiElement != null

    /**
     * Get total count of callers recursively.
     */
    fun totalCallerCount(): Int = callers.size + callers.sumOf { it.totalCallerCount() }

    /**
     * Get total count of callees recursively.
     */
    fun totalCalleeCount(): Int = callees.size + callees.sumOf { it.totalCalleeCount() }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CallNode) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "CallNode($displayName, type=$type, callers=${callers.size}, callees=${callees.size})"

    companion object {
        /**
         * Create a unique ID from a PsiMethod.
         */
        fun createId(method: PsiMethod): String {
            val className = method.containingClass?.qualifiedName ?: "Unknown"
            val methodName = method.name
            val params = method.parameterList.parameters.joinToString(",") {
                it.type.canonicalText
            }
            return "$className#$methodName#$params"
        }

        /**
         * Create a CallNode from a PsiMethod.
         */
        fun fromPsiMethod(
            method: PsiMethod,
            type: NodeType = NodeType.UNKNOWN,
            depth: Int = 0
        ): CallNode {
            val containingClass = method.containingClass
            return CallNode(
                id = createId(method),
                methodName = method.name,
                className = containingClass?.name ?: "Unknown",
                packageName = containingClass?.qualifiedName
                    ?.substringBeforeLast(".", "") ?: "",
                type = type,
                psiElement = method,
                metadata = extractMetadata(method),
                depth = depth
            )
        }

        /**
         * Extract metadata from a PsiMethod.
         */
        private fun extractMetadata(method: PsiMethod): NodeMetadata {
            val annotations = method.annotations.mapNotNull { it.qualifiedName }
            val isTransactional = annotations.any { it.contains("Transactional") }
            val propagation = if (isTransactional) {
                extractTransactionPropagation(method)
            } else {
                TransactionPropagation.NONE
            }
            val readOnly = if (isTransactional) {
                extractReadOnly(method)
            } else {
                false
            }

            // Extract file path for test detection
            val filePath = method.containingFile?.virtualFile?.path

            return NodeMetadata(
                isAsync = annotations.any { it.contains("Async") },
                isTransactional = isTransactional,
                isReadOnlyTx = readOnly,
                transactionPropagation = propagation,
                httpMethod = extractHttpMethod(annotations),
                httpPath = extractHttpPath(method),
                annotations = annotations.map { it.substringAfterLast(".") },
                isStatic = method.hasModifierProperty(com.intellij.psi.PsiModifier.STATIC),
                parameterTypes = method.parameterList.parameters.map {
                    it.type.presentableText
                },
                returnType = method.returnType?.presentableText ?: "void",
                lineNumber = method.textOffset,
                filePath = filePath
            )
        }

        /**
         * Extract transaction propagation level from @Transactional annotation.
         */
        private fun extractTransactionPropagation(method: PsiMethod): TransactionPropagation {
            for (annotation in method.annotations) {
                val name = annotation.qualifiedName ?: continue
                if (name.contains("Transactional")) {
                    // Check propagation attribute
                    val propagationValue = annotation.findAttributeValue("propagation")
                    val propagationText = propagationValue?.text ?: return TransactionPropagation.REQUIRED

                    return when {
                        propagationText.contains("REQUIRES_NEW") -> TransactionPropagation.REQUIRES_NEW
                        propagationText.contains("NOT_SUPPORTED") -> TransactionPropagation.NOT_SUPPORTED
                        propagationText.contains("SUPPORTS") -> TransactionPropagation.SUPPORTS
                        propagationText.contains("MANDATORY") -> TransactionPropagation.MANDATORY
                        propagationText.contains("NEVER") -> TransactionPropagation.NEVER
                        propagationText.contains("NESTED") -> TransactionPropagation.NESTED
                        else -> TransactionPropagation.REQUIRED // Default
                    }
                }
            }
            return TransactionPropagation.REQUIRED
        }

        /**
         * Extract readOnly attribute from @Transactional annotation.
         */
        private fun extractReadOnly(method: PsiMethod): Boolean {
            for (annotation in method.annotations) {
                val name = annotation.qualifiedName ?: continue
                if (name.contains("Transactional")) {
                    val readOnlyValue = annotation.findAttributeValue("readOnly")
                    val readOnlyText = readOnlyValue?.text ?: return false
                    return readOnlyText.contains("true", ignoreCase = true)
                }
            }
            return false
        }

        private fun extractHttpMethod(annotations: List<String>): String? {
            return when {
                annotations.any { it.contains("GetMapping") } -> "GET"
                annotations.any { it.contains("PostMapping") } -> "POST"
                annotations.any { it.contains("PutMapping") } -> "PUT"
                annotations.any { it.contains("DeleteMapping") } -> "DELETE"
                annotations.any { it.contains("PatchMapping") } -> "PATCH"
                annotations.any { it.contains("RequestMapping") } -> "REQUEST"
                else -> null
            }
        }

        private fun extractHttpPath(method: PsiMethod): String? {
            val mappingAnnotations = listOf(
                "GetMapping", "PostMapping", "PutMapping",
                "DeleteMapping", "PatchMapping", "RequestMapping"
            )
            for (annotation in method.annotations) {
                val name = annotation.qualifiedName?.substringAfterLast(".") ?: continue
                if (name in mappingAnnotations) {
                    val value = annotation.findAttributeValue("value")
                        ?: annotation.findAttributeValue("path")
                    return value?.text?.trim('"')
                }
            }
            return null
        }
    }
}
