package realestate.application;

import akka.Done;
import akka.javasdk.annotations.ComponentId;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@ComponentId("client-info-entity")
public class ClientInfoEntity extends EventSourcedEntity<ClientInfoEntity.ClientState, ClientInfoEntity.ClientEvent> {

  private Logger logger = LoggerFactory.getLogger(getClass());

  public record ClientState(String name, String email, String phone) {
  }

  sealed interface ClientEvent {
    record SaveClientInfo(String name, String email, String phone) implements ClientEvent { }
  }

  @Override
  public ClientState applyEvent(ClientEvent clientEvent) {
    return switch(clientEvent) {
      case ClientEvent.SaveClientInfo saveClientInfo -> new ClientState(saveClientInfo.name, saveClientInfo.email, saveClientInfo.phone);
    };
  }


  public record SaveInfoCmd(String name, String email, String phone) {
  }

  public Effect<Done> saveClientInfo(SaveInfoCmd saveInfoCmd) {
    logger.info("Saving client info: " + saveInfoCmd);
    return effects()
        .persist(new ClientEvent.SaveClientInfo(saveInfoCmd.name, saveInfoCmd.email, saveInfoCmd.phone))
        .thenReply(__ -> Done.getInstance());
  }

  public ReadOnlyEffect<ClientState> get() {
    logger.info("Reading client info, entityId={}", commandContext().entityId());
    return effects().reply(currentState());
  }

}
