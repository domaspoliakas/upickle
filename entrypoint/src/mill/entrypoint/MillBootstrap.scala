package mill.entrypoint
import ammonite.util.Colors
import mill.MillCliConfig
import mill.api.PathRef

import java.io.{InputStream, PrintStream}
import mill.eval.Evaluator
import mill.internal.Watchable
import mill.main.{EvaluatorState, RunScript}
import mill.util.PrintLogger
import mill.define.SelectMode

import scala.annotation.tailrec
import scala.collection.immutable.Seq
object MillBootstrap{



  def evaluate(config: MillCliConfig,
               mainInteractive: Boolean,
               stdin: InputStream,
               stdout: PrintStream,
               stderr: PrintStream,
               env: Map[String, String],
               threadCount: Option[Int],
               systemProperties: Map[String, String],
               targetsAndParams: Seq[String]): (Seq[os.Path], Either[String, EvaluatorState]) = {
    val bootstrapModule = new MillBootstrapModule(
      ammonite.util.Classpath
        .classpath(getClass.getClassLoader, None)
        .map(_.toURI).filter(_.getScheme == "file")
        .map(_.getPath)
        .map(os.Path(_)),
      os.pwd / "mill-build"
    )

    val colored = config.color.getOrElse(mainInteractive)
    val colors = if (colored) ammonite.util.Colors.Default else ammonite.util.Colors.BlackWhite
    val logger = makeLogger(config, stdin, stdout, stderr, colored, colors)
    val evaluator = makeEvaluator(os.pwd / "out" / "mill-build", config, env, threadCount, bootstrapModule, logger)

    RunScript.evaluateTasks(
      evaluator,
      Seq("runClasspath"),
      mill.define.SelectMode.Separated
    ) match{
      case Left(msg) => (Nil, Left(msg))
      case Right((bootstrapWatched, evaluated)) =>
        val bootstrapWatchedPaths = bootstrapWatched.map(_.path)
        evaluated match{
          case Left(msg) => (bootstrapWatchedPaths, Left(msg))
          case Right(Seq((runClasspath: Seq[PathRef], _))) =>
            val runClassLoader = new java.net.URLClassLoader(
              runClasspath.map(_.path.toNIO.toUri.toURL).toArray
            )
            val cls = runClassLoader.loadClass("millbuild.MillBuildModule$")
            val rootModule = cls.getField("MODULE$").get(cls).asInstanceOf[mill.define.BaseModule]

            val evalState = EvaluatorState(
              rootModule, Nil, collection.mutable.Map.empty, Nil, systemProperties.keySet, Nil
            )
            val evaluator2 = makeEvaluator(os.pwd / "out", config, env, threadCount, rootModule, logger)

            RunScript.evaluateTasks(evaluator2, targetsAndParams, SelectMode.Separated) match{
              case Left(msg) => (bootstrapWatchedPaths, Left(msg))
              case Right((buildWatched, evaluated)) =>

                val buildWatchedPaths = (bootstrapWatched ++ buildWatched).map(_.path)
                evaluated match{
                  case Left(msg) => (buildWatchedPaths, Left(msg))
                  case Right(items) =>

                    (buildWatchedPaths, Right(evalState))
                }

            }

        }

    }

  }

  private def makeLogger(config: MillCliConfig, stdin: InputStream, stdout: PrintStream, stderr: PrintStream, colored: Boolean, colors: Colors) = {
    mill.util.PrintLogger(
      colored = colored,
      disableTicker = config.disableTicker.value,
      infoColor = colors.info(),
      errorColor = colors.error(),
      outStream = stdout,
      infoStream = stderr,
      errStream = stderr,
      inStream = stdin,
      debugEnabled = config.debugLog.value,
      context = ""
    )
  }

  private def makeEvaluator(outPath: os.Path,
                            config: MillCliConfig,
                            env: Map[String, String],
                            threadCount: Option[Int],
                            bootstrapModule: mill.define.BaseModule,
                            logger: PrintLogger) = {
    Evaluator(
      config.home,
      outPath,
      outPath,
      bootstrapModule,
      logger
    ).withClassLoaderSig(Nil)
      .withWorkerCache(collection.mutable.Map.empty)
      .withEnv(env)
      .withFailFast(!config.keepGoing.value)
      .withThreadCount(threadCount)
      .withImportTree(Nil)
  }
}
