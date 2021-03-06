// Databricks notebook source
// MAGIC %md
// MAGIC ## Writing your own Model (Custom Spark Estimators and Transformers)

// COMMAND ----------

// MAGIC %md
// MAGIC ### PCA for Anomaly detection
// MAGIC 1. Filter out nomalous points and perform PCA to extract Principal Components
// MAGIC 2. Reconstruct the features using the Principal Components and the feature vectors.
// MAGIC 3. To calculate the Anomaly Score, calculate the normalized error between the reconstructed features and the original feature vector
// MAGIC   - In this case, we use the sum of squared differences from the two vectors
// MAGIC   
// MAGIC For more information:
// MAGIC - [PCA-based Anomaly Detection](https://docs.microsoft.com/en-us/azure/machine-learning/studio-module-reference/pca-based-anomaly-detection)
// MAGIC - [A randomized algorithm for principal component analysis](https://arxiv.org/abs/0809.2274). Rokhlin, Szlan and Tygert
// MAGIC - [Finding Structure with Randomness: Probabilistic Algorithms for Constructing Approximate Matrix Decompositions](http://users.cms.caltech.edu/~jtropp/papers/HMT11-Finding-Structure-SIREV.pdf). Halko, Martinsson and Tropp.

// COMMAND ----------

package org.apache.spark.ml.feature

import org.apache.hadoop.fs.Path

import org.apache.spark.ml._
import org.apache.spark.ml.linalg._
import org.apache.spark.ml.param._
import org.apache.spark.ml.param.shared._
import org.apache.spark.ml.util._
import org.apache.spark.sql._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.{StructField, StructType, DoubleType}

import breeze.linalg.{DenseVector, sum}
import breeze.numerics.pow

/**
 * Params for [[PCAAnomaly]] and [[PCAAnomalyModel]].
 */
trait PCAAnomalyParams extends Params with HasInputCol with HasOutputCol {
  final val outputPCACol = new Param[String](this, "outputPCACol", "The output column with PCA features")
  final val outputAbsScoreCol = new Param[String](this, "outputAbsScoreCol", "The output column with non-normalized Anomaly Scores")
  final val labelCol = new Param[String](this, "labelCol", "Label column")
  setDefault(outputPCACol, "pca_features")
  setDefault(outputAbsScoreCol, "nonnorm_anomaly_score")
  setDefault(labelCol, "label")
  
  final val k: IntParam = new IntParam(this, "k", "the number of principal components (> 0)",
    ParamValidators.gt(0))
  
  /** Validates and transforms the input schema. */
  protected def validateAndTransformSchema(schema: StructType): StructType = {
    //SchemaUtils.checkColumnType(schema, $(inputCol), new VectorUDT)
    require(!schema.fieldNames.contains($(outputCol)), s"Output column ${$(outputCol)} already exists.")
    val outputFields = schema.fields :+ 
      StructField($(outputPCACol), new VectorUDT, false) :+ 
      StructField($(outputCol), DoubleType, false)
    StructType(outputFields)
  }
}

/**
 * PCA trains a model to project vectors to a lower dimensional space of the top `PCA!.k`
 * principal components.
 */
class PCAAnomaly (override val uid: String)
  extends Estimator[PCAAnomalyModel] with PCAAnomalyParams with DefaultParamsWritable {

  def this() = this(Identifiable.randomUID("pca_anomaly"))

  def setInputCol(value: String): this.type = set(inputCol, value)
  def setOutputCol(value: String): this.type = set(outputCol, value)
  def setLabelCol(value: String): this.type = set(labelCol, value)
  def setOutputPCACol(value: String): this.type = set(outputPCACol, value)
  def setOutputAbsScoreCol(value: String): this.type = set(outputAbsScoreCol, value)
  def setK(value: Int): this.type = set(k, value)

  /**
   * Computes a [[PCAAnomalyModel]] that contains the principal components of the input vectors.
   */
  override def fit(dataset: Dataset[_]): PCAAnomalyModel = {
    transformSchema(dataset.schema, logging = true)
    
    // remove anomalies
    val cleanDataset = dataset.filter(col($(labelCol)) === 0)
    
    // Fit regular PCA model
    val pcaModel = new PCA()
      .setInputCol($(inputCol))
      .setOutputCol($(outputPCACol))
      .setK($(k))
      .fit(cleanDataset)
    
    copyValues(new PCAAnomalyModel(uid, pcaModel).setParent(this))
  }

  override def transformSchema(schema: StructType): StructType = {
    validateAndTransformSchema(schema)
  }

  override def copy(extra: ParamMap): PCAAnomaly = defaultCopy(extra)
}

