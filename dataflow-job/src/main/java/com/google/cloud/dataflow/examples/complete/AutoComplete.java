/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.dataflow.examples.complete;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.datastore.v1.client.DatastoreHelper.makeKey;
import static com.google.datastore.v1.client.DatastoreHelper.makeValue;

import com.google.api.services.bigquery.model.TableFieldSchema;
import com.google.api.services.bigquery.model.TableReference;
import com.google.api.services.bigquery.model.TableRow;
import com.google.api.services.bigquery.model.TableSchema;
import com.google.cloud.dataflow.examples.common.DataflowExampleUtils;
import com.google.cloud.dataflow.examples.common.ExampleBigQueryTableOptions;
import com.google.cloud.dataflow.examples.common.ExamplePubsubTopicOptions;
import com.google.cloud.dataflow.sdk.Pipeline;
import com.google.cloud.dataflow.sdk.PipelineResult;
import com.google.cloud.dataflow.sdk.io.BigQueryIO;
import com.google.cloud.dataflow.sdk.io.PubsubIO;
import com.google.cloud.dataflow.sdk.io.TextIO;
import com.google.cloud.dataflow.sdk.io.datastore.DatastoreIO;
import com.google.cloud.dataflow.sdk.options.Default;
import com.google.cloud.dataflow.sdk.options.Description;
import com.google.cloud.dataflow.sdk.options.PipelineOptionsFactory;
import com.google.cloud.dataflow.sdk.options.Validation;
import com.google.cloud.dataflow.sdk.runners.DataflowPipelineRunner;
import com.google.cloud.dataflow.sdk.transforms.Count;
import com.google.cloud.dataflow.sdk.transforms.DoFn;
import com.google.cloud.dataflow.sdk.transforms.GroupByKey;
import com.google.cloud.dataflow.sdk.transforms.Filter;
import com.google.cloud.dataflow.sdk.transforms.Flatten;
import com.google.cloud.dataflow.sdk.transforms.PTransform;
import com.google.cloud.dataflow.sdk.transforms.ParDo;
import com.google.cloud.dataflow.sdk.transforms.Partition;
import com.google.cloud.dataflow.sdk.transforms.Partition.PartitionFn;
import com.google.cloud.dataflow.sdk.transforms.SerializableFunction;
import com.google.cloud.dataflow.sdk.transforms.Top;
import com.google.cloud.dataflow.sdk.transforms.windowing.GlobalWindows;
import com.google.cloud.dataflow.sdk.transforms.windowing.SlidingWindows;
import com.google.cloud.dataflow.sdk.transforms.windowing.Window;
import com.google.cloud.dataflow.sdk.transforms.windowing.WindowFn;
import com.google.cloud.dataflow.sdk.values.KV;
import com.google.cloud.dataflow.sdk.values.PBegin;
import com.google.cloud.dataflow.sdk.values.PCollection;
import com.google.cloud.dataflow.sdk.values.PCollectionList;
import com.google.common.base.MoreObjects;
import com.google.datastore.v1.Entity;
import com.google.datastore.v1.Key;
import com.google.datastore.v1.Value;

import org.joda.time.Duration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An example that computes product names based on prefixes.
 * Results can be used for low latency autocomplete
 *
 * <p>Concepts: Using the same pipeline in both streaming and batch, combiners,
 *              composite transforms.
 *
 * <p>To execute this pipeline using the Dataflow service in batch mode,
 * specify pipeline configuration:
 * <pre>{@code
 *   --project=YOUR_PROJECT_ID
 *   --stagingLocation=gs://YOUR_STAGING_DIRECTORY
 *   --runner=DataflowPipelineRunner
 *   --inputFile=gs://path/to/input*.txt
 * }</pre>
 *
 * <p>To execute this pipeline using the Dataflow service in streaming mode,
 * specify pipeline configuration:
 * <pre>{@code
 *   --project=YOUR_PROJECT_ID
 *   --stagingLocation=gs://YOUR_STAGING_DIRECTORY
 *   --runner=DataflowPipelineRunner
 *   --inputFile=gs://YOUR_INPUT_DIRECTORY/*.txt
 *   --streaming
 * }</pre>
 *
 * <p>This will update the Cloud Datastore every 10 seconds based on the last
 * 30 minutes of data received.
 */
public class AutoComplete {

  /**
   * Takes as input a set of strings, and emits a prefix key + full string value
   */
  static class ExtractPrefixes 
      extends DoFn<String, KV<String, String>> {
	private final int minPrefix;
	private final int maxPrefix;
	public ExtractPrefixes(int minPrefix) {
	  this(minPrefix, Integer.MAX_VALUE);
	}
	public ExtractPrefixes(int minPrefix, int maxPrefix) {
	  this.minPrefix = minPrefix;
	  this.maxPrefix = maxPrefix;
	}

    @Override
    public void processElement(ProcessContext c) {
      String line = c.element();

      // Split the line into words.
      String[] words = line.split("[^a-zA-Z']+");
      for (String word : words) {
		  for (int i = minPrefix; i <= Math.min(word.length(), maxPrefix); i++) {
//			c.output(KV.of(word.substring(0, i), c.element()));
			c.output(KV.of(c.element(), word.substring(0, i).toLowerCase()));
		}
	  }
    }
  }

