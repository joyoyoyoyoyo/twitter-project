
import java.io.File

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.io.{Codec, Source}
import java.util.concurrent.ForkJoinPool

import scala.collection.concurrent
import scala.util.{Success, Try}

object ValidateApprovals extends App {
  val projectPath = new File(".").getCanonicalPath + "/"
  val arg1, arg2 = ValidatorCLI.parse(args, projectPath)
  val acceptors =
  arg1.value._1.foldLeft(List[String]()){ (acc, elem) => elem :: acc }
  val modifiedFiles =
    arg2.value._2.foldLeft(List[String]()){ (acc, elem) => elem :: acc }


  // threading and parellism context
  val parallelism = Runtime.getRuntime.availableProcessors * 32
  val forkJoinPool = new ForkJoinPool(parallelism)
  val executionContext = ExecutionContext.fromExecutorService(forkJoinPool)

  // Our persistent data structures
  // on a read, we determine a directories dependencies
  val localPathToDependents = concurrent.TrieMap[String, List[String]]()
  val localPathToAuthorizers = concurrent.TrieMap[String, List[String]]()
  val localPathToSuccessors = concurrent.TrieMap[String, String]()
  val localPathToChangedFiles = concurrent.TrieMap[String, String]()

  val root = new File(".")
  walkTree(root)(executionContext)

  val edges = localPathToDependents.keys.foldLeft(List.empty[(String, String)]) { (edgesAcc,e1) =>
    val destination = localPathToDependents.get(e1)
    if (Try(destination.get).isSuccess) {
      destination.get.flatMap(e2 =>
        (e1, e2) :: edgesAcc
      )
    }
    else
      edgesAcc
  }.distinct
  val nodes = localPathToDependents.keys.toList.distinct
  val dependencyGraph: Digraph[String] = new Digraph(nodes, edges)

  // Acceptance Check
  val validationMap = Map[String, Boolean]().withDefaultValue(false)
  acceptors.foreach { proposedAcceptor => {
      modifiedFiles.foreach { file =>
        val directory = java.nio.file.Paths.get(modifiedFiles.head).getParent.toString
        val dependencies = dependencyGraph.dfs(directory)
        dependencies.foreach { dep =>
          val users = localPathToAuthorizers.getOrElse(dep, Nil)
          if (users.contains(proposedAcceptor))
            validationMap.updated(proposedAcceptor, true)
        }
      }
    }
  }

  if(validationMap.values.count(_ == false) == 0)
    println("Accepted")
  else
    println("Insufficient approvals")



  def parallelTraverse[A, B, C, D](
        localFile: File,
        cacheDirectories: File => Unit,
        cacheFiles: File => Unit,
        cacheOwners: File => Unit,
        cacheDependencies: File => Unit) (implicit ec: ExecutionContext): Future[Unit] = {
    localFile match {
      case directories if directories.isDirectory => { Future.successful(cacheDirectories(directories)) }
      case changedFiles if changedFiles.isFile => { Future.successful(cacheFiles(changedFiles)) }
      case owners if owners.getName.endsWith(ReadOnly.OWNERS.toString) => { Future.successful(cacheOwners(owners)) }
      case dependencies if dependencies.getName.endsWith(ReadOnly.DEPENDENCIES.toString) => { Future.successful(cacheDependencies(dependencies)) }
    }
  }

  def walkTree(file: File)(implicit ec: ExecutionContext): Iterable[File] = {
    Future { parallelTraverse(file, cacheDirectories, cacheFiles, cacheOwners, cacheDependencies)(ec) }
    val children = new Iterable[File] {
      def iterator = if (file.isDirectory) file.listFiles.iterator else Iterator.empty
    }
    Seq(file) ++: children.flatMap(walkTree)
  }

  // asynchronously cache files in project repository
  def cacheDirectories(file: File)(implicit ec: ExecutionContext) = {
    val parent = file.getParentFile.getCanonicalFile
    if (!root.getCanonicalFile.equals(parent))
      localPathToSuccessors.put(file.getCanonicalPath, file.getParentFile.getCanonicalPath)

  }
  def cacheFiles(file: File) = {
    modifiedFiles.foreach { changedFile =>
      if (file.getCanonicalPath.contains(changedFile))
        localPathToChangedFiles.put(changedFile, file.getCanonicalPath)
    }
    val canonicalDirectory = file.getCanonicalPath
    val parent = file.getParentFile.getCanonicalFile
    if (!root.getCanonicalFile.equals(parent))
      localPathToChangedFiles.put(file.getCanonicalPath, file.getParentFile.getCanonicalPath)

  }


  /**
    * Create an association between the current directory and the directories listed on the dependency list
    *
    * Example: src/com/twitter/message/Dependencies -> List["eclarke","kantonelli"]
    *
    * @param file: USERS file
    * @param ec: Threading context
    */
  def cacheOwners(file: File)(implicit ec: ExecutionContext) = {
    val owners = { Future.successful(Source.fromFile(file)(Codec.UTF8).getLines.toList) }
    owners.onComplete {
      case Success(authorizedUsers) => {
        val canonicalDirectory = file.getCanonicalPath.substring(0, file.getCanonicalPath.length -
          ReadOnly.OWNERS.toString.length - 1)
        val uniqueUsers = localPathToAuthorizers.getOrElse(file.getCanonicalPath.substring(0, file.getCanonicalPath.length -
          ReadOnly.OWNERS.toString.length - 1), List[String]()) ::: authorizedUsers
        localPathToAuthorizers.put(file.getCanonicalPath, uniqueUsers)
        val parent = file.getParentFile.getCanonicalFile
        if (!root.getCanonicalFile.equals(parent))
        localPathToSuccessors.put(file.getCanonicalPath.substring(0, file.getCanonicalPath.length -
          ReadOnly.OWNERS.toString.length - 1), file.getParentFile.getCanonicalPath)

      }
    }
  }

  /**
    * Create an association between the current directory (canonical name) and the list of owners in that directory
    *
    * Example: src/com/twitter/message/Dependencies -> List["src/com/twitter/follow", "src/com/twitter/user"]
    *
    * @param file: DEPENDENCIES file in the current directory
    * @param ec: Threading context
    */
  def cacheDependencies(file: File)(implicit ec: ExecutionContext) = { // normalized the project directory format
    val dependencies = { Future.successful(Source.fromFile(file)(Codec.UTF8).getLines.map(path => s"$projectPath$path").toList) }
    dependencies.onComplete {
      case Success(dependencyList) => {
        val canonicalDirectory = file.getCanonicalPath.substring(0, file.getCanonicalPath.length -
          ReadOnly.DEPENDENCIES.toString.length - 1)
        val canonicalDependency =
          localPathToDependents.getOrElse(canonicalDirectory, List[String]()) ::: dependencyList
        localPathToDependents.put(canonicalDirectory, canonicalDependency)
        val parent = file.getParentFile.getCanonicalFile
        if (!root.getCanonicalFile.equals(parent)) {
          localPathToSuccessors.put(canonicalDirectory, file.getParentFile.getCanonicalPath)
        }
      }
    }
  }

}

object ReadOnly extends Enumeration {
  type ReadOnly = Value
  val OWNERS, DEPENDENCIES = Value
}