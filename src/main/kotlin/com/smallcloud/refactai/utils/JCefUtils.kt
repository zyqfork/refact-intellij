package com.smallcloud.refactai.utils

import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.cef.browser.CefBrowser

private val logger = Logger.getInstance("com.smallcloud.refactai.utils.JCefUtils")
private const val JS_EXECUTION_TIMEOUT_MS = 5000L
/**
 * Checks if JCEF can start
 */
fun isJcefCanStart(): Boolean {
    return try {
        JBCefApp.isSupported() && JBCefApp.isStarted()
        JBCefApp.isSupported()
    } catch (_: Exception) {
        false
    }
}

/**
 * Safely executes JavaScript in a JCEF browser
 *
 * @param browser The JBCefBrowser instance
 * @param script The JavaScript code to execute
 * @param scope The coroutine scope to use for execution
 */
fun safeExecuteJavaScript(
    browser: JBCefBrowser?,
    script: String,
    repaint: Boolean = false,
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    if (!isBrowserInitialized(browser)) {
        logger.warn("Cannot execute JavaScript: JCEF browser not initialized")
        return
    }

    scope.launch {
        try {
            withTimeout(JS_EXECUTION_TIMEOUT_MS) {
                withContext(Dispatchers.Default) {
                    try {
                        if (browser?.cefBrowser != null) {
                            browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)
                            if(repaint) {
                                browser.component.repaint()
                            }
                        } else {
                            logger.warn("Cannot execute JavaScript: CefBrowser is null")
                        }
                    } catch (e: IllegalStateException) {
                        logger.warn("Failed to execute JavaScript: ${e.message}")
                    } catch (e: Exception) {
                        if (e !is CancellationException) {
                            logger.warn("Error executing JavaScript: ${e.message}", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (e !is CancellationException) {
                logger.warn("JavaScript execution timed out or was cancelled: ${e.message}")
            }
        }
    }
}

/**
 * Checks if a JCEF browser is properly initialized and ready for JavaScript execution
 *
 * @param browser The JBCefBrowser instance to check
 * @return True if the browser is initialized, false otherwise
 */
fun isBrowserInitialized(browser: JBCefBrowser?): Boolean {
    if (browser == null) return false
    
    return try {
        browser.cefBrowser != null && 
            browser.jbCefClient != null && 
            !browser.isDisposed
    } catch (e: Exception) {
        logger.warn("Error checking browser initialization state: ${e.message}")
        false
    }
}
