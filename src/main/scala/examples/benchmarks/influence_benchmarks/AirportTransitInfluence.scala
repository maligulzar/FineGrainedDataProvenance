package examples.benchmarks.influence_benchmarks

import examples.benchmarks.AggregationFunctions
import org.apache.spark.{SparkConf, SparkContext}
import provenance.data.InfluenceMarker
import provenance.rdd.{AbsoluteTopNIntInfluenceTracker, BottomNInfluenceTracker, IntStreamingOutlierInfluenceTracker, MaxInfluenceTracker, MinInfluenceTracker, StreamingOutlierInfluenceTracker, TopNInfluenceTracker}
import sparkwrapper.SparkContextWithDP
import symbolicprimitives.{SymInt, SymString, Utils}

/**
  * Created by ali on 7/20/17.
  * Copied from BigSiftUI repo by jteoh on 4/16/20
  * https://github.com/maligulzar/BigSiftUI/blob/master/src/airport/AirportTransitAnalysis.scala
  * Logging and other miscellaneous bigsift-specific functionality is removed.
  */
object AirportTransitInfluence {
  def main(args: Array[String]): Unit = {
    //set up spark configuration
    val sparkConf = new SparkConf()
    var logFile = ""
    var local = 500
    if (args.length < 2) {
      sparkConf.setMaster("local[6]")
      sparkConf.setAppName("Airport Transit Time Analysis").set("spark.executor.memory", "2g")
      logFile = "datasets/airportdata"
    } else {
      logFile = args(0)
      local = args(1).toInt
    } //set up spark context
    val ctx = new SparkContext(sparkConf) //set up lineage context and start capture lineage
    val scdp = new SparkContextWithDP(ctx)
    val input = scdp.textFileProv(logFile)
    
    val map = input.map { s =>
      val tokens = s.split(",")
      val dept_hr = tokens(3).split(":")(0)
      val diff = getDiff(tokens(2), tokens(3))
      val airport = tokens(4)
      ((airport, dept_hr), diff)
    }
    val fil = map.filter { v =>
      v._2 < 45
    }
    
    val out = AggregationFunctions.sumByKeyWithInfluence(fil)
    // other considerations for influence functions: Min Infl, BottomN, AbsoluteTopN
    Utils.runTraceAndPrintStats(out,
                                  (row: ((String, String), Int)) => row._2 < 0,
                                  input,
                                  (s: String) => {
                                    val tokens = s.split(",")
                                    val diff = getDiff(tokens(2), tokens(3))
                                    diff < 0
                                  })
  }
  
  def getDiff(arr: String, dep: String): Int = {
    val arr_min = arr.split(":")(0).toInt * 60 + arr.split(":")(1).toInt
    val dep_min = dep.split(":")(0).toInt * 60 + dep.split(":")(1).toInt
    if(dep_min - arr_min < 0){
      return 24*60 + dep_min - arr_min
    }
    return dep_min - arr_min
  }
  
  
}
