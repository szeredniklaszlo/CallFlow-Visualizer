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

    // --- Table Scan Risk detection (Feature 5) ---

    /**
     * Checks if a repository method queries on a non-indexed field,
     * which risks a full table scan and table-level lock.
     * Parses Spring Data method naming (findBy..., deleteBy...) and @Query annotations.
     */
    fun detectTableScanRisk(method: PsiMethod): Boolean {
        val methodName = method.name
        val containingClass = method.containingClass ?: return false

        // Only check repository methods
        if (!isRepositoryClass(containingClass)) return false

        // Extract WHERE fields from method name or @Query
        val queryFields = extractQueryFields(method) ?: return false
        if (queryFields.isEmpty()) return false

        // Resolve the entity class the repository operates on
        val entityClass = resolveRepositoryEntityClass(containingClass) ?: return false

        // Check if the query fields are indexed
        for (field in queryFields) {
            if (!isFieldIndexed(entityClass, field)) {
                // DELETE or UPDATE on non-indexed field is especially dangerous
                val isDangerousOp = methodName.startsWith("delete") ||
                        methodName.startsWith("remove") ||
                        methodName.startsWith("update")
                if (isDangerousOp) {
                    LOG.info("TABLE_SCAN_RISK: ${containingClass.name}.$methodName queries non-indexed field '$field'")
                    return true
                }
                // Even findBy on non-indexed is risky in a transaction
                LOG.info("TABLE_SCAN_RISK: ${containingClass.name}.$methodName queries non-indexed field '$field'")
                return true
            }
        }
        return false
    }

    private fun isRepositoryClass(psiClass: PsiClass): Boolean {
        // Check for @Repository annotation or Spring Data interfaces
        return psiClass.annotations.any {
            val name = it.qualifiedName ?: ""
            name.contains("Repository")
        } || psiClass.name?.contains("Repository") == true
    }

    /**
     * Extracts field names from Spring Data method naming conventions or @Query annotation.
     */
    private fun extractQueryFields(method: PsiMethod): List<String>? {
        val methodName = method.name

        // Try @Query annotation first
        for (annotation in method.annotations) {
            val name = annotation.qualifiedName ?: continue
            if (name.contains("Query")) {
                val queryValue = annotation.findAttributeValue("value")
                val queryText = queryValue?.text?.trim('"') ?: continue
                return extractFieldsFromJpql(queryText)
            }
        }

        // Parse Spring Data method name: findByEmail, deleteByStatusAndCreatedDate, etc.
        val prefixes = listOf("findBy", "deleteBy", "removeBy", "countBy", "existsBy", "readBy", "getBy", "queryBy")
        for (prefix in prefixes) {
            if (methodName.startsWith(prefix)) {
                val fieldPart = methodName.removePrefix(prefix)
                return parseSpringDataFieldPart(fieldPart)
            }
        }

        return null
    }

    /**
     * Parses JPQL WHERE clause for field references.
     * e.g., "SELECT e FROM Entity e WHERE e.email = ?1" → ["email"]
     */
    private fun extractFieldsFromJpql(jpql: String): List<String> {
        val fields = mutableListOf<String>()
        val whereIndex = jpql.lowercase().indexOf("where")
        if (whereIndex == -1) return fields

        val whereClause = jpql.substring(whereIndex + 5)
        // Match patterns like "e.fieldName" or "entity.fieldName"
        val fieldPattern = Regex("""\w+\.(\w+)\s*[=<>!]""")
        fieldPattern.findAll(whereClause).forEach { match ->
            fields.add(match.groupValues[1])
        }
        return fields
    }

    /**
     * Parses Spring Data method field part.
     * e.g., "EmailAndStatus" → ["email", "status"]
     * e.g., "StatusOrderByCreatedDate" → ["status"]
     */
    private fun parseSpringDataFieldPart(fieldPart: String): List<String> {
        // Remove OrderBy clause
        val withoutOrderBy = fieldPart.split("OrderBy")[0]
        // Split by And/Or
        val parts = withoutOrderBy.split(Regex("And|Or"))
        return parts.map { part ->
            // Remove comparison suffixes
            val cleaned = part
                .replace(Regex("(GreaterThan|LessThan|Between|Like|NotNull|IsNull|Not|In|NotIn|Before|After|Containing|StartingWith|EndingWith)$"), "")
            // Convert PascalCase to camelCase
            cleaned.replaceFirstChar { it.lowercase() }
        }.filter { it.isNotEmpty() }
    }

    /**
     * Resolves the entity class from a Repository's generic type parameter.
     */
    private fun resolveRepositoryEntityClass(repoClass: PsiClass): PsiClass? {
        // Check super interfaces for generic type parameters (e.g., JpaRepository<Payment, String>)
        for (superType in repoClass.superTypes) {
            if (superType is PsiClassType) {
                for (param in superType.parameters) {
                    if (param is PsiClassType) {
                        val resolved = param.resolve()
                        if (resolved != null && isJpaEntity(resolved)) return resolved
                    }
                }
            }
        }

        // Fallback: check return types of methods for entity classes
        for (m in repoClass.methods) {
            val returnType = m.returnType ?: continue
            val entityClass = resolveReturnEntityClass(returnType)
            if (entityClass != null) return entityClass
        }

        return null
    }

    /**
     * Checks if a field in an entity class is indexed.
     * Checks @Id, @Column(unique=true), and @Index in @Table annotation.
     */
    private fun isFieldIndexed(entityClass: PsiClass, fieldName: String): Boolean {
        // Find the field
        val field = entityClass.fields.find {
            it.name.equals(fieldName, ignoreCase = true)
        } ?: return false // If field not found, assume not indexed (conservative)

        // Check @Id
        if (field.annotations.any { it.qualifiedName?.contains("Id") == true }) return true

        // Check @Column(unique = true)
        for (annotation in field.annotations) {
            val name = annotation.qualifiedName ?: continue
            if (name.contains("Column")) {
                val uniqueValue = annotation.findAttributeValue("unique")
                if (uniqueValue?.text?.contains("true", ignoreCase = true) == true) return true
            }
        }

        // Check @Table(indexes = @Index(columnList = "fieldName")) on the class
        for (annotation in entityClass.annotations) {
            val name = annotation.qualifiedName ?: continue
            if (name.contains("Table")) {
                val indexesValue = annotation.findAttributeValue("indexes")
                val indexText = indexesValue?.text ?: continue
                if (indexText.contains(fieldName, ignoreCase = true)) return true
            }
        }

        return false
    }

    // --- Cascade Operation detection (Feature 6) ---

    /**
     * Checks if an entity has @...ToMany with cascade=ALL/REMOVE or orphanRemoval=true.
     * Used when save()/delete() is called on this entity type.
     */
    fun detectCascadeOperations(method: PsiMethod): Boolean {
        // Check parameter type for entity with cascade relations
        for (param in method.parameterList.parameters) {
            val paramType = param.type
            if (paramType is PsiClassType) {
                val paramClass = paramType.resolve() ?: continue
                if (isJpaEntity(paramClass) && hasCascadeRelations(paramClass)) {
                    return true
                }
            }
        }

        // Also check return type (for findBy methods that return cascading entities)
        val returnClass = resolveReturnEntityClass(method.returnType ?: return false)
        if (returnClass != null && hasCascadeRelations(returnClass)) {
            return true
        }

        return false
    }

    private fun hasCascadeRelations(entityClass: PsiClass): Boolean {
        for (field in entityClass.fields) {
            for (annotation in field.annotations) {
                val name = annotation.qualifiedName ?: continue
                if (name.contains("OneToMany") || name.contains("ManyToMany") || name.contains("OneToOne")) {
                    // Check cascade attribute
                    val cascadeValue = annotation.findAttributeValue("cascade")
                    val cascadeText = cascadeValue?.text ?: ""
                    if (cascadeText.contains("ALL") || cascadeText.contains("REMOVE") || cascadeText.contains("DELETE")) {
                        LOG.info("CASCADE found on ${entityClass.name}.${field.name}: $cascadeText")
                        return true
                    }

                    // Check orphanRemoval
                    val orphanValue = annotation.findAttributeValue("orphanRemoval")
                    if (orphanValue?.text?.contains("true", ignoreCase = true) == true) {
                        LOG.info("orphanRemoval=true on ${entityClass.name}.${field.name}")
                        return true
                    }
                }
            }
        }
        return false
    }

    // --- Early INSERT Lock detection (Feature 7) ---

    /**
     * Checks if an entity's @Id uses @GeneratedValue(strategy = IDENTITY),
     * which causes immediate INSERT on save() instead of deferring to commit.
     */
    fun detectEarlyInsertLock(method: PsiMethod): Boolean {
        for (param in method.parameterList.parameters) {
            val paramType = param.type
            if (paramType is PsiClassType) {
                val paramClass = paramType.resolve() ?: continue
                if (isJpaEntity(paramClass) && hasIdentityStrategy(paramClass)) {
                    return true
                }
            }
        }
        return false
    }

    private fun hasIdentityStrategy(entityClass: PsiClass): Boolean {
        for (field in entityClass.fields) {
            // Find the @Id field
            val hasId = field.annotations.any { it.qualifiedName?.contains("Id") == true }
            if (!hasId) continue

            // Check for @GeneratedValue(strategy = GenerationType.IDENTITY)
            for (annotation in field.annotations) {
                val name = annotation.qualifiedName ?: continue
                if (name.contains("GeneratedValue")) {
                    val strategyValue = annotation.findAttributeValue("strategy")
                    val strategyText = strategyValue?.text ?: continue
                    if (strategyText.contains("IDENTITY")) {
                        LOG.info("IDENTITY strategy found on ${entityClass.name}.${field.name}")
                        return true
                    }
                }
            }
        }
        return false
    }
}

