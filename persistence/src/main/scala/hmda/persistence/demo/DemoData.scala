package hmda.persistence.demo

import akka.actor.ActorSystem
import akka.util.Timeout
import hmda.model.fi._
import hmda.model.institution.Agency.{ CFPB, FDIC, HUD, OCC }
import hmda.model.institution.ExternalIdType.{ FdicCertNo, FederalTaxId, OccCharterId, RssdId }
import hmda.model.institution.InstitutionStatus.{ Active, Inactive }
import hmda.model.institution.InstitutionType.{ Bank, CreditUnion }
import hmda.model.institution.{ ExternalId, Institution }
import hmda.persistence.CommonMessages._
import hmda.persistence.institutions.FilingPersistence.CreateFiling
import hmda.persistence.institutions.InstitutionPersistence.CreateInstitution
import hmda.persistence.institutions.{ FilingPersistence, SubmissionPersistence }
import hmda.persistence.institutions.SubmissionPersistence.{ CreateSubmission, UpdateSubmissionStatus }

import scala.concurrent.duration._

object DemoData {

  val externalId0 = ExternalId("externalTest0", FdicCertNo)
  val externalId1 = ExternalId("externalTest1", RssdId)
  val externalId2 = ExternalId("externalTest2", OccCharterId)
  val externalId3 = ExternalId("externalTest3", FederalTaxId)

  val testInstitutions = {
    val i0 = Institution("0", "Bank 0", Set(externalId0), FDIC, Bank, hasParent = true, status = Active)
    val i1 = Institution("1", "Bank 1", Set(externalId1), CFPB, CreditUnion, hasParent = true, status = Active)
    val i2 = Institution("2", "Bank 2", Set(externalId2), OCC, CreditUnion, hasParent = false, status = Inactive)
    val i3 = Institution("3", "Bank 3", Set(externalId3), HUD, CreditUnion, hasParent = true, status = Active)
    Set(i0, i1, i2, i3)
  }

  val testFilings = {
    val f1 = Filing("2016", "0", Completed)
    val f2 = Filing("2017", "0", NotStarted)
    val f3 = Filing("2017", "1", Completed)
    val f4 = Filing("2016", "2", Completed)
    val f5 = Filing("2016", "3", Completed)
    val f6 = Filing("2017", "3", NotStarted)
    Seq(f1, f2, f3)
  }

  val testSubmissions = {
    val institutionId = "0"
    val period = "2017"
    val s1 = Submission(SubmissionId(institutionId, period, 1), Created)
    val s2 = Submission(SubmissionId(institutionId, period, 2), Created)
    val s3 = Submission(SubmissionId(institutionId, period, 3), Created)
    Seq(s1, s2, s3)
  }

  val demoInstitutions = DemoInstitutions.values

  val demoFilings = DemoFilings.values

  val demoSubmissions = DemoSubmissions.values

  implicit val timeout = Timeout(5.seconds)

  def loadDemoData(system: ActorSystem): Unit = {
    Thread.sleep(500)
    loadInstitutions(demoInstitutions, system)
    loadFilings(demoFilings, system)
    loadSubmissions(demoSubmissions, system)
  }

  def loadTestData(system: ActorSystem): Unit = {
    Thread.sleep(500)
    loadInstitutions(testInstitutions, system)
    loadFilings(testFilings, system)
    loadSubmissions(testSubmissions.map(s => ("0", "2017", s)), system)
  }

  val institutionSummary = {
    val institution = testInstitutions.head
    val f = testFilings.filter(x => x.institutionId == institution.id.toString)
    (institution.id, institution.name, f.reverse)
  }

  def loadInstitutions(institutions: Set[Institution], system: ActorSystem): Unit = {
    val institutionsActor = system.actorSelection("/user/supervisor/institutions")
    institutions.foreach(i => institutionsActor ! CreateInstitution(i))
  }

  def loadFilings(filings: Seq[Filing], system: ActorSystem): Unit = {
    filings.foreach { filing =>
      val filingActor = system.actorOf(FilingPersistence.props(filing.institutionId))
      filingActor ! CreateFiling(filing)
      Thread.sleep(100)
      filingActor ! Shutdown
    }
  }

  def loadSubmissions(submissions: Seq[(String, String, Submission)], system: ActorSystem): Unit = {
    submissions.foreach { s =>
      s match {
        case (id: String, period: String, submission: Submission) =>
          val submissionsActor = system.actorOf(SubmissionPersistence.props(id, period))
          submissionsActor ! CreateSubmission
          Thread.sleep(100)
          submissionsActor ! UpdateSubmissionStatus(submission.id, submission.submissionStatus)
          Thread.sleep(100)
          submissionsActor ! Shutdown
      }
    }
  }

}
