package hmda.api.http

import java.time.Instant

import akka.actor.{ ActorRef, ActorSelection, ActorSystem }
import akka.event.LoggingAdapter
import akka.stream.ActorMaterializer
import akka.pattern.ask
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.StandardRoute
import akka.stream.scaladsl.Framing
import akka.util.{ ByteString, Timeout }
import hmda.api.model._
import hmda.persistence.institutions.FilingPersistence.GetFilingByPeriod
import hmda.persistence.institutions.InstitutionPersistence.GetInstitutionById
import hmda.persistence.institutions.SubmissionPersistence.{ CreateSubmission, GetLatestSubmission }
import hmda.api.protocol.processing.{ ApiErrorProtocol, InstitutionProtocol }
import hmda.model.fi.{ Filing, Institution, Submission }
import hmda.persistence.CommonMessages._
import hmda.persistence.institutions.{ FilingPersistence, SubmissionPersistence }
import hmda.persistence.processing.HmdaRawFile._
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

trait InstitutionsHttpApi extends InstitutionProtocol with ApiErrorProtocol with HmdaCustomDirectives {

  implicit val system: ActorSystem
  implicit val materializer: ActorMaterializer
  val log: LoggingAdapter

  implicit val timeout: Timeout

  val splitLines = Framing.delimiter(ByteString("\n"), 2048, allowTruncation = true)

  val institutionsPath =
    path("institutions") {
      val path = "institutions"
      val institutionsActor = system.actorSelection("/user/institutions")
      timedGet {
        val fInstitutions = (institutionsActor ? GetState).mapTo[Set[Institution]]
        onComplete(fInstitutions) {
          case Success(institutions) =>
            complete(ToResponseMarshallable(Institutions(institutions)))
          case Failure(error) => fiveHundred(path, error)
        }
      }
    }

  val institutionByIdPath =
    path("institutions" / Segment) { institutionId =>
      val path = s"institutions/$institutionId"
      extractExecutionContext { executor =>
        val institutionsActor = system.actorSelection("/user/institutions")
        val filingsActor = system.actorOf(FilingPersistence.props(institutionId))
        timedGet {
          implicit val ec: ExecutionContext = executor
          val fInstitutionDetails = institutionDetails(institutionId, institutionsActor, filingsActor)
          onComplete(fInstitutionDetails) {
            case Success(institutionDetails) =>
              filingsActor ! Shutdown
              if (institutionDetails.institution.id != "")
                complete(ToResponseMarshallable(institutionDetails))
              else {
                val errorResponse = ErrorResponse(404, s"Institution $institutionId not found", path)
                complete(ToResponseMarshallable(StatusCodes.NotFound -> errorResponse))
              }
            case Failure(error) =>
              filingsActor ! Shutdown
              fiveHundred(path, error)
          }
        }
      }
    }

  val filingByPeriodPath =
    path("institutions" / Segment / "filings" / Segment) { (institutionId, period) =>
      val path = s"institutions/$institutionId/filings/$period"
      extractExecutionContext { executor =>
        val filingsActor = system.actorOf(FilingPersistence.props(institutionId))
        val submissionActor = system.actorOf(SubmissionPersistence.props(institutionId, period))
        timedGet {
          implicit val ec: ExecutionContext = executor
          val fDetails: Future[FilingDetail] = filingDetailsByPeriod(period, filingsActor, submissionActor)
          onComplete(fDetails) {
            case Success(filingDetails) =>
              filingsActor ! Shutdown
              submissionActor ! Shutdown
              val filing = filingDetails.filing
              if (filing.institutionId == institutionId && filing.period == period)
                complete(ToResponseMarshallable(filingDetails))
              else {
                val errorResponse = ErrorResponse(404, s"$period filing not found for institution $institutionId", path)
                complete(ToResponseMarshallable(StatusCodes.NotFound -> errorResponse))
              }
            case Failure(error) =>
              filingsActor ! Shutdown
              submissionActor ! Shutdown
              fiveHundred(path, error)
          }
        }
      }
    }

  val submissionPath =
    path("institutions" / Segment / "filings" / Segment / "submissions") { (institutionId, period) =>
      val path = s"institutions/$institutionId/filings/$period/submissions"
      timedPost {
        implicit val ec = system.dispatcher
        val filingsActor = system.actorOf(FilingPersistence.props(institutionId))
        val submissionsActor = system.actorOf(SubmissionPersistence.props(institutionId, period))
        val fFiling = (filingsActor ? GetFilingByPeriod(period)).mapTo[Filing]
        onComplete(fFiling) {
          case Success(filing) =>
            if (filing.period == period) {
              submissionsActor ! CreateSubmission
              val fLatest = (submissionsActor ? GetLatestSubmission).mapTo[Submission]
              onComplete(fLatest) {
                case Success(submission) =>
                  submissionsActor ! Shutdown
                  filingsActor ! Shutdown
                  complete(ToResponseMarshallable(StatusCodes.Created -> submission))
                case Failure(error) =>
                  submissionsActor ! Shutdown
                  fiveHundred(path, error)
              }
            } else {
              val errorResponse = ErrorResponse(404, s"$period filing not found for institution $institutionId", path)
              complete(ToResponseMarshallable(StatusCodes.NotFound -> errorResponse))
            }
          case Failure(error) =>
            filingsActor ! Shutdown
            submissionsActor ! Shutdown
            fiveHundred(path, error)
        }
      }
    }