  static class FormatForBigquery extends DoFn<KV<String, Iterable<String>>, TableRow> {
	private final int maxEntries;
	public FormatForBigquery(int maxEntries) {
	  this.maxEntries = maxEntries;
	}
	
    @Override
    public void processElement(ProcessContext c) {
      List<TableRow> prefixes = new ArrayList<>();
      
      for (String prefix : c.element().getValue()) {
        prefixes.add(new TableRow()
            .set("prefix", prefix));
      }
      TableRow row = new TableRow()
          .set("entry", c.element().getKey())
          .set("prefixes", prefixes);
      c.output(row);
    }

    /**
     * Defines the BigQuery schema used for the output.
     */
    static TableSchema getSchema() {
      List<TableFieldSchema> fields = new ArrayList<>();
      fields.add(new TableFieldSchema().setName("entry").setType("STRING"));
      List<TableFieldSchema> prefixes = new ArrayList<>();
      prefixes.add(new TableFieldSchema().setName("prefix").setType("STRING"));
      fields.add(new TableFieldSchema()
          .setName("prefixes").setType("RECORD").setMode("REPEATED").setFields(prefixes));
      return new TableSchema().setFields(fields);
    }
  }

  /**
   * Takes as input a the top candidates per prefix, and emits an entity
   * suitable for writing to Cloud Datastore.
   *
   * <p>Note: We use ancestor keys for strong consistency. See the Cloud Datastore documentation on
   * <a href="https://cloud.google.com/datastore/docs/concepts/structuring_for_strong_consistency">
   * Structuring Data for Strong Consistency</a>
   */
  static class FormatForDatastore extends DoFn<KV<String, Iterable<String>>, Entity> {
    private String kind;
    private String ancestorKey;
	private final int maxEntries;

    public FormatForDatastore(String kind, String ancestorKey, int maxEntries) {
      this.kind = kind;
      this.ancestorKey = ancestorKey;
	  this.maxEntries = maxEntries;
    }

    @Override
    public void processElement(ProcessContext c) {
		int n = 1;
		int counter = 0;
		Iterator<String> prefixIterator = c.element().getValue().iterator();
		boolean anotherLoop = true;
		while(anotherLoop) {
		  anotherLoop = false;
		  Entity.Builder entityBuilder = Entity.newBuilder();
		  Key key = makeKey(makeKey(kind, ancestorKey).build(), kind, c.element().getKey() + n).build();

		  entityBuilder.setKey(key);
		  List<Value> prefixes = new ArrayList<>();
		  Map<String, Value> properties = new HashMap<>();
		  while(prefixIterator.hasNext()) {
				String prefix = prefixIterator.next();
				prefixes.add(makeValue(prefix).build());
				if(counter > maxEntries && prefixIterator.hasNext()) {
					anotherLoop = true;
					counter = 0;
					n++;
					break;
				}
				counter++;
		  }
		  properties.put("entry", makeValue(c.element().getKey()).build());
		  properties.put("prefixes", makeValue(prefixes).build());
		  entityBuilder.putAllProperties(properties);
		  c.output(entityBuilder.build());
		}
    }
  }


/*
 * 
 *     public void processElement(ProcessContext c) {
      Entity.Builder entityBuilder = Entity.newBuilder();
      Key key = makeKey(makeKey(kind, ancestorKey).build(), kind, c.element().getKey()).build();

      entityBuilder.setKey(key);
      List<Value> entries = new ArrayList<>();
      Map<String, Value> properties = new HashMap<>();
      int n = 0;
      for (String entry : c.element().getValue()) {
		if(n++ > maxEntries) {
			break;
		}
        //Entity.Builder entryEntity = Entity.newBuilder();
        //properties.put("entry", makeValue(entry).build());
        entries.add(makeValue(entry).build());
      }
      properties.put("prefix", makeValue(c.element().getKey()).build());
      properties.put("entries", makeValue(entries).build());
      entityBuilder.putAllProperties(properties);
      c.output(entityBuilder.build());
    }

 * 
 */
  
  /*
    static class FormatForDatastore extends DoFn<KV<String, List<CompletionCandidate>>, Entity> {
    private String kind;
    private String ancestorKey;

    public FormatForDatastore(String kind, String ancestorKey) {
      this.kind = kind;
      this.ancestorKey = ancestorKey;
    }

    @Override
    public void processElement(ProcessContext c) {
      Entity.Builder entityBuilder = Entity.newBuilder();
      Key key = makeKey(makeKey(kind, ancestorKey).build(), kind, c.element().getKey()).build();

      entityBuilder.setKey(key);
      List<Value> candidates = new ArrayList<>();
      Map<String, Value> properties = new HashMap<>();
      for (CompletionCandidate tag : c.element().getValue()) {
        Entity.Builder tagEntity = Entity.newBuilder();
        properties.put("tag", makeValue(tag.value).build());
        properties.put("count", makeValue(tag.count).build());
        candidates.add(makeValue(tagEntity).build());
      }
      properties.put("candidates", makeValue(candidates).build());
      entityBuilder.putAllProperties(properties);
      c.output(entityBuilder.build());
    }
  }
  */

