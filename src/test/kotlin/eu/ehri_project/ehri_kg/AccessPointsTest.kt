package eu.ehri_project.ehri_kg

import eu.ehri_project.ehri_kg.database.DatabaseManager
import eu.ehri_project.ehri_kg.model.EHRIEvent
import eu.ehri_project.ehri_kg.processors.EHRIUpdatesProcessor
import eu.ehri_project.ehri_kg.processors.UpdatesProcessorFactory
import eu.ehri_project.ehri_kg.support.FakeGraphQLClient
import eu.ehri_project.ehri_kg.support.InMemorySparqlStore
import eu.ehri_project.ehri_kg.support.testConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import org.apache.jena.rdf.model.ResourceFactory.createProperty
import org.apache.jena.rdf.model.ResourceFactory.createResource
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Access points are owned by the unit: updating or deleting it updates or removes them, in both directions. */
class AccessPointsTest {

    @TempDir
    lateinit var dir: File

    val unitId = "be-000001-kd_00001"
    val unit = createResource("http://lod.ehri-project-test.eu/units/$unitId")
    val creator = createResource("http://lod.ehri-project-test.eu/vocabularies/ehri-cb/000001")
    val camp = createResource("http://lod.ehri-project-test.eu/vocabularies/ehri-camps/1")
    val term = createResource("http://lod.ehri-project-test.eu/vocabularies/ehri-terms/1")

    val hasCreator = createProperty("https://www.ica.org/standards/RiC/ontology#hasCreator")
    val isCreatorOf = createProperty("https://www.ica.org/standards/RiC/ontology#isCreatorOf")
    val hasOrHadSubject = createProperty("https://www.ica.org/standards/RiC/ontology#hasOrHadSubject")
    val isOrWasSubjectOf = createProperty("https://www.ica.org/standards/RiC/ontology#isOrWasSubjectOf")

    val original = File("src/test/resources/example/graphql/$unitId.json").readText()

    /** The unit without its creator and with only its first subject. */
    val updated = Json.parseToJsonElement(original).jsonObject.let { doc ->
        val query = doc.getValue("data").jsonObject.getValue("query").jsonObject
        val subjects = query.getValue("subjects").jsonArray
        JsonObject(mapOf("data" to JsonObject(mapOf("query" to JsonObject(
            query + mapOf("creators" to JsonArray(emptyList()), "subjects" to JsonArray(subjects.take(1)))
        ))))).toString()
    }

    private fun process(vararg events: Pair<String, String>): InMemorySparqlStore {
        val store = InMemorySparqlStore()
        val responses = events.map { it.second }.iterator()
        val config = testConfig(dir)
        val processor = EHRIUpdatesProcessor(
            config,
            DatabaseManager(config),
            UpdatesProcessorFactory(config, FakeGraphQLClient { responses.next() }, sparqlStore = store)::createUpdateProcessor
        )
        events.forEachIndexed { i, (eventType, _) ->
            val report = processor.processEvent(EHRIEvent("e$i", eventType, "2026-01-01T00:00:00Z", unitId, "DocumentaryUnit"))
            assertEquals("", report.errors)
        }
        return store
    }

    @Test
    fun `a unit's access points are mapped in both directions`() {
        val kg = process("create-event" to original).dataset.defaultModel

        assertTrue(kg.contains(unit, hasCreator, creator))
        assertTrue(kg.contains(creator, isCreatorOf, unit))
        assertTrue(kg.contains(unit, hasOrHadSubject, camp))
        assertTrue(kg.contains(camp, isOrWasSubjectOf, unit))
    }

    @Test
    fun `access points removed from a unit are removed in both directions on update`() {
        val kg = process("create-event" to original, "update-event" to updated).dataset.defaultModel

        assertFalse(kg.contains(unit, hasCreator, creator))
        assertFalse(kg.contains(creator, isCreatorOf, unit))
        assertFalse(kg.contains(unit, hasOrHadSubject, camp))
        assertFalse(kg.contains(camp, isOrWasSubjectOf, unit))
        assertTrue(kg.contains(unit, hasOrHadSubject, term))
        assertTrue(kg.contains(term, isOrWasSubjectOf, unit))
    }

    @Test
    fun `a deleted unit's access points are removed in both directions`() {
        val kg = process("create-event" to original, "delete-event" to original).dataset.defaultModel

        assertFalse(kg.contains(unit, hasCreator, creator))
        assertFalse(kg.contains(creator, isCreatorOf, unit))
        assertFalse(kg.contains(unit, hasOrHadSubject, camp))
        assertFalse(kg.contains(camp, isOrWasSubjectOf, unit))
        assertFalse(kg.contains(term, isOrWasSubjectOf, unit))
    }
}
