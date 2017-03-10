package com.linkedpipes.plugin.extractor.sparql.endpointlist;

import com.linkedpipes.etl.dataunit.core.rdf.WritableSingleGraphDataUnit;
import com.linkedpipes.etl.executor.api.v1.LpException;
import com.linkedpipes.etl.rdf.utils.vocabulary.RDF;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

class ErrorReportConsumer {

    private static final Logger LOG =
            LoggerFactory.getLogger(ErrorReportConsumer.class);

    private final WritableSingleGraphDataUnit errorOutputRdf;

    private final ValueFactory valueFactory = SimpleValueFactory.getInstance();

    private int counter = 0;

    public ErrorReportConsumer(
            WritableSingleGraphDataUnit errorOutputRdf) {
        this.errorOutputRdf = errorOutputRdf;
    }

    public synchronized void reportError(Task task, Exception exception) {
        IRI resource = valueFactory.createIRI("http://localhost/resources/" +
                "e-sparqlEndpointList/error/" + ++counter);

        final List<Statement> statements = new ArrayList<>();

        statements.add(valueFactory.createStatement(resource,
                valueFactory.createIRI(RDF.TYPE),
                valueFactory.createIRI(SparqlEndpointListVocabulary.REPORT)));

        statements.add(valueFactory.createStatement(resource,
                valueFactory.createIRI(SparqlEndpointListVocabulary.HAS_TASK),
                valueFactory.createIRI(task.getIri())));

        IRI exceptionResource = valueFactory.createIRI(
                resource.stringValue() + "/exception");

        statements.add(valueFactory.createStatement(resource,
                valueFactory.createIRI(
                        SparqlEndpointListVocabulary.HAS_EXCEPTION),
                exceptionResource));

        statements.addAll(translateException(exceptionResource, exception));


        LOG.error("Error: {}", task.getIri(), exception);

        try {
            errorOutputRdf.execute((connection) -> {
                connection.add(statements, errorOutputRdf.getWriteGraph());
            });
        } catch (LpException ex) {
            LOG.error("Can't save information about error.", ex);
        }
    }

    private List<Statement> translateException(IRI resource, Exception exception) {
        List<Statement> statements = new ArrayList<>(3);

        statements.add(valueFactory.createStatement(resource,
                valueFactory.createIRI(RDF.TYPE),
                valueFactory.createIRI(
                        SparqlEndpointListVocabulary.EXCEPTION)));

        statements.add(valueFactory.createStatement(resource,
                valueFactory.createIRI(
                        SparqlEndpointListVocabulary.HAS_EXCEPTION_MESSAGE),
                valueFactory.createLiteral(exception.getMessage())));

        statements.add(valueFactory.createStatement(resource,
                valueFactory.createIRI(
                        SparqlEndpointListVocabulary.HAS_EXCEPTION_CLASS),
                valueFactory.createLiteral(exception.getClass().getName())));

        return statements;
    }

}