  /**
   * Options supported by this class.
   *
   * <p>Inherits standard Dataflow configuration options.
   */
  private interface Options extends ExamplePubsubTopicOptions, ExampleBigQueryTableOptions {
    @Description("Input text file")
    @Validation.Required
    String getInputFile();
    void setInputFile(String value);

    @Description("Cloud Datastore entity kind")
    @Default.String("AutocompletePrefixes")
    String getKind();
    void setKind(String value);

    @Description("min size of prefix to be stored")
    @Default.Integer(3)
    Integer getMinPrefix();
    void setMinPrefix(Integer value);

    @Description("max size of prefix to be stored")
    @Default.Integer(10)
    Integer getMaxPrefix();
    void setMaxPrefix(Integer value);

    @Description("max prefixes to be stored per entry")
    @Default.Integer(1024)
    Integer getMaxEntries();
    void setMaxEntries(Integer value);

    @Description("Whether output to BigQuery")
    @Default.Boolean(true)
    Boolean getOutputToBigQuery();
    void setOutputToBigQuery(Boolean value);

    @Description("Whether output to Cloud Datastore")
    @Default.Boolean(false)
    Boolean getOutputToDatastore();
    void setOutputToDatastore(Boolean value);

    @Description("Cloud Datastore output dataset ID, defaults to project ID")
    String getOutputDataset();
    void setOutputDataset(String value);

    @Description("Cloud Datastore ancestor key")
    @Default.String("root")
    String getDatastoreAncestorKey();
    void setDatastoreAncestorKey(String value);
  }

  public static void main(String[] args) throws IOException {
    Options options = PipelineOptionsFactory.fromArgs(args).withValidation().as(Options.class);

    if (options.isStreaming()) {
      // In order to cancel the pipelines automatically,
      // {@literal DataflowPipelineRunner} is forced to be used.
      options.setRunner(DataflowPipelineRunner.class);
    }

    options.setBigQuerySchema(FormatForBigquery.getSchema());
    DataflowExampleUtils dataflowUtils = new DataflowExampleUtils(options);

    // We support running the same pipeline in either
    // batch or windowed streaming mode.
    PTransform<? super PBegin, PCollection<String>> readSource;
    WindowFn<Object, ?> windowFn;
    if (options.isStreaming()) {
      checkArgument(
          !options.getOutputToDatastore(), "DatastoreIO is not supported in streaming.");
      dataflowUtils.setupPubsub();

      readSource = PubsubIO.Read.topic(options.getPubsubTopic());
      windowFn = SlidingWindows.of(Duration.standardMinutes(30)).every(Duration.standardSeconds(5));
    } else {
      readSource = TextIO.Read.from(options.getInputFile());
      windowFn = new GlobalWindows();
    }

    // Create the pipeline.
    Pipeline p = Pipeline.create(options);
    PCollection<KV<String, Iterable<String>>> toWrite = p
        .apply(readSource)
        .apply(ParDo.of(new ExtractPrefixes(options.getMinPrefix(),options.getMaxPrefix())))
        .apply(GroupByKey.<String,String>create());

    if (options.getOutputToDatastore()) {
      toWrite
          .apply(ParDo.named("FormatForDatastore").of(new FormatForDatastore(options.getKind(),
              options.getDatastoreAncestorKey(), options.getMaxEntries())))
          .apply(DatastoreIO.v1().write().withProjectId(MoreObjects.firstNonNull(
              options.getOutputDataset(), options.getProject())));
    }
    if (options.getOutputToBigQuery()) {
      dataflowUtils.setupBigQueryTable();

      TableReference tableRef = new TableReference();
      tableRef.setProjectId(options.getProject());
      tableRef.setDatasetId(options.getBigQueryDataset());
      tableRef.setTableId(options.getBigQueryTable());

      toWrite
        .apply(ParDo.of(new FormatForBigquery(options.getMaxEntries())))
        .apply(BigQueryIO.Write
               .to(tableRef)
               .withSchema(FormatForBigquery.getSchema())
               .withCreateDisposition(BigQueryIO.Write.CreateDisposition.CREATE_IF_NEEDED)
               .withWriteDisposition(options.isStreaming()
                   ? BigQueryIO.Write.WriteDisposition.WRITE_APPEND
                   : BigQueryIO.Write.WriteDisposition.WRITE_TRUNCATE));
    }

    // Run the pipeline.
    PipelineResult result = p.run();

    if (options.isStreaming() && !options.getInputFile().isEmpty()) {
      // Inject the data into the Pub/Sub topic with a Dataflow batch pipeline.
      dataflowUtils.runInjectorPipeline(options.getInputFile(), options.getPubsubTopic());
    }

    // dataflowUtils will try to cancel the pipeline and the injector before the program exists.
    dataflowUtils.waitToFinish(result);
  }
}
