package sbtrelease

import sbt._
import sbt.Aggregation.KeyValue
import Utilities._

object SbtCompat {
  val EmptySetting = ""

  def runTaskAggregated[T](taskKey: TaskKey[T], state: State) = {
    import EvaluateTask._
    val extra = Aggregation.Dummies(KNil, HNil)
    val extracted = state.extract
    val config = extractedConfig(extracted, extracted.structure)

    val rkey = Utilities.resolve(taskKey.scopedKey, extracted)
    val tasks = Aggregation.getTasks(rkey, extracted.structure, true)
    val toRun = tasks map { case KeyValue(k,t) => t.map(v => KeyValue(k,v)) } join;


    val (newS, result) = withStreams(extracted.structure, state){ str =>
      val transform = nodeView(state, str, extra.tasks, extra.values)
      runTask(toRun, state,str, extracted.structure.index.triggers, config)(transform)
    }
    (newS, result)
  }

}