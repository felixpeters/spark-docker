package me.felixpeters.spark
import org.apache.spark.sql.SparkSession
import org.apache.spark.ml.{Pipeline, PipelineModel}
import org.apache.spark.ml.feature.{MinMaxScaler, OneHotEncoderEstimator, StringIndexer, VectorAssembler}
import org.apache.spark.ml.evaluation.RegressionEvaluator
import org.apache.spark.ml.regression.{GBTRegressionModel, GBTRegressor}

object RossmannSalesForecasting {
  def main(args: Array[String]) {
    val spark = SparkSession.builder
      .appName("RossmannSalesForecasting")
      .getOrCreate()

    val salesPath = args(0)
    val storePath = args(1)

    val sales = spark.read
      .option("header", "true")
      .option("inferSchema", "true")
      .csv(salesPath)
    val stores = spark.read
      .option("header", "true")
      .option("inferSchema", "true")
      .csv(storePath)

    val data = sales.join(stores, "Store")

    // convert string columns to numbers
    val indexCols = Array("StateHoliday", "StoreType", "Assortment", "PromoInterval")
    val indexers = indexCols.map { colName =>
      new StringIndexer()
        .setHandleInvalid("keep")
        .setInputCol(colName)
        .setOutputCol(colName + "_indexed")
    }

    // one-hot encode categorical columns
    val catCols = Array(
      "DayOfWeek", 
      "StateHoliday_indexed", 
      "StoreType_indexed", 
      "Assortment_indexed",
      "PromoInterval_indexed"
    )
    val encoders = catCols.map { colName =>
      new OneHotEncoderEstimator()
        .setInputCols(Array(colName))
        .setOutputCols(Array(colName + "_vector"))
    }

    // collect features
    val featCols = Array(
      "DayOfWeek_vector",
      "StateHoliday_indexed_vector",
      "StoreType_indexed_vector",
      "Assortment_indexed_vector",
      "PromoInterval_indexed_vector",
      "Customers",
      "Open",
      "Promo",
      "SchoolHoliday",
      "CompetitionDistance",
      "Promo2"
    )
    val assembler = new VectorAssembler()
      .setHandleInvalid("skip")
      .setInputCols(featCols)
      .setOutputCol("features")

    // scale features to 0-1 range
    val scaler = new MinMaxScaler()
      .setMin(0.0)
      .setMax(1.0)
      .setInputCol("features")
      .setOutputCol("scaled_features")

    // create feature engineering pipeline
    val pipelineStages = indexers ++ encoders
    val pipeline = new Pipeline().setStages(pipelineStages)

    val transformations = pipeline.fit(data).transform(data)
    val features = assembler.transform(transformations)
    val scaled_features = scaler.fit(features).transform(features)
      
    val dataset = scaled_features.select("features", "scaled_features", "Sales")

    dataset.printSchema

    // train gradient-boosted tree model
    val Array(trainingData, validationData) = dataset.randomSplit(Array(0.8, 0.2))

    val gbt = new GBTRegressor()
      .setLabelCol("Sales")
      .setFeaturesCol("scaled_features")
      .setMaxIter(10)

    val model = gbt.fit(trainingData)
    
    // evaluate model on validation data
    val predictions = model.transform(validationData)

    predictions.select("prediction", "Sales").show(5)

    val evaluator = new RegressionEvaluator()
      .setLabelCol("Sales")
      .setPredictionCol("prediction")
      .setMetricName("rmse")
    val rmse = evaluator.evaluate(predictions)
    println("Root Mean Squared Error (RMSE) on test data = " + rmse)

    spark.stop()
  }
}
