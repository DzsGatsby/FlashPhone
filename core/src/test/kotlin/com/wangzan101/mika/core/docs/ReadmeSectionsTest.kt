package com.wangzan101.mika.core.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 继承组件清单里生成的段落和源头一致;不一致就跑 `./gradlew updateReadme`。 */
class ReadmeSectionsTest {
    private val root = File("..").canonicalFile

    @Test fun englishCatalogMatchesGenerated() {
        val catalog = File(root, ReadmeSections.CATALOG_EN).readText()
        val expected = ReadmeSections.render(catalog, ReadmeSections.sections(root, english = true))
        assertEquals("${ReadmeSections.CATALOG_EN} 里生成的段落过期了,运行 ./gradlew updateReadme", expected, catalog)
    }

    @Test fun chineseCatalogMatchesGenerated() {
        val catalog = File(root, ReadmeSections.CATALOG_ZH).readText()
        val expected = ReadmeSections.render(catalog, ReadmeSections.sections(root, english = false))
        assertEquals("${ReadmeSections.CATALOG_ZH} 里生成的段落过期了,运行 ./gradlew updateReadme", expected, catalog)
    }

    @Test fun renderReplacesOnlyBetweenMarkers() {
        val text = "前\n${ReadmeSections.start("x")}\n旧\n${ReadmeSections.end("x")}\n后\n"
        assertEquals(
            "前\n${ReadmeSections.start("x")}\n新\n${ReadmeSections.end("x")}\n后\n",
            ReadmeSections.render(text, mapOf("x" to "新")),
        )
    }

    @Test fun missingMarkerFails() {
        val e = assertThrows(IllegalStateException::class.java) { ReadmeSections.render("没有标记", mapOf("x" to "新")) }
        assertTrue(e.message.orEmpty(), "缺少" in e.message.orEmpty())
    }

    @Test fun readsPresetsFromSource() {
        val src = """
            ProviderPreset("甲", ProviderConfig.Kind.OpenAiCompat, "https://a.example/v1", caps()),
            ProviderPreset("乙", ProviderConfig.Kind.Gemini, "https://b.example", caps(vision = true)),
            ProviderPreset("自定义", ProviderConfig.Kind.OpenAiCompat, "", caps()),
        """.trimIndent()
        assertEquals(
            listOf(
                ReadmeSections.Preset("甲", "OpenAiCompat", "https://a.example/v1"),
                ReadmeSections.Preset("乙", "Gemini", "https://b.example"),
                ReadmeSections.Preset("自定义", "OpenAiCompat", ""),
            ),
            ReadmeSections.presets(src),
        )
    }

    /** 自定义能接的接口也写 Responses,排在最后(和界面上的次序一致),不按预设里出现的先后。 */
    @Test fun customKindsListResponsesLast() {
        val presets = listOf(
            ReadmeSections.Preset("甲", "OpenAiCompat", "https://a.example/v1"),
            ReadmeSections.Preset("乙", "OpenAiResponses", "https://b.example/v1"),
            ReadmeSections.Preset("丙", "Gemini", "https://c.example"),
            ReadmeSections.Preset("自定义", "OpenAiCompat", ""),
        )
        assertTrue(ReadmeSections.providers(presets).endsWith("内置 甲、乙、丙 等服务商预设；其他服务商选「自定义」，OpenAI 兼容、Gemini、OpenAI Responses 接口都能接。"))
        assertTrue(ReadmeSections.providersEn(presets).endsWith("which takes any OpenAI-compatible, Gemini or OpenAI Responses endpoint."))
    }
}
