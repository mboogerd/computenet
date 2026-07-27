package civictech.cell.proxy

import civictech.cell.Consumer
import civictech.cell.host.HostManagementApi
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * W4.6 (C-5 completion, spec 10/14 §Reflection budget): every in-process cell
 * API proxy for a `@Contract` interface must be backed by a KSP-generated
 * class registered in `civictech.gen.wire.ProxyRegistry` — never a
 * `java.lang.reflect.Proxy.newProxyInstance` dynamic proxy.
 */
class ProxyGenerationTest {

    @Test
    fun `data contract proxies are KSP-generated, not JDK dynamic proxies`() {
        val calls = mutableListOf<String>()
        val consumer = Proxy.fromClass<Consumer<String>>(Consumer::class.java) { _, method, args ->
            calls += "${method.name}(${args?.joinToString(",")})"
            null
        }

        java.lang.reflect.Proxy.isProxyClass(consumer.javaClass) shouldBe false
        consumer.javaClass.name.startsWith("civictech.gen.wire.generated.") shouldBe true

        consumer.provide("hi")
        calls shouldBe listOf("provide(hi)")
    }

    @Test
    fun `management contract proxies are KSP-generated too`() {
        val calls = mutableListOf<String>()
        val api = Proxy.fromClass<HostManagementApi>(HostManagementApi::class.java) { _, method, _ ->
            calls += method.name
            null
        }

        java.lang.reflect.Proxy.isProxyClass(api.javaClass) shouldBe false
    }

    @Test
    fun `standard proxy behaviors (noop, buffering, callback) resolve through the registry`() {
        java.lang.reflect.Proxy.isProxyClass(noop<Consumer<Int>>().javaClass) shouldBe false
        java.lang.reflect.Proxy.isProxyClass(buffering<Consumer<Int>>(mutableListOf()).javaClass) shouldBe false
        java.lang.reflect.Proxy.isProxyClass(callback<Consumer<Int>> { }.javaClass) shouldBe false
    }
}
