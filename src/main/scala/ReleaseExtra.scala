package sbtrelease

import java.io.File
import sbt._
import Keys._
import sbt.Package.ManifestAttributes
import annotation.tailrec
import Utilities._

object ReleaseStateTransformations {
  import ReleasePlugin.ReleaseKeys._
  import Utilities._

  lazy val checkSnapshotDependencies: ReleaseStep = { st: State =>
    val thisRef = st.extract.get(thisProjectRef)
    val (newSt, result) = SbtCompat.runTaskAggregated(snapshotDependencies in thisRef, st)
    val snapshotDeps = result match {
      case Value(value) => value.flatMap(_.value)
      case Inc(cause) => sys.error("Error checking for snapshot dependencies: " + cause)
    }
    val useDefs = newSt.get(useDefaults).getOrElse(false)
    if (!snapshotDeps.isEmpty) {
      if (useDefs) {
        sys.error("Aborting release due to snapshot dependencies.")
      } else {
        st.log.warn("Snapshot dependencies detected:\n" + snapshotDeps.mkString("\n"))
        SimpleReader.readLine("Do you want to continue (y/n)? [n] ") match {
          case Yes() =>
          case _ => sys.error("Aborting release due to snapshot dependencies.")
        }
      }
    }
    newSt
  }


  lazy val inquireVersions: ReleaseStep = { st: State =>
    val extracted = Project.extract(st)

    val useDefs = st.get(useDefaults).getOrElse(false)
    val currentV = extracted.get(version)

    val releaseFunc = extracted.get(releaseVersion)
    val suggestedReleaseV = releaseFunc(currentV)

    val releaseV = readVersion(suggestedReleaseV, "Release version [%s] : ", useDefs)

    val nextFunc = extracted.get(nextVersion)
    val suggestedNextV = nextFunc(releaseV)
    val nextV = readVersion(suggestedNextV, "Next version [%s] : ", useDefs)

    st.put(versions, (releaseV, nextV))
  }


  lazy val runTest: ReleaseStep = {st: State =>
    if (!st.get(skipTests).getOrElse(false)) {
      val extracted = Project.extract(st)
      val ref = extracted.get(thisProjectRef)
      extracted.runAggregated(test in Test in ref, st)
    } else st
  }

  lazy val setReleaseVersion: ReleaseStep = setVersion(_._1)
  lazy val setNextVersion: ReleaseStep = setVersion(_._2)
  private[sbtrelease] def setVersion(selectVersion: Versions => String): ReleaseStep =  { st: State =>
    val vs = st.get(versions).getOrElse(sys.error("No versions are set! Was this release part executed before inquireVersions?"))
    val selected = selectVersion(vs)


    val extracted: Extracted = Project.extract(st)
    import extracted._

    val dir = extracted.get(baseDirectory)
    st.log.info("Setting version to '%s' in directory '%s'." format (selected,dir))

    val versionString = "%sversion := \"%s\"%s" format (lineSep, selected, lineSep)
    IO.write(dir / "version.sbt", versionString)

    reapply(Seq(
      version := selected
    ), st)
  }

  lazy val commitReleaseVersion = ReleaseStep(commitReleaseVersionAction, initialGitChecks)

  private[sbtrelease] lazy val initialGitChecks = { st: State =>
    if (!new File(".git").exists) {
      sys.error("Aborting release. Working directory is not a git repository.")
    }

    val status = (Git.status !!).trim
    if (status.nonEmpty) {
      sys.error("Aborting release. Working directory is dirty.")
    }

    st.log.info("Starting release process off git commit: " + Git.currentHash)
    st
  }

  private[sbtrelease] lazy val commitReleaseVersionAction = { st: State =>
    val newState = commitVersion("Releasing %s")(st)
    reapply(Seq[Setting[_]](
      packageOptions += ManifestAttributes(
        "Git-Release-Hash" -> Git.currentHash
      )
    ), newState)
  }

  lazy val commitNextVersion: ReleaseStep = ReleaseStep(commitVersion("Bump to %s"))
  private[sbtrelease] def commitVersion(msgPattern: String) = { st: State =>
    val v = st.extract.get(version)

    val currentProj = st.extract.currentProject
    val versionName = "%s%s" format (currentProj.id, v)

    val path = currentProj.base + "/version.sbt"
    println("\npath%s\n" format path)
    Git.add(path) !! st.log
    val status = (Git.status !!) trim

    if (status.nonEmpty) {
      Git.commit(msgPattern format versionName) ! st.log
    } else {
      // nothing to commit. this happens if the version.sbt file hasn't changed.
    }
    st
  }

  lazy val tagRelease: ReleaseStep = { st: State =>
    @tailrec
    def findTag(tag: String): Option[String] = {
      if (Git.existsTag(tag)) {
        SimpleReader.readLine("Tag [%s] exists! Overwrite, keep or abort or enter a new tag (o/k/a)? [a] " format tag) match {
          case Some("" | "a" | "A") =>
            sys.error("Aborting release!")

          case Some("k" | "K") =>
            st.log.warn("The current tag [%s] does not point to the commit for this release!" format tag)
            None

          case Some("o" | "O") =>
            st.log.warn("Overwriting a tag can cause problems if others have already seen the tag (see `git help tag`)!")
            Some(tag)

          case Some(newTag) =>
            findTag(newTag)

          case None =>
            sys.error("No tag entered. Aborting release!")
        }
      } else {
        Some(tag)
      }
    }

    val tag = st.extract.get(tagName)
    val tagToUse = findTag(tag)
    tagToUse.foreach(Git.tag(_, force = true) !! st.log)


    tagToUse map (t =>
      reapply(Seq[Setting[_]](
        packageOptions += ManifestAttributes("Git-Release-Tag" -> t)
      ), st)
    ) getOrElse st
  }

