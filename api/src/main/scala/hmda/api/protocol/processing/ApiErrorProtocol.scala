package hmda.api.protocol.processing

import hmda.api.model.ErrorResponse
import spray.json.DefaultJsonProtocol

trait ApiErrorProtocol extends DefaultJsonProtocol {

  implicit val apiErrorFormat = jsonFormat3(ErrorResponse.apply)

}
