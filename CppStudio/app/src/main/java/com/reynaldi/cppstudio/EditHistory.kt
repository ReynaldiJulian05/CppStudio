package com.reynaldi.cppstudio

import android.os.SystemClock
import kotlin.math.abs

/**
 * Undo / redo history for the editor.
 * Stores whole-text snapshots (programs are small). Typing that happens within [GROUP_MS] of the
 * previous keystroke is grouped into one step, so Undo removes a burst of typing, not one letter.
 */
class EditHistory(private val maxSteps: Int = 100, private val maxChars: Int = 2_000_000) {
    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()
    private var current = ""
    private var lastChange = 0L
    private var chars = 0

    /** Forget everything and start from [text] (used when a file is opened). */
    fun reset(text: String) {
        undoStack.clear(); redoStack.clear(); chars = 0
        current = text; lastChange = 0
    }

    /** Call after every user edit with the full new text. */
    fun record(text: String) {
        if (text == current) return
        val now = SystemClock.uptimeMillis()
        val big = abs(text.length - current.length) > 3          // paste, delete selection, clear all...
        if (undoStack.isEmpty() || now - lastChange > GROUP_MS || big) {
            undoStack.add(current); chars += current.length
            while (undoStack.size > maxSteps || (chars > maxChars && undoStack.size > 1)) {
                chars -= undoStack.removeFirst().length
            }
        }
        redoStack.clear()
        current = text
        lastChange = if (big) 0L else now
    }

    /** Record a formatter or other bulk operation as one distinct undo step. */
    fun recordStep(text: String) {
        if (text == current) return
        undoStack.add(current)
        chars += current.length
        while (undoStack.size > maxSteps || (chars > maxChars && undoStack.size > 1)) {
            chars -= undoStack.removeFirst().length
        }
        redoStack.clear()
        current = text
        lastChange = 0L
    }

    fun canUndo() = undoStack.isNotEmpty()
    fun canRedo() = redoStack.isNotEmpty()

    /** Returns the text to show after Undo, or null if there is nothing to undo. */
    fun undo(): String? {
        if (undoStack.isEmpty()) return null
        val prev = undoStack.removeLast(); chars -= prev.length
        redoStack.add(current)
        current = prev; lastChange = 0L
        return prev
    }

    /** Returns the text to show after Redo, or null if there is nothing to redo. */
    fun redo(): String? {
        if (redoStack.isEmpty()) return null
        val next = redoStack.removeLast()
        undoStack.add(current); chars += current.length
        current = next; lastChange = 0L
        return next
    }

    companion object { const val GROUP_MS = 800L }
}
