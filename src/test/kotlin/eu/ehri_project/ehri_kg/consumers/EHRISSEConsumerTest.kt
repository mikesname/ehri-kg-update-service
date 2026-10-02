package eu.ehri_project.ehri_kg.consumers

import kotlin.test.Test
import kotlin.test.assertEquals

class EHRISSEConsumerTest {

    private val mappingPath = "conf/ehri_sse_mapping.shexml"
    private val streamLine = "STREAM ehri_sse <https://portal.ehri-project.eu/admin/monitor/_events>"

    private fun changedLines(consumer: EHRISSEConsumer): List<Pair<String, String>> {
        val original = consumer.mappingRules.lines()
        val rewritten = consumer.buildMappingRules().lines()
        assertEquals(original.size, rewritten.size)
        return original.zip(rewritten).filter { (a, b) -> a != b }
    }

    @Test
    fun `mapping rules are unchanged without overrides`() {
        val consumer = EHRISSEConsumer(mappingPath)

        assertEquals(consumer.mappingRules, consumer.buildMappingRules())
    }

    @Test
    fun `last event id is appended to the stream URL only`() {
        val consumer = EHRISSEConsumer(mappingPath, lastEventId = "12345")

        assertEquals(
            listOf(streamLine to "STREAM ehri_sse <https://portal.ehri-project.eu/admin/monitor/_events?Last-Event-Id=12345>"),
            changedLines(consumer)
        )
    }

    @Test
    fun `sse endpoint replaces the stream URL`() {
        val consumer = EHRISSEConsumer(mappingPath, sseEndpoint = "http://localhost:9000/events")

        assertEquals(
            listOf(streamLine to "STREAM ehri_sse <http://localhost:9000/events>"),
            changedLines(consumer)
        )
    }

    @Test
    fun `sse endpoint and last event id are combined`() {
        val consumer = EHRISSEConsumer(mappingPath, lastEventId = "12345", sseEndpoint = "http://localhost:9000/events")

        assertEquals(
            listOf(streamLine to "STREAM ehri_sse <http://localhost:9000/events?Last-Event-Id=12345>"),
            changedLines(consumer)
        )
    }

    @Test
    fun `last event id is added to an existing query string`() {
        val consumer = EHRISSEConsumer(mappingPath, lastEventId = "12345", sseEndpoint = "http://localhost:9000/events?type=all")

        assertEquals(
            listOf(streamLine to "STREAM ehri_sse <http://localhost:9000/events?type=all&Last-Event-Id=12345>"),
            changedLines(consumer)
        )
    }

    @Test
    fun `last event id is URL-encoded`() {
        val consumer = EHRISSEConsumer(mappingPath, lastEventId = "a b&c")

        assertEquals(
            listOf(streamLine to "STREAM ehri_sse <https://portal.ehri-project.eu/admin/monitor/_events?Last-Event-Id=a+b%26c>"),
            changedLines(consumer)
        )
    }
}
