package io.sease.rre.core;

import com.fasterxml.jackson.databind.JsonNode;
import io.sease.rre.Func;
import io.sease.rre.core.domain.*;
import io.sease.rre.core.domain.metrics.Metric;
import io.sease.rre.search.api.QueryOrSearchResponse;
import io.sease.rre.search.api.SearchPlatform;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static io.sease.rre.Field.*;
import static io.sease.rre.Func.*;
import static java.util.Arrays.stream;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;

/**
 * RRE core engine.
 * The engine is responsible for the whole evaluation process execution.
 *
 * @author agazzarini
 * @since 1.0
 */
public class Engine {
    private final static Logger LOGGER = LogManager.getLogger(Engine.class);

    private final File configurationsFolder;
    private final File corporaFolder;
    private final File ratingsFolder;
    private final File templatesFolder;

    private final List<Class<? extends Metric>> availableMetricsDefs;

    private final SearchPlatform platform;
    private final String[] fields;

    /**
     * Builds a new {@link Engine} instance with the given data.
     *
     * @param platform                 the search platform in use.
     * @param configurationsFolderPath the configurations folder path.
     * @param corporaFolderPath        the corpora folder path.
     * @param ratingsFolderPath        the ratings folder path.
     * @param templatesFolderPath      the query templates folder path.
     */
    public Engine(
            final SearchPlatform platform,
            final String configurationsFolderPath,
            final String corporaFolderPath,
            final String ratingsFolderPath,
            final String templatesFolderPath,
            final List<String> metrics,
            final String[] fields) {
        this.configurationsFolder = new File(configurationsFolderPath);
        this.corporaFolder = new File(corporaFolderPath);
        this.ratingsFolder = new File(ratingsFolderPath);
        this.templatesFolder = new File(templatesFolderPath);
        this.platform = platform;
        this.fields = safe(fields);

        this.availableMetricsDefs =
                metrics.stream()
                        .map(Func::newMetricDefinition)
                        .filter(Objects::nonNull)
                        .collect(toList());
    }

    private List<String> versions;

