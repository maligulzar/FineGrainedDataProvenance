package examples.benchmarks.symbolic_benchmarks

import examples.benchmarks.AggregationFunctions
import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkConf, SparkContext}
import sparkwrapper.SparkContextWithDP
import symbolicprimitives.{SymInt, SymString, Utils}

/**
  * Created by ali on 7/20/17.
  * Copied from BigSiftUI repo by jteoh on 4/16/20
  * https://github.com/maligulzar/BigSiftUI/blob/master/src/airport/AirportTransitAnalysis.scala
  * Logging and other miscellaneous bigsift-specific functionality is removed.
  */
object AirportTransitSymbolic {
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
    val input = scdp.textFileSymbolic(logFile)
    Utils.setUDFAwareDefaultValue(true)
    
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
    // TODO: define some sort of influence function
    val out = AggregationFunctions.sumByKey(fil)
    
    out.collectWithProvenance().foreach(println)
  }
  
  def getDiff(arr: SymString, dep: SymString): SymInt = {
    val arr_min = arr.split(":")(0).toInt * 60 + arr.split(":")(1).toInt
    val dep_min = dep.split(":")(0).toInt * 60 + dep.split(":")(1).toInt
    if(dep_min - arr_min < 0){
      return dep_min - arr_min + 24*60
    }
    return dep_min - arr_min
  }
  
  
}
