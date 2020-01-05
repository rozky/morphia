package dev.morphia.aggregation.experimental;

import dev.morphia.aggregation.experimental.stages.AddFields;
import dev.morphia.aggregation.experimental.stages.AutoBucket;
import dev.morphia.aggregation.experimental.stages.Bucket;
import dev.morphia.aggregation.experimental.stages.Facet;
import dev.morphia.aggregation.experimental.stages.Group;
import dev.morphia.aggregation.experimental.stages.Projection;
import dev.morphia.aggregation.experimental.stages.Sample;
import dev.morphia.aggregation.experimental.stages.Skip;
import dev.morphia.aggregation.experimental.stages.Sort;
import dev.morphia.aggregation.experimental.stages.SortByCount;
import dev.morphia.aggregation.experimental.stages.Stage;
import dev.morphia.aggregation.experimental.stages.Unset;
import dev.morphia.aggregation.experimental.stages.Unwind;
import dev.morphia.query.Query;
import dev.morphia.query.internal.MorphiaCursor;
import org.bson.Document;

import java.util.List;

/**
 * @since 2.0
 */
public interface Aggregation<T> {
    /**
     * Categorizes incoming documents into a specific number of groups, called buckets, based on a specified expression. Bucket
     * boundaries are automatically determined in an attempt to evenly distribute the documents into the specified number of buckets.
     * <p>
     * Each bucket is represented as a document in the output. The document for each bucket contains an _id field, whose value specifies
     * the inclusive lower bound and the exclusive upper bound for the bucket, and a count field that contains the number of documents in
     * the bucket. The count field is included by default when the output is not specified.
     *
     * @param bucket the bucket definition
     * @return this
     * @mongodb.driver.manual reference/operator/aggregation/bucketAuto $bucketAuto
     */
    Aggregation<T> autoBucket(AutoBucket bucket);

    /**
     * Categorizes incoming documents into groups, called buckets, based on a specified expression and bucket boundaries.
     * <p>
     * Each bucket is represented as a document in the output. The document for each bucket contains an _id field, whose value specifies
     * the inclusive lower bound of the bucket and a count field that contains the number of documents in the bucket. The count field is
     * included by default when the output is not specified.
     * <p>
     * $bucket only produces output documents for buckets that contain at least one input document.
     *
     * @param bucket the bucket definition
     * @return this
     * @mongodb.driver.manual reference/operator/aggregation/bucket $bucket
     */
    Aggregation<T> bucket(Bucket bucket);

    /**
     * Passes a document to the next stage that contains a count of the number of documents input to the stage.
     *
     * @param name the field name for the resulting count value
     * @return this
     * @mongodb.driver.manual reference/operator/aggregation/count $count
     */
    Aggregation<T> count(String name);

    /**
     * Execute the aggregation and get the results.
     *
     * @param <S> the output type
     * @return a MorphiaCursor
     */
    <S> MorphiaCursor<S> execute(final Class<S> resultType);

    /**
     * Execute the aggregation and get the results.
     *
     * @param <S>     the output type
     * @param options the options to apply
     * @return a MorphiaCursor
     */
    <S> MorphiaCursor<S> execute(final Class<S> resultType, final AggregationOptions options);

    /**
     * Processes multiple aggregation pipelines within a single stage on the same set of input documents. Each sub-pipeline has its own
     * field in the output document where its results are stored as an array of documents.
     * <p>
     * The $facet stage allows you to create multi-faceted aggregations which characterize data across multiple dimensions, or facets,
     * within a single aggregation stage. Multi-faceted aggregations provide multiple filters and categorizations to guide data browsing
     * and analysis. Retailers commonly use faceting to narrow search results by creating filters on product price, manufacturer, size, etc.
     * <p>
     * Input documents are passed to the $facet stage only once. $facet enables various aggregations on the same set of input documents,
     * without needing to retrieve the input documents multiple times.
     *
     * @param facet the facet definition
     * @return this
     * @mongodb.driver.manual reference/operator/aggregation/facet $facet
     */
    Aggregation<T> facet(Facet facet);

    /**
     * @morphia.internal
     */
    List<Document> getDocuments();

    /**
     * @return the named stage or stages in this aggregation
     * @morphia.internal
     */
    <S extends Stage> S getStage(String name);

    /**
     * @return the stage in this aggregation
     * @morphia.internal
     */
    List<Stage> getStages();

    /**
     * Groups input documents by the specified _id expression and for each distinct grouping, outputs a document. The _id field of each
     * output document contains the unique group by value. The output documents can also contain computed fields that hold the values of
     * some accumulator expression.
     *
     * @param group the group definition
     * @return this
     * @mongodb.driver.manual reference/operator/aggregation/group $group
     */
    Aggregation<T> group(Group group);

    /**
     * Limits the number of documents passed to the next stage in the pipeline.
     *
     * @param limit the maximum docs to pass along to the next stage
     * @return this
     * @mongodb.driver.manual reference/operator/aggregation/limit $limit
     */
    Aggregation<T> limit(int limit);

