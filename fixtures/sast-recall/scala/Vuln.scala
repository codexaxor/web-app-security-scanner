import sys.process._
import scala.util.Random

object Vuln {
  def token(): Int = {
    val r = new Random()
    r.nextInt()
  }
  def run(cmd: String): Unit = {
    Seq(cmd, "-l").!
  }
}
