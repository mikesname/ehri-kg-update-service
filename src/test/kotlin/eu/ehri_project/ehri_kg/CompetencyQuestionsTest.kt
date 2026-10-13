package eu.ehri_project.ehri_kg

import eu.ehri_project.ehri_kg.database.DatabaseManager
import eu.ehri_project.ehri_kg.model.EHRIEvent
import eu.ehri_project.ehri_kg.processors.EHRIUpdatesProcessor
import eu.ehri_project.ehri_kg.processors.UpdatesProcessorFactory
import eu.ehri_project.ehri_kg.support.FakeGraphQLClient
import eu.ehri_project.ehri_kg.support.InMemorySparqlStore
import eu.ehri_project.ehri_kg.support.testConfig
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.jena.query.QueryExecutionFactory
import org.apache.jena.query.QueryFactory
import org.apache.jena.query.ResultSetFactory
import org.apache.jena.query.ResultSetFormatter
import org.apache.jena.riot.Lang
import org.apache.jena.riot.RDFDataMgr
import org.apache.jena.riot.ResultSetMgr
import org.apache.jena.riot.resultset.ResultSetLang
import org.apache.jena.sparql.resultset.ResultSetCompare
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Runs the competency questions of the EHRI-KG example dataset (https://github.com/EHRI/ehri-kg-example) against
 * a KG built from mock GraphQL responses, comparing their results with those of the example dataset.
 *
 * The KG is written to build/e2e/kg.ttl for inspection.
 */
class CompetencyQuestionsTest {

    companion object {
        val example = File("src/test/resources/example")

        /** The KG, with mock federated query endpoints as named graphs. */
        val store = InMemorySparqlStore().apply {
            dataset.addNamedModel("https://dbpedia.org/sparql/", RDFDataMgr.loadModel("$example/services/dbpedia.ttl"))
            dataset.addNamedModel("http://lod.xdams.org/sparql", RDFDataMgr.loadModel("$example/services/cdec.ttl"))
        }

        /** Processes each entity in graphql/events.csv as a create event, with the response in graphql/<id>.json. */
        val processingErrors = run {
            val config = testConfig(createTempDirectory().toFile())
            val graphQL = FakeGraphQLClient { File(example, "graphql/${it.id}.json").readText() }
            val processor = EHRIUpdatesProcessor(
                config,
                DatabaseManager(config),
                UpdatesProcessorFactory(config, graphQL, sparqlStore = store)::createUpdateProcessor
            )
            readCsv("graphql/events.csv")
                .map { EHRIEvent("e2e", "create-event", "2026-01-01T00:00:00Z", it.getValue("id"), it.getValue("type")) }
                .map(processor::processEvent)
                .filter { it.errors.isNotEmpty() }
                .map { "${it.receivedEvent.id}: ${it.errors.lineSequence().first()}" }
        }

        init {
            File("build/e2e").mkdirs()
            File("build/e2e/kg.ttl").outputStream().use { RDFDataMgr.write(it, store.dataset.defaultModel, Lang.TURTLE) }
        }

        fun readCsv(path: String): List<Map<String, String>> =
            CSVParser.parse(File(example, path), Charsets.UTF_8, CSVFormat.DEFAULT.withFirstRecordAsHeader())
                .use { parser -> parser.records.map { it.toMap() } }
    }

    @Test
    fun `the example GraphQL responses are processed without errors`() {
        assertEquals(emptyList(), processingErrors)
    }

    @TestFactory
    fun `competency questions`() = readCsv("competency_questions.csv")
        .filter { !it["SPARQL"].isNullOrBlank() }
        .map { cq ->
            dynamicTest("${cq["#"]}: ${cq["Competency Question"]}") {
                // Query the mock endpoints' named graphs instead of the remote services
                val query = QueryFactory.create(cq["SPARQL"]!!.replace(Regex("""\bSERVICE\s+<"""), "GRAPH <"))
                val actual = QueryExecutionFactory.create(query, store.dataset)
                    .use { ResultSetFactory.copyResults(it.execSelect()) }
                val expected = File(example, "expected/${cq["#"]}.tsv").inputStream()
                    .use { ResultSetFactory.copyResults(ResultSetMgr.read(it, ResultSetLang.SPARQLResultSetTSV)) }
                val matches = if (query.hasOrderBy()) ResultSetCompare.equalsByValueAndOrder(expected, actual)
                    else ResultSetCompare.equalsByValue(expected, actual)
                if (!matches) {
                    expected.reset()
                    actual.reset()
                    fail("expected:\n${ResultSetFormatter.asText(expected)}\nactual:\n${ResultSetFormatter.asText(actual)}")
                }
            }
        }
}