    /**
     * Performs a left outer join to an unsharded collection in the same database to filter in documents from the “joined” collection for
     * processing. To each input document, the $lookup stage adds a new array field whose elements are the matching documents from the
     * “joined” collection. The $lookup stage passes these reshaped documents to the next stage.
     *
     * @param lookup the lookup definition
     * @return this
     * @mongodb.driver.manual reference/operator/aggregation/lookup $lookup
     */
    Aggregation<T> lookup(Lookup lookup);

    /**
     * Filters the document stream to allow only matching documents to pass unmodified into the next pipeline stage. $match uses standard
     * MongoDB queries. For each input document, outputs either one document (a match) or zero documents (no match).
     *
     * @param query the query to use when matching
     * @return this
     * @mongodb.driver.manual reference/operator/aggregation/match $match
     */
    Aggregation<T> match(Query<?> query);

    /**
     * Execute the aggregation and write the results to a collection.  The target collection will be created if it's missing or replaced
     * with the results if it already exists.
     *
     * @param <O> the output type used to determine the target collection
     * @mongodb.driver.manual reference/operator/aggregation/out $out
     */
    <O> void out(Class<O> type);

    /**
     * Execute the aggregation and write the results to a collection.  The target collection will be created if it's missing or replaced
     * with the results if it already exists.
     *
     * @param collection the collection to create/overwrite
     * @mongodb.driver.manual reference/operator/aggregation/out $out
     */
    <O> void out(String collection);

    /**
     * Execute the aggregation and write the results to a collection.  The target collection will be created if it's missing or replaced
     * with the results if it already exists.
     *
     * @param collection the collection to create/overwrite
     * @param options    the options to apply
     * @mongodb.driver.manual reference/operator/aggregation/out $out
     */
    void out(String collection, AggregationOptions options);

    /**
     * Execute the aggregation and write the results to a collection.  The target collection will be created if it's missing or replaced
     * with the results if it already exists.
     *
     * @param <O>     the output type used to determine the target collection
     * @param options the options to apply
     * @mongodb.driver.manual reference/operator/aggregation/out $out
     */
    <O> void out(Class<O> type, AggregationOptions options);

    /**
     * Passes along the documents with the requested fields to the next stage in the pipeline. The specified fields can be existing fields
     * from the input documents or newly computed fields.
     *
     * @param projection
     * @return
     * @mongodb.driver.manual reference/operator/aggregation/project $project
     */
    Aggregation<T> project(Projection projection);

    /**
     * Randomly selects the specified number of documents from the previous pipeline stage.
     *
     * @param sample the sample definition
     * @return this
     * @mongodb.driver.manual reference/operator/aggregation/sample $sample
     */
    Aggregation<T> sample(Sample sample);

    /**
     * Adds new fields to documents. $addFields outputs documents that contain all existing fields from the input documents and newly
     * added fields.
     * <p>
     * The $addFields stage is equivalent to a $project stage that explicitly specifies all existing fields in the input documents and
     * adds the new fields.
     *
     * @param fields the stage definition
     * @return this
     * @mongodb.driver.manual reference/operator/aggregation/set $set
     */
    default Aggregation<T> set(AddFields fields) {
        return addFields(fields);
    }

    /**
     * Adds new fields to documents. $addFields outputs documents that contain all existing fields from the input documents and newly
     * added fields.
     * <p>
     * The $addFields stage is equivalent to a $project stage that explicitly specifies all existing fields in the input documents and
     * adds the new fields.
     *
     * @param fields the stage definition
     * @return this
     * @mongodb.driver.manual reference/operator/aggregation/addFields $addFields
     */
    Aggregation<T> addFields(AddFields fields);

    /**
     * Skips over the specified number of documents that pass into the stage and passes the remaining documents to the next stage in the
     * pipeline.
     *
     * @param skip the skip definition
     * @return this
     * @mongodb.driver.manual reference/operator/aggregation/skip $skip
     */
    Aggregation<T> skip(Skip skip);

    /**
     * Sorts all input documents and returns them to the pipeline in sorted order.
     *
     * @param sort the sort definition
     * @return this
     * @mongodb.driver.manual reference/operator/aggregation/sort $sort
     */
    Aggregation<T> sort(Sort sort);

    /**
     * Groups incoming documents based on the value of a specified expression, then computes the count of documents in each distinct group.
     * <p>
     * Each output document contains two fields: an _id field containing the distinct grouping value, and a count field containing the
     * number of documents belonging to that grouping or category.
     * <p>
     * The documents are sorted by count in descending order.
     *
     * @param sort the sort definition
     * @return this
     * @mongodb.driver.manual reference/operator/aggregation/sortByCount $sortByCount
     */
    Aggregation<T> sortByCount(SortByCount sort);

    /**
     * Removes/excludes fields from documents.  Names must not start with '$'.
     *
     * @param unset the unset definition
     * @return this
     * @mongodb.driver.manual reference/operator/aggregation/unset $unset
     */
    Aggregation<T> unset(Unset unset);

    /**
     * Deconstructs an array field from the input documents to output a document for each element. Each output document is the input
     * document with the value of the array field replaced by the element.
     *
     * @param unwind the unwind definition
     * @return this
     * @mongodb.driver.manual reference/operator/aggregation/unwind $unwind
     */
    Aggregation<T> unwind(Unwind unwind);
}
