import scala.annotation.tailrec
import scala.util.{Success, Try}


object ValidatorCLI {
  def parse(args: Array[String], projectPath: String)  = {
    Predef.require(args.length > 0)

    def usage = "--validate_approvers --approvals <comma-delimited-approvers> --changed-files <comma-delimited-changed-files>"
    @tailrec
    def loopOnArgs(acc : List[String], approvers: List[String], cf: List[String]): (List[String], List[String]) = {
      acc match {
        case Nil => (approvers, cf)
        case "--approvers" :: approversDelimited :: (tail: List[String]) =>
          loopOnArgs(tail, approversDelimited.split(",").toList ::: approvers, cf)
        case "--changed-files" :: changedFilesDelimited :: tail =>
          loopOnArgs(tail, approvers, changedFilesDelimited.split(",").map(projectPath + _).toList ::: cf) }
    }

    // Run
    Try(loopOnArgs(args.toList, Nil, Nil)) match {
      case Success(x) => x
      case _ => Logger.warn(s"Usage:${usage}"); sys.exit(0)
    }
  }
}
