package eu.ehri_project.ehri_kg.sparql

import org.apache.jena.query.*
import org.apache.jena.rdf.model.Model
import org.apache.jena.update.UpdateExecutionFactory
import org.apache.jena.update.UpdateFactory

class SparqlDatasetQueryProcessor(val dataset: Dataset) {

    fun query(query: String): ResultSet {
        val compiledQuery = QueryFactory.create(query)
        return QueryExecution
            .create()
            .dataset(dataset.asDatasetGraph())
            .query(compiledQuery)
            .build().execSelect()
    }
}

interface SparqlStore {
    fun construct(query: String): Model
    fun update(query: String)
}

class RemoteSparqlStore(val queryEndpoint: String, val updateEndpoint: String) : SparqlStore {
    override fun construct(query: String): Model = SparqlEndpointQueryProcessor(queryEndpoint).construct(query)
    override fun update(query: String) = SparqlEndpointQueryProcessor(updateEndpoint).update(query)
}

class SparqlEndpointQueryProcessor(val endpoint: String) {

    fun query(query: String): ResultSet {
        val compiledQuery = QueryFactory.create(query)
        return QueryExecutionFactory
            .sparqlService(endpoint, compiledQuery)
            .execSelect()
    }

    fun construct(query: String): Model {
        val compiledQuery = QueryFactory.create(query)
        return QueryExecutionFactory
            .sparqlService(endpoint, compiledQuery)
            .execConstruct()
    }

    fun update(query: String) {
        val compiledQuery = UpdateFactory.create(query)
        UpdateExecutionFactory
            .createRemote(compiledQuery, endpoint)
            .execute()
    }
}