object PCAAnomaly extends DefaultParamsReadable[PCAAnomaly] {
  override def load(path: String): PCAAnomaly = super.load(path)
}

/**
 * Model fitted by [[PCAAnomaly]]. Uses PCA to detect anomalies
 *
 * @param pcaModel A PCA model
 */
class PCAAnomalyModel (
  override val uid: String, 
  val pcaModel: PCAModel)
  extends Model[PCAAnomalyModel] with PCAAnomalyParams with MLWritable {

  import PCAAnomalyModel._

  def setInputCol(value: String): this.type = set(inputCol, value)
  def setOutputCol(value: String): this.type = set(outputCol, value)
  def setLabelCol(value: String): this.type = set(labelCol, value)
  def setOutputPCACol(value: String): this.type = set(outputPCACol, value)
  def setOutputAbsScoreCol(value: String): this.type = set(outputAbsScoreCol, value)
  def setK(value: Int): this.type = set(k, value)
    
  /**
   * Transform a vector by computed Principal Components.
   *
   * @note Vectors to be transformed must be the same length as the source vectors given
   * to `PCAAnomaly.fit()`.
   */
  override def transform(dataset: Dataset[_]): DataFrame = {
    transformSchema(dataset.schema, logging = true)
    
    val pcaResults = pcaModel.transform(dataset)
    
    val anomalyScoreUdf = udf((originalFeatures:Vector, pcaFeatures:Vector) => {
      // Reconstruct vector using Principal components
      val reconstructedFeatures = pcaModel.pc.multiply(pcaFeatures) 
      
      // Calculate error (sum of squared differences)
      val originalFeaturesB = DenseVector(originalFeatures.toArray)
      val reconstructedFeaturesB = DenseVector(reconstructedFeatures.toArray)
      val diff = originalFeaturesB - reconstructedFeaturesB
      val error = sum(pow(diff, 2))
      error
    })
    val anomalyScore = pcaResults.withColumn($(outputAbsScoreCol), anomalyScoreUdf(col($(inputCol)), col($(outputPCACol))))
    
    // Normalize
    val Row(maxVal: Double) = anomalyScore.select(max($(outputAbsScoreCol))).head
    val Row(minVal: Double) = anomalyScore.select(min($(outputAbsScoreCol))).head
    val nomarlizeAnomalyScore = anomalyScore
      .withColumn($(outputCol), (col($(outputAbsScoreCol)) - minVal) / (maxVal - minVal))
    
    nomarlizeAnomalyScore
  }

  override def transformSchema(schema: StructType): StructType = {
    validateAndTransformSchema(schema)
  }

  override def copy(extra: ParamMap): PCAAnomalyModel = {
    val copied = new PCAAnomalyModel(uid, pcaModel)
    copyValues(copied, extra).setParent(parent)
  }

  override def write: MLWriter = new PCAAnomalyModelWriter(this)
}

object PCAAnomalyModel extends MLReadable[PCAAnomalyModel] {

  private[PCAAnomalyModel] class PCAAnomalyModelWriter(instance: PCAAnomalyModel) extends MLWriter {
    override protected def saveImpl(path: String): Unit = {
      DefaultParamsWriter.saveMetadata(instance, path, sc)
      val pcaPath = new Path(path, "pca").toString
      instance.pcaModel.save(pcaPath)
    }
  }

  private class PCAAnomalyModelReader extends MLReader[PCAAnomalyModel] {

    private val className = classOf[PCAAnomalyModel].getName

    /**
     * Loads a [[PCAAnomalyModel]] from data located at the input path.
     *
     * @param path path to serialized model data
     * @return a [[PCAAnomalyModel]]
     */
    override def load(path: String): PCAAnomalyModel = {
      val metadata = DefaultParamsReader.loadMetadata(path, sc, className)
      val pcaPath = new Path(path, "pca").toString
      val pcaModel = PCAModel.load(pcaPath)
      val model = new PCAAnomalyModel(metadata.uid, pcaModel)
      DefaultParamsReader.getAndSetParams(model, metadata)
      model
    }
  }

  override def read: MLReader[PCAAnomalyModel] = new PCAAnomalyModelReader

  override def load(path: String): PCAAnomalyModel = super.load(path)
}



// COMMAND ----------

// MAGIC %md
// MAGIC ## Use Custom Model in a Pipeline

// COMMAND ----------

// MAGIC %md
// MAGIC ### Setup

// COMMAND ----------

