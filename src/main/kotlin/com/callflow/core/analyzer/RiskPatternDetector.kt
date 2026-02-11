package com.callflow.core.analyzer

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.*

/**
 * Detects risk patterns inside method bodies that can lead to
 * deadlocks, long-running transactions, and performance issues.
 * Designed for Spring Boot + MSSQL (RCSI OFF) environments.
 */
class RiskPatternDetector(private val project: Project) {

    private val LOG = Logger.getInstance(RiskPatternDetector::class.java)

    // --- External call detection ---

    /**
     * Scans a Java method body for external call patterns.
     * Returns a list of flags like "MQ_SEND" and "HTTP_CALL".
     */
    fun detectExternalCalls(body: PsiElement): List<String> {
        val flags = mutableSetOf<String>()

        body.accept(object : JavaRecursiveElementVisitor() {
            override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                super.visitMethodCallExpression(expression)
                classifyExternalCall(expression)?.let { flags.add(it) }
            }
        })

        return flags.toList()
    }

    /**
     * Scans a Kotlin function body for external call patterns.
     */
    fun detectExternalCallsKotlin(body: KtExpression): List<String> {
        val flags = mutableSetOf<String>()

        body.accept(object : KtTreeVisitorVoid() {
            override fun visitCallExpression(expression: KtCallExpression) {
                super.visitCallExpression(expression)
                classifyExternalCallKotlin(expression)?.let { flags.add(it) }
            }
        })

        return flags.toList()
    }

    private fun classifyExternalCall(expression: PsiMethodCallExpression): String? {
        val methodName = expression.methodExpression.referenceName ?: return null
        val resolved = expression.resolveMethod() ?: return null
        val className = resolved.containingClass?.qualifiedName ?: resolved.containingClass?.name ?: ""

        // Message Queue sends
        if (isMqSend(className, methodName)) return "MQ_SEND"

        // HTTP calls
        if (isHttpCall(className, methodName, resolved)) return "HTTP_CALL"

        return null
    }

    private fun classifyExternalCallKotlin(expression: KtCallExpression): String? {
        val calleeName = expression.calleeExpression?.text ?: return null

        // Check dot-qualified parent for receiver type
        val parent = expression.parent
        if (parent is KtDotQualifiedExpression) {
            val receiverText = parent.receiverExpression.text.lowercase()

            // MQ patterns
            if ((receiverText.contains("kafkatemplate") || receiverText.contains("kafka")) &&
                calleeName in listOf("send", "sendDefault", "sendAndReceive")) {
                return "MQ_SEND"
            }
            if ((receiverText.contains("rabbittemplate") || receiverText.contains("rabbit")) &&
                calleeName in listOf("convertAndSend", "send", "convertSendAndReceive")) {
                return "MQ_SEND"
            }

            // HTTP patterns
            if (receiverText.contains("resttemplate") ||
                receiverText.contains("webclient") ||
                receiverText.contains("client")) {
                if (calleeName in listOf("getForObject", "getForEntity", "postForObject", "postForEntity",
                        "exchange", "execute", "put", "delete", "patchForObject",
                        "get", "post", "patch", "mutate", "retrieve", "bodyToMono", "bodyToFlux")) {
                    return "HTTP_CALL"
                }
            }
        }

        return null
    }

    private fun isMqSend(className: String, methodName: String): Boolean {
        // Kafka
        if ((className.contains("KafkaTemplate") || className.contains("kafka")) &&
            methodName in listOf("send", "sendDefault", "sendAndReceive")) {
            return true
        }
        // RabbitMQ
        if ((className.contains("RabbitTemplate") || className.contains("rabbit")) &&
            methodName in listOf("convertAndSend", "send", "convertSendAndReceive")) {
            return true
        }
        // JMS
        if (className.contains("JmsTemplate") &&
            methodName in listOf("send", "convertAndSend")) {
            return true
        }
        return false
    }

    private fun isHttpCall(className: String, methodName: String, method: PsiMethod): Boolean {
        // RestTemplate
        if (className.contains("RestTemplate") &&
            methodName in listOf("getForObject", "getForEntity", "postForObject", "postForEntity",
                "exchange", "execute", "put", "delete", "patchForObject")) {
            return true
        }

        // WebClient
        if (className.contains("WebClient") || className.contains("RequestBodySpec") ||
            className.contains("RequestHeadersSpec") || className.contains("ResponseSpec")) {
            return true
        }

        // FeignClient - check if the containing class/interface has @FeignClient annotation
        val containingClass = method.containingClass
        if (containingClass != null) {
            val hasFeignAnnotation = containingClass.annotations.any {
                it.qualifiedName?.contains("FeignClient") == true
            }
            if (hasFeignAnnotation) return true
        }

        return false
    }

    // --- Flush detection ---

    /**
     * Detects calls to saveAndFlush() in a Java method body.
     */
    fun detectFlushCalls(body: PsiElement): Boolean {
        var found = false

        body.accept(object : JavaRecursiveElementVisitor() {
            override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                super.visitMethodCallExpression(expression)
                val name = expression.methodExpression.referenceName
                if (name == "saveAndFlush" || name == "flush") {
                    found = true
                }
            }
        })

        return found
    }

    /**
     * Detects calls to saveAndFlush() in a Kotlin function body.
     */
    fun detectFlushCallsKotlin(body: KtExpression): Boolean {
        var found = false

        body.accept(object : KtTreeVisitorVoid() {
            override fun visitCallExpression(expression: KtCallExpression) {
                super.visitCallExpression(expression)
                val name = expression.calleeExpression?.text
                if (name == "saveAndFlush" || name == "flush") {
                    found = true
                }
            }
        })

        return found
    }

    // --- Eager fetch detection ---

    /**
     * Checks if a repository method's return type entity has @...ToMany(fetch = EAGER) fields.
     */
    fun detectEagerFetching(method: PsiMethod): Boolean {
        val returnType = method.returnType ?: return false
        val returnClass = resolveReturnEntityClass(returnType) ?: return false

        return hasEagerToManyRelations(returnClass)
    }

    private fun resolveReturnEntityClass(type: PsiType): PsiClass? {
        // Unwrap generics like Optional<Entity>, List<Entity>, etc.
        if (type is PsiClassType) {
            val resolved = type.resolve()

            // If it's a JPA entity directly
            if (resolved != null && isJpaEntity(resolved)) return resolved

            // Check type parameters (e.g., Optional<Entity>, List<Entity>)
            for (param in type.parameters) {
                if (param is PsiClassType) {
                    val paramClass = param.resolve()
                    if (paramClass != null && isJpaEntity(paramClass)) return paramClass
                }
            }
        }
        return null
    }

    private fun isJpaEntity(psiClass: PsiClass): Boolean {
        return psiClass.annotations.any {
            val name = it.qualifiedName ?: ""
            name.contains("Entity") || name.contains("Table")
        }
    }

    private fun hasEagerToManyRelations(entityClass: PsiClass): Boolean {
        for (field in entityClass.fields) {
            for (annotation in field.annotations) {
                val name = annotation.qualifiedName ?: continue
                if (name.contains("OneToMany") || name.contains("ManyToMany")) {
                    val fetchValue = annotation.findAttributeValue("fetch")
                    val fetchText = fetchValue?.text ?: continue
                    if (fetchText.contains("EAGER")) {
                        LOG.info("Found EAGER fetch on ${entityClass.name}.${field.name}")
                        return true
                    }
                }
            }
        }
        return false
    }

    // --- Loop detection ---

    /**
     * Checks if a PSI element (method call) is inside a loop construct.
     * Traverses up the PSI tree looking for for/while/foreach/do-while statements.
     */
    fun isInsideLoop(element: PsiElement): Boolean {
        // Java loops
        if (PsiTreeUtil.getParentOfType(element, PsiForStatement::class.java) != null) return true
        if (PsiTreeUtil.getParentOfType(element, PsiWhileStatement::class.java) != null) return true
        if (PsiTreeUtil.getParentOfType(element, PsiForeachStatement::class.java) != null) return true
        if (PsiTreeUtil.getParentOfType(element, PsiDoWhileStatement::class.java) != null) return true

        return false
    }

    /**
     * Checks if a Kotlin call expression is inside a loop or iteration pattern.
     */
    fun isInsideLoopKotlin(element: KtExpression): Boolean {
        // Kotlin for/while/do-while
        if (PsiTreeUtil.getParentOfType(element, KtForExpression::class.java) != null) return true
        if (PsiTreeUtil.getParentOfType(element, KtWhileExpression::class.java) != null) return true
        if (PsiTreeUtil.getParentOfType(element, KtDoWhileExpression::class.java) != null) return true

        // Also check for .forEach { } and .map { } lambdas on collections
        val lambdaParent = PsiTreeUtil.getParentOfType(element, KtLambdaExpression::class.java)
        if (lambdaParent != null) {
            val callParent = PsiTreeUtil.getParentOfType(lambdaParent, KtCallExpression::class.java)
            val calleeName = callParent?.calleeExpression?.text
            if (calleeName in listOf("forEach", "forEachIndexed", "map", "flatMap", "filter", "onEach")) {
                return true
            }
        }

        return false
    }
}
