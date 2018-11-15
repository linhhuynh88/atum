/*
 * Copyright 2018 ABSA Group Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package za.co.absa.atum.core

import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.{DecimalType, LongType, StringType}
import org.apache.spark.sql.{Column, Dataset, Row}
import za.co.absa.atum.model.Measurement
import za.co.absa.atum.utils.ControlUtils

/**
  * This class is used for processing Spark Dataset to calculate aggregates / control measures
  */
class MeasurementProcessor(private var measurements: Seq[Measurement]) {
  type MeasurementFunction = Dataset[Row] => Any
  type MeasurementProcessor = (Measurement, MeasurementFunction)

  // Assigning measurement function to each measurement
  var processors: Seq[MeasurementProcessor] =
    measurements.map(m => (m, getMeasurementFunction(m)))

  private val valueColumnName: String = "value"

  /** The method calculates measurements for each control.  */
  private[atum] def measureDataset(ds: Dataset[Row]): Seq[Measurement] = {
    Atum.log.info(s"Schema: ${ds.schema.treeString}")
    processors.map(p => Measurement(controlName = p._1.controlName,
      controlType = p._1.controlType,
      controlCol = p._1.controlCol,
      controlValue = p._2(ds) // call measurement function
    ))
  }

  /** Register new column name so that the new name is used when calculating a new checkpoint.  */
  private[atum] def registerColumnRename(oldName: String, newName: String): Unit = {
    val oldLowercaseName = oldName.trim.toLowerCase
    val newMeasurements = measurements.map(measure => {
      if (measure.controlCol.trim.toLowerCase == oldLowercaseName) {
        measure.copy(controlCol = newName)
      }
      else {
        measure
      }
    })

    processors = newMeasurements.map(m => (m, getMeasurementFunction(m)))
    measurements = newMeasurements
  }

  /** Register a column drop so no measurements tracking is necessary.  */
  private[atum] def registerColumnDrop(columnName: String): Unit = {
    val oldLowercaseName = columnName.trim.toLowerCase
    val newMeasurements = measurements.filter(measure => measure.controlCol.trim.toLowerCase != oldLowercaseName)

    processors = newMeasurements.map(m => (m, getMeasurementFunction(m)))
    measurements = newMeasurements
  }

  /** The method maps string representation of control type to measurement function.  */
  private def getMeasurementFunction(measurement: Measurement): MeasurementFunction = {

    measurement.controlType.toLowerCase match {
      case a if isControlMeasureTypeEqual(a, Constants.controlTypeRecordCount) => (ds: Dataset[Row]) => ds.count()
      case a if isControlMeasureTypeEqual(a, Constants.controlTypeDistinctCount) => (ds: Dataset[Row]) => {
          ds.select(col(measurement.controlCol)).distinct().count()
      }
      case a if isControlMeasureTypeEqual(a, Constants.controlTypeAggregatedTotal) =>
        (ds: Dataset[Row]) => {
          val aggCol = sum(col(valueColumnName))
          aggregateColumn(ds, measurement.controlCol, aggCol)
        }
      case a if isControlMeasureTypeEqual(a, Constants.controlTypeAbsAggregatedTotal) =>
        (ds: Dataset[Row]) => {
          val aggCol = sum(abs(col(valueColumnName)))
          aggregateColumn(ds, measurement.controlCol, aggCol)
        }
      case a if isControlMeasureTypeEqual(a, Constants.controlTypeHashCrc32) =>
        (ds: Dataset[Row]) => {
          val aggColName = ControlUtils.getTemporaryColumnName(ds)
          ds.withColumn(aggColName, crc32(col(measurement.controlCol).cast("String")))
            .agg(sum(col(aggColName)))
            .collect()(0)(0)
        }
      case _ =>
        Atum.log.error(s"Unrecognized control measurement type '${measurement.controlType}'. Available control measurement types are: " +
          s"${getListOfControlMeasurementTypes.mkString(",")}.")
        (_: Dataset[Row]) => "N/A"
    }
  }

  /* Compares control measurement types. Ignores case and supports measurement types without the prefix, e.g. "count" instead of "controlType.Count" */
  private def isControlMeasureTypeEqual(controlMeasureTypeA: String, controlMeasureTypeB: String): Boolean = {
    if (controlMeasureTypeA.toLowerCase == controlMeasureTypeB.toLowerCase) {
      true
    } else {
      val strippedMeasureTypeA = if (controlMeasureTypeA.contains('.')) controlMeasureTypeA.split('.').last.toLowerCase else controlMeasureTypeA.toLowerCase
      val strippedMeasureTypeB = if (controlMeasureTypeB.contains('.')) controlMeasureTypeB.split('.').last.toLowerCase else controlMeasureTypeB.toLowerCase
      strippedMeasureTypeA == strippedMeasureTypeB
    }
  }

  def getListOfControlMeasurementTypes: Seq[String] = {
    List(Constants.controlTypeRecordCount,
      Constants.controlTypeDistinctCount,
      Constants.controlTypeAggregatedTotal,
      Constants.controlTypeAbsAggregatedTotal,
      Constants.controlTypeHashCrc32)
  }

  private def workaroundBigDecimalIssues(value: Any) = {
    // If aggregated value is java.math.BigDecimal, convert it to scala.math.BigDecimal
    value match {
      case v: java.math.BigDecimal =>
        val valueInScala = scala.math.BigDecimal(v)
        // If it is zero, return zero instead of BigDecimal which can be something like 0E-18
        if (valueInScala == 0) 0 else valueInScala
      case a => a
    }
  }

  private def aggregateColumn(ds: Dataset[Row], measureColumn: String, aggExpression: Column) = {
    val dataType = ds.select(measureColumn).schema.fields(0).dataType
    val aggregatedValue = dataType match {
      case _: LongType =>
        // This is protection against long overflow, e.g. Long.MaxValue = 9223372036854775807:
        //   scala> sc.parallelize(List(Long.MaxValue, 1)).toDF.agg(sum("value")).take(1)(0)(0)
        //   res11: Any = -9223372036854775808
        // Converting to BigDecimal fixes the issue
        //val ds2 = ds.select(col(measurement.controlCol).cast(DecimalType(38, 0)).as("value"))
        //ds2.agg(sum(abs($"value"))).collect()(0)(0)
        val ds2 = ds.select(col(measureColumn).cast(DecimalType(38, 0)).as(valueColumnName))
        val collected = ds2.agg(aggExpression).collect()(0)(0)
        if (collected == null) 0 else collected
      case _: StringType =>
        // Support for string type aggregation
        val ds2 = ds.select(col(measureColumn).cast(DecimalType(38, 18)).as(valueColumnName))
        val collected = ds2.agg(aggExpression).collect()(0)(0)
        val value = if (collected==null) new java.math.BigDecimal(0) else collected.asInstanceOf[java.math.BigDecimal]
        val stringResult = value
          .stripTrailingZeros    // removes trailing zeros (2001.500000 -> 2001.5, but can introduce scientific notation (600.000 -> 6E+2)
          .toPlainString         // converts to normal string (6E+2 -> "600")
        BigDecimal(stringResult) // converts back to BigDecimal
      case _ =>
        val ds2 = ds.select(col(measureColumn).as(valueColumnName))
        val collected = ds2.agg(aggExpression).collect()(0)(0)
        if (collected == null) 0 else collected
    }
    //check if total is required to be presented as larger type - big decimal
    workaroundBigDecimalIssues(aggregatedValue)
  }

}
