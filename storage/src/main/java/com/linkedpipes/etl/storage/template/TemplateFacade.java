package com.linkedpipes.etl.storage.template;

import com.linkedpipes.etl.storage.BaseException;
import com.linkedpipes.etl.storage.configuration.ConfigurationFacade;
import com.linkedpipes.etl.storage.template.mapping.MappingFacade;
import com.linkedpipes.etl.storage.template.repository.TemplateRepository;
import com.linkedpipes.etl.storage.unpacker.TemplateSource;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;


@Service
public class TemplateFacade implements TemplateSource {

    private static final Logger LOG
            = LoggerFactory.getLogger(TemplateFacade.class);

    private final TemplateManager manager;

    private final MappingFacade mapping;

    private final TemplateRepository repository;

    @Autowired
    public TemplateFacade(
            TemplateManager manager,
            MappingFacade mapping) {
        this.manager = manager;
        this.mapping = mapping;
        this.repository = manager.getRepository();
    }

    public Template getTemplate(String iri) {
        return manager.getTemplates().get(iri);
    }

    public Collection<Template> getTemplates() {
        return manager.getTemplates().values();
    }

    public Template getParent(Template template) {
        if (template instanceof ReferenceTemplate) {
            ReferenceTemplate ref = (ReferenceTemplate) template;
            return getTemplate(ref.getTemplate());
        }
        return null;
    }

    public Template getRootTemplate(Template template) {
        Template output = template;
        Template next = this.getParent(output);
        while (next != null) {
            output = next;
            next = this.getParent(output);
        }
        return output;
    }

    /**
     * The path from root (template) to the given template.
     */
    public List<Template> getAncestors(Template template) {
        LinkedList<Template> templates = collectAncestors(template);
        Collections.reverse(templates);
        return templates;
    }

    private LinkedList<Template> collectAncestors(Template template) {
        LinkedList<Template> output = new LinkedList<>();
        while (true) {
            output.add(template);
            if (template.getType() == Template.Type.JAR_TEMPLATE) {
                break;
            } else if (template.getType() == Template.Type.REFERENCE_TEMPLATE) {
                ReferenceTemplate reference = (ReferenceTemplate) template;
                template = getTemplate(reference.getTemplate());
                if (template == null) {
                    LOG.warn("Missing template for: {}", reference.getIri());
                    break;
                }
            } else {
                throw new RuntimeException("Unknown template type: " +
                        template.getType().name());
            }
        }
        return output;
    }

    public List<Template> getAncestorsWithoutJarTemplate(Template template) {
        LinkedList<Template> templates = collectAncestors(template);
        templates.remove(templates.removeLast());
        Collections.reverse(templates);
        return templates;
    }

    public Collection<Template> getSuccessors(Template template) {
        Map<Template, List<Template>> children = buildChildrenIndex();
        Set<Template> output = new HashSet<>();
        Set<Template> toTest = new HashSet<>();
        toTest.addAll(children.getOrDefault(
                template, Collections.EMPTY_LIST));
        while (!toTest.isEmpty()) {
            final Template item = toTest.iterator().next();
            toTest.remove(item);
            if (output.contains(item)) {
                continue;
            }
            List<Template> itemChildren = children.getOrDefault(
                    item, Collections.EMPTY_LIST);
            output.add(item);
            output.addAll(itemChildren);
            toTest.addAll(itemChildren);
        }
        return output;
    }

    private Map<Template, List<Template>> buildChildrenIndex() {
        Map<Template, List<Template>> children = new HashMap<>();
        for (Template item : getTemplates()) {
            if (item.getType() != Template.Type.REFERENCE_TEMPLATE) {
                continue;
            }
            ReferenceTemplate reference = (ReferenceTemplate) item;
            Template parent = getTemplate(reference.getTemplate());
            List<Template> brothers = children.computeIfAbsent(
                    parent, key -> new LinkedList<>());
            // Create if does not exists.
            brothers.add(reference);
        }
        return children;
    }

    public Collection<Statement> getInterface(Template template)
            throws BaseException {
        return repository.getInterface(template);
    }

    public Collection<Statement> getInterfaces()
            throws BaseException {
        List<Statement> output = new ArrayList<>();
        for (Template template : manager.getTemplates().values()) {
            output.addAll(getInterface(template));
        }
        return output;
    }

    public Collection<Statement> getDefinition(Template template)
            throws BaseException {
        return repository.getDefinition(template);
    }

    /**
     * @return Template config for execution or as merged parent configuration.
     * Configuration of all ancestors are applied.
     */
    public Collection<Statement> getConfigEffective(Template template)
            throws BaseException {
        // TODO Move to extra class and add caching.
        if (!template.isSupportingControl()) {
            // For template without inheritance control, the current
            // configuration is the effective one.
            return getConfig(template);
        }
        List<Collection<Statement>> configurations = new ArrayList<>();
        for (Template item : getAncestors(template)) {
            configurations.add(getConfig(item));
        }
        ValueFactory valueFactory = SimpleValueFactory.getInstance();
        return ConfigurationFacade.merge(configurations,
                getConfigDescription(template),
                template.getIri() + "/effective/",
                valueFactory.createIRI(template.getIri()));
    }

    /**
     * @return Configuration of given template for a dialog.
     */
    public Collection<Statement> getConfig(Template template)
            throws BaseException {
        return repository.getConfig(template);
    }

    /**
     * @return Configuration for instances of given template.
     */
    public Collection<Statement> getConfigInstance(Template template)
            throws BaseException {
        ValueFactory valueFactory = SimpleValueFactory.getInstance();
        IRI graph = valueFactory.createIRI(template.getIri() + "/new");
        boolean isJarTemplate =
                template.getType() == Template.Type.JAR_TEMPLATE;
        Collection<Statement> statements =
                ConfigurationFacade.createNewConfiguration(
                        this.repository.getConfig(template),
                        this.repository.getConfigDescription(template),
                        graph.stringValue(),
                        graph,
                        !isJarTemplate);
        return statements;
    }

    public Collection<Statement> getConfigDescription(Template template)
            throws BaseException {
        return repository.getConfigDescription(template);
    }

    public File getDialogResource(
            Template template, String dialog, String path) {
        return repository.getDialogFile(template, dialog, path);
    }

    public File getStaticResource(Template template, String path) {
        return repository.getStaticFile(template, path);
    }

    public Template createTemplate(
            Collection<Statement> definition,
            Collection<Statement> configuration)
            throws BaseException {
        Template template = manager.createTemplate(definition, configuration);
        return template;
    }

    public void updateInterface(
            Template template, Collection<Statement> diff)
            throws BaseException {
        manager.updateTemplateInterface(template, diff);
    }

    public void updateConfig(
            Template template, Collection<Statement> statements)
            throws BaseException {
        manager.updateConfig(template, statements);
    }

    public void remove(Template template) throws BaseException {
        manager.remove(template);
        mapping.remove(template);
    }

    // Implementation of unpacker.TemplateSource .

    @Override
    public Collection<Statement> getDefinition(String iri)
            throws BaseException {
        return getDefinition(getTemplate(iri));
    }

    @Override
    public Collection<Statement> getConfiguration(String iri)
            throws BaseException {
        return getConfig(getTemplate(iri));
    }

    @Override
    public Collection<Statement> getConfigurationDescription(String iri)
            throws BaseException {
        return getConfigDescription(getTemplate(iri));
    }

}