    /**
     * Executes the evaluation process.
     *
     * @param configuration the engine configuration.
     * @return the evaluation result.
     */
    @SuppressWarnings("unchecked")
    public Evaluation evaluate(final Map<String, Object> configuration) {
        try {
            LOGGER.info("RRE: New evaluation session is starting...");

            platform.beforeStart(configuration);

            LOGGER.info("RRE: Search Platform in use: " + platform.getName());
            LOGGER.info("RRE: Starting " + platform.getName() + "...");

            platform.start();

            LOGGER.info("RRE: " + platform.getName() + " Search Platform successfully started.");

            platform.afterStart();

            final Evaluation evaluation = new Evaluation();
            final List<Query> queries = new ArrayList<>();

            ratings().forEach(ratingsNode -> {
                LOGGER.info("RRE: Ratings Set processing starts");

                final String indexName =
                                requireNonNull(
                                        ratingsNode.get(INDEX_NAME),
                                        "WARNING!!! \"" + INDEX_NAME + "\" attribute not found!").asText();
                final String idFieldName =
                                requireNonNull(
                                        ratingsNode.get(ID_FIELD_NAME),
                                        "WARNING!!! \"" + ID_FIELD_NAME + "\" attribute not found!")
                                        .asText(DEFAULT_ID_FIELD_NAME);
                final File data =
                        new File(
                            corporaFolder,
                            requireNonNull(
                                    ratingsNode.get(CORPORA_FILENAME),
                                    "WARNING!!! \"" + CORPORA_FILENAME + "\" attribute not found!").asText());
                final String queryPlaceholder = ofNullable(ratingsNode.get("query_placeholder")).map(JsonNode::asText).orElse("$query");

                if (!data.canRead()) {
                    throw new IllegalArgumentException("RRE: WARNING!!! Unable to read the corpus file " + data.getAbsolutePath());
                }

                LOGGER.info("RRE: Index name => " + indexName);
                LOGGER.info("RRE: ID Field name => " + idFieldName);
                LOGGER.info("RRE: Test Collection => " + data.getAbsolutePath());
                LOGGER.info("RRE: Ratings Set processing starts");

                prepareData(indexName, data);

                final Corpus corpus = evaluation.findOrCreate(data.getName(), Corpus::new);
                all(ratingsNode.get(TOPICS), "topics")
                        .forEach(topicNode -> {
                            final Topic topic = corpus.findOrCreate(topicNode.get(DESCRIPTION).asText(), Topic::new);
                            all(topicNode.get(QUERY_GROUPS), "query_groups")
                                    .forEach(groupNode -> {
                                        final QueryGroup group =
                                                topic.findOrCreate(groupNode.get(NAME).asText(), QueryGroup::new);
                                        final Optional<String> sharedTemplate = ofNullable(groupNode.get("template")).map(JsonNode::asText);
                                        all(groupNode.get(QUERIES), "queries")
                                                .forEach(queryNode -> {
                                                    final String queryString = queryNode.findValue(queryPlaceholder).asText();
                                                    final JsonNode relevantDocuments = groupNode.get(RELEVANT_DOCUMENTS);
                                                    final Query queryEvaluation = group.findOrCreate(queryString, Query::new);
                                                    queryEvaluation.setIdFieldName(idFieldName);
                                                    queryEvaluation.setRelevantDocuments(relevantDocuments);

                                                    queries.add(queryEvaluation);

                                                    queryEvaluation.prepare(availableMetrics(availableMetricsDefs, idFieldName, relevantDocuments, versions));

                                                    versions.forEach(version -> {
                                                        final AtomicInteger rank = new AtomicInteger(1);
                                                        final QueryOrSearchResponse response =
                                                                platform.executeQuery(
                                                                        indexFqdn(indexName, version),
                                                                        query(queryNode, sharedTemplate, version),
                                                                        fields,
                                                                        Math.max(10, relevantDocuments.size()));

                                                        queryEvaluation.setTotalHits(response.totalHits(), version);
                                                        response.hits().forEach(hit -> queryEvaluation.collect(hit, rank.getAndIncrement(), version));
                                                    });
                                                });
                                    });
                        });
            });

            queries.forEach(Query::notifyCollectedMetrics);

            return evaluation;
        } finally {
            platform.beforeStop();
            LOGGER.info("RRE: " + platform.getName() + " Search Platform shutdown procedure executed.");
        }
    }

    /**
     * Creates a new set of metrics.
     *
     * @param definitions          the metrics definitions.
     * @param idFieldName          the id fieldname.
     * @param relevantDocumentsMap the relevant documents for a given query.
     * @param versions             the available versions for a given query.
     * @return a new metrics set for the current query evaluation.
     */
    private List<Metric> availableMetrics(
            final List<Class<? extends Metric>> definitions,
            final String idFieldName,
            final JsonNode relevantDocumentsMap,
            final List<String> versions) {
        return definitions
                .stream()
                .map(def -> {
                    try {
                        final Metric metric = def.newInstance();
                        metric.setIdFieldName(idFieldName);
                        metric.setRelevantDocuments(relevantDocumentsMap);
                        metric.setVersions(versions);
                        return metric;
                    } catch (final Exception exception) {
                        throw new IllegalArgumentException(exception);
                    }})
                .peek(metric -> LOGGER.info("RRE: Found metric definition \"" + metric.getName() + " which maps to " + metric.getClass()))
                .collect(toList());
    }

