package org.hildan.chrome.devtools.domains.dom

import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A CSS selector string, as [defined by the W3C](https://www.w3schools.com/cssref/css_selectors.asp).
 */
typealias CssSelector = String

/**
 * Retrieves the root [Node] of the current document.
 */
suspend fun DOMDomain.getDocumentRoot(): Node = getDocument().root

/**
 * Retrieves the ID of the root node of the current document.
 */
suspend fun DOMDomain.getDocumentRootNodeId(): NodeId = getDocumentRoot().nodeId

/**
 * Retrieves the ID of the node corresponding to the given [selector], or null if not found.
 *
 * Note that the returned [NodeId] cannot really be used to retrieve actual node information, and this is apparently
 * [by design of the DOM domain](https://github.com/ChromeDevTools/devtools-protocol/issues/20).
 * It can be used to perform other CDP commands that require a [NodeId], though.
 */
suspend fun DOMDomain.findNodeBySelector(selector: CssSelector): NodeId? =
    querySelectorOnNode(getDocumentRootNodeId(), selector)

private suspend fun DOMDomain.querySelectorOnNode(nodeId: NodeId, selector: CssSelector): NodeId? {
    // for some reason, the API returns nodeId == 0 when not found
    return querySelector(nodeId, selector).nodeId.takeIf { it != 0 }
}

/**
 * Retrieves the ID of the node corresponding to the given [selector], and retries until there is a match using the
 * given [pollingPeriod].
 *
 * This method may suspend forever if the [selector] never matches any node.
 * The caller is responsible for using [withTimeout][kotlinx.coroutines.withTimeout] or similar cancellation mechanisms
 * around calls to this method if handling this case is necessary.
 *
 * Note that the returned [NodeId] cannot really be used to retrieve actual node information, and this is apparently
 * [by design of the DOM domain](https://github.com/ChromeDevTools/devtools-protocol/issues/20).
 * It can be used to perform other CDP commands that require a [NodeId], though.
 */
suspend fun DOMDomain.awaitNodeBySelector(selector: CssSelector, pollingPeriod: Duration = 200.milliseconds): NodeId {
    while (true) {
        // it looks like we do need to get a new document at each poll otherwise we may not see the new nodes
        val nodeId = findNodeBySelector(selector)
        if (nodeId != null) {
            return nodeId
        }
        delay(pollingPeriod)
    }
}

@Deprecated(
    message = "Prefer the overload using kotlin.time.Duration",
    replaceWith = ReplaceWith(
        expression = "this.awaitNodeBySelector(selector, pollingPeriodMillis.milliseconds)",
        imports = ["kotlin.time.Duration.Companion.milliseconds"],
    ),
)
suspend fun DOMDomain.awaitNodeBySelector(selector: CssSelector, pollingPeriodMillis: Long): NodeId =
    awaitNodeBySelector(selector, pollingPeriodMillis.milliseconds)

/**
 * Waits until the given [selector] matches no node in the DOM. Inspects the DOM every [pollingPeriod].
 *
 * This method may suspend forever if the [selector] keeps matching at least one node.
 * The caller is responsible for using [withTimeout][kotlinx.coroutines.withTimeout] or similar cancellation mechanisms
 * around calls to this method if handling this case is necessary.
 */
suspend fun DOMDomain.awaitNodeAbsentBySelector(selector: CssSelector, pollingPeriod: Duration = 200.milliseconds) {
    while (true) {
        // it looks like we do need to get a new document at each poll otherwise we may not see the new nodes
        findNodeBySelector(selector) ?: return
        delay(pollingPeriod)
    }
}

/**
 * Retrieves the ID of the node corresponding to the given [selector], or throw an exception if not found.
 *
 * Note that the returned [NodeId] cannot really be used to retrieve actual node information, and this is apparently
 * [by design of the DOM domain](https://github.com/ChromeDevTools/devtools-protocol/issues/20).
 * It can be used to perform other CDP commands that require a [NodeId], though.
 */
suspend fun DOMDomain.getNodeBySelector(selector: CssSelector): NodeId =
    findNodeBySelector(selector) ?: error("DOM node not found with selector: $selector")

/**
 * Moves the focus to the node corresponding to the given [selector], or null if not found.
 */
suspend fun DOMDomain.focusNodeBySelector(selector: CssSelector) {
    focus {
        nodeId = findNodeBySelector(selector) ?: error("Cannot focus: no node found using selector '$selector'")
    }
}

/**
 * Gets the attributes of the node corresponding to the given [nodeSelector], or null if the selector didn't match
 * any node.
 */
suspend fun DOMDomain.getTypedAttributes(nodeSelector: CssSelector): DOMAttributes? =
    findNodeBySelector(nodeSelector)?.let { nodeId -> getTypedAttributes(nodeId) }

/**
 * Gets the attributes of the node corresponding to the given [nodeId].
 */
suspend fun DOMDomain.getTypedAttributes(nodeId: NodeId): DOMAttributes =
    getAttributes(nodeId).attributes.asDOMAttributes()

/**
 * Gets the value of the attribute [attributeName] of the node corresponding to the given [nodeSelector], or null if
 * the selector didn't match any node or if the attribute was not present on the node.
 */
suspend fun DOMDomain.getAttributeValue(nodeSelector: CssSelector, attributeName: String): String? =
    getTypedAttributes(nodeSelector)?.get(attributeName)

/**
 * Gets the value of the attribute [attributeName] of the node corresponding to the given [nodeId], or null if
 * the attribute was not present on the node.
 */
suspend fun DOMDomain.getAttributeValue(nodeId: NodeId, attributeName: String): String? =
    getTypedAttributes(nodeId)[attributeName]

/**
 * Sets the attribute of the given [name] to the given [value] on the node corresponding to the given [nodeSelector].
 * Throws an exception if the selector didn't match any node.
 */
suspend fun DOMDomain.setAttributeValue(nodeSelector: CssSelector, name: String, value: String) {
    setAttributeValue(nodeId = getNodeBySelector(nodeSelector), name, value)
}

/**
 * Returns boxes for the node corresponding to the given [selector], or null if the selector didn't match any node.
 */
suspend fun DOMDomain.getBoxModel(selector: CssSelector): BoxModel? = findNodeBySelector(selector)?.let { getBoxModel(it) }

/**
 * Returns boxes for the node corresponding to the given [nodeId].
 *
 * [Official doc](https://chromedevtools.github.io/devtools-protocol/tot/DOM/#method-getBoxModel)
 */
suspend fun DOMDomain.getBoxModel(nodeId: NodeId): BoxModel = getBoxModel { this.nodeId = nodeId }.model
