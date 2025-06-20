package realestate.application;

import akka.Done;
import akka.javasdk.annotations.ComponentId;
import akka.javasdk.annotations.TypeName;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import realestate.domain.ClientEvent;
import realestate.domain.ClientState;


@ComponentId("client-info-entity")
public class ClientInfoEntity extends EventSourcedEntity<ClientState, ClientEvent> {

  private Logger logger = LoggerFactory.getLogger(getClass());

  @Override
  public ClientState applyEvent(ClientEvent clientEvent) {
    return switch(clientEvent) {
      case ClientEvent.ClientInfoSaved saved -> new ClientState(saved.name(), saved.email(), saved.phone());
    };
  }

  public enum TransactionType {
    RENT,
    BUY
  }

  public record PropertyDetails(String location, String type, TransactionType transactionType) {
    public static PropertyDetails of(String location, String type, String transactionType) {
      var tType = switch (transactionType.toLowerCase()) {
        case "rent" -> TransactionType.RENT;
        case "buy" -> TransactionType.BUY;
        default -> throw new IllegalArgumentException("Invalid transaction type: " + transactionType);
      };
      return new PropertyDetails(location, type, tType);
    }
  }

  public record SaveInfoCmd(String name, String email, String phone, PropertyDetails details) { }

  public Effect<Done> saveClientInfo(SaveInfoCmd saveInfoCmd) {
    logger.info("Saving client info: " + saveInfoCmd);
    return effects()
        .persist(new ClientEvent.ClientInfoSaved(saveInfoCmd.name, saveInfoCmd.email, saveInfoCmd.phone))
        .thenReply(__ -> Done.getInstance());
  }

  public ReadOnlyEffect<ClientState> get() {
    logger.info("Reading client info, entityId={}", commandContext().entityId());
    return effects().reply(currentState());
  }

}