    /**
     * Loads the query template associated with the given name.
     *
     * @param defaultTemplateName the default template.
     * @param templateName        the query template name.
     * @param version             the current version being executed.
     * @return the query template associated with the given name.
     */
    private String queryTemplate(final Optional<String> defaultTemplateName, final Optional<String> templateName, final String version) {
        final File versionFolder = new File(templatesFolder, version);
        final File actualTemplateFolder = versionFolder.canRead() ? versionFolder : templatesFolder;

        try {
            final String templateNameInUse =
                    templateName.orElseGet(
                            () -> defaultTemplateName.orElseThrow(
                                    () -> new IllegalArgumentException("Unable to determine the query template.")));
            return of(templateNameInUse)
                    .map(name -> name.contains("${version}") ? name.replace("${version}", version) : name)
                    .map(name -> new File(actualTemplateFolder, name))
                    .map(this::templateContent)
                    .orElseThrow(() -> new IllegalArgumentException("Unable to determine the query template."));
        } catch (final Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    /**
     * Reads a template content.
     *
     * @param file the template file.
     * @return the template content.
     */
    private String templateContent(final File file) {
        try {
            return new String(Files.readAllBytes(file.toPath()));
        } catch (final Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    /**
     * Loads the ratings associated with the given name.
     *
     * @return the ratings / judgements for this evaluation suite.
     */
    private Stream<JsonNode> ratings() {
        final File [] ratingsFiles =
                requireNonNull(
                        ratingsFolder.listFiles(ONLY_JSON_FILES),
                        "Unable to find the ratings folder.");

        LOGGER.info("RRE: found " + ratingsFiles.length + " ratings sets.");

        return stream(ratingsFiles).map(Func::toJson);
    }

    /**
     * Streams out all members of a given JSON node.
     *
     * @param source the parent JSON node.
     * @return a stream consisting of all children of the given JSON node.
     */
    private Stream<JsonNode> all(final JsonNode source, final String name) {
        return ofNullable(source)
                .map(node -> StreamSupport.stream(source.spliterator(), false))
                .orElseGet(() -> {
                    LOGGER.error("RRE: WARNING!!! \"" + name + "\" node is not defined or empty!");
                    return Stream.empty();});
    }

    /**
     * Prepares the search platform with the given index name and dataset.
     *
     * @param indexName the index name.
     * @param data      the dataset.
     */
    private void prepareData(final String indexName, final File data) {
        final File[] versionFolders = safe(configurationsFolder.listFiles(ONLY_DIRECTORIES));
        stream(versionFolders)
                .flatMap(versionFolder -> stream(safe(versionFolder.listFiles(ONLY_NON_HIDDEN_FILES))))
                .filter(file -> (file.isDirectory() && file.getName().equals(indexName))
                        || (file.isFile() && file.getName().equals("index-shape.json")))
                .peek(file -> LOGGER.info("RRE: Loading the Test Collection into " + platform.getName() + ", configuration version " + file.getParentFile().getName()))
                .forEach(fileOrFolder -> platform.load(data, fileOrFolder, indexFqdn(indexName, fileOrFolder.getParentFile().getName())));

        LOGGER.info("RRE: " + platform.getName() + " has been correctly loaded.");

        this.versions = stream(versionFolders).map(File::getName).collect(toList());
    }

    /**
     * Returns the FDQN of the target index that will be used.
     * Starting from the index name declared in the configuration, RRE uses an internal naming (which adds the version
     * name) for avoiding conflicts between versions.
     *
     * @param indexName the index name.
     * @param version   the current version.
     * @return the FDQN of the target index that will be used.
     */
    private String indexFqdn(final String indexName, final String version) {
        return (indexName + "_" + version).toLowerCase();
    }

    /**
     * Returns a query (as a string) that will be used for executing a specific evaluation.
     * A query string is the result of replacing all placeholders found in the template.
     *
     * @param queryNode       the JSON query node (in ratings configuration).
     * @param defaultTemplate the default template that will be used if a query doesn't declare it.
     * @param version         the version being executed.
     * @return a query (as a string) that will be used for executing a specific evaluation.
     */
    private String query(final JsonNode queryNode, final Optional<String> defaultTemplate, final String version) {
        String query = queryTemplate(defaultTemplate, ofNullable(queryNode.get("template")).map(JsonNode::asText), version);
        for (final Iterator<String> iterator = queryNode.get("placeholders").fieldNames(); iterator.hasNext(); ) {
            final String name = iterator.next();
            query = query.replace(name, queryNode.get("placeholders").get(name).asText());
        }
        return query;
    }
}