  lazy val pushChanges: ReleaseStep = ReleaseStep(pushChangesAction, checkUpstream)
  private[sbtrelease] lazy val checkUpstream = { st: State =>
    if (!Git.hasUpstream) {
      sys.error("No tracking branch is set up. Either configure a remote tracking branch, or remove the pushChanges release part.")
    }

    st.log.info("Fetching from remote [%s] ..." format Git.trackingRemote)
    val result = Git.fetch(Git.trackingRemote) ! st.log
    if (result != 0) {
      SimpleReader.readLine("Error while fetching from remote. Still continue (y/n)? [n] ") match {
        case Yes() => // do nothing
        case _ => sys.error("Aborting the release!")
      }
    }

    if (Git.isBehindRemote) {
      SimpleReader.readLine("The upstream branch has unmerged commits. A subsequent push will fail! Continue (y/n)? [n] ") match {
        case Yes() => // do nothing
        case _ => sys.error("Merge the upstream commits and run `release` again.")
      }
    }
    st
  }

  private[sbtrelease] lazy val pushChangesAction = { st: State =>
    if (Git.hasUpstream) {
      SimpleReader.readLine("Push changes to the remote repository (y/n)? [y] ") match {
        case Yes() =>
          Git.pushCurrentBranch !! st.log
          Git.pushTags !! st.log
        case _ => st.log.warn("Remember to push the changes yourself!")
      }
    } else {
      st.log.info("Changes were NOT pushed, because no upstream branch is configured for the local branch [%s]" format Git.currentBranch)
    }
    st
  }

  lazy val publishArtifacts = ReleaseStep(
    action = publishArtifactsAction,
    check = st => {
      // getPublishTo fails if no publish repository is set up.
      val ex = st.extract
      val ref = ex.get(thisProjectRef)
      Classpaths.getPublishTo(ex.get(publishTo in Global in ref))
      st
    }
  )
  private[sbtrelease] lazy val publishArtifactsAction = { st: State =>
    val extracted = st.extract
    val ref = extracted.get(thisProjectRef)
    extracted.runAggregated(publish in Global in ref, st)
  }

  private def readVersion(ver: String, prompt: String, useDef: Boolean): String = {
    if (useDef) ver
    else SimpleReader.readLine(prompt format ver) match {
      case Some("") => ver
      case Some(input) => Version(input).map(_.string).getOrElse(versionFormatError)
      case None => sys.error("No version provided!")
    }
  }

  def reapply(settings: Seq[Setting[_]], state: State) = {
    val extracted = state.extract
    import extracted._

    val append = Load.transformSettings(Load.projectScope(currentRef), currentRef.build, rootProject, settings)

    // We don't want even want to be able to save the settings that are applied to the session during the release cycle.
    // Just using an empty string works fine and in case the user calls `session save`, empty lines will be generated.
		val newSession = session.appendSettings( append map (a => (a, SbtCompat.EmptySetting)))
		BuiltinCommands.reapply(newSession, structure, state)
  }
}


object ExtraReleaseCommands {
  import ReleaseStateTransformations._

  private lazy val initialGitChecksCommandKey = "release-git-checks"
  lazy val initialGitChecksCommand = Command.command(initialGitChecksCommandKey)(initialGitChecks)

  private lazy val checkSnapshotDependenciesCommandKey = "release-check-snapshot-dependencies"
  lazy val checkSnapshotDependenciesCommand = Command.command(checkSnapshotDependenciesCommandKey)(checkSnapshotDependencies)

  private lazy val inquireVersionsCommandKey = "release-inquire-versions"
  lazy val inquireVersionsCommand = Command.command(inquireVersionsCommandKey)(inquireVersions)

  private lazy val setReleaseVersionCommandKey = "release-set-release-version"
  lazy val setReleaseVersionCommand = Command.command(setReleaseVersionCommandKey)(setReleaseVersion)

  private lazy val setNextVersionCommandKey = "release-set-next-version"
  lazy val setNextVersionCommand = Command.command(setNextVersionCommandKey)(setNextVersion)

  private lazy val commitReleaseVersionCommandKey = "release-commit-release-version"
  lazy val commitReleaseVersionCommand =  Command.command(commitReleaseVersionCommandKey)(commitReleaseVersion)

  private lazy val commitNextVersionCommandKey = "release-commit-next-version"
  lazy val commitNextVersionCommand = Command.command(commitNextVersionCommandKey)(commitNextVersion)

  private lazy val tagReleaseCommandKey = "release-tag-release"
  lazy val tagReleaseCommand = Command.command(tagReleaseCommandKey)(tagRelease)

  private lazy val pushChangesCommandKey = "release-push-changes"
  lazy val pushChangesCommand = Command.command(pushChangesCommandKey)(pushChanges)
}


object Utilities {
  val lineSep = sys.props.get("line.separator").getOrElse(sys.error("No line separator? Really?"))

  class StateW(st: State) {
    def extract = Project.extract(st)
  }
  implicit def stateW(st: State): StateW = new StateW(st)

  private[sbtrelease] def resolve[T](key: ScopedKey[T], extracted: Extracted): ScopedKey[T] =
		Project.mapScope(Scope.resolveScope(GlobalScope, extracted.currentRef.build, extracted.rootProject) )( key.scopedKey )

  object Yes {
    def unapply(s: Option[String]) = s.exists(_.toLowerCase == "y")
  }
}