import org.apache.spark.ml.feature.{StringIndexer, OneHotEncoderEstimator, VectorAssembler, PCA, StandardScaler, MinMaxScaler, PCAAnomaly}
import org.apache.spark.ml.{Pipeline, PipelineModel}
import org.apache.spark.ml.linalg.{Vector, Vectors}
import org.apache.spark.sql.functions._
import breeze.linalg.{DenseVector, sum}
import breeze.numerics.pow

val modelDir = "mnt/blob_storage/models/PCAAnomalyModel"

// COMMAND ----------

// MAGIC %md
// MAGIC ### Load and transform data

// COMMAND ----------

// Read data
spark.catalog.refreshTable("kdd") // need to refresh to invalidate cache
val df = spark.read.table("kdd")

// Clean data
val cleanDf = df
  .withColumn("is_anomaly", when(col("label") === "normal.", 0).otherwise(1))
  .na.drop()

// Clean up labels for anomaly
display(cleanDf)

val columns = cleanDf.columns.toSet
val features = columns -- Set("id", "label", "is_anomaly")
val categoricalFeatures = Set("protocol_type", "service", "flag")
val continuousFeatures = features -- categoricalFeatures

// Split
val Array(training, test) = cleanDf.randomSplit(Array(0.8, 0.2), seed = 123)


// COMMAND ----------

// MAGIC %md
// MAGIC ### Define Feature Estimators and Transformers

// COMMAND ----------

// Indexers
val indexers = categoricalFeatures.map({ colName =>
  new StringIndexer().setInputCol(colName).setOutputCol(colName + "_index").setHandleInvalid("keep")
}).toArray

// Encoders
val encoder = new OneHotEncoderEstimator()
  .setInputCols(categoricalFeatures.map(colName => colName + "_index").toArray)
  .setOutputCols(categoricalFeatures.map(colName => colName + "_encoded").toArray)

// Vector Assembler
var selectedFeatures = continuousFeatures ++ categoricalFeatures.map(colName => colName + "_encoded") 
val assembler = new VectorAssembler()
  .setInputCols(selectedFeatures.toArray)
  .setOutputCol("features")

// Standard Scalar
val standardScalar = new StandardScaler()
  .setInputCol("features")
  .setOutputCol("norm_features")
  .setWithMean(true)
  .setWithStd(true)

// PCA Anomaly model
val pcaAnom = new PCAAnomaly()
  .setInputCol("norm_features")
  .setOutputPCACol("pca_features")  
  .setOutputCol("anomaly_score")
  .setLabelCol("is_anomaly")
  .setK(2)

// COMMAND ----------

// MAGIC %md
// MAGIC ### Build and Fit Pipeline using PCAAnomaly (custom model)
// MAGIC ![PCAAnomaly Pipeline](files/images/PCAAnomalyPipeline.PNG)

// COMMAND ----------

// Pipeline
val mainPipeline = new Pipeline()
  .setStages(indexers ++ 
     Array(encoder, assembler, standardScalar, pcaAnom)) //pcaAnom

// Fit pipeline
val mainPipelineModel = mainPipeline.fit(training)

// Save pipeline
mainPipelineModel
  .write
  .overwrite
  .save(modelDir)

// COMMAND ----------

// MAGIC %md
// MAGIC ### Use Model to predict anomalies

// COMMAND ----------

// MAGIC %md
// MAGIC #### Using training data

// COMMAND ----------

// Load saved model
val model = PipelineModel.load(modelDir)

// Use model 
val transformedTraining = model.transform(training)
  .select("is_anomaly", "label", "anomaly_score")
  .cache()

display(transformedTraining
        .groupBy("is_anomaly")
        .agg(avg("anomaly_score")))

// COMMAND ----------

display(transformedTraining
  .groupBy("label")
  .agg(avg("anomaly_score").alias("anomaly_score"))
  .sort(desc("anomaly_score")))

// COMMAND ----------

// MAGIC %md
// MAGIC #### Using test data

// COMMAND ----------

val transformedTest = mainPipelineModel.transform(test)
  .select("is_anomaly", "label", "anomaly_score")
  .cache()

display(transformedTest
        .groupBy("is_anomaly")
        .agg(avg("anomaly_score")))

// COMMAND ----------

display(transformedTest
  .groupBy("label")
  .agg(avg("anomaly_score").alias("anomaly_score"))
  .sort(desc("anomaly_score")))

// COMMAND ----------

// MAGIC %md
// MAGIC ### Evaluate Model using Test data

// COMMAND ----------

import  org.apache.spark.ml.evaluation.BinaryClassificationEvaluator

val evaluator = new BinaryClassificationEvaluator()
  .setMetricName("areaUnderROC")
  .setLabelCol("is_anomaly")
  .setRawPredictionCol("anomaly_score")

evaluator.evaluate(transformedTraining)