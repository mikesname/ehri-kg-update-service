package eu.ehri_project.ehri_kg.processors

import eu.ehri_project.ehri_kg.database.DatabaseManager
import eu.ehri_project.ehri_kg.helpers.Config
import eu.ehri_project.ehri_kg.helpers.SourceHelper
import eu.ehri_project.ehri_kg.model.EHRIEvent
import eu.ehri_project.ehri_kg.model.EHRITypes
import eu.ehri_project.ehri_kg.model.EHRIUpdateReport
import eu.ehri_project.ehri_kg.model.UnsupportedEntityTypeException
import eu.ehri_project.ehri_kg.sparql.SparqlDatasetQueryProcessor
import io.github.oshai.kotlinlogging.KotlinLogging
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Single
import org.apache.jena.query.Dataset

class EHRIUpdatesProcessor(
    val config: Config,
    val database: DatabaseManager,
    private val createUpdateProcessor: (EHRITypes) -> UpdatesProcessor,
    private val dryRun: Boolean = false
) {

    val eventDetailsSparqlQuery = config.get("eventDetailsSparqlQuery")
    val emptyEventReport = EHRIUpdateReport(EHRIEvent("", "", "", "", ""), emptyList(), emptyList())
    var previousEventErroredOrNotProcessed = false

    init {
        org.apache.jena.query.ARQ.init()
    }

    private val logger = KotlinLogging.logger {}

    fun process(observable: Single<Flowable<Dataset>>): Single<Flowable<EHRIUpdateReport>> {
        return observable.map {
            it.concatMap {
                Flowable.fromIterable(getEventTypeAndId(it)).map {
                   processEvent(it)
                }
            }
        }
    }

    fun processEvent(event: EHRIEvent): EHRIUpdateReport {
        try {
            with(createUpdateProcessor(selectEntityTypeCase(event.type, event.id))) {
                if(!previousEventErroredOrNotProcessed && database.checkIfSuccessfullyProcessed(event)) {
                    logger.info { "Skipping the event as it was already successfully processed in a previous run: $event" }
                    return emptyEventReport
                } else {
                    previousEventErroredOrNotProcessed = true
                    val graphQLContent = fetchGraphQLData(event)
                    val dataBefore = getDataStatus(event)
                    val turtleResult = transformToRDF(graphQLContent)
                    val executedQueries = update(event, turtleResult)
                    val dataAfter = getDataStatus(event)
                    val dataDiff = compareGraphs(dataBefore, dataAfter)
                    return EHRIUpdateReport(event, executedQueries, dataDiff, dryRun = dryRun)
                }
            }
        } catch (e: UnsupportedEntityTypeException) {
            logger.debug { "Ignoring event ${event.eventId}: ${e.message}" }
            return emptyEventReport
        } catch (e: Exception) {
            return EHRIUpdateReport(event, emptyList(), emptyList(), e.stackTraceToString(), dryRun = dryRun)
        }
    }

    private fun selectEntityTypeCase(type: String, id: String? = "null"): EHRITypes {
        return when(type) {
            "Country" -> EHRITypes.COUNTRY
            "Repository" -> EHRITypes.INSTITUTION
            "DocumentaryUnit" -> EHRITypes.ARCHIVAL_DESCRIPTION
            "CvocConcept" -> EHRITypes.VOCABULARY
            "Link" -> EHRITypes.LINK
            "HistoricalAgent" -> id?.let {
                if(it.startsWith("ehri_cb")) EHRITypes.CORPORATE_BODY
                else if(id.startsWith("ehri_pers")) EHRITypes.PERSON
                else null
            } ?: throw Exception("Unknown or unsupported Historical Agent type for id $id")
            else -> throw UnsupportedEntityTypeException("Unknown or unsupported type $type")
        }
    }

    private fun getEventTypeAndId(dataset: Dataset): List<EHRIEvent> {
        logger.info { "Extracting event information" }
        val sparqlQuery = SourceHelper.readFile(eventDetailsSparqlQuery)
        val resultSet = SparqlDatasetQueryProcessor(dataset).query(sparqlQuery)
        val events = resultSet.asSequence().toList().map {
            EHRIEvent(
                it.getLiteral("eventId").string,
                it.getLiteral("eventType").string,
                it.getLiteral("date").string,
                it.getLiteral("ids").string,
                it.getLiteral("types").string,
            )
        }
        if (events.isEmpty()) {
            // Keep-alive messages carry no data, but the mapping still turns them into a skeleton event
            // whose only literal is an empty event type
            val hasData = dataset.defaultModel.listObjects().toList()
                .any { it.isLiteral && it.asLiteral().lexicalForm.isNotEmpty() }
            if (!hasData) logger.debug { "Keep-alive or empty message received" }
            else logger.warn {
                "Event data received but no event could be extracted from it; " +
                    "check that the SSE mapping rules match $eventDetailsSparqlQuery"
            }
        }
        return events
    }
}

