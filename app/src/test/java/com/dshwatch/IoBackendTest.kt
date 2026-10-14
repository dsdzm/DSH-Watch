package com.dshwatch

import com.dshwatch.domain.model.Provider
import com.dshwatch.domain.model.ReasoningEffort
import com.dshwatch.net.backend.ChatBackend
import com.dshwatch.net.backend.ChatEvent
import com.dshwatch.net.backend.ChatRequest
import com.dshwatch.net.backend.DiagStep
import com.dshwatch.net.backend.IoBackend
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * 后端调用的线程上下文测试。
 *
 * 起因是真机上的 `NetworkOnMainThreadException`：
 * `listModels()` 里是裸的 `call.execute()`，而「供应商」页从 Main dispatcher 调它。
 * 这类问题**JVM 单测里永远抓不到**（没有主线程网络检查），所以改成用装饰器
 * 从结构上保证，并且用「线程名不同」把这个保证钉死。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IoBackendTest {

    private val provider = Provider(id = "p1", name = "Test")

    /** 记录每个方法被调用时所处的线程名 */
    private class RecordingBackend : ChatBackend {
        var listModelsThread = ""
        var testThread = ""
        var diagnoseThread = ""
        var streamThread = ""

        override fun streamChat(request: ChatRequest): Flow<ChatEvent> = flow {
            streamThread = Thread.currentThread().name
            emit(ChatEvent.Completed)
        }

        override suspend fun listModels(
            provider: Provider,
            apiKey: String,
            serverToken: String,
        ): List<String> {
            listModelsThread = Thread.currentThread().name
            return emptyList()
        }

        override suspend fun testConnection(
            provider: Provider,
            apiKey: String,
            serverToken: String,
        ): String {
            testThread = Thread.currentThread().name
            return "ok"
        }

        override suspend fun diagnose(
            provider: Provider,
            apiKey: String,
            serverToken: String,
        ): List<DiagStep> {
            diagnoseThread = Thread.currentThread().name
            return emptyList()
        }

        override fun cancelActive() = Unit
    }

    private fun request() = ChatRequest(
        provider = provider,
        baseUrl = "https://example.com/v1",
        apiKey = "k",
        serverToken = "",
        model = "m",
        systemPrompt = "",
        history = emptyList(),
        temperature = 0f,
        topP = 1f,
        maxTokens = 16,
        stream = true,
        reasoningEffort = ReasoningEffort.DEFAULT,
        usageInStream = false,
    )

    @Test
    fun `suspend calls are moved off the caller thread`() = runTest {
        val delegate = RecordingBackend()
        val backend: ChatBackend = IoBackend(delegate)
        val callerThread = Thread.currentThread().name

        backend.listModels(provider, "key", "token")
        backend.testConnection(provider, "key", "token")
        backend.diagnose(provider, "key", "token")

        assertThat(delegate.listModelsThread).isNotEmpty()
        assertThat(delegate.listModelsThread).isNotEqualTo(callerThread)
        assertThat(delegate.testThread).isNotEqualTo(callerThread)
        assertThat(delegate.diagnoseThread).isNotEqualTo(callerThread)
    }

    @Test
    fun `streaming is collected off the caller thread`() = runTest {
        val delegate = RecordingBackend()
        val backend: ChatBackend = IoBackend(delegate)
        val callerThread = Thread.currentThread().name

        backend.streamChat(request()).collect { }

        assertThat(delegate.streamThread).isNotEmpty()
        assertThat(delegate.streamThread).isNotEqualTo(callerThread)
    }

    @Test
    fun `cancel is passed straight through`() = runTest {
        var cancelled = false
        val delegate = object : ChatBackend by RecordingBackend() {
            override fun cancelActive() {
                cancelled = true
            }
        }
        IoBackend(delegate).cancelActive()
        assertThat(cancelled).isTrue()
    }
}
