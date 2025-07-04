package com.smallcloud.refactai.panes.sharedchat

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.laf.UIThemeLookAndFeelInfo
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.testFramework.LightPlatform4TestCase
import com.smallcloud.refactai.panes.sharedchat.browser.ChatWebView
import org.junit.Test
import org.junit.Assert
import org.junit.Ignore
import org.mockito.Mockito
import org.mockito.MockedStatic

/**
 * Test for ChatWebView to verify it handles race conditions properly.
 * This test specifically checks that the ChatWebView can handle a situation where
 * setStyle() is called before the browser is fully initialized, and then the
 * component is disposed while JavaScript might still be executing.
 */
class ChatWebViewTest: LightPlatform4TestCase() {
    private lateinit var mockProject: Project
    private lateinit var mockEditor: Editor
    private lateinit var mockLafManager: LafManager
    private lateinit var mockTheme: UIThemeLookAndFeelInfo
    private lateinit var mockLafManagerStatic: MockedStatic<LafManager>


    override fun setUp() {
        super.setUp()
        mockProject = Mockito.mock(Project::class.java)
        mockEditor = Mockito.mock(Editor::class.java)
        mockLafManager = Mockito.mock(LafManager::class.java)
        mockTheme = Mockito.mock(UIThemeLookAndFeelInfo::class.java)
        
        // Mock the LafManager.getInstance() static method
        mockLafManagerStatic = Mockito.mockStatic(LafManager::class.java)
        mockLafManagerStatic.`when`<LafManager> { LafManager.getInstance() }.thenReturn(mockLafManager)
        
        // Mock the currentUIThemeLookAndFeel property
        Mockito.`when`(mockLafManager.currentUIThemeLookAndFeel).thenReturn(mockTheme)
        
        // Mock the isDark property
        Mockito.`when`(mockTheme.isDark).thenReturn(true)
        
        // Mock the necessary methods of the Editor class
        Mockito.`when`(mockEditor.project).thenReturn(mockProject)
        
        // Create a mock configuration
        val mockConfig = Mockito.mock(Events.Config.UpdatePayload::class.java)
        Mockito.`when`(mockEditor.getUserConfig()).thenReturn(mockConfig)
    }

    override fun tearDown() {
        // Close the static mock to prevent memory leaks
        mockLafManagerStatic.close()
        super.tearDown()
    }
    
    @Test
    fun testBrowserInitializationRaceCondition() {
        // Create a ChatWebView instance with the mocked editor
        val chatWebView = ChatWebView(mockEditor) { /* message handler */ }

        // First test with valid theme - should not throw
        try {
            chatWebView.setStyle()
        } catch (exception: Exception)  {
            Assert.fail("Exception should not have been thrown: ${exception.message}")
        }
        // Force disposal while JavaScript might still be executing
        Thread.sleep(100) // Small delay to ensure the coroutine has started
        chatWebView.dispose()
    }

    @Test @Ignore("fails in ci")
    fun testSetupReactRaceCondition() {
        val chatWebView = ChatWebView(mockEditor) { /* message handler */ }
        try {
            chatWebView.setUpReact(chatWebView.webView.cefBrowser)
        } catch (exception: Exception) {
            Assert.fail("Exception should not have been thrown: ${exception.message}")
        }
        Thread.sleep(100) // Small delay to ensure the coroutine has started
        chatWebView.dispose()
    }

    @Test @Ignore("fails in ci")
    fun testPostMessageRaceCondition() {
        val chatWebView = ChatWebView(mockEditor) { /* message handler */ }
        try {
            chatWebView.postMessage("hello")
            // Just test with a string message
            chatWebView.postMessage("{\"type\": \"chat_message\", \"payload\": {\"message\": \"test message\"}}")
        } catch (exception: Exception) {
            Assert.fail("Exception should not have been thrown: ${exception.message}")
        }
        Thread.sleep(100) // Small delay to ensure the coroutine has started
        chatWebView.dispose()
    }

    @Test
    fun testOpenFileMessageHandling() {
        val openFileMessageReceived = mutableListOf<Events.FromChat>()
        val chatWebView = ChatWebView(mockEditor) { event ->
            openFileMessageReceived.add(event)
        }

        // Test that the OpenFile message is properly parsed and handled
        val openFileMessage = """{"type":"ide/openFile","payload":{"file_path":"/home/mitya/.config/refact/customization.yaml"}}"""
        val parsedEvent = Events.parse(openFileMessage)

        Assert.assertNotNull("OpenFile message should be parsed successfully", parsedEvent)
        Assert.assertTrue("Parsed event should be OpenFile type", parsedEvent is Events.OpenFile)

        val openFileEvent = parsedEvent as Events.OpenFile
        Assert.assertEquals("File path should match", "/home/mitya/.config/refact/customization.yaml", openFileEvent.payload.filePath)

        chatWebView.dispose()
    }
}
