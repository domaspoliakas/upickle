package mill.entrypoint

import mainargs.Flag
import mill.MillCliConfig
import mill.define.SelectMode
import mill.util.SystemStreams
import os.Path
import utest._

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, PrintStream}
import java.nio.file.NoSuchFileException
import scala.util.control.NonFatal

abstract class ScriptTestSuite(fork: Boolean, clientServer: Boolean = false) extends TestSuite {
  def workspaceSlug: String
  def scriptSourcePath: os.Path
  def buildPath: os.SubPath = os.sub / "build.sc"

  def workspacePath: os.Path = os.pwd / "target" / "workspace" / workspaceSlug
  def wd = workspacePath / buildPath / os.up
  val stdOutErr = System.out // new PrintStream(new ByteArrayOutputStream())
  val stdIn = new ByteArrayInputStream(Array())
  val disableTicker = false
  val debugLog = false
  val keepGoing = false
  val systemProperties = Map[String, String]()
  val threadCount = sys.props.get("MILL_THREAD_COUNT").map(_.toInt).orElse(Some(1))
  private def runnerStdout(stdout: PrintStream, s: Seq[String]) = {

    val streams = new SystemStreams(System.out, stdOutErr, stdIn)
    val config = MillCliConfig()
    new MillBootstrap(
      wd,
      config,
      streams,
      Map.empty,
      threadCount,
      systemProperties,
      s.toList,
      ringBell = false,
      _ => (),
      None,
      sys.props.toMap,
      mill.entrypoint.MillMain.getLogger(streams, config, mainInteractive = false)
    ).runScript()
  }
  def eval(s: String*): Boolean = {
    if (!fork) runnerStdout(System.out, s)._1
    else evalFork(os.Inherit, s)
  }
  def evalStdout(s: String*): (Boolean, Seq[String]) = {
    if (!fork) {
      val outputStream = new ByteArrayOutputStream
      val printStream = new PrintStream(outputStream)
      val result = runnerStdout(printStream, s.toList)
      val stdoutArray = outputStream.toByteArray
      val stdout: Seq[String] =
        if (stdoutArray.isEmpty) Seq.empty
        else new String(stdoutArray).split('\n')
      (result._1, stdout)
    } else {
      val output = Seq.newBuilder[String]
      val processOutput = os.ProcessOutput.Readlines(output += _)

      val result = evalFork(processOutput, s)
      (result, output.result())
    }
  }
  private def evalFork(stdout: os.ProcessOutput, s: Seq[String]): Boolean = {
    val millRelease = Option(System.getenv("MILL_TEST_RELEASE"))
      .getOrElse(throw new NoSuchElementException(
        s"System environment variable `MILL_TEST_RELEASE` not defined. It needs to point to the Mill binary to use for the test."
      ))
    val millReleaseFile = os.Path(millRelease, os.pwd)
    if (!os.exists(millReleaseFile)) {
      throw new NoSuchFileException(s"Mill binary to use for test not found under: ${millRelease}")
    }

    val extraArgs = if (clientServer) Seq() else Seq("--no-server")
    val env = Map("MILL_TEST_SUITE" -> this.getClass().toString())

    try {
      os.proc(millReleaseFile, extraArgs, s).call(
        cwd = wd,
        stdin = os.Inherit,
        stdout = stdout,
        stderr = os.Inherit,
        env = env
      )
      if (clientServer) {
        // try to stop the server
        try {
          os.proc(millReleaseFile, "shutdown").call(
            cwd = wd,
            stdin = os.Inherit,
            stdout = stdout,
            stderr = os.Inherit,
            env = env
          )
        } catch { case NonFatal(_) => }
      }
      true
    } catch { case NonFatal(_) => false }
  }
  def meta(s: String): String = {
    val Seq((List(selector), _)) =
      mill.define.ParseArgs.apply(Seq(s), SelectMode.Single).getOrElse(???)

    val segments = selector._2.value.flatMap(_.pathSegments)
    os.read(wd / "out" / segments.init / s"${segments.last}.json")
  }

  def initWorkspace(): Path = {
    os.remove.all(workspacePath)
    os.makeDir.all(workspacePath / os.up)
    // The unzipped git repo snapshots we get from github come with a
    // wrapper-folder inside the zip file, so copy the wrapper folder to the
    // destination instead of the folder containing the wrapper.

    os.copy(scriptSourcePath, workspacePath)
    workspacePath
  }
}