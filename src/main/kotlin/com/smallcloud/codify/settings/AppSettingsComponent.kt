package com.smallcloud.codify.settings

import com.intellij.ui.JBSplitter
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.*
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import com.smallcloud.codify.CodifyBundle
import java.awt.Color
import java.awt.event.FocusEvent
import java.awt.event.FocusListener
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener


class AppSettingsComponent {
    val splitter: JBSplitter = JBSplitter(true, 0.3f)
    private val mainPanel: JPanel
    private val experimentalPanel: JPanel
    val myTokenText = JBTextField().apply {
        addKeyListener(object : KeyListener {
            override fun keyTyped(e: KeyEvent?) {}
            override fun keyReleased(e: KeyEvent?) {}
            override fun keyPressed(e: KeyEvent?) {
                if (e?.keyCode == KeyEvent.VK_MINUS && e.isControlDown && e.isAltDown) {
                    developerModeCheckBox.isVisible = true
                    myLongthinkModelText.isVisible = true
                    myLongthinkModelLabel.isVisible = true
                }
            }

        })
    }
    private val myModelText = JBTextField()
    private val myContrastUrlText = JBTextField()
    private val myUseForceCompletionMode = JCheckBox(CodifyBundle.message("advancedSettings.useForceCompletionMode"))
    private val myUseMultipleFilesCompletion = JCheckBox(CodifyBundle.message("advancedSettings.useMultipleFilesCompletion"))
    private val developerModeCheckBox = JCheckBox(CodifyBundle.message("advancedSettings.developerMode")).apply {
        isVisible = false
    }
    private val myLongthinkModelText = JBTextField().apply {
        isVisible = false
    }
    private val myLongthinkModelLabel = JBLabel("Longthink model:").apply {
        isVisible = false
    }

    init {
        mainPanel = FormBuilder.createFormBuilder().run {
            addLabeledComponent(JBLabel("${CodifyBundle.message("advancedSettings.secretApiKey")}: "),
                myTokenText, 1, false)
            addLabeledComponent(JBLabel("${CodifyBundle.message("advancedSettings.model")}: "), myModelText,
                (UIUtil.DEFAULT_VGAP * 1.5).toInt(), false)
            addComponentToRightColumn(
                JBLabel(
                    CodifyBundle.message("advancedSettings.leaveIfNotSure"), UIUtil.ComponentStyle.SMALL,
                    UIUtil.FontColor.BRIGHTER
                ), 0
            )
            addLabeledComponent(JBLabel("${CodifyBundle.message("advancedSettings.inferenceURL")}: "),
                myContrastUrlText, (UIUtil.DEFAULT_VGAP * 1.5).toInt(), false)
            addComponentToRightColumn(
                JBLabel(
                    CodifyBundle.message("advancedSettings.inferenceURLDescription"),
                    UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER
                ), 0
            )
            addComponent(myUseForceCompletionMode, (UIUtil.DEFAULT_VGAP * 1.5).toInt())
            addComponent(
                JBLabel(
                    CodifyBundle.message("advancedSettings.useForceCompletionModeDescription","alt + /"),
                    UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER
                ), 0
            )
            addComponentFillVertically(JPanel(), 0)
        }.panel

        experimentalPanel = FormBuilder.createFormBuilder().run {
            addComponent(TitledSeparator(CodifyBundle.message("advancedSettings.experimentalFeatures")))
            addComponent(myUseMultipleFilesCompletion, UIUtil.LARGE_VGAP)
            addComponent(
                JBLabel(
                    CodifyBundle.message("advancedSettings.useMultipleFilesCompletionDescription"),
                    UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER
                ), 0
            )
            addComponent(developerModeCheckBox, UIUtil.LARGE_VGAP)
            addLabeledComponent(myLongthinkModelLabel, myLongthinkModelText, UIUtil.LARGE_VGAP)
            addComponentFillVertically(JPanel(), 0)
        }.panel

        splitter.firstComponent = mainPanel
        splitter.secondComponent = experimentalPanel
        splitter.isShowDividerControls = true
    }

    val preferredFocusedComponent: JComponent
        get() = myTokenText

    var tokenText: String
        get() = myTokenText.text
        set(newText) {
            myTokenText.text = newText
        }

    var modelText: String
        get() = myModelText.text
        set(newText) {
            myModelText.text = newText
        }

    var contrastUrlText: String
        get() = myContrastUrlText.text
        set(newText) {
            myContrastUrlText.text = newText
        }

    var useForceCompletion: Boolean
        get() {
            return myUseForceCompletionMode.isSelected
        }
        set(value) {
            myUseForceCompletionMode.isSelected = value
        }

    var useMultipleFilesCompletion: Boolean
        get() {
            return myUseMultipleFilesCompletion.isSelected
        }
        set(value) {
            myUseMultipleFilesCompletion.isSelected = value
        }

    var useDeveloperMode: Boolean
        get() = developerModeCheckBox.isSelected
        set(newVal) {
            developerModeCheckBox.isSelected = newVal
        }
    var longthinkModel: String
        get() = myLongthinkModelText.text
        set(newVal) {
            myLongthinkModelText.text = newVal
        }

}