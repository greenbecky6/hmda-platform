package hmda.persistence
import akka.actor.ActorSystem
import hmda.persistence.CommonMessages.Event

trait LocalEventPublisher extends EventPublisher {

  def system: ActorSystem

  override def publishEvent(e: Event): Unit = {
    system.eventStream.publish(e)
  }

}
