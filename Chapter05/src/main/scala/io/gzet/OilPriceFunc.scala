/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.gzet

import java.text.SimpleDateFormat
import java.util.Calendar

import org.apache.spark
import org.apache.spark.sql.expressions.Window

object OilPriceFunc {

    // use this if the window function misbehaves due to timezone e.g. BST
    // ./spark-shell --driver-java-options "-Duser.timezone=UTC"

    // define a function to reformat the date field
    def convert(date:String) : String = {
      val df1 = new SimpleDateFormat("dd/MM/yyyy")
      val dt = df1.parse(date)
      val df2 = new SimpleDateFormat("yyyy-MM-dd")
      df2.format(dt)
    }

    // create and save oil price changes
    def createOilPriceDF(inputfile: String, outputfile: String) = {

      val oilPriceDF = spark.
        read.
        option("header", "true").
        option("inferSchema", "true").
        csv(inputfile)

      val convertDateUDF = udf { (Date: String) => convert(Date) }

      val oilPriceDatedDF = oilPriceDF.withColumn("DATE", convertDateUDF(oilPriceDF("DATE")))

      // offset to start at beginning of week
      val windowDF = oilPriceDatedDF.groupBy(window(oilPriceDatedDF.col("DATE"), "7 days", "7 days", "4 days"))

      val windowLastDF = windowDF.agg(last("PRICE") as "last(PRICE)").sort("window")

//      windowLastDF.show(20, false)

      val sortedWindow = Window.orderBy("window.start")

      val lagLastCol = lag(col("last(PRICE)"), 1).over(sortedWindow)
      val lagLastColDF = windowLastDF.withColumn("lastPrev(PRICE)", lagLastCol)

//      lagLastColDF.show(20, false)

      val simplePriceChangeFunc = udf { (last: Double, prevLast: Double) =>
        var change = ((last - prevLast) compare 0).signum
        if (change == -1)
          change = 0
        change.toDouble
      }

      val findDateTwoDaysAgoUDF = udf { (date: String) =>
        val dateFormat = new SimpleDateFormat("yyyy-MM-dd")
        val cal = Calendar.getInstance
        cal.setTime(dateFormat.parse(date))
        cal.add(Calendar.DATE, -3)
        dateFormat.format(cal.getTime)
      }

      val oilPriceChangeDF = lagLastColDF.withColumn("label", simplePriceChangeFunc(
        lagLastColDF("last(PRICE)"),
        lagLastColDF("lastPrev(PRICE)")
      )).withColumn("commonFriday", findDateTwoDaysAgoUDF(lagLastColDF("window.end")))

//      oilPriceChangeDF.show(20, false)

      oilPriceChangeDF.select("label", "commonFriday").
        write.
        format("com.databricks.spark.csv").
        //.option("header", "true")
        //.option("codec", "org.apache.hadoop.io.compress.GzipCodec")
        save(outputfile)
    }
}

