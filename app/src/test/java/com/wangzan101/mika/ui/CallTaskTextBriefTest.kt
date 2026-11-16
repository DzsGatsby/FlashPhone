package com.wangzan101.mika.ui

import com.wangzan101.mika.calls.CallTaskBrief
import org.junit.Assert.*
import org.junit.Test

class CallTaskTextBriefTest {
    @Test fun allLegacyFieldsSurviveOpeningAndSavingTheSingleEditor() {
        val original = CallTaskDraft().apply {
            restoreFields(listOf("15555550123", "确认今天营业时间", "几点关门？\n今天营业吗？", "之前去过\n有会员卡", "不要预约或付款", "sim-a"))
        }
        val before = original.snapshotFields()
        val editor = CallTaskTextFields.fromDraft(original)
        assertTrue(editor.hasSections)
        val parsed = CallTaskTextFields.parse(editor.editorText(), true).getOrThrow()
        parsed.applyTo(original)
        assertEquals(before, original.snapshotFields())
        assertEquals(listOf("几点关门？", "今天营业吗？"), parsed.brief().validatedSnapshot().questions)
    }

    @Test fun editingOnlyTheGoalPreservesLegacyQuestionsContextAndBoundaries() {
        val original = CallTaskTextFields("旧目标", "旧问题？", "旧背景", "不能付款")
        val edited = CallTaskTextFields.parse(original.editorText().replace("旧目标", "确认营业时间"), true).getOrThrow()
        assertEquals(original.copy(goal = "确认营业时间"), edited)
    }

    @Test fun plainTextIsOneGoalAndCanUseTheExistingDialValidation() {
        val text = "确认营业时间，并问是否需要预约。不要付款。"
        val fields = CallTaskTextFields.parse(text).getOrThrow()
        assertEquals(text, fields.goal)
        assertEquals(text, fields.editorText())
        assertFalse(fields.hasSections)
        assertEquals(CallTaskBrief(text), fields.brief().validatedSnapshot())
    }

    @Test fun incompleteLegacySectionEditsNeverPartiallyOverwriteTheDraft() {
        val draft = CallTaskDraft().apply { restoreFields(listOf("12345", "目标", "问题", "背景", "边界", "sim1")) }
        val before = draft.snapshotFields()
        val incomplete = CallTaskTextFields.fromDraft(draft).editorText().replace("【背景】", "【背")
        val result = CallTaskTextFields.parse(incomplete, requireSections = true)
        assertTrue(result.isFailure)
        result.getOrNull()?.applyTo(draft)
        assertEquals(before, draft.snapshotFields())
        assertTrue(CallTaskTextFields.parse("", requireSections = true).isFailure)
    }

    @Test fun sectionsMayBeIntentionallyClearedWithoutDroppingOtherFields() {
        val original = CallTaskTextFields("目标", "问题", "背景", "边界")
        val cleared = CallTaskTextFields.parse(original.editorText().replace("【必问问题】\n问题", "【必问问题】\n"), true).getOrThrow()
        assertEquals(original.copy(questions = ""), cleared)
    }

    @Test fun whitespaceAndMultilineLegacyTextRoundTripsExactly() {
        val fields = CallTaskTextFields(" 目标 \n", "\n问题一\n问题二\n", "\n背景\n", " 边界 \n\n")
        assertEquals(fields, CallTaskTextFields.parse(fields.editorText(), true).getOrThrow())
    }

    @Test fun ambiguousOrReorderedHeadingsAreRejectedRatherThanLosingContent() {
        val fields = CallTaskTextFields("目标", "问题", "背景", "边界")
        assertTrue(CallTaskTextFields.parse(fields.editorText() + "\n【背景】\n另一段", true).isFailure)
        assertTrue(CallTaskTextFields.parse(fields.editorText().replace("【背景】", "【边界】"), true).isFailure)
    }

    @Test fun existingLimitsStillApplyToSingleEditorAndMigratedFields() {
        assertTrue(runCatching { CallTaskTextFields("x".repeat(CallTaskBrief.MAX_GOAL_CHARS + 1)).brief().validatedSnapshot() }.isFailure)
        assertTrue(runCatching { CallTaskTextFields("目标", (1..9).joinToString("\n") { "问题$it" }).brief().validatedSnapshot() }.isFailure)
        assertTrue(runCatching { CallTaskTextFields("目标", context = "x".repeat(1001)).brief().validatedSnapshot() }.isFailure)
        assertTrue(runCatching { CallTaskTextFields("目标", constraints = "x".repeat(601)).brief().validatedSnapshot() }.isFailure)
    }
}
