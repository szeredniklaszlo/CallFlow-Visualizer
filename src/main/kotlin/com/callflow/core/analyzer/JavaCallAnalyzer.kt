package com.callflow.core.analyzer

import com.callflow.core.model.CallGraph
import com.callflow.core.model.CallNode
import com.callflow.core.model.EdgeProperties
import com.callflow.core.model.NodeType
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.MethodReferencesSearch
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.asJava.elements.KtLightMethod
import org.jetbrains.kotlin.asJava.toLightMethods
import org.jetbrains.kotlin.psi.*

/**
 * Java and Kotlin implementation of CallAnalyzer.
 * Uses PSI and IntelliJ's search APIs for accurate call graph analysis.
 */
class JavaCallAnalyzer(
    private val project: Project,
    private val config: AnalysisConfig = AnalysisConfig()
) : CallAnalyzer {

    private val LOG = Logger.getInstance(JavaCallAnalyzer::class.java)
    private val riskDetector = RiskPatternDetector(project)

    /**
     * Wraps a resolved PsiMethod with the call-site PSI element for context analysis.
     */
    private data class MethodCallInfo(
        val method: PsiMethod,
        val callSite: PsiElement
    )

    override fun analyzeCallees(method: PsiMethod, depth: Int): CallNode {
        val visited = mutableSetOf<String>()
        val startTime = System.currentTimeMillis()

        return ReadAction.compute<CallNode, Exception> {
            analyzeCalleesInternal(method, depth, 0, visited)
        }.also {
            LOG.info("Callee analysis completed in ${System.currentTimeMillis() - startTime}ms, " +
                    "visited ${visited.size} nodes")
        }
    }

    override fun analyzeCallers(method: PsiMethod, depth: Int): CallNode {
        val visited = mutableSetOf<String>()
        val startTime = System.currentTimeMillis()

        return ReadAction.compute<CallNode, Exception> {
            analyzeCallersInternal(method, depth, 0, visited)
        }.also {
            LOG.info("Caller analysis completed in ${System.currentTimeMillis() - startTime}ms, " +
                    "visited ${visited.size} nodes")
        }
    }

    override fun analyzeBidirectional(method: PsiMethod, depth: Int): CallGraph {
        val startTime = System.currentTimeMillis()
        val visitedCallees = mutableSetOf<String>()
        val visitedCallers = mutableSetOf<String>()

        val rootWithCallees = ReadAction.compute<CallNode, Exception> {
            analyzeCalleesInternal(method, depth, 0, visitedCallees)
        }

        val rootWithCallers = ReadAction.compute<CallNode, Exception> {
            analyzeCallersInternal(method, depth, 0, visitedCallers)
        }

        // Merge both analyses into a single root node
        val mergedRoot = rootWithCallees.copy(
            callers = rootWithCallers.callers.toMutableList()
        )

        val totalNodes = visitedCallees.size + visitedCallers.size - 1
        val analysisTime = System.currentTimeMillis() - startTime

        return CallGraph(
            root = mergedRoot,
            maxDepth = depth,
            direction = CallGraph.AnalysisDirection.BIDIRECTIONAL,
            nodeCount = totalNodes,
            analysisTimeMs = analysisTime,
            isTruncated = totalNodes >= config.maxNodes
        )
    }

    /**
     * Internal recursive callee analysis.
     */
    private fun analyzeCalleesInternal(
        method: PsiMethod,
        maxDepth: Int,
        currentDepth: Int,
        visited: MutableSet<String>
    ): CallNode {
        val nodeId = CallNode.createId(method)

        // Check for cycles
        if (nodeId in visited) {
            return createCyclicRefNode(method, currentDepth)
        }

        // Check depth limit
        if (currentDepth > maxDepth) {
            return CallNode.fromPsiMethod(method, detectNodeType(method), currentDepth)
        }

        // Check node limit
        if (visited.size >= config.maxNodes) {
            return CallNode.fromPsiMethod(method, detectNodeType(method), currentDepth)
        }

        visited.add(nodeId)

        var node = CallNode.fromPsiMethod(method, detectNodeType(method), currentDepth)

        // Enrich metadata with risk patterns from method body
        node = enrichNodeWithRiskPatterns(method, node)

        // Find method calls - handle both Java and Kotlin
        val calledMethods = findMethodCallsInMethod(method)

        calledMethods.forEach { callInfo ->
            if (shouldIncludeMethod(callInfo.method)) {
                val childNode = analyzeCalleesInternal(
                    callInfo.method,
                    maxDepth,
                    currentDepth + 1,
                    visited
                )
                node.callees.add(childNode)

                // Check if this call is inside a loop and set edge properties
                val isInLoop = riskDetector.isInsideLoop(callInfo.callSite)
                if (isInLoop) {
                    node.calleeEdgeProperties[childNode.id] = EdgeProperties(isInsideLoop = true)
                }

                // Also check for eager fetch on the called method
                if (riskDetector.detectEagerFetching(callInfo.method)) {
                    val updatedFlags = childNode.metadata.warningFlags.toMutableList()
                    if ("EAGER_FETCH" !in updatedFlags) {
                        updatedFlags.add("EAGER_FETCH")
                    }
                    val idx = node.callees.indexOf(childNode)
                    if (idx >= 0) {
                        node.callees[idx] = childNode.copy(
                            metadata = childNode.metadata.copy(warningFlags = updatedFlags)
                        )
                    }
                }

                // If this is an interface method, add implementations as children of the interface node
                if (config.resolveImplementations && callInfo.method.containingClass?.isInterface == true) {
                    val implementations = findOverridingMethods(callInfo.method)
                    implementations.forEach { implMethod ->
                        if (shouldIncludeMethod(implMethod)) {
                            val implNode = analyzeCalleesInternal(
                                implMethod,
                                maxDepth,
                                currentDepth + 2,
                                visited
                            )
                            childNode.callees.add(implNode)
                        }
                    }
                }
            }
        }

        return node
    }

    /**
     * Enrich a node's metadata with risk patterns detected in the method body.
     * Returns a new node with enriched metadata.
     */
    private fun enrichNodeWithRiskPatterns(method: PsiMethod, node: CallNode): CallNode {
        val externalFlags = mutableListOf<String>()
        val warningFlags = mutableListOf<String>()

        if (method is KtLightMethod) {
            val ktFunction = method.kotlinOrigin
            if (ktFunction is KtNamedFunction) {
                ktFunction.bodyExpression?.let { body ->
                    externalFlags.addAll(riskDetector.detectExternalCallsKotlin(body))
                    if (riskDetector.detectFlushCallsKotlin(body)) {
                        warningFlags.add("FLUSH")
                    }
                }
            }
        } else {
            method.body?.let { body ->
                externalFlags.addAll(riskDetector.detectExternalCalls(body))
                if (riskDetector.detectFlushCalls(body)) {
                    warningFlags.add("FLUSH")
                }
            }
        }

        return if (externalFlags.isNotEmpty() || warningFlags.isNotEmpty()) {
            node.copy(
                metadata = node.metadata.copy(
                    externalCallFlags = node.metadata.externalCallFlags + externalFlags,
                    warningFlags = node.metadata.warningFlags + warningFlags
                )
            )
        } else {
            node
        }
    }

    /**
     * Internal recursive caller analysis.
     */
    private fun analyzeCallersInternal(
        method: PsiMethod,
        maxDepth: Int,
        currentDepth: Int,
        visited: MutableSet<String>
    ): CallNode {
        val nodeId = CallNode.createId(method)

        // Check for cycles
        if (nodeId in visited) {
            return createCyclicRefNode(method, currentDepth)
        }

        // Check depth limit
        if (currentDepth > maxDepth) {
            return CallNode.fromPsiMethod(method, detectNodeType(method), currentDepth)
        }

        // Check node limit
        if (visited.size >= config.maxNodes) {
            return CallNode.fromPsiMethod(method, detectNodeType(method), currentDepth)
        }

        visited.add(nodeId)

        val node = CallNode.fromPsiMethod(method, detectNodeType(method), currentDepth)

        // Find references to this method
        val scope = GlobalSearchScope.projectScope(project)
        val references = MethodReferencesSearch.search(method, scope, true).findAll()

        references.forEach { reference ->
            val callingMethod = findContainingMethod(reference.element)
            if (callingMethod != null && shouldIncludeMethod(callingMethod)) {
                val parentNode = analyzeCallersInternal(
                    callingMethod,
                    maxDepth,
                    currentDepth + 1,
                    visited
                )
                node.callers.add(parentNode)
            }
        }

        // Also check for interface implementations
        if (config.resolveImplementations) {
            findOverridingMethods(method).forEach { overridingMethod ->
                val overrideReferences = MethodReferencesSearch.search(
                    overridingMethod, scope, true
                ).findAll()

                overrideReferences.forEach { reference ->
                    val callingMethod = findContainingMethod(reference.element)
                    if (callingMethod != null && shouldIncludeMethod(callingMethod)) {
                        val parentNode = analyzeCallersInternal(
                            callingMethod,
                            maxDepth,
                            currentDepth + 1,
                            visited
                        )
                        node.callers.add(parentNode)
                    }
                }
            }
        }

        return node
    }

    /**
     * Find the containing method (Java or Kotlin) for a PSI element.
     */
    private fun findContainingMethod(element: PsiElement): PsiMethod? {
        // Try Java method first
        val javaMethod = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
        if (javaMethod != null) return javaMethod

        // Try Kotlin function
        val ktFunction = PsiTreeUtil.getParentOfType(element, KtNamedFunction::class.java)
        if (ktFunction != null) {
            val lightMethods = ktFunction.toLightMethods()
            if (lightMethods.isNotEmpty()) {
                return lightMethods.first()
            }
        }

        return null
    }

    // Note: toLightMethods is imported from org.jetbrains.kotlin.asJava.toLightMethods

    /**
     * Find all method calls within a method body (Java or Kotlin).
     */
    private fun findMethodCallsInMethod(method: PsiMethod): List<MethodCallInfo> {
        val calls = mutableListOf<MethodCallInfo>()

        // Check if this is a Kotlin Light method
        if (method is KtLightMethod) {
            val ktFunction = method.kotlinOrigin
            if (ktFunction is KtNamedFunction) {
                calls.addAll(findKotlinMethodCalls(ktFunction))
                return calls.distinctBy { CallNode.createId(it.method) }
            }
        }

        // Java method - use Java visitor
        method.body?.let { body ->
            calls.addAll(findJavaMethodCalls(body))
        }

        return calls.distinctBy { CallNode.createId(it.method) }
    }

    /**
     * Find method calls in Java code.
     */
    private fun findJavaMethodCalls(element: PsiElement): List<MethodCallInfo> {
        val calls = mutableListOf<MethodCallInfo>()

        element.accept(object : JavaRecursiveElementVisitor() {
            override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                super.visitMethodCallExpression(expression)
                expression.resolveMethod()?.let { resolved ->
                    calls.add(MethodCallInfo(resolved, expression))
                }
            }

            override fun visitNewExpression(expression: PsiNewExpression) {
                super.visitNewExpression(expression)
                expression.resolveConstructor()?.let { constructor ->
                    calls.add(MethodCallInfo(constructor, expression))
                }
            }
        })

        return calls
    }

    /**
     * Find method calls in Kotlin code (K1/K2 compatible).
     * Note: Does NOT include implementations here - they're added as children in analyzeCalleesInternal
     */
    private fun findKotlinMethodCalls(ktFunction: KtNamedFunction): List<MethodCallInfo> {
        val calls = mutableListOf<MethodCallInfo>()

        LOG.info("Analyzing Kotlin function: ${ktFunction.name}")

        ktFunction.bodyExpression?.accept(object : KtTreeVisitorVoid() {
            override fun visitCallExpression(expression: KtCallExpression) {
                super.visitCallExpression(expression)
                resolveKotlinCall(expression)?.let { resolved ->
                    LOG.info("Resolved call: ${expression.text} -> ${resolved.name}")
                    calls.add(MethodCallInfo(resolved, expression))
                }
            }
        })

        LOG.info("Found ${calls.size} method calls in ${ktFunction.name}")
        return calls
    }

    /**
     * Resolve a Kotlin call expression to PsiMethod (K1/K2 compatible).
     */
    private fun resolveKotlinCall(callExpression: KtCallExpression): PsiMethod? {
        // Strategy 1: Try resolving through callExpression's own references
        for (reference in callExpression.references) {
            try {
                val resolved = reference.resolve()
                LOG.info("CallExpression ref resolved: $resolved (${resolved?.javaClass?.simpleName})")
                when (resolved) {
                    is PsiMethod -> return resolved
                    is KtNamedFunction -> {
                        val lightMethods = resolved.toLightMethods()
                        if (lightMethods.isNotEmpty()) {
                            return lightMethods.first()
                        }
                    }
                }
            } catch (e: Exception) {
                LOG.debug("CallExpression reference resolution failed: ${e.message}")
            }
        }

        // Strategy 2: Try resolving through callee's references
        val callee = callExpression.calleeExpression
        if (callee != null) {
            LOG.info("Callee: ${callee.text}, refs: ${callee.references.size}")
            for (reference in callee.references) {
                try {
                    val resolved = reference.resolve()
                    LOG.info("Callee ref resolved: $resolved (${resolved?.javaClass?.simpleName})")
                    when (resolved) {
                        is PsiMethod -> return resolved
                        is KtNamedFunction -> {
                            val lightMethods = resolved.toLightMethods()
                            if (lightMethods.isNotEmpty()) {
                                return lightMethods.first()
                            }
                        }
                    }
                } catch (e: Exception) {
                    LOG.debug("Callee reference resolution failed: ${e.message}")
                }
            }
        }

        // Strategy 3: For dot-qualified expressions, try using MethodReferencesSearch in reverse
        val parent = callExpression.parent
        if (parent is KtDotQualifiedExpression) {
            val receiver = parent.receiverExpression
            val methodName = callee?.text

            LOG.info("Dot-qualified: receiver=${receiver.text}, method=$methodName")

            // Try to find receiver type and lookup method
            for (ref in receiver.references) {
                try {
                    val receiverResolved = ref.resolve()
                    LOG.info("Receiver resolved: $receiverResolved (${receiverResolved?.javaClass?.simpleName})")

                    when (receiverResolved) {
                        is KtParameter -> {
                            // Constructor parameter - find method in parameter type
                            val typeRef = receiverResolved.typeReference?.text
                            if (typeRef != null && methodName != null) {
                                val method = findMethodByTypeAndName(typeRef, methodName)
                                if (method != null) return method
                            }
                        }
                        is KtProperty -> {
                            // Property - find method in property type
                            val typeRef = receiverResolved.typeReference?.text
                            if (typeRef != null && methodName != null) {
                                val method = findMethodByTypeAndName(typeRef, methodName)
                                if (method != null) return method
                            }
                        }
                    }
                } catch (e: Exception) {
                    LOG.debug("Receiver resolution failed: ${e.message}")
                }
            }
        }

        LOG.info("Could not resolve call: ${callExpression.text}")
        return null
    }

    /**
     * Find a method by class name and method name.
     */
    private fun findMethodByTypeAndName(typeName: String, methodName: String): PsiMethod? {
        val scope = GlobalSearchScope.projectScope(project)
        val psiFacade = JavaPsiFacade.getInstance(project)

        // Try to find the class
        val psiClass = psiFacade.findClass(typeName, scope)
            ?: psiFacade.findClass("com.example.kotlin.$typeName", scope)
            ?: psiFacade.findClass("com.example.$typeName", scope)

        if (psiClass != null) {
            LOG.info("Found class: ${psiClass.qualifiedName}, looking for method: $methodName")
            val method = psiClass.findMethodsByName(methodName, true).firstOrNull()
            if (method != null) {
                LOG.info("Found method: ${method.name} in ${psiClass.qualifiedName}")
                return method
            }
        }

        return null
    }

    /**
     * Find methods that override the given method (for interfaces/abstract classes).
     */
    private fun findOverridingMethods(method: PsiMethod): List<PsiMethod> {
        val containingClass = method.containingClass ?: return emptyList()

        if (!containingClass.isInterface &&
            !method.hasModifierProperty(PsiModifier.ABSTRACT)
        ) {
            return emptyList()
        }

        val scope = GlobalSearchScope.projectScope(project)
        return OverridingMethodsSearch.search(method, scope, true)
            .findAll()
            .filter { shouldIncludeMethod(it) }
    }

    /**
     * Determine if a method should be included based on config filters.
     */
    private fun shouldIncludeMethod(method: PsiMethod): Boolean {
        val qualifiedName = method.containingClass?.qualifiedName ?: return false

        // Check exclusions
        if (config.excludePackages.any { qualifiedName.startsWith(it) }) {
            return false
        }

        // Check inclusions (if specified)
        if (config.includePackages.isNotEmpty()) {
            return config.includePackages.any { qualifiedName.startsWith(it) }
        }

        // Exclude external libraries if configured
        if (!config.includeExternalCalls) {
            val file = method.containingFile?.virtualFile ?: return false
            val index = com.intellij.openapi.roots.ProjectFileIndex.getInstance(project)
            if (!index.isInSource(file)) {
                return false
            }
        }

        return true
    }

    /**
     * Detect the NodeType from a method's containing class.
     */
    private fun detectNodeType(method: PsiMethod): NodeType {
        val containingClass = method.containingClass ?: return NodeType.UNKNOWN

        if (containingClass.isInterface) {
            return NodeType.INTERFACE
        }

        // For Kotlin classes, get annotations from Kotlin origin
        val classAnnotations = getClassAnnotations(containingClass, method)

        LOG.info("detectNodeType: class=${containingClass.name}, annotations=$classAnnotations")

        return NodeType.fromAnnotations(classAnnotations)
    }

    /**
     * Get annotations from a class, handling both Java and Kotlin classes.
     */
    private fun getClassAnnotations(containingClass: PsiClass, method: PsiMethod): List<String> {
        // Try Kotlin annotations first (for KtLightMethod)
        if (method is KtLightMethod) {
            val ktOrigin = method.kotlinOrigin
            // Get the containing Kotlin class (traverse up from the method)
            val ktClass = PsiTreeUtil.getParentOfType(ktOrigin, KtClassOrObject::class.java)
            if (ktClass != null) {
                val ktAnnotations = ktClass.annotationEntries.mapNotNull { entry: KtAnnotationEntry ->
                    // Get the short name and also try to resolve the full name
                    val shortName = entry.shortName?.asString()
                    val typeRef = entry.typeReference?.text
                    LOG.info("Kotlin annotation: shortName=$shortName, typeRef=$typeRef")
                    // Return both possible names to match against
                    shortName ?: typeRef
                }
                if (ktAnnotations.isNotEmpty()) {
                    LOG.info("Found Kotlin annotations: $ktAnnotations for class ${containingClass.name}")
                    return ktAnnotations
                }
            }
        }

        // Fall back to Java/Light class annotations
        return containingClass.annotations
            .mapNotNull { it.qualifiedName }
    }

    /**
     * Create a node representing a cyclic reference.
     */
    private fun createCyclicRefNode(method: PsiMethod, depth: Int): CallNode {
        return CallNode.fromPsiMethod(method, detectNodeType(method), depth)
            .copy(isCyclicRef = true)
    }
}
