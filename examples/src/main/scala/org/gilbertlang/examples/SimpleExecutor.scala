package org.gilbertlang.examples

import org.gilbertlang.language.Gilbert
import eu.stratosphere.client.LocalExecutor
import org.gilbertlang.runtime._
import org.gilbertlang.runtimeMacros.linalg.RuntimeConfiguration

object SimpleExecutor {

  def main(args:Array[String]){
    val executable = Gilbert.compileRessource("test.gb")
    val optimized = Gilbert.optimize(executable, transposePushdown = true, mmReorder = true)

    val runtimeConfig =new RuntimeConfiguration(blocksize = 5,
      checkpointDir = Some("/Users/till/uni/ws14/dima/mastersthesis/workspace/gilbert/spark"),
      iterationsUntilCheckpoint = 3)
    val engineConfiguration = EngineConfiguration(parallelism=4)

//    withMahout()
    withBreeze()
//    local(optimized)
//    withSpark.local(engineConfiguration).execute(optimized, runtimeConfig)
    withStratosphere.local(engineConfiguration).execute(optimized, runtimeConfig)
  }
}