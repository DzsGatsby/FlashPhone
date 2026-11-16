package com.wangzan101.mika.ui

import com.wangzan101.mika.calls.CallTaskBrief

/** A single editor presentation of the existing, six-field durable draft. No storage migration. */
internal data class CallTaskTextFields(
    val goal: String,
    val questions: String = "",
    val context: String = "",
    val constraints: String = "",
) {
    val hasSections: Boolean get() = questions.isNotEmpty() || context.isNotEmpty() || constraints.isNotEmpty()

    fun editorText(): String = if (!hasSections) goal else
        headings.zip(listOf(goal, questions, context, constraints))
            .joinToString("\n\n") { (heading, value) -> "$heading\n$value" }

    fun brief() = CallTaskBrief(goal, questions.lineSequence().map(String::trim).filter(String::isNotEmpty).toList(),
        context, constraints)

    fun applyTo(draft: CallTaskDraft) {
        draft.goal = goal
        draft.questions = questions
        draft.context = context
        draft.constraints = constraints
    }

    companion object {
        private val headings = listOf("【目标】", "【必问问题】", "【背景】", "【边界】")
        private val headingPattern = Regex("(?m)^【(目标|必问问题|背景|边界)】\\r?$")

        fun fromDraft(draft: CallTaskDraft) = CallTaskTextFields(draft.goal, draft.questions, draft.context, draft.constraints)

        fun parse(text: String, requireSections: Boolean = false): Result<CallTaskTextFields> = runCatching {
            val markers = headingPattern.findAll(text).toList()
            if (markers.isEmpty() && !requireSections) return@runCatching CallTaskTextFields(text)
            require(markers.map { it.value.trimEnd('\r') } == headings && text.substring(0, markers.first().range.first).isBlank()) {
                "请保留目标、必问问题、背景和边界这四个分段标题，避免遗漏原有交代。"
            }
            val values = markers.mapIndexed { index, match ->
                val start = match.range.last + 1
                val end = markers.getOrNull(index + 1)?.range?.first ?: text.length
                // Strip only the serializer's line separators; preserve user content otherwise.
                text.substring(start, end).removePrefix("\r\n").removePrefix("\n")
                    .let { if (index < markers.lastIndex) it.removeSuffix("\n\n") else it }
            }
            CallTaskTextFields(values[0], values[1], values[2], values[3])
        }
    }
}