  val uploadPath =
    path("institutions" / Segment / "filings" / Segment / "submissions" / Segment) { (institutionId, period, submissionId) =>
      val path = s"institutions/$institutionId/filings/$period/submissions/$submissionId"
      val uploadTimestamp = Instant.now.toEpochMilli
      val processingActor = createHmdaRawFile(system, submissionId)
      processingActor ! StartUpload
      fileUpload("file") {
        case (metadata, byteSource) if (metadata.fileName.endsWith(".txt")) =>
          time {
            val uploadedF = byteSource
              .via(splitLines)
              .map(_.utf8String)
              .runForeach(line => processingActor ! AddLine(uploadTimestamp, line))

            onComplete(uploadedF) {
              case Success(response) =>
                processingActor ! CompleteUpload
                processingActor ! Shutdown
                complete {
                  "uploaded"
                }
              case Failure(error) =>
                processingActor ! Shutdown
                log.error(error.getLocalizedMessage)
                val errorResponse = ErrorResponse(400, "Invalid File Format", path)
                complete(ToResponseMarshallable(StatusCodes.BadRequest -> errorResponse))
            }
          }

        case _ =>
          time {
            processingActor ! Shutdown
            val errorResponse = ErrorResponse(400, "Invalid File Format", path)
            complete(ToResponseMarshallable(StatusCodes.BadRequest -> errorResponse))
          }
      }
    }

  val institutionSummaryPath =
    path("institutions" / Segment / "summary") { institutionId =>
      val path = s"institutions/$institutionId/summary"
      extractExecutionContext { executor =>
        val institutionsActor = system.actorSelection("/user/institutions")
        val filingsActor = system.actorOf(FilingPersistence.props(institutionId))
        implicit val ec = executor
        get {
          val fInstitution = (institutionsActor ? GetInstitutionById(institutionId)).mapTo[Institution]
          val fFilings = (filingsActor ? GetState).mapTo[Seq[Filing]]
          val fSummary = for {
            institution <- fInstitution
            filings <- fFilings
          } yield InstitutionSummary(institution.id, institution.name, filings)

          onComplete(fSummary) {
            case Success(summary) =>
              filingsActor ! Shutdown
              complete(ToResponseMarshallable(summary))
            case Failure(error) =>
              filingsActor ! Shutdown
              fiveHundred(path, error)
          }
        }
      }
    }

  val unauthorizedAccess = {
    val errorResponse = ErrorResponse(403, "Unauthorized Access", "")
    complete(ToResponseMarshallable(StatusCodes.Forbidden -> errorResponse))
  }

  private def institutionDetails(institutionId: String, institutionsActor: ActorSelection, filingsActor: ActorRef)(implicit ec: ExecutionContext): Future[InstitutionDetail] = {
    val fInstitution = (institutionsActor ? GetInstitutionById(institutionId)).mapTo[Institution]
    for {
      institution <- fInstitution
      filings <- (filingsActor ? GetState).mapTo[Seq[Filing]]
    } yield InstitutionDetail(institution, filings)
  }

  private def filingDetailsByPeriod(period: String, filingsActor: ActorRef, submissionActor: ActorRef)(implicit ec: ExecutionContext): Future[FilingDetail] = {
    val fFiling = (filingsActor ? GetFilingByPeriod(period)).mapTo[Filing]
    for {
      filing <- fFiling
      submissions <- (submissionActor ? GetState).mapTo[Seq[Submission]]
    } yield FilingDetail(filing, submissions)
  }

  private def fiveHundred(path: String, error: Throwable): StandardRoute = {
    log.error(error.getLocalizedMessage)
    val errorResponse = ErrorResponse(500, "Internal server error", path)
    complete(ToResponseMarshallable(StatusCodes.InternalServerError -> errorResponse))
  }

  val institutionsRoutes =
    hmdaAuthorize {
      institutionsPath ~
        institutionByIdPath ~
        institutionSummaryPath ~
        filingByPeriodPath ~
        submissionPath ~
        uploadPath
    } ~ unauthorizedAccess
}
