package com.smallcloud.codify.modes.completion.renderer


import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Color
import java.awt.Graphics
import java.awt.Rectangle


class BlockElementRenderer(
    private val editor: Editor,
    private val blockText: List<String>,
    private val deprecated: Boolean
) : EditorCustomElementRenderer {
    private var color: Color? = null

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val line = blockText.maxByOrNull { it.length }
        return editor.contentComponent
            .getFontMetrics(RenderHelper.getFont(editor, deprecated)).stringWidth(line!!)
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int {
        return editor.lineHeight * blockText.size
    }

    override fun paint(
        inlay: Inlay<*>,
        g: Graphics,
        targetRegion: Rectangle,
        textAttributes: TextAttributes
    ) {
        color = color ?: RenderHelper.color
        g.color = color
        g.font = RenderHelper.getFont(editor, deprecated)

        blockText.withIndex().forEach { (i, line) ->
            g.drawString(
                line,
                0,
                targetRegion.y + i * editor.lineHeight + editor.ascent
            )
        }
    }
}
