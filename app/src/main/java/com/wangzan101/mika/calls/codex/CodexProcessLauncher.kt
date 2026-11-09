package com.wangzan101.mika.calls.codex

import android.content.Context
import android.os.Build
import java.io.File
import java.util.concurrent.TimeUnit

internal class CodexProcessLauncher(context: Context) {
    private val app = context.applicationContext
    private val base get() = File(app.noBackupFilesDir, "calls-codex-home")
    private val binary get() = File(app.applicationInfo.nativeLibraryDir, BINARY_NAME)
    val workspace: File get() = File(base, "workspace")
    val available: Boolean
        get() = Build.SUPPORTED_ABIS.contains("arm64-v8a") && binary.isFile && binary.canExecute()

    /** Blocking. A fresh, short-lived process is the only recipient of the selected provider key. */
    fun launch(apiKey: String): Process = launch(base, apiKey)

    /** Instrumented smoke test only: fresh profile, no credentials, and no remote provider. */
    fun launchHandshakeProbe(profile: File): Process = launch(profile, null)

    private fun launch(profile: File, apiKey: String?): Process {
        if (!available) throw CodexProblem(CodexIssue.ENGINE_UNAVAILABLE)
        val home = File(profile, "engine")
        val workspace = File(profile, "workspace")
        val temp = File(profile, "tmp")
        listOf(home, workspace, temp).forEach {
            if (!it.isDirectory && !it.mkdirs()) throw CodexProblem(CodexIssue.STORAGE)
        }

        val argv = mutableListOf(binary.absolutePath, "--listen", "stdio://", "--strict-config")
        // The pinned upstream config keys are passed as fixed arguments. No user content or credentials.
        val baseUrl = if (apiKey == null) "https://127.0.0.1:9" else "https://api.openai.com/v1"
        val config = listOf(
            """model_provider="mika-openai"""",
            """model_providers.mika-openai={name="OpenAI",base_url="$baseUrl",env_key="OPENAI_API_KEY",wire_api="responses",request_max_retries=0,stream_max_retries=0,stream_idle_timeout_ms=30000}""",
            """cli_auth_credentials_store="ephemeral"""",
            """approval_policy="never"""",
            """sandbox_mode="read-only"""",
            """web_search="disabled"""",
            """model_reasoning_effort="low"""",
            """model_verbosity="low"""",
            "check_for_update_on_startup=false",
            "analytics.enabled=false",
            "allow_login_shell=false",
            """shell_environment_policy={inherit="none"}""",
            "mcp_servers={}",
            "features.memories=false",
            "memories.generate_memories=false",
            "memories.use_memories=false",
            "features.shell_tool=false",
            "features.unified_exec=false",
            "features.code_mode=false",
            "features.apply_patch_freeform=false",
            "features.codex_git_commit=false",
            "features.apps=false",
            "features.connectors=false",
            "features.plugins=false",
            "features.hooks=false",
            "features.codex_hooks=false",
            "features.multi_agent=false",
            "features.remote_control=false",
            "features.remote_models=false",
            "features.request_permissions_tool=false",
            "features.tool_search=false",
        )
        for (entry in config) argv.addAll(listOf("-c", entry))
        return try {
            ProcessBuilder(argv).directory(workspace).redirectErrorStream(false).apply {
                environment().clear()
                environment()["PATH"] = "/system/bin"
                environment()["SHELL"] = "/system/bin/sh"
                environment()["HOME"] = home.absolutePath
                environment()["CODEX_HOME"] = home.absolutePath
                environment()["TMPDIR"] = temp.absolutePath
                if (apiKey != null) environment()["OPENAI_API_KEY"] = apiKey
                else for (name in listOf("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY")) {
                    environment()[name] = "http://127.0.0.1:9"
                }
            }.start()
        } catch (_: Exception) {
            throw CodexProblem(CodexIssue.ENGINE_START)
        }
    }

    companion object {
        const val BINARY_NAME = "libmika_codex.so"
        const val ENGINE_VERSION = "0.167.0"

        fun stop(process: Process, graceful: Boolean) {
            if (graceful) {
                runCatching { process.outputStream.close() }
                if (runCatching { process.waitFor(500, TimeUnit.MILLISECONDS) }.getOrDefault(false)) {
                    runCatching { process.inputStream.close() }
                    runCatching { process.errorStream.close() }
                    return
                }
            }
            // Destroy first so closing a stream cannot wait on a live peer.
            runCatching { process.destroy() }
            if (runCatching { !process.waitFor(300, TimeUnit.MILLISECONDS) }.getOrDefault(true)) {
                runCatching { process.destroyForcibly() }
                runCatching { process.waitFor(700, TimeUnit.MILLISECONDS) }
            }
            runCatching { process.outputStream.close() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
        }
    }
}
