package examples.benchmarks.baseline

import org.apache.spark.{SparkConf, SparkContext}
import symbolicprimitives.Utils

/**
  * Created by ali on 7/20/17.
  * Copied from BigSiftUI repo by jteoh on 4/16/20
  * https://github.com/maligulzar/BigSiftUI/blob/master/src/airport/AirportTransitAnalysis.scala
  * Logging and other miscellaneous bigsift-specific functionality is removed.
  */
object AirportTransitBaseline {
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
    val sc = new SparkContext(sparkConf) //set up lineage context and start capture lineage
    val input = sc.textFile(logFile)
    
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
    
    val out = fil.reduceByKey(_+_)
    // other considerations for influence functions: Min Infl, BottomN, AbsoluteTopN
    Utils.runBaseline(out)
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
