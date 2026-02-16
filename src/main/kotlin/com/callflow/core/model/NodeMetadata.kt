package com.callflow.core.model

/**
 * Transaction propagation levels from Spring @Transactional
 */
enum class TransactionPropagation(val displayName: String) {
    NONE("No TX"),           // Not transactional
    REQUIRED("TX"),          // Default - join existing or create new
    REQUIRES_NEW("NEW TX"),  // Always create new transaction
    NOT_SUPPORTED("NO TX"),  // Execute without transaction
    SUPPORTS("TX?"),         // Use TX if exists, otherwise no TX
    MANDATORY("TX!"),        // Must have existing TX
    NEVER("NEVER TX"),       // Must NOT have existing TX
    NESTED("NESTED TX")      // Nested transaction
}

/**
 * Additional metadata associated with a call graph node.
 * Contains Spring-specific information and behavioral markers.
 */
data class NodeMetadata(
    /** Whether the method is annotated with @Async */
    val isAsync: Boolean = false,

    /** Whether the method is annotated with @Transactional */
    val isTransactional: Boolean = false,

    /** Whether the @Transactional has readOnly = true */
    val isReadOnlyTx: Boolean = false,

    /** Transaction propagation level */
    val transactionPropagation: TransactionPropagation = TransactionPropagation.NONE,

    /** HTTP method if this is a REST endpoint (GET, POST, etc.) */
    val httpMethod: String? = null,

    /** HTTP path mapping if this is a REST endpoint */
    val httpPath: String? = null,

    /** Event class name if this is an event publisher/listener */
    val eventClass: String? = null,

    /** Whether this method publishes an event (Spring ApplicationEvent or MQ message) */
    val isEventPublisher: Boolean = false,

    /** Type of event published (e.g., "Kafka Message", "MyEvent") */
    val eventType: String? = null,

    /** All annotations on the method */
    val annotations: List<String> = emptyList(),

    /** Method visibility (public, private, protected, package-private) */
    val visibility: Visibility = Visibility.PUBLIC,

    /** Whether this method is static */
    val isStatic: Boolean = false,

    /** Parameter types for method signature display */
    val parameterTypes: List<String> = emptyList(),

    /** Return type */
    val returnType: String = "void",

    /** Line number in source file */
    val lineNumber: Int = -1,

    /** File path for test detection */
    val filePath: String? = null,

    /** External call flags detected in method body (e.g., "MQ_SEND", "HTTP_CALL") */
    val externalCallFlags: List<String> = emptyList(),

    /** Warning flags for risky patterns (e.g., "FLUSH", "EAGER_FETCH") */
    val warningFlags: List<String> = emptyList()
) {
    enum class Visibility {
        PUBLIC, PROTECTED, PACKAGE_PRIVATE, PRIVATE
    }

    /**
     * Returns a compact signature string like: "process(String, Long): Boolean"
     */
    fun toSignature(methodName: String): String {
        val params = parameterTypes.joinToString(", ") { it.substringAfterLast(".") }
        val ret = returnType.substringAfterLast(".")
        return "$methodName($params): $ret"
    }

    /**
     * Returns annotation badges for UI display.
     */
    fun getBadges(): List<String> = buildList {
        if (isAsync) add("@Async")
        if (isTransactional) {
            when {
                isReadOnlyTx -> add("@Tx(RO)")
                transactionPropagation == TransactionPropagation.REQUIRES_NEW -> add("!TX(NEW)!")
                else -> add("@Tx")
            }
        }
        httpMethod?.let { add(it) }
        if (eventClass != null) add("Event")
        if ("MQ_SEND" in externalCallFlags) add("MQ Send")
        if ("HTTP_CALL" in externalCallFlags) add("HTTP Call")
        if ("FLUSH" in warningFlags) add("FLUSH!")
        if ("EAGER_FETCH" in warningFlags) add("EAGER!")
        if ("TABLE_SCAN_RISK" in warningFlags) add("\u2620 TABLE SCAN!")
        if ("CASCADE_OPERATION" in warningFlags) add("CASCADE")
        if ("EARLY_INSERT_LOCK" in warningFlags) add("\u26A1 EARLY LOCK")
        if (isEventPublisher) add("[EVENT PUB 📤]")
    }
